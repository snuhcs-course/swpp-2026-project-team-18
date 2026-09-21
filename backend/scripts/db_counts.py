"""현재 DATABASE_URL 이 가리키는 DB 의 행 수를 센다.

이관 전후를 대조하는 데 쓴다. 옮기고 나서 "된 것 같다" 로 넘어가면 일부만
넘어간 것을 몇 주 뒤에 발견한다.

    # 로컬 SQLite
    python scripts/db_counts.py

    # 공용 Postgres
    $env:DATABASE_URL="postgres://..."
    python scripts/db_counts.py

출력은 표준출력과 `--out` 파일 양쪽에 쓴다. 콘솔 코드페이지 때문에 한글이
깨져 보이는 경우가 있어 파일 쪽이 확실하다.
"""

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from django.apps import apps  # noqa: E402
from django.contrib.auth import get_user_model  # noqa: E402
from django.db import connection  # noqa: E402

# 세어 볼 모델. contenttypes·permission 은 마이그레이션이 만드는 것이라
# 이관 대상이 아니고 수가 달라도 정상이다.
TARGETS = [
    ("accounts", "User"),
    ("accounts", "Profile"),
    ("events", "Place"),
    ("events", "EventTag"),
    ("events", "Event"),
    ("planning", "AlarmPlan"),
    ("observations", "TripObservation"),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="")
    out_path = parser.parse_args().out

    lines = []
    vendor = connection.vendor
    name = connection.settings_dict.get("NAME", "")
    host = connection.settings_dict.get("HOST", "") or "(local file)"
    lines.append(f"vendor = {vendor}")
    lines.append(f"db     = {name}")
    lines.append(f"host   = {host}")
    lines.append("")

    total = 0
    for app_label, model_name in TARGETS:
        try:
            model = apps.get_model(app_label, model_name)
        except LookupError:
            lines.append(f"  {app_label}.{model_name:18} (모델 없음)")
            continue
        try:
            n = model.objects.count()
        except Exception as e:  # 테이블이 아직 없는 경우
            lines.append(f"  {app_label}.{model_name:18} 조회 실패: {type(e).__name__}")
            continue
        total += n
        lines.append(f"  {app_label}.{model_name:18} {n:>6}")

    lines.append("")
    lines.append(f"  합계(대상 모델만)         {total:>6}")

    # 계정 목록은 이관이 제대로 됐는지 가장 빨리 확인할 수 있는 지표다.
    User = get_user_model()
    lines.append("")
    lines.append("계정:")
    for u in User.objects.order_by("id")[:20]:
        has_home = getattr(getattr(u, "profile", None), "home_lat", None) is not None
        lines.append(f"  #{u.id} {u.email}  집={'O' if has_home else 'X'}")

    text = "\n".join(lines) + "\n"
    print(text)
    if out_path:
        Path(out_path).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
