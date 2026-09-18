"""DB 상태를 한눈에 본다.

접속 정보, 마이그레이션 적용 여부, 모델별 행 수, 테이블 목록을 출력한다.
데이터 내용은 찍지 않는다(비밀번호 해시 등이 섞이지 않게).

사용법:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\db_status.py

    # 사용자 목록까지 보려면
    .\\.venv\\Scripts\\python.exe scripts\\db_status.py --rows
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

from django.apps import apps as django_apps  # noqa: E402
from django.conf import settings  # noqa: E402
from django.db import connection  # noqa: E402
from django.db.migrations.executor import MigrationExecutor  # noqa: E402

SHOW_ROWS = "--rows" in sys.argv


def line(char="-", n=74):
    print(char * n)


# ---------------------------------------------------------------------------
# 접속 정보
# ---------------------------------------------------------------------------
line("=")
print("DB 접속")
line("=")
db = settings.DATABASES["default"]
engine = db.get("ENGINE", "").split(".")[-1]
name = db.get("NAME")
print(f"  ENGINE   {engine}")
print(f"  NAME     {name}")
if engine == "sqlite3":
    p = Path(str(name))
    if p.exists():
        kb = p.stat().st_size / 1024
        print(f"  파일     {kb:,.0f} KB, 수정 {p.stat().st_mtime}")
    else:
        print("  파일     없음 (migrate 를 실행해야 한다)")
    print("  전환     .env 의 DATABASE_URL 을 채우면 Postgres 로 간다 (P2)")
else:
    print(f"  HOST     {db.get('HOST')}:{db.get('PORT')}")
print(f"  DEBUG    {settings.DEBUG}")
print()

# ---------------------------------------------------------------------------
# 마이그레이션
# ---------------------------------------------------------------------------
line("=")
print("마이그레이션")
line("=")
executor = MigrationExecutor(connection)
plan = executor.migration_plan(executor.loader.graph.leaf_nodes())
applied = executor.loader.applied_migrations

by_app: dict[str, list[str]] = {}
for app_label, mig_name in sorted(applied):
    by_app.setdefault(app_label, []).append(mig_name)

for app_label in sorted(by_app):
    migs = by_app[app_label]
    print(f"  {app_label:16} 적용 {len(migs)}개  (마지막: {migs[-1]})")

if plan:
    print()
    print(f"  ** 미적용 {len(plan)}개 — `manage.py migrate` 를 실행해야 한다 **")
    for migration, _ in plan:
        print(f"     - {migration.app_label}.{migration.name}")
else:
    print()
    print("  미적용 없음. 최신 상태다.")
print()

# ---------------------------------------------------------------------------
# 모델별 행 수
# ---------------------------------------------------------------------------
line("=")
print("모델별 행 수")
line("=")
OURS = {"accounts", "events", "planning"}
ours, others = [], []
for model in django_apps.get_models():
    label = model._meta.app_label
    try:
        count = model.objects.count()
    except Exception as e:
        count = f"조회 실패 ({type(e).__name__})"
    row = (f"{label}.{model.__name__}", count, model._meta.db_table)
    (ours if label in OURS else others).append(row)

print("  [우리 앱]")
for full, count, table in sorted(ours):
    print(f"    {full:34} {str(count):>6}   {table}")
print()
print("  [Django 내장]")
for full, count, table in sorted(others):
    print(f"    {full:34} {str(count):>6}   {table}")
print()

# ---------------------------------------------------------------------------
# 계정 요약
# ---------------------------------------------------------------------------
from apps.accounts.models import Profile, User  # noqa: E402

line("=")
print("계정 요약")
line("=")
print(f"  전체 사용자        {User.objects.count()}")
print(f"  superuser         {User.objects.filter(is_superuser=True).count()}")
print(f"  staff             {User.objects.filter(is_staff=True).count()}")
print(f"  비활성            {User.objects.filter(is_active=False).count()}")
print(f"  프로필 없는 사용자  {User.objects.filter(profile__isnull=True).count()}")
print(f"  프로필            {Profile.objects.count()}")

admins = list(User.objects.filter(is_superuser=True).values_list("email", flat=True))
if admins:
    print(f"  관리자 계정        {', '.join(admins)}")
else:
    print("  관리자 계정        없음 — admin 에 로그인할 수 없다.")
    print("                     manage.py createsuperuser 로 만든다.")
print()

if SHOW_ROWS:
    line("=")
    print("사용자 목록  (비밀번호 해시는 출력하지 않는다)")
    line("=")
    for u in User.objects.order_by("id"):
        p = Profile.objects.filter(user=u).first()
        flags = []
        if u.is_superuser:
            flags.append("superuser")
        if u.is_staff:
            flags.append("staff")
        if not u.is_active:
            flags.append("비활성")
        tag = f"  [{','.join(flags)}]" if flags else ""
        print(
            f"  id={u.id:<3} {u.email:<32} {u.nickname:<10} "
            f"tau={getattr(p, 'default_tau', '-')}{tag}"
        )
    print()

line("=")
print("확인 방법")
line("=")
print("  1. Django admin      http://127.0.0.1:8000/admin/")
print("  2. 이 스크립트        scripts\\db_status.py [--rows]")
print("  3. SQL 직접          manage.py dbshell   (.tables / .schema accounts_user)")
print("  4. ORM 셸            manage.py shell")
print("  5. 사용자만          scripts\\dump_users.py")
print("  6. GUI               DB Browser for SQLite 로 db.sqlite3 열기")
