"""나중에 추가된 NOT NULL 컬럼에 DB 기본값이 있는지 검사한다. **읽기만 한다.**

    cd backend
    python scripts/check_db_defaults.py

## 무엇을 막는가

Django 의 `default` 는 **파이썬 쪽** 기본값이다. `AddField` 가 그 값으로 기존
행을 채운 뒤 **DB 기본값은 남기지 않는다.** 그래서 이런 컬럼이 생긴다.

    NOT NULL · DB 기본값 없음

이 컬럼을 모르는 코드가 INSERT 를 하면 제약 위반으로 **쓰기만 500** 이 난다.
읽기와 `/api/health` 는 정상이라 겉으로는 멀쩡해 보이고, 그래서 원인을 찾는 데
오래 걸린다. 실제로 한 번 겪었다 — Neon 에는 마이그레이션이 적용됐는데 컨테이너는
구버전이었다(docs/team-setup.md 8절).

이 상황은 **배포가 두 단계로 일어나는 한 반드시 생긴다.** 마이그레이션은 DB 에
즉시 적용되고 코드는 컨테이너가 교체될 때 바뀐다. 그 사이가 항상 존재한다.

그래서 새 필드에는 `db_default` 를 함께 준다.

    route_key = models.CharField(max_length=120, blank=True, default="", db_default="")

## 왜 pytest 가 아니라 스크립트인가

pytest 는 **메모리 SQLite** 에서 돈다(`config/settings/test.py`). SQLite 는
컬럼 기본값을 Postgres 와 다르게 다루므로, 거기서 검사하면 배포 DB 의 실제 상태를
확인하지 못한다. 이 검사는 **지금 연결된 DB 를 직접 본다.**

Postgres 가 아니면 건너뛴다 — SQLite 로컬 DB 에 대고 물을 질문이 아니다.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from django.apps import apps  # noqa: E402
from django.db import connection  # noqa: E402
from django.db.migrations.loader import MigrationLoader  # noqa: E402


def columns_added_after_initial() -> set[tuple[str, str]]:
    """`0001_initial` 이후 `AddField` 로 추가된 (모델, 필드).

    초기 마이그레이션의 컬럼은 모든 버전의 코드가 알고 있으므로 위험하지 않다.
    나중에 추가된 것만 본다.
    """
    loader = MigrationLoader(connection, ignore_no_migrations=True)
    added: set[tuple[str, str]] = set()

    for (app_label, name), migration in loader.disk_migrations.items():
        if name.startswith("0001_initial"):
            continue
        for operation in migration.operations:
            if operation.__class__.__name__ != "AddField":
                continue
            added.add((f"{app_label}.{operation.model_name}", operation.name))
    return added


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")

    print("=" * 74)
    print("나중에 추가된 NOT NULL 컬럼의 DB 기본값 검사")
    print(f"vendor = {connection.vendor}")
    print("=" * 74)

    if connection.vendor != "postgresql":
        print()
        print("Postgres 가 아니라 건너뛴다. 배포 DB(Neon)에 붙여서 돌릴 것.")
        print("  backend/.env 의 DATABASE_URL 을 확인한다.")
        return 0

    added = columns_added_after_initial()
    if not added:
        print("\n0001_initial 이후 추가된 필드가 없다.")
        return 0

    risky: list[str] = []
    checked = 0

    with connection.cursor() as cur:
        for model_path, field_name in sorted(added):
            app_label, model_name = model_path.split(".")
            try:
                model = apps.get_model(app_label, model_name)
            except LookupError:
                # 모델이 지워졌다. 컬럼도 없을 것이므로 검사 대상이 아니다.
                continue

            field = next(
                (f for f in model._meta.get_fields() if getattr(f, "name", None) == field_name),
                None,
            )
            if field is None or not hasattr(field, "column"):
                continue

            cur.execute(
                """
                SELECT is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = %s AND column_name = %s
                """,
                [model._meta.db_table, field.column],
            )
            row = cur.fetchone()
            if row is None:
                print(f"[   ?] {model._meta.db_table}.{field.column} — DB 에 컬럼이 없다(미적용?)")
                continue

            checked += 1
            is_nullable, default = row
            if is_nullable == "YES":
                print(f"[ OK ] {model._meta.db_table}.{field.column} — NULL 허용")
            elif default:
                print(f"[ OK ] {model._meta.db_table}.{field.column} — 기본값 {default}")
            else:
                print(f"[FAIL] {model._meta.db_table}.{field.column} — NOT NULL 인데 기본값이 없다")
                risky.append(f"{model._meta.db_table}.{field.column}")

    print()
    print("=" * 74)
    print(f"검사 {checked}건 · 위험 {len(risky)}건")
    print("=" * 74)

    if risky:
        print()
        print("이 컬럼을 모르는 코드가 INSERT 하면 쓰기만 500 이 난다.")
        print("해당 필드에 db_default 를 주고 makemigrations 를 돌릴 것:")
        print()
        print('    models.CharField(..., blank=True, default="", db_default="")')
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
