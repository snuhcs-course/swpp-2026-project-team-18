"""출발지 선택 검증.

경로 후보 조회가 `origin_*` 을 받고, 고른 출발지가 `Event` 에 저장되고, 알람
계산이 **같은 출발지**를 쓰는지 확인한다. 마지막 항목이 이 기능의 핵심이다 —
저장하지 않으면 사용자가 본 소요시간과 알람이 어긋난다.

실행:
    .venv\\Scripts\\python.exe scripts\\check_origin_api.py
"""

import os
import sys
import uuid

import django
import requests

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
django.setup()

from django.contrib.auth import get_user_model  # noqa: E402

BASE = os.environ.get("JIT_BASE", "http://127.0.0.1:8000")

HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"lat": 37.459882, "lng": 126.951905, "name": "서울대학교 관악캠퍼스"}
# 집과 충분히 떨어진 곳. 소요시간이 확실히 달라야 비교가 의미 있다.
ORIGIN = {"lat": 37.5665, "lng": 126.9780, "label": "서울시청"}

passed, failed = 0, 0


def check(label, ok, note=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}  {note}")


email = f"origin_{uuid.uuid4().hex[:8]}@example.com"
password = "originchk1234"

res = requests.post(
    f"{BASE}/api/auth/register",
    json={
        "email": email,
        "nickname": "출발지검증",
        "password": password,
        "password_confirm": password,
    },
    timeout=30,
)
if res.status_code != 201:
    print(f"회원가입 실패 status={res.status_code} body={res.text[:300]}")
    raise SystemExit(1)

auth = {"Authorization": f"Bearer {res.json()['access']}"}

print("=" * 74)
print("출발지 선택 검증")
print("=" * 74)

# --- 1. 집 없이도 출발지를 직접 주면 경로가 나온다 --------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={
        "dest_lat": DEST["lat"],
        "dest_lng": DEST["lng"],
        "origin_lat": ORIGIN["lat"],
        "origin_lng": ORIGIN["lng"],
        "origin_label": ORIGIN["label"],
    },
    headers=auth,
    timeout=60,
)
check(
    "집이 없어도 출발지를 주면 200",
    res.status_code == 200,
    f"status={res.status_code} body={res.text[:160]}",
)
body = res.json() if res.status_code == 200 else {}
origin_echo = body.get("origin") or {}
check(
    "응답 origin 이 보낸 좌표와 같다",
    abs((origin_echo.get("lat") or 0) - ORIGIN["lat"]) < 1e-6,
    str(origin_echo),
)
candidates = body.get("results") or []
check("후보가 1개 이상", len(candidates) >= 1, f"n={len(candidates)}")
picked = candidates[0]["key"] if candidates else None
minutes_from_origin = candidates[0]["minutes"] if candidates else None

# --- 2. 집도 없고 출발지도 없으면 409 ---------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": DEST["lat"], "dest_lng": DEST["lng"]},
    headers=auth,
    timeout=30,
)
check("집·출발지 둘 다 없으면 409", res.status_code == 409, f"status={res.status_code}")

# --- 3. 반쪽 좌표는 400 -----------------------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={
        "dest_lat": DEST["lat"],
        "dest_lng": DEST["lng"],
        "origin_lat": ORIGIN["lat"],
    },
    headers=auth,
    timeout=30,
)
check("좌표가 반쪽이면 400", res.status_code == 400, f"status={res.status_code}")

# --- 4. 국외 좌표는 400 -----------------------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={
        "dest_lat": DEST["lat"],
        "dest_lng": DEST["lng"],
        "origin_lat": 48.8584,
        "origin_lng": 2.2945,
    },
    headers=auth,
    timeout=30,
)
check("국외 출발지는 400", res.status_code == 400, f"status={res.status_code}")

# --- 5. 역지오코딩 ----------------------------------------------------------
res = requests.get(
    f"{BASE}/api/places/reverse",
    params={"lat": ORIGIN["lat"], "lng": ORIGIN["lng"]},
    headers=auth,
    timeout=30,
)
check("좌표→주소 200", res.status_code == 200, f"status={res.status_code}")
rev = (res.json() or {}) if res.status_code == 200 else {}
result = rev.get("result")
check(
    "주소 1건을 돌려준다",
    bool(result and result.get("address")),
    f"degraded={rev.get('degraded')} result={result}",
)
check(
    "좌표는 보낸 값 그대로",
    bool(result) and abs((result.get("lat") or 0) - ORIGIN["lat"]) < 1e-6,
    str(result),
)

res = requests.get(
    f"{BASE}/api/places/reverse",
    params={"lat": 999, "lng": 0},
    headers=auth,
    timeout=20,
)
check("좌표 범위 밖이면 400", res.status_code == 400, f"status={res.status_code}")

# --- 6. 출발지를 저장한 일정의 알람이 그 출발지로 계산된다 -------------------
# 이 기능의 핵심이다. 저장한 출발지를 계산기가 무시하면 화면에 보인 소요시간과
# 알람이 어긋난다.
create_body = {
    "title": "출발지 검증 일정",
    "start_at": "2026-12-25T18:00:00+09:00",
    "place": {"name": DEST["name"], "lat": DEST["lat"], "lng": DEST["lng"]},
    "tag_key": "class",
    "origin_lat": ORIGIN["lat"],
    "origin_lng": ORIGIN["lng"],
    "origin_label": ORIGIN["label"],
}
res = requests.post(f"{BASE}/api/events", json=create_body, headers=auth, timeout=60)
check("출발지 포함 일정 생성 201", res.status_code == 201, f"status={res.status_code} {res.text[:200]}")
created = res.json() if res.status_code == 201 else {}
event_id = created.get("id")
check(
    "응답에 출발지가 남아 있다",
    abs((created.get("origin_lat") or 0) - ORIGIN["lat"]) < 1e-6,
    f"origin_lat={created.get('origin_lat')}",
)
plan = created.get("alarm_plan") or {}
check(
    "집이 없어도 status=ok (출발지가 있으니)",
    plan.get("status") == "ok",
    f"status={plan.get('status')} label={plan.get('status_label')!r}",
)
travel_from_origin = plan.get("travel_minutes")

# --- 7. 출발지를 집으로 바꾸면 소요시간이 달라진다 --------------------------
# 같은 목적지·같은 경로 key 인데 출발지만 다르면 이동 시간이 달라야 한다.
# 같으면 계산기가 출발지를 무시하고 있다는 뜻이다.
res = requests.patch(
    f"{BASE}/api/profile",
    json={"home_lat": HOME["lat"], "home_lng": HOME["lng"], "home_label": HOME["label"]},
    headers=auth,
    timeout=60,
)
check("집 설정 200", res.status_code == 200, f"status={res.status_code}")

res = requests.patch(
    f"{BASE}/api/events/{event_id}",
    json={"origin_lat": None, "origin_lng": None, "origin_label": ""},
    headers=auth,
    timeout=60,
)
check("출발지 비우기 200", res.status_code == 200, f"status={res.status_code} {res.text[:200]}")
plan_home = (res.json() or {}).get("alarm_plan") or {}
travel_from_home = plan_home.get("travel_minutes")
check(
    "출발지를 바꾸면 이동 시간이 달라진다",
    travel_from_origin is not None
    and travel_from_home is not None
    and travel_from_origin != travel_from_home,
    f"origin={travel_from_origin}분 home={travel_from_home}분",
)

# --- 8. 반쪽 좌표는 API 층에서 막힌다 ---------------------------------------
res = requests.patch(
    f"{BASE}/api/events/{event_id}",
    json={"origin_lat": ORIGIN["lat"]},
    headers=auth,
    timeout=30,
)
check("일정에 반쪽 좌표는 400", res.status_code == 400, f"status={res.status_code}")

# --- 정리 ------------------------------------------------------------------
get_user_model().objects.filter(email=email).delete()

print("=" * 74)
print(f"통과 {passed} / 실패 {failed}")
print("=" * 74)
raise SystemExit(1 if failed else 0)
