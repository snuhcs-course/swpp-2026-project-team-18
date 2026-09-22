"""로컬 서버를 띄우고 검증 스크립트 전체를 한 번에 돌린다.

    cd backend
    python scripts/run_local_suite.py              # 전부
    python scripts/run_local_suite.py --only check_app_contract.py

## 왜 이 파일이 저장소에 있어야 하는가

`scripts/check_*.py` 는 **살아 있는 서버**를 상대로 HTTP 를 부른다. pytest 가
못 잡는 것을 잡는다 — URL 이 실제로 연결됐는지, 직렬화가 앱이 읽는 모양인지,
권한이 붙어 있는지. 그런데 그걸 돌리려면 서버를 띄우고 DB 를 고정하고 포트를
확인해야 해서, 한동안 그 절차가 한 사람의 PC 에만 있었다. 그러면 나머지 팀원은
"돌릴 수 없는 검증" 을 갖게 되고, CI 도 같은 절차를 YAML 에 베껴 써야 한다.
절차를 코드로 저장소에 두면 사람과 CI 가 같은 것을 돌린다.

## 이 러너가 막아 주는 세 가지 사고

1. **공용 DB 오염.** `DATABASE_URL` 을 명시적으로 SQLite 로 고정한다. 셸에서
   비우려는 시도(`$env:DATABASE_URL=""`)는 PowerShell 에서 변수 *삭제*로
   처리되고, 그러면 `base.py` 의 `load_dotenv` 가 `.env` 의 공용 Neon 주소를
   다시 읽는다. 실제로 그렇게 테스트 계정 21개가 팀 공용 DB 에 들어갔다.

2. **구버전 코드 검증.** 포트가 이미 잡혀 있으면 새 서버는 바인드에 실패하는데,
   헬스 체크는 남아 있던 옛 서버가 주는 200 을 보고 "기동 성공" 으로 읽는다.
   그 뒤 전 항목이 옛 코드를 상대로 통과한다. 실제로 그렇게 됐다 — 새로 추가한
   엔드포인트가 404 였는데 코드가 틀린 것처럼 보였다. 그래서 포트가 잡혀 있으면
   **조용히 재사용하지 않고 실패한다.**

3. **서버 로그 유실.** 로그를 파이프로 받으면 셸이 잘라 먹는다. 파일로 받는다.

## 외부 API 키가 없어도 된다

`KAKAO_REST_API_KEY` 가 없으면 경로 계산이 필요한 항목은 각 스크립트가
**건너뛴다**(실패가 아니다). CI 에는 키를 넣지 않는다 — 무료 쿼터가 계정당
하루 단위라 CI 가 그것을 태우면 사람이 개발을 못 한다.

여기서 돌리지 않는 것:
  - `check_deployed.py`, `check_external_apis.py`  외부 망·키가 필요하다
  - `check_same_db.py`, `db_*.py`, `purge_*.py`    공용 DB 를 직접 본다
"""

from __future__ import annotations

import argparse
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent

# 로컬 서버를 상대로 도는 검증. 순서에 의미가 있다 — 계정·일정이 먼저 있어야
# 뒤쪽 스크립트가 볼 것이 생긴다.
SCRIPTS = [
    "check_auth_api.py",
    "check_events_api.py",
    "check_route_api.py",
    "check_origin_api.py",
    "check_observations_api.py",
    "check_timezone.py",
    "check_routines_api.py",
    "check_app_contract.py",
]


def local_env() -> dict[str, str]:
    """검증용 환경. `DATABASE_URL` 을 **반드시 명시**한다."""
    import os

    env = os.environ.copy()
    env["DATABASE_URL"] = "sqlite:///db.sqlite3"
    # 윈도우 콘솔 기본 코드페이지에서 한글 출력이 깨지는 것을 막는다.
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    return env


def python_exe() -> str:
    """가상환경이 있으면 그것을 쓴다. 없으면 지금 도는 인터프리터."""
    for candidate in (
        BACKEND / ".venv" / "Scripts" / "python.exe",  # Windows
        BACKEND / ".venv" / "bin" / "python",          # macOS·Linux
    ):
        if candidate.exists():
            return str(candidate)
    return sys.executable


def port_busy(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def wait_healthy(port: int, timeout: float = 90.0) -> bool:
    url = f"http://127.0.0.1:{port}/api/health"
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as res:
                if res.status == 200:
                    return True
        except (urllib.error.URLError, OSError):
            time.sleep(0.5)
    return False


def summarize(text: str) -> str:
    """스크립트 출력에서 마지막 집계 줄을 찾는다.

    형식이 두 가지다. `check_app_contract.py` 는 "통과 95 · 실패 0 · 건너뜀 0",
    오래된 스크립트는 "17/17 통과". 둘 다 읽어야 한다 — 집계를 못 읽으면 CI
    로그에 빈칸이 남고, **아무것도 검사하지 않고 통과한 것과 구분되지 않는다.**
    """
    for line in reversed(text.splitlines()):
        stripped = line.strip()
        if "통과" not in stripped:
            continue
        if "실패" in stripped or re.search(r"\d+\s*/\s*\d+", stripped):
            return stripped
    return ""


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--only",
        action="append",
        default=None,
        help="이 스크립트만 돌린다. 여러 번 줄 수 있다.",
    )
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument(
        "--keep-going",
        action="store_true",
        help="실패해도 남은 스크립트를 계속 돌린다(기본 동작).",
    )
    args = parser.parse_args()

    scripts = args.only or SCRIPTS
    tmp = Path(tempfile.gettempdir())
    log_path = tmp / "jit_local_suite_server.log"

    if port_busy(args.port):
        print(f"중단: 포트 {args.port} 이 이미 잡혀 있다.")
        print("  남아 있던 옛 서버를 검증하면 전 항목이 구버전 코드로 통과한다.")
        print("  그 프로세스를 먼저 끝내거나 --port 로 다른 포트를 준다.")
        return 2

    env = local_env()
    py = python_exe()

    print("=" * 74)
    print("로컬 전체 회귀")
    print(f"  python = {py}")
    print(f"  DB     = {env['DATABASE_URL']}")
    print(f"  서버로그= {log_path}")
    print("=" * 74)

    migrate = subprocess.run(
        [py, "manage.py", "migrate", "--noinput"],
        cwd=BACKEND, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if migrate.returncode != 0:
        print(migrate.stdout[-2000:])
        print(migrate.stderr[-2000:])
        print("중단: 마이그레이션 실패")
        return migrate.returncode
    print("마이그레이션 완료")

    results: list[tuple[str, int, str]] = []

    with log_path.open("w", encoding="utf-8") as log:
        server = subprocess.Popen(
            [py, "manage.py", "runserver", f"127.0.0.1:{args.port}", "--noreload"],
            cwd=BACKEND, env=env, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            if not wait_healthy(args.port):
                print("서버가 응답하지 않는다. 로그 마지막 30줄:")
                tail = log_path.read_text(encoding="utf-8", errors="replace")
                print("\n".join(tail.splitlines()[-30:]))
                return 1
            print(f"서버 기동 :{args.port}")
            print()

            for name in scripts:
                target = BACKEND / "scripts" / name
                if not target.exists():
                    print(f"[FAIL] {name}  파일이 없다")
                    results.append((name, -1, "파일 없음"))
                    continue

                proc = subprocess.run(
                    [py, str(target), "--base", f"http://127.0.0.1:{args.port}"]
                    if _takes_base(target) else [py, str(target)],
                    cwd=BACKEND, env=env, capture_output=True, text=True,
                    encoding="utf-8", errors="replace",
                )
                text = (proc.stdout or "") + (proc.stderr or "")
                summary = summarize(text)
                code = proc.returncode

                # **아무것도 안 하고 0 을 돌려준 것은 성공이 아니다.**
                # 스크립트가 서버에 못 붙거나 앞부분에서 조용히 빠져나오면
                # 검사 없이 0 이 나올 수 있고, 그것을 초록으로 보고하면 CI 가
                # 거짓말을 한다.
                #
                # 단, **건너뜀이 있으면 판정하지 않는다.** 외부 키가 없어 전부
                # 건너뛴 스크립트는 정상이다. 통과와 건너뜀이 둘 다 0 일 때만
                # 뒤집는다.
                if (
                    code == 0
                    and summary
                    and passed_count(summary) == 0
                    and skipped_count(summary) == 0
                ):
                    code = 3
                    summary = f"{summary}  ← 검증한 항목이 없다"

                results.append((name, code, summary or "(집계 줄 없음)"))

                mark = " OK " if code == 0 else "FAIL"
                print(f"[{mark}] {name}  {summary or '(집계 줄 없음)'}")

                # 실패한 스크립트는 원인을 보여 준다. 집계만 보고는 무엇이
                # 깨졌는지 알 수 없고, **CI 로그는 나중에 열어 볼 수 없다.**
                if code != 0:
                    print_failure_detail(text)
        finally:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()

    failed = [r for r in results if r[1] != 0]
    print()
    print("=" * 74)
    print(f"스크립트 {len(results)}개 · 실패 {len(failed)}개")
    for name, code, _ in failed:
        print(f"  - {name} (rc={code})")
    print("=" * 74)
    return 1 if failed else 0


def print_failure_detail(text: str, fail_limit: int = 12, tail: int = 25) -> None:
    """실패 원인을 읽을 수 있게 찍는다.

    한 번 `Traceback` 한 줄만 찍고 끝난 적이 있다. 예외 **종류와 메시지는 그
    아래**에 있으므로 그 한 줄로는 아무것도 알 수 없었고, CI 로그는 권한이 없어
    나중에 열어 볼 수도 없었다. 그래서 개별 실패 줄과 **꼬리 전체**를 둘 다
    찍는다. 조금 길어지는 것이 원인을 못 찾는 것보다 낫다.
    """
    lines = text.splitlines()

    fails = [l.rstrip() for l in lines if l.lstrip().startswith("[FAIL]")]
    for line in fails[:fail_limit]:
        print(f"         {line}")
    if len(fails) > fail_limit:
        print(f"         ... 실패 {len(fails) - fail_limit}건 더")

    body = [l.rstrip() for l in lines if l.strip()][-tail:]
    if body:
        print(f"         ── 마지막 {len(body)}줄 " + "─" * 30)
        for line in body:
            print(f"         {line}")


def passed_count(summary: str) -> int:
    """집계 줄에서 통과 건수를 뽑는다. 못 읽으면 -1(판정 보류)."""
    # "통과 95 · 실패 0 · 건너뜀 0"
    match = re.search(r"통과\s*(\d+)", summary)
    if match:
        return int(match.group(1))
    # "17/17 통과"
    match = re.search(r"(\d+)\s*/\s*(\d+)\s*통과", summary)
    if match:
        return int(match.group(1))
    return -1


def skipped_count(summary: str) -> int:
    """집계 줄에서 건너뜀 건수를 뽑는다. 항목이 없으면 0."""
    match = re.search(r"건너뜀\s*(\d+)", summary)
    return int(match.group(1)) if match else 0


def _takes_base(target: Path) -> bool:
    """`--base` 인자를 받는 스크립트인가.

    전부 받는 것은 아니다. 받지 않는 스크립트에 넘기면 argparse 가 죽는다.
    """
    try:
        source = target.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return '"--base"' in source or "'--base'" in source


if __name__ == "__main__":
    raise SystemExit(main())
