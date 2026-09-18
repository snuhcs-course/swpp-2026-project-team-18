"""일정 API 를 실제 HTTP 로 검증한다.

핵심 확인 사항
  1. **새 계정의 일정 목록은 비어 있다.** 표본 데이터가 섞이지 않는다
  2. 일정 추가 → 목록에 반영된다
  3. **다른 사용자의 일정이 보이지 않는다** (사용자 스코프)
  4. 집 위치가 없으면 알람 계획이 NO_HOME 이다
  5. 집 위치를 설정하면 알람이 계산되고 확률은 null 로 남는다

서버가 떠 있어야 한다.
    .\\.venv\\Scripts\\python.exe scripts\\check_events_api.py
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

BASE = "http://127.0.0.1:8000"
KST = timezone(timedelta(hours=9))
results: list[tuple[str, bool, str]] = []


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return -1, {"_exc": f"{type(e).__name__}: {e}"}


def check(name, cond, note):
    results.append((name, bool(cond), note))
    print(f"{'[ OK ]' if cond else '[FAIL]'} {name}\n       {note}\n")


def signup(slug: str, nickname: str):
    """slug 는 ASCII 여야 한다. 이메일 검증기가 한글을 거부한다."""
    email = f"ev_{slug}_{uuid.uuid4().hex[:6]}@snu.ac.kr"
    st, body = call("POST", "/api/auth/register", {
        "email": email, "nickname": nickname,
        "password": "swpp2026Alarm!", "password_confirm": "swpp2026Alarm!",
    })
    assert st == 201, f"회원가입 실패 {st} {body}"
    return email, body["access"]


print("=" * 74)
print("일정 API 검증")
print("=" * 74 + "\n")

st, body = call("GET", "/api/health")
check("health", st == 200, f"status={st}")
if st != 200:
    raise SystemExit(1)

# --- 사용자 A / B 생성 -------------------------------------------------------
email_a, token_a = signup("a", "에이")
email_b, token_b = signup("b", "비")
print(f"A = {email_a}\nB = {email_b}\n")

# 1) 새 계정은 일정이 없다
st, body = call("GET", "/api/events", token=token_a)
items = body if isinstance(body, list) else body.get("results", [])
check("새 계정 일정 목록이 빔", st == 200 and len(items) == 0,
      f"status={st} 개수={len(items)}")

# 2) 프로필 초기 상태 — 집 위치 없음
st, prof = call("GET", "/api/profile", token=token_a)
check("새 계정 집 위치 미설정", st == 200 and prof.get("has_home") is False,
      f"status={st} has_home={prof.get('has_home')} default_tau={prof.get('default_tau')}")

# 3) 태그 목록 (마이그레이션 시드)
st, tags = call("GET", "/api/events/tags", token=token_a)
keys = [t["key"] for t in tags] if isinstance(tags, list) else []
check("태그 시드", st == 200 and "class" in keys and "exam" in keys,
      f"status={st} keys={keys}")

# 4) 장소 검색 (카카오 프록시)
st, body = call("GET", "/api/places/search?" + urllib.parse.urlencode({"q": "서울대학교 302동"}),
                token=token_a)
places = body.get("results", [])
first_place = places[0] if places else None
check("장소 검색 프록시", st == 200 and first_place is not None,
      f"status={st} 개수={len(places)} 첫결과={first_place.get('name') if first_place else None}")

# 5) 일정 추가 — 집 위치가 없으니 알람 계획은 NO_HOME
start = (datetime.now(KST) + timedelta(days=1)).replace(hour=9, minute=0, second=0, microsecond=0)
payload = {
    "title": "자료구조 및 알고리즘",
    "start_at": start.isoformat(),
    "tag_key": "class",
}
if first_place:
    payload["place"] = {
        "name": first_place["name"],
        "lat": first_place["lat"],
        "lng": first_place["lng"],
        "address": first_place.get("address", ""),
        "kakao_place_id": first_place.get("kakao_place_id"),
    }
st, created = call("POST", "/api/events", payload, token=token_a)
plan = (created or {}).get("alarm_plan") or {}
event_id = (created or {}).get("id")
check("일정 생성 201", st == 201 and event_id,
      f"status={st} id={event_id} title={created.get('title')!r} "
      f"place={(created.get('place') or {}).get('name')} tag={(created.get('tag') or {}).get('label')}")
check("집 위치 없으면 NO_HOME", plan.get("status") == "no_home",
      f"status={plan.get('status')} label={plan.get('status_label')!r} alarm_at={plan.get('alarm_at')}")

# 6) 목록에 반영
st, body = call("GET", "/api/events", token=token_a)
items = body if isinstance(body, list) else body.get("results", [])
check("목록에 1건 반영", st == 200 and len(items) == 1,
      f"status={st} 개수={len(items)}")

# 7) 사용자 스코프 — B 는 A 의 일정을 못 본다
st, body = call("GET", "/api/events", token=token_b)
b_items = body if isinstance(body, list) else body.get("results", [])
check("B 목록에 A 일정 없음", st == 200 and len(b_items) == 0,
      f"status={st} 개수={len(b_items)}")

# 8) 사용자 스코프 — B 는 A 의 일정을 id 로도 못 읽는다
st, body = call("GET", f"/api/events/{event_id}", token=token_b)
check("B 가 A 일정 상세 접근 시 404", st == 404, f"status={st}")

# 9) 인증 없이 접근 차단
st, body = call("GET", "/api/events")
check("토큰 없이 목록 401", st == 401, f"status={st}")

# 10) 집 위치 설정 → 알람 계산
st, prof = call("PATCH", "/api/profile", {
    "home_lat": 37.4808, "home_lng": 126.9526, "home_label": "신림동",
    "onboarding_prep_min": 28,
}, token=token_a)
check("집 위치 설정", st == 200 and prof.get("has_home") is True,
      f"status={st} has_home={prof.get('has_home')} 재계산={prof.get('recomputed_plans')}")

# 11) 재계산 결과 확인
st, body = call("GET", "/api/events", token=token_a)
items = body if isinstance(body, list) else body.get("results", [])
plan = (items[0].get("alarm_plan") or {}) if items else {}
ok = plan.get("status") == "ok" and plan.get("alarm_at")
check("집 위치 설정 후 알람 계산됨", ok,
      f"status={plan.get('status')} alarm_at={plan.get('alarm_at')} "
      f"준비={plan.get('prep_minutes')}분 이동={plan.get('travel_minutes')}분 "
      f"버퍼={plan.get('buffer_minutes')}분 합계={plan.get('total_minutes')}분 "
      f"수단={plan.get('travel_mode')!r} 경로={plan.get('route_summary')!r}")

# 12) 확률은 여전히 null — 관측이 없으므로 만들 수 없다
check("확률은 null (학습 데이터 없음)", plan.get("on_time_probability") is None,
      f"on_time_probability={plan.get('on_time_probability')} "
      f"tau_used={plan.get('tau_used')}")

# 13) 준비시간이 프로필 값을 따르는지
check("준비시간이 프로필 값(28분)을 씀", plan.get("prep_minutes") == 28,
      f"prep_minutes={plan.get('prep_minutes')}")

# 14) 수정 → 재계산
new_start = (start + timedelta(hours=2)).isoformat()
st, updated = call("PATCH", f"/api/events/{event_id}", {"start_at": new_start}, token=token_a)
new_plan = (updated or {}).get("alarm_plan") or {}
moved = new_plan.get("alarm_at") != plan.get("alarm_at")
check("시각 수정 시 알람 재계산", st == 200 and moved,
      f"status={st} 이전={plan.get('alarm_at')} 이후={new_plan.get('alarm_at')}")

# 15) 장소 없는 일정은 NO_PLACE
st, no_place = call("POST", "/api/events", {
    "title": "장소 미정 모임",
    "start_at": (start + timedelta(days=2)).isoformat(),
}, token=token_a)
np_plan = (no_place or {}).get("alarm_plan") or {}
check("장소 없으면 NO_PLACE", st == 201 and np_plan.get("status") == "no_place",
      f"status={st} plan={np_plan.get('status')} label={np_plan.get('status_label')!r}")

# 16) 삭제
st, _ = call("DELETE", f"/api/events/{no_place.get('id')}", token=token_a)
st2, body2 = call("GET", "/api/events", token=token_a)
items2 = body2 if isinstance(body2, list) else body2.get("results", [])
check("삭제 204 후 목록 1건", st == 204 and len(items2) == 1,
      f"delete={st} 남은개수={len(items2)}")

# 17) B 는 A 의 일정을 삭제할 수 없다
st, _ = call("DELETE", f"/api/events/{event_id}", token=token_b)
check("B 가 A 일정 삭제 시 404", st == 404, f"status={st}")

# 18) 잘못된 태그 거부
st, body = call("POST", "/api/events", {
    "title": "테스트", "start_at": start.isoformat(), "tag_key": "없는태그",
}, token=token_a)
msg = ((body or {}).get("error") or {}).get("message")
check("없는 태그 400", st == 400, f"status={st} message={msg!r}")

print("=" * 74)
print("요약")
print("=" * 74)
w = max(len(n) for n, _, _ in results)
for name, ok, _ in results:
    print(f"  {name:{w}}  {'OK' if ok else 'FAIL'}")
n_ok = sum(1 for _, ok, _ in results if ok)
print(f"\n  {n_ok}/{len(results)} 통과")
raise SystemExit(0 if n_ok == len(results) else 1)
