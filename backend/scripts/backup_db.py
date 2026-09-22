"""지금 붙어 있는 DB 를 JSON 파일로 내려받는다. **읽기만 한다.**

    cd backend
    python scripts/backup_db.py                    # 기본 위치에 저장
    python scripts/backup_db.py --out C:/tmp/a.json
    python scripts/backup_db.py --list             # 기존 백업 목록

## 왜 필요한가

팀이 DB 를 **Neon 하나**로 쓰기로 했다. 그러면 로컬 사본이라는 안전망이 없어진다.
그 전에는 실수로 데이터를 날려도 로컬 SQLite 에 비슷한 것이 남아 있었다.

되돌릴 수 없는 명령이 몇 개 있고, 전부 한 줄이다.

    manage.py flush                   전체 삭제
    purge_test_accounts.py --yes      계정 삭제 (cascade 로 일정·관측까지)
    User.objects.filter(...).delete() 셸에서 한 줄

Neon 자체도 시점 복구를 제공하지만, 그건 **콘솔 접근 권한이 있는 사람만** 쓸 수
있고 보존 기간이 있다. 작업 전에 파일 하나 만들어 두는 비용이 훨씬 싸다.

## 저장되는 것과 안 되는 것

`dumpdata` 를 쓴다. 계정·일정·계획·관측·학습 결과가 들어간다. 비밀번호는
해시로 들어간다 — **그래서 이 파일은 커밋하지 않는다.** `.gitignore` 가
`*.json` 을 막지 않으므로 기본 저장 위치를 저장소 밖(임시 폴더 하위)으로 둔다.

세션과 admin 로그는 뺀다. 복구할 가치가 없고 파일만 커진다.

## 되돌리는 방법

    python manage.py loaddata <파일>

**주의: loaddata 는 비우지 않고 덮어쓴다.** 같은 pk 는 갱신하고 없는 것은
만든다. 삭제된 행을 되살리는 데는 맞지만, "그 시점으로 완전히 되돌리기" 는
아니다. 완전 복구가 필요하면 Neon 콘솔의 시점 복구를 쓴다.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# 저장소 밖에 둔다. 비밀번호 해시가 들어 있어 실수로 커밋되면 안 된다.
BACKUP_DIR = Path(tempfile.gettempdir()) / "jit-db-backups"

# 복구할 가치가 없는 것들. 빼면 파일이 훨씬 작아진다.
EXCLUDE = [
    "contenttypes",
    "auth.permission",
    "sessions.session",
    "admin.logentry",
]


def python_exe() -> str:
    for candidate in (
        BASE_DIR / ".venv" / "Scripts" / "python.exe",
        BASE_DIR / ".venv" / "bin" / "python",
    ):
        if candidate.exists():
            return str(candidate)
    return sys.executable


def describe_target() -> str:
    """어느 DB 를 백업하는지 한 줄로. 엉뚱한 DB 를 받는 것을 막는다."""
    sys.path.insert(0, str(BASE_DIR))
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
    import django

    django.setup()
    from django.db import connection

    settings_dict = connection.settings_dict
    host = settings_dict.get("HOST") or "(로컬 파일)"
    name = settings_dict.get("NAME")
    return f"{connection.vendor}  host={host}  name={name}"


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", help="저장 경로. 생략하면 임시 폴더에 시각으로 만든다")
    parser.add_argument("--list", action="store_true", help="기존 백업 목록만 보여준다")
    args = parser.parse_args()

    if args.list:
        if not BACKUP_DIR.exists():
            print(f"백업이 없다. ({BACKUP_DIR})")
            return 0
        files = sorted(BACKUP_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime)
        if not files:
            print(f"백업이 없다. ({BACKUP_DIR})")
            return 0
        print(f"{BACKUP_DIR}")
        for path in files:
            size = path.stat().st_size / 1024
            when = datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
            print(f"  {path.name:<34} {size:>9.1f} KB  {when}")
        return 0

    target = describe_target()
    print("=" * 74)
    print("DB 백업")
    print(f"  대상 = {target}")
    print("=" * 74)

    if args.out:
        out_path = Path(args.out).expanduser().resolve()
    else:
        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = BACKUP_DIR / f"jit-{stamp}.json"

    out_path.parent.mkdir(parents=True, exist_ok=True)

    cmd = [python_exe(), "manage.py", "dumpdata", "--indent", "2"]
    for label in EXCLUDE:
        cmd += ["--exclude", label]

    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"

    # dumpdata 는 stdout 으로 내보낸다. `--output` 대신 직접 받아 쓴다 —
    # --output 은 진행 표시를 같은 파일에 섞는 버전이 있었다.
    proc = subprocess.run(
        cmd, cwd=BASE_DIR, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        print("실패:")
        print(proc.stderr[-2000:])
        return proc.returncode

    payload = proc.stdout
    if not payload.strip().startswith("["):
        print("내려받은 내용이 JSON 배열이 아니다. 중단한다.")
        print(payload[:400])
        return 1

    out_path.write_text(payload, encoding="utf-8")
    size = out_path.stat().st_size / 1024

    # 몇 건인지 알려 준다. 0건이면 엉뚱한 DB 에 붙은 것이다.
    import json

    try:
        records = len(json.loads(payload))
    except json.JSONDecodeError:
        records = -1

    print()
    print(f"  저장 = {out_path}")
    print(f"  크기 = {size:.1f} KB · {records}건")
    print()
    if records == 0:
        print("  ** 0건이다. 붙은 DB 가 비어 있다. 대상을 확인할 것. **")
        return 1

    print("  되돌릴 때:  python manage.py loaddata " + str(out_path))
    print("  주의: loaddata 는 비우지 않고 덮어쓴다. 완전 복구는 Neon 시점 복구를 쓴다.")
    print()
    print("  이 파일에는 비밀번호 해시가 들어 있다. 커밋하지 말 것.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
