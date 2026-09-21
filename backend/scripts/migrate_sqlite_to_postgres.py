"""로컬 SQLite 데이터를 공용 Postgres 로 옮긴다.

혼자 개발하던 동안 `db.sqlite3` 에 쌓인 계정·일정을 공용 DB 로 한 번 넘기는
용도다. **일회성 도구다.** 공용 DB 가 자리를 잡은 뒤에는 쓰지 않는다.

    # 1. 로컬 SQLite 를 덤프한다 (DATABASE_URL 을 비운 상태로)
    python scripts/migrate_sqlite_to_postgres.py dump

    # 2. 공용 DB 로 넣는다
    set DATABASE_URL=postgres://...        (PowerShell: $env:DATABASE_URL="...")
    python scripts/migrate_sqlite_to_postgres.py load

Neon 을 쓸 때는 **`-pooler` 없는 direct 주소**를 넘긴다. pooler(PgBouncer
트랜잭션 모드)는 `PREPARE` 같은 세션 기능이 없어 스키마 변경이 깨질 수 있다.

왜 `dumpdata | loaddata` 인가. `dbshell` 로 SQL 을 옮기면 시퀀스·불리언 표현이
DB 마다 달라 깨진다. Django 직렬화를 거치면 모델 계층에서 값이 검증된다.

**contenttypes 와 auth.permission 을 제외한다.** 두 테이블은 마이그레이션이
새 DB 에서 다시 만들기 때문에, 덤프한 행을 그대로 넣으면 primary key 가
충돌하거나 권한이 엉뚱한 모델에 붙는다.
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DUMP_PATH = BASE_DIR / "datadump.json"

# 새 DB 의 마이그레이션이 스스로 만드는 테이블. 덤프에서 빼야 한다.
EXCLUDED = [
    "contenttypes",
    "auth.permission",
    # 세션은 옮길 가치가 없다. 토큰 기반 인증이라 앱 로그인과 무관하다.
    "sessions.session",
    # 관리자 작업 로그. 모델 참조가 contenttypes 에 묶여 있어 충돌한다.
    "admin.logentry",
]


def _manage(args: list[str], env: dict | None = None) -> int:
    cmd = [sys.executable, "manage.py", *args]
    print(f"$ {' '.join(cmd)}")
    return subprocess.call(cmd, cwd=BASE_DIR, env=env)


def do_dump() -> int:
    if os.getenv("DATABASE_URL", "").strip():
        print(
            "DATABASE_URL 이 설정돼 있다. 이 상태로 dump 하면 Postgres 를 덤프한다.\n"
            "SQLite 를 덤프하려면 먼저 DATABASE_URL 을 비울 것.",
            file=sys.stderr,
        )
        return 1

    sqlite_path = BASE_DIR / "db.sqlite3"
    if not sqlite_path.exists():
        print(f"{sqlite_path} 가 없다. 옮길 데이터가 없다.", file=sys.stderr)
        return 1

    args = ["dumpdata", "--natural-foreign", "--natural-primary", "--indent", "2"]
    for label in EXCLUDED:
        args += ["--exclude", label]
    args += ["--output", str(DUMP_PATH)]

    rc = _manage(args)
    if rc == 0:
        size = DUMP_PATH.stat().st_size
        print(f"\n덤프 완료: {DUMP_PATH} ({size:,} bytes)")
        print("다음: DATABASE_URL 을 공용 DB 로 설정하고 `load` 를 실행할 것.")
    return rc


def do_load() -> int:
    if not os.getenv("DATABASE_URL", "").strip():
        print(
            "DATABASE_URL 이 비어 있다. 이 상태로 load 하면 로컬 SQLite 에 "
            "다시 넣는다. 공용 DB 주소를 설정할 것.",
            file=sys.stderr,
        )
        return 1

    if not DUMP_PATH.exists():
        print(f"{DUMP_PATH} 가 없다. 먼저 `dump` 를 실행할 것.", file=sys.stderr)
        return 1

    # 스키마를 먼저 만든다. 빈 DB 에 loaddata 를 하면 테이블이 없어 실패한다.
    rc = _manage(["migrate", "--noinput"])
    if rc != 0:
        return rc

    rc = _manage(["loaddata", str(DUMP_PATH)])
    if rc == 0:
        print("\n적재 완료. 확인:")
        print("  python manage.py shell -c \"from django.contrib.auth import get_user_model as g; print(g().objects.count())\"")
    return rc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["dump", "load"])
    action = parser.parse_args().action
    return do_dump() if action == "dump" else do_load()


if __name__ == "__main__":
    raise SystemExit(main())
