"""검증 스크립트가 공용 DB 를 건드리지 못하게 막는다.

## 실제로 있었던 사고

`check_auth_api.py` 같은 스크립트는 계정과 일정을 만들어 검증한다. 로컬
SQLite 를 쓴다고 전제했지만, `backend/.env` 의 `DATABASE_URL` 이 공용 Neon 을
가리키고 있어서 **21개 테스트 계정과 12건 일정이 팀 공용 DB 에 들어갔다.**

셸에서 막으려 한 시도도 실패했다. PowerShell 은

    $env:DATABASE_URL=""

를 "빈 문자열 대입" 이 아니라 **변수 삭제**로 처리한다. 변수가 사라지면
`base.py` 의 `load_dotenv` 가 `.env` 값을 다시 넣는다. 즉 셸에서 비우려 할수록
공용 DB 로 붙는다. 로컬로 고정하려면 값을 **명시**해야 한다.

    $env:DATABASE_URL="sqlite:///db.sqlite3"

이 함정은 눈에 보이지 않는다. 스크립트가 통과하고 로그도 정상이다. 그래서
셸 습관이 아니라 코드로 막는다.

## 쓰는 법

ORM 으로 쓰기를 하거나, 로컬 서버를 전제하는 스크립트 맨 위에서 부른다.

    from _local_guard import require_local_database
    require_local_database()

공용 DB 를 의도적으로 건드려야 하는 스크립트(`purge_test_accounts.py`,
`db_counts.py`, `migrate_sqlite_to_postgres.py`)는 부르지 않는다.

탈출구는 환경변수다. 의도한 경우에만 명시적으로 켠다.

    $env:JIT_ALLOW_REMOTE_DB="1"
"""

from __future__ import annotations

import os
import sys

# 로컬로 인정하는 호스트. 빈 값은 SQLite(파일 기반)다.
LOCAL_HOSTS = {"", "localhost", "127.0.0.1", "::1", "0.0.0.0"}

ESCAPE_HATCH = "JIT_ALLOW_REMOTE_DB"


class RemoteDatabaseRefused(RuntimeError):
    """공용 DB 로 검증 스크립트를 돌리려 했을 때."""


def _resolve_database_url() -> str:
    """Django 없이 최종 `DATABASE_URL` 을 알아낸다.

    `base.py` 와 같은 순서를 따른다 — 환경변수가 있으면 그것, 없으면
    `backend/.env` 의 값. Django 를 부르지 않는 스크립트(HTTP 전용 검증)도
    같은 판정을 받아야 하므로 여기서 직접 읽는다.
    """
    from_env = os.environ.get("DATABASE_URL")
    if from_env is not None and from_env.strip():
        return from_env.strip()
    if from_env is not None:
        # 빈 문자열로 **존재**하면 base.py 는 SQLite 로 떨어진다.
        return ""

    # 환경변수가 없으면 .env 를 읽는다. dotenv 를 쓰지 않고 직접 파싱한다 —
    # 이 모듈은 Django 설정 로딩 전에도 불릴 수 있어야 한다.
    from pathlib import Path

    env_path = Path(__file__).resolve().parent.parent / ".env"
    if not env_path.exists():
        return ""
    for raw in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() == "DATABASE_URL":
            return value.strip().strip('"').strip("'")
    return ""


def describe_database() -> tuple[str, str, str]:
    """`(engine, host, name)`.

    Django 설정이 이미 로드돼 있으면 그것을 믿는다(테스트 설정처럼 코드가
    덮어쓴 경우를 존중해야 한다). 아니면 `DATABASE_URL` 을 직접 파싱한다.
    """
    try:
        from django.conf import settings

        if settings.configured:
            db = settings.DATABASES["default"]
            engine = db.get("ENGINE", "").rsplit(".", 1)[-1]
            return engine, (db.get("HOST") or ""), str(db.get("NAME") or "")
    except Exception:  # noqa: BLE001 - Django 가 없거나 미설정
        pass

    url = _resolve_database_url()
    if not url or url.startswith("sqlite"):
        return "sqlite3", "", url or "db.sqlite3"

    from urllib.parse import urlparse

    parsed = urlparse(url)
    return (parsed.scheme or "unknown"), (parsed.hostname or ""), (parsed.path or "").lstrip("/")


def is_local_database() -> bool:
    engine, host, _name = describe_database()
    if "sqlite" in engine:
        return True
    return host.strip().lower() in LOCAL_HOSTS


def require_local_database(*, exit_code: int = 2) -> None:
    """공용 DB 면 안내를 출력하고 종료한다.

    예외를 던지지 않고 `sys.exit` 하는 이유는, 스크립트가 통째로 죽는 것이
    원하는 동작이기 때문이다. 부분적으로 실행되면 이미 계정이 만들어진다.
    """
    if os.environ.get(ESCAPE_HATCH, "").strip() in {"1", "true", "yes"}:
        engine, host, name = describe_database()
        print(
            f"[경고] {ESCAPE_HATCH} 가 켜져 있어 원격 DB 를 허용한다: "
            f"{engine} {host}/{name}",
            file=sys.stderr,
        )
        return

    if is_local_database():
        return

    engine, host, name = describe_database()
    print("=" * 74, file=sys.stderr)
    print("중단: 이 스크립트는 공용/원격 DB 에서 돌리면 안 된다.", file=sys.stderr)
    print("=" * 74, file=sys.stderr)
    print(f"  현재 DB : {engine}  host={host}  name={name}", file=sys.stderr)
    print("", file=sys.stderr)
    print("  이 스크립트는 검증용 계정과 일정을 만든다. 공용 DB 에서 돌리면", file=sys.stderr)
    print("  팀 전체가 보는 목록이 테스트 데이터로 채워진다.", file=sys.stderr)
    print("", file=sys.stderr)
    print("  로컬 SQLite 로 고정하고 다시 실행할 것:", file=sys.stderr)
    print('    $env:DATABASE_URL="sqlite:///db.sqlite3"', file=sys.stderr)
    print("", file=sys.stderr)
    print('  **$env:DATABASE_URL="" 는 듣지 않는다.** PowerShell 이 변수를', file=sys.stderr)
    print("  삭제해 버리고, 그러면 .env 의 값이 다시 읽힌다.", file=sys.stderr)
    print("", file=sys.stderr)
    print(f"  의도한 경우에만: $env:{ESCAPE_HATCH}=\"1\"", file=sys.stderr)
    print("=" * 74, file=sys.stderr)
    sys.exit(exit_code)
