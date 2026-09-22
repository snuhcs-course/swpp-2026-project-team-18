"""타임존 오프셋 처리를 검증한다.

에뮬레이터가 GMT 라서 앱 E2E 로는 오프셋 취급이 맞는지 알 수 없다.
KST(+09:00) 로 보낸 시각이 UTC 로 옳게 저장되고, 알람 산식이 그 위에서
맞게 도는지 실행 중인 서버에 HTTP 로 확인한다.

검증용 계정(`tz_*@snu.ac.kr`)을 만들고 끝나면 지운다. 이전 실행이 중간에
죽어 남긴 계정도 시작할 때 정리한다.

사용법:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\check_timezone.py

    # manage.py runserver 0.0.0.0:8000 이 떠 있어야 한다.
"""


from __future__ import annotations

# --- 공용 DB 보호 -----------------------------------------------------------
# 이 스크립트는 검증용 계정과 일정을 만든다. 공용 DB 에서 돌리면 팀 전체가
# 보는 목록이 테스트 데이터로 채워진다. 실제로 그런 사고가 있었다 -
# 근거와 재현 조건은 scripts/_local_guard.py 상단에 적어 두었다.
#
# **부수효과가 생기기 전에** 돌아야 하므로 맨 위에 둔다.
import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parent))
from _local_guard import require_local_database  # noqa: E402

require_local_database()
# ---------------------------------------------------------------------------


import os
import sys
import uuid
from datetime import date, timedelta
from datetime import timezone as pytz
from pathlib import Path

import requests

BASE = "http://127.0.0.1:8000"

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

# 공용/원격 DB 에서 돌리지 못하게 막는다. 이 스크립트는 검증용 계정과


from django.contrib.auth import get_user_model  # noqa: E402
from django.utils import timezone as djtz  # noqa: E402

from apps.events.models import Event  # noqa: E402


def brief(res) -> str:
    """에러 HTML 을 통째로 찍지 않는다."""
    text = res.text
    return text[:300] if len(text) <= 300 else text[:300] + " ...(생략)"


# 이전 실행이 중간에 죽어 남긴 검증 계정을 먼저 지운다.
stale = get_user_model().objects.filter(email__startswith="tz_")
if stale.exists():
    print(f"  이전 검증 계정 {stale.count()}개 정리")
    stale.delete()

suffix = uuid.uuid4().hex[:6]
email = f"tz_{suffix}@snu.ac.kr"

res = requests.post(
    f"{BASE}/api/auth/register",
    json={
        "email": email,
        "nickname": "tz",
        "password": "tzcheck1234",
        "password_confirm": "tzcheck1234",
    },
    timeout=20,
)
assert res.status_code == 201, f"register {res.status_code}: {brief(res)}"
token = res.json()["access"]
auth = {"Authorization": f"Bearer {token}"}

res = requests.patch(
    f"{BASE}/api/profile",
    json={
        "home_lat": 37.484267,
        "home_lng": 126.929745,
        "home_label": "신림역",
        "onboarding_prep_min": 30,
    },
    headers=auth,
    timeout=30,
)
assert res.status_code == 200, f"profile {res.status_code}: {brief(res)}"

# 내일 09:00 KST. UTC 로는 같은 날 00:00 이다.
tomorrow: date = (djtz.localtime() + timedelta(days=1)).date()
start_kst = f"{tomorrow.isoformat()}T09:00:00+09:00"

res = requests.post(
    f"{BASE}/api/events",
    json={
        "title": "타임존 확인",
        "start_at": start_kst,
        "tag_key": "class",
        "place": {
            "name": "서울대학교 관악캠퍼스",
            "lat": 37.459786,
            "lng": 126.951124,
            "address": "서울 관악구 관악로 1",
            "kakao_place_id": "11137036",
        },
    },
    headers=auth,
    timeout=60,
)
assert res.status_code == 201, f"event {res.status_code}: {brief(res)}"
body = res.json()

event = Event.objects.get(pk=body["id"])
plan = event.alarm_plan

utc = event.start_at.astimezone(pytz.utc)
kst = djtz.localtime(event.start_at)

print("=" * 74)
print("타임존 오프셋 검증")
print("=" * 74)
print(f"  보낸 값            {start_kst}")
print(f"  DB 저장 (UTC)      {utc:%Y-%m-%d %H:%M %Z}")
print(f"  DB 저장 (KST)      {kst:%Y-%m-%d %H:%M %Z}")
print(f"  API 응답 start_at  {body['start_at']}")
print()
print(f"  UTC 0시인가        {'OK' if utc.hour == 0 else f'실패 (h={utc.hour})'}")
print(f"  KST 9시인가        {'OK' if kst.hour == 9 else f'실패 (h={kst.hour})'}")
print()
print(f"  status            {plan.status}")

if plan.status == "ok":
    total = plan.prep_minutes + plan.travel_minutes + plan.buffer_minutes
    delta = int((event.start_at - plan.alarm_at).total_seconds() // 60)
    print(f"  준비/이동/버퍼      {plan.prep_minutes} / {plan.travel_minutes} / {plan.buffer_minutes}")
    print(f"  알람 (KST)         {djtz.localtime(plan.alarm_at):%Y-%m-%d %H:%M}")
    print(f"  출발 (KST)         {djtz.localtime(plan.depart_by):%H:%M}")
    print(f"  도착 (KST)         {djtz.localtime(plan.arrive_at):%H:%M}")
    print(f"  산식              {'OK' if delta == total else f'실패 ({delta}분 vs 합계 {total}분)'}")
    print(f"  확률              {plan.on_time_probability!r}  (관측 없으면 None)")
    print(f"  경로              {plan.travel_mode} · {plan.route_summary}")

# 뒷정리. 검증용 계정과 일정을 남기지 않는다.
event.user.delete()
print()
print("  검증 계정 삭제 완료")
