"""출발지 선택 검증.

경로 후보 조회가 `origin_*` 을 받고, 고른 출발지가 `Event` 에 저장되고, 알람
계산이 **같은 출발지**를 쓰는지 확인한다. 마지막 항목이 이 기능의 핵심이다 —
저장하지 않으면 사용자가 본 소요시간과 알람이 어긋난다.

실행:
    .venv\\Scripts\\python.exe scripts\\check_origin_api.py
"""

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

import django
import requests

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
django.setup()

# 공용/원격 DB 에서 돌리지 못하게 막는다. 이 스크립트는 검증용 계정과


from django.contrib.auth import get_user_model  # noqa: E402

from _capabilities import banner, kakao_configured, kakao_skip_reason  # noqa: E402

BASE = os.environ.get("JIT_BASE", "http://127.0.0.1:8000")

HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"lat": 37.459882, "lng": 126.951905, "name": "서울대학교 관악캠퍼스"}
# 집과 충분히 떨어진 곳. 소요시간이 확실히 달라야 비교가 의미 있다.
ORIGIN = {"lat": 37.5665, "lng": 126.9780, "label": "서울시청"}

passed, failed, skipped = 0, 0, 0
HAS_KAKAO = kakao_configured()


def check(label, ok, note=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}  {note}")


def skip(label, why=""):
    """검사할 수 없었던 항목. 실패로 세지 않는다.

    **키가 있는데 실패하면 그것은 실패다.** 판정은 결과가 아니라 키 유무로
    한다 — 자세한 근거는 `_capabilities` 상단에 있다.
    """
    global skipped
    skipped += 1
    print(f"  [SKIP] {label}  {why or kakao_skip_reason()}")


email = f"origin_{uuid.uuid4().hex[:8]}@example.com"
password = "Zr9-copper-meadow-43"  # 이메일 접두와 유사하면 가입이 400 이 된다(유사도 0.7 임계)

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
print(banner())
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
if HAS_KAKAO:
    check("후보가 1개 이상", len(candidates) >= 1, f"n={len(candidates)}")
else:
    skip("후보가 1개 이상", f"{kakao_skip_reason()} · n={len(candidates)}")
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
if HAS_KAKAO:
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
else:
    skip("주소 1건을 돌려준다", f"{kakao_skip_reason()} · degraded={rev.get('degraded')}")
    skip("좌표는 보낸 값 그대로")
    # 키가 없어도 **이것은** 지켜야 한다. 역지오코딩이 실패할 때 500 을 내면
    # 앱의 출발지 선택 화면이 통째로 죽는다. degraded 로 알려야 한다.
    check(
        "역지오코딩 실패는 degraded 로 알린다 (500 아님)",
        res.status_code == 200 and rev.get("degraded") is True,
        f"status={res.status_code} degraded={rev.get('degraded')}",
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
if HAS_KAKAO:
    check(
        "집이 없어도 status=ok (출발지가 있으니)",
        plan.get("status") == "ok",
        f"status={plan.get('status')} label={plan.get('status_label')!r}",
    )
else:
    skip("집이 없어도 status=ok (출발지가 있으니)",
         f"{kakao_skip_reason()} · status={plan.get('status')}")
    # 이동시간을 못 구해도 **no_home 으로 떨어지면 안 된다.** 출발지가 있으니
    # 집이 없다는 진단은 틀렸고, 그 문구는 사용자를 엉뚱한 설정으로 보낸다.
    # 이 판정은 카카오와 무관하다.
    check(
        "출발지가 있으면 no_home 으로 진단하지 않는다",
        plan.get("status") != "no_home",
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
if HAS_KAKAO:
    check(
        "출발지를 바꾸면 이동 시간이 달라진다",
        travel_from_origin is not None
        and travel_from_home is not None
        and travel_from_origin != travel_from_home,
        f"origin={travel_from_origin}분 home={travel_from_home}분",
    )
else:
    # 양쪽 다 None 이라 "달라졌는가" 를 물을 수 없다. 이것이 이 스크립트의 핵심
    # 항목이므로 건너뛴 사실을 분명히 남긴다.
    skip("출발지를 바꾸면 이동 시간이 달라진다",
         f"{kakao_skip_reason()} · origin={travel_from_origin} home={travel_from_home}")

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
print(f"통과 {passed} / 실패 {failed} / 건너뜀 {skipped}")
print("=" * 74)
raise SystemExit(1 if failed else 0)
