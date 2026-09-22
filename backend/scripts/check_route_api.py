"""경로 선택 API 검증.

흐름: 가입 → 집 설정 → 경로 후보 조회 → 후보를 골라 일정 생성 →
알람이 그 경로로 계산됐는지 확인 → 다른 경로로 바꿔 재계산 확인.

사용법:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\check_route_api.py

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
from datetime import timedelta
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

from _capabilities import banner, kakao_configured, kakao_skip_reason  # noqa: E402

HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {
    "name": "서울대학교 관악캠퍼스",
    "lat": 37.459786,
    "lng": 126.951124,
    "address": "서울 관악구 관악로 1",
    "kakao_place_id": "11137036",
}

results: list[tuple[str, bool, str]] = []
skipped: list[tuple[str, str]] = []
HAS_KAKAO = kakao_configured()


def check(name: str, ok: bool, note: str = "") -> None:
    results.append((name, bool(ok), note))


def skip(name: str, why: str = "") -> None:
    """검사할 수 없었던 항목. 실패로 세지 않는다.

    이 스크립트는 거의 전부가 카카오 경로 응답에 달려 있다. 그래도 통째로
    건너뛰지 않는다 — 좌표 검증·권한·`route_key` 형식 같은 **서버 자체 로직**은
    키 없이도 확인할 수 있고, 그것이 회귀하면 여기서 잡아야 한다.

    판정은 결과가 아니라 키 유무로 한다. 근거는 `_capabilities` 상단에 있다.
    """
    skipped.append((name, why or kakao_skip_reason()))


def brief(res) -> str:
    return res.text[:200]


# 이전 실행이 남긴 계정 정리
stale = get_user_model().objects.filter(email__startswith="rt_")
if stale.exists():
    stale.delete()

email = f"rt_{uuid.uuid4().hex[:6]}@snu.ac.kr"
res = requests.post(
    f"{BASE}/api/auth/register",
    json={
        "email": email,
        "nickname": "경로",
        "password": "routecheck1234",
        "password_confirm": "routecheck1234",
    },
    timeout=20,
)
assert res.status_code == 201, f"register {res.status_code}: {brief(res)}"
auth = {"Authorization": f"Bearer {res.json()['access']}"}

# --- 1. 집 없이 후보 조회하면 409 -------------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": DEST["lat"], "dest_lng": DEST["lng"]},
    headers=auth,
    timeout=30,
)
check(
    "집 미설정이면 409 no_home",
    res.status_code == 409 and res.json().get("error", {}).get("code") == "no_home",
    f"status={res.status_code}",
)

# --- 2. 집 설정 -------------------------------------------------------------
res = requests.patch(
    f"{BASE}/api/profile",
    json={
        "home_lat": HOME["lat"],
        "home_lng": HOME["lng"],
        "home_label": HOME["label"],
        "onboarding_prep_min": 30,
    },
    headers=auth,
    timeout=30,
)
check("집 위치 저장 200", res.status_code == 200, f"status={res.status_code}")

# --- 3. 좌표 누락 400 -------------------------------------------------------
res = requests.get(f"{BASE}/api/routes/candidates", headers=auth, timeout=20)
check("좌표 없으면 400", res.status_code == 400, f"status={res.status_code}")

res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": 999, "dest_lng": 0},
    headers=auth,
    timeout=20,
)
check("좌표 범위 밖이면 400", res.status_code == 400, f"status={res.status_code}")

# --- 4. 후보 조회 -----------------------------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": DEST["lat"], "dest_lng": DEST["lng"]},
    headers=auth,
    timeout=60,
)
check("후보 조회 200", res.status_code == 200, f"status={res.status_code}")
body = res.json() if res.status_code == 200 else {}
candidates = body.get("results") or []

# 출발지 에코는 카카오와 무관하다. 후보가 비어도 서버는 어디서 출발하는지
# 말해야 한다 — 앱이 그 값을 화면에 띄운다.
check(
    "출발지가 프로필 집",
    (body.get("origin") or {}).get("label") == HOME["label"],
    str(body.get("origin")),
)

if HAS_KAKAO:
    check("후보가 2개 이상", len(candidates) >= 2, f"n={len(candidates)}")
    check(
        "후보 key 중복 없음",
        len({c["key"] for c in candidates}) == len(candidates),
    )
    check(
        "소요시간 오름차순",
        [c["minutes"] for c in candidates] == sorted(c["minutes"] for c in candidates),
    )
    check(
        "모든 후보에 mode·minutes·key 있음",
        all(c.get("mode") and c.get("minutes") and c.get("key") for c in candidates),
    )
    check(
        "가장 빠름 라벨이 첫 항목에만",
        sum(1 for c in candidates if c.get("reason") == "가장 빠름") == 1
        and candidates[0].get("reason") == "가장 빠름",
        str([c.get("reason") for c in candidates]),
    )
else:
    for label in (
        "후보가 2개 이상",
        "후보 key 중복 없음",
        "소요시간 오름차순",
        "모든 후보에 mode·minutes·key 있음",
        "가장 빠름 라벨이 첫 항목에만",
    ):
        skip(label)
    # 키가 없어도 **빈 목록으로 200** 이어야 한다. 500 을 내면 앱의 경로 선택
    # 화면이 통째로 죽는다. 이 판정은 카카오 응답 내용과 무관하다.
    check("경로를 못 구해도 200 과 빈 목록", res.status_code == 200 and candidates == [],
          f"status={res.status_code} n={len(candidates)}")

# 가장 빠른 것이 아닌 후보를 고른다. 선택이 실제로 반영되는지 보려면
# 기본값(최단)과 달라야 한다.
chosen = None
for c in reversed(candidates):
    if c is not candidates[0]:
        chosen = c
        break

# 예전에는 여기서 맨 `assert` 로 중단했다. 카카오 키가 없으면 스크립트가
# Traceback 으로 죽어서 **뒤에 있는 33개 중 10여 개의 키 무관 검사까지 전부
# 못 돌았다.** 중단하지 않고 그 구간만 건너뛴다.
if chosen is None and HAS_KAKAO:
    check("고를 후보가 있다", False, f"후보 {len(candidates)}개 — 경로 조회가 회귀했다")

# --- 5. 경로를 골라 일정 생성 ------------------------------------------------
start = (djtz.localtime() + timedelta(days=1)).replace(
    hour=9, minute=0, second=0, microsecond=0
)
create_payload = {
    "title": "경로 선택 확인",
    "start_at": start.isoformat(),
    "tag_key": "class",
    "place": DEST,
}
if chosen:
    create_payload["route_key"] = chosen["key"]

res = requests.post(f"{BASE}/api/events", json=create_payload, headers=auth, timeout=60)
# 경로를 못 골랐어도 일정은 만들어져야 한다. 뒤의 `route_key` 형식 검증과
# 권한 검사가 이 일정을 쓴다.
check("경로 지정 일정 생성 201", res.status_code == 201, f"status={res.status_code} {brief(res)}")
event = res.json() if res.status_code == 201 else {}
plan = event.get("alarm_plan") or {}

if chosen:
    check("일정에 route_key 저장", event.get("route_key") == chosen["key"], str(event.get("route_key")))
    check("알람 상태 ok", plan.get("status") == "ok", str(plan.get("status")))
    check(
        "선택한 경로의 소요시간이 쓰임",
        plan.get("travel_minutes") == chosen["minutes"],
        f"plan={plan.get('travel_minutes')} chosen={chosen['minutes']}",
    )
    check(
        "사용한 경로 key 가 선택과 일치",
        plan.get("route_key") == chosen["key"],
        f"{plan.get('route_key')} vs {chosen['key']}",
    )
    check("route_choice_honored=true", plan.get("route_choice_honored") is True,
          str(plan.get("route_choice_honored")))
    check(
        "이동시간 출처가 카카오",
        (plan.get("travel_time_source") or "").startswith("kakao_"),
        str(plan.get("travel_time_source")),
    )
    check("준비시간 출처 onboarding", plan.get("prep_source") == "onboarding",
          str(plan.get("prep_source")))
    check("버퍼 출처 fixed", plan.get("buffer_source") == "fixed", str(plan.get("buffer_source")))
else:
    for label in (
        "일정에 route_key 저장",
        "알람 상태 ok",
        "선택한 경로의 소요시간이 쓰임",
        "사용한 경로 key 가 선택과 일치",
        "route_choice_honored=true",
        "이동시간 출처가 카카오",
        "준비시간 출처 onboarding",
        "버퍼 출처 fixed",
    ):
        skip(label)

# 산식: 시작 - 버퍼 - 이동 - 준비 = 알람
if plan.get("status") == "ok":
    total = plan["prep_minutes"] + plan["travel_minutes"] + plan["buffer_minutes"]
    check("total_minutes 일치", plan.get("total_minutes") == total, f"{plan.get('total_minutes')} vs {total}")
else:
    skip("total_minutes 일치", f"계획 상태가 {plan.get('status')} — 값이 없다")

# --- 6. 경로를 바꾸면 알람이 다시 계산된다 -----------------------------------
other = candidates[0] if candidates else None
if other and chosen and other["key"] != chosen["key"]:
    res = requests.patch(
        f"{BASE}/api/events/{event['id']}",
        json={"route_key": other["key"]},
        headers=auth,
        timeout=60,
    )
    check("경로 변경 200", res.status_code == 200, f"status={res.status_code}")
    plan2 = (res.json() or {}).get("alarm_plan") or {}
    check(
        "바꾼 경로의 소요시간으로 재계산",
        plan2.get("travel_minutes") == other["minutes"],
        f"plan={plan2.get('travel_minutes')} other={other['minutes']}",
    )
    check(
        "알람 시각이 달라짐",
        plan2.get("alarm_at") != plan.get("alarm_at"),
        f"{plan.get('alarm_at')} -> {plan2.get('alarm_at')}",
    )
else:
    for label in ("경로 변경 200", "바꾼 경로의 소요시간으로 재계산", "알람 시각이 달라짐"):
        skip(label, "바꿔 볼 다른 후보가 없다" if HAS_KAKAO else kakao_skip_reason())

# --- 7. 잘못된 route_key 는 400 --------------------------------------------
res = requests.patch(
    f"{BASE}/api/events/{event['id']}",
    json={"route_key": "지하철타고가기"},
    headers=auth,
    timeout=20,
)
check("형식 틀린 route_key 400", res.status_code == 400, f"status={res.status_code}")

# --- 8. route_key 를 비우면 서버가 알아서 고른다 ----------------------------
res = requests.patch(
    f"{BASE}/api/events/{event['id']}",
    json={"route_key": ""},
    headers=auth,
    timeout=60,
)
check("route_key 비우기 200", res.status_code == 200, f"status={res.status_code}")
plan3 = (res.json() or {}).get("alarm_plan") or {}
if HAS_KAKAO:
    check("비우면 상태 ok 유지", plan3.get("status") == "ok", str(plan3.get("status")))
else:
    skip("비우면 상태 ok 유지", f"{kakao_skip_reason()} · status={plan3.get('status')}")
# 이것은 키와 무관하다. 고른 적이 없으면 "지켰다/못 지켰다" 를 말할 수 없으므로
# null 이어야 한다. false 로 내리면 앱이 "서버가 내 선택을 무시했다" 고 표시한다.
check(
    "고른 적 없으면 honored=null",
    plan3.get("route_choice_honored") is None,
    str(plan3.get("route_choice_honored")),
)

# --- 9. 출발지 -------------------------------------------------------------
# 출발지는 `origin_lat`/`origin_lng` 로만 받는다. 그 외 이름은 무시하고 집을 쓴다.
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={
        "dest_lat": DEST["lat"],
        "dest_lng": DEST["lng"],
        "start_lat": 35.1,
        "start_lng": 129.0,
    },
    headers=auth,
    timeout=60,
)
ok = res.status_code == 200 and (res.json().get("origin") or {}).get("label") == HOME["label"]
check("모르는 출발지 파라미터는 무시됨", ok, str((res.json() or {}).get("origin")))

# 집이 아닌 곳에서 출발할 수 있어야 한다. 집에서만 출발한다는 가정이 틀리기 때문이다.
ORIGIN = {"lat": 37.5665, "lng": 126.9780, "label": "서울시청"}
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
body = res.json() if res.status_code == 200 else {}
origin = body.get("origin") or {}
check(
    "origin_* 을 보내면 그 출발지를 쓴다",
    res.status_code == 200
    and abs((origin.get("lat") or 0) - ORIGIN["lat"]) < 1e-6
    and origin.get("label") == ORIGIN["label"],
    f"status={res.status_code} origin={origin}",
)

# 좌표 하나만 오면 400 이다. 조용히 집으로 돌아가면 사용자가 고른 곳과
# 다르게 계산되는데 아무도 알 수 없다.
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": DEST["lat"], "dest_lng": DEST["lng"], "origin_lat": ORIGIN["lat"]},
    headers=auth,
    timeout=30,
)
check("출발지 좌표가 반쪽이면 400", res.status_code == 400, f"status={res.status_code}")

# 국외 좌표는 막는다. 카카오가 경로를 주지 않고, 열어 두면 이 엔드포인트가
# 전 세계 경로 프록시가 된다.
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

# --- 10. 인증 없이는 401 ----------------------------------------------------
res = requests.get(
    f"{BASE}/api/routes/candidates",
    params={"dest_lat": DEST["lat"], "dest_lng": DEST["lng"]},
    timeout=20,
)
check("인증 없으면 401", res.status_code == 401, f"status={res.status_code}")

# --- 결과 ------------------------------------------------------------------
get_user_model().objects.filter(email=email).delete()

print("=" * 74)
print("경로 선택 API 검증")
print(banner())
print("=" * 74)
passed = 0
for name, ok, note in results:
    mark = "OK" if ok else "실패"
    tail = f"   {note}" if (note and not ok) else ""
    print(f"  {name:<40} {mark}{tail}")
    passed += ok
for name, why in skipped:
    print(f"  {name:<40} 건너뜀   {why}")
print()
print(f"  통과 {passed} / 실패 {len(results) - passed} / 건너뜀 {len(skipped)}")
print()
print("  참고 — 조회된 후보")
for c in candidates:
    fare = f"{c['fare']:,}원" if c["fare"] else ("무료" if c["fare"] == 0 else "요금 미정")
    print(f"    {c['minutes']:>3}분  {c['mode']:<14} 환승{c['transfers']}  {fare:<10} {c['detail']}  {c.get('reason', '')}")

sys.exit(0 if passed == len(results) else 1)
