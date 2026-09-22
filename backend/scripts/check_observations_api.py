"""이동 관측 API 를 실제 HTTP 로 검증한다.

핵심 확인 사항
  1. **재전송해도 행이 늘지 않는다.** 같은 client_uuid 는 무시된다
  2. 계획 대비 지연이 부호까지 맞게 계산된다
  3. **오차가 큰 위치는 거부된다.** 흐린 fix 로 반경 판정을 받아 주면 학습이 오염된다
  4. 남의 일정에 관측을 심을 수 없다 (404)
  5. 계획이 없는 일정의 관측은 지연이 null 이다 (임의값을 만들지 않는다)

서버가 떠 있어야 한다.
    .\\.venv\\Scripts\\python.exe scripts\\check_observations_api.py
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


import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

# 공용/원격 DB 에서 돌리지 못하게 막는다. 이 스크립트는 검증용 계정과


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
        with urllib.request.urlopen(req, timeout=30) as r:
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
    email = f"obs_{slug}_{uuid.uuid4().hex[:6]}@snu.ac.kr"
    st, body = call(
        "POST",
        "/api/auth/register",
        {
            "email": email,
            "nickname": nickname,
            "password": "swpp2026Alarm!",
            "password_confirm": "swpp2026Alarm!",
        },
    )
    assert st == 201, f"회원가입 실패 {st} {body}"
    return email, body["access"]


def items_of(body):
    """페이지네이션 응답과 배열 응답을 함께 받는다."""
    return body if isinstance(body, list) else body.get("results", [])


def err_message(body):
    return ((body or {}).get("error") or {}).get("message")


def iso(dt: datetime) -> str:
    return dt.isoformat()


def parse(raw: str) -> datetime:
    return datetime.fromisoformat(raw)


def observation(event_id, kind, observed_at, **over):
    """관측 한 건의 기본 본문. 유효한 값으로 채우고 필요한 것만 덮어쓴다."""
    payload = {
        "event": event_id,
        "kind": kind,
        "observed_at": iso(observed_at),
        "lat": 37.4805,
        "lng": 126.9526,
        "accuracy_m": 12.0,
        "distance_m": 180.0,
        "client_uuid": str(uuid.uuid4()),
    }
    payload.update(over)
    return payload


print("=" * 74)
print("이동 관측 API 검증")
print("=" * 74 + "\n")

st, body = call("GET", "/api/health")
check("health", st == 200, f"status={st}")
if st != 200:
    raise SystemExit(1)

# --- 사용자 A / B 생성 -------------------------------------------------------
email_a, token_a = signup("a", "에이")
email_b, token_b = signup("b", "비")
print(f"A = {email_a}\nB = {email_b}\n")

# 1) 새 계정은 관측이 없다
st, body = call("GET", "/api/observations", token=token_a)
check(
    "새 계정 관측 목록이 빔",
    st == 200 and len(items_of(body)) == 0,
    f"status={st} 개수={len(items_of(body))}",
)

# 2) 집 위치 설정 — 알람이 계산되려면 출발지가 있어야 한다
st, body = call(
    "PATCH",
    "/api/profile",
    {
        "home_lat": 37.4842,
        "home_lng": 126.9295,
        "home_label": "신림역",
        "onboarding_prep_min": 28,
    },
    token=token_a,
)
check("집 위치 설정", st == 200 and body.get("has_home") is True, f"status={st}")

# 3) 장소 있는 일정 생성 — 계획이 계산된다
start = (datetime.now(KST) + timedelta(days=1)).replace(
    hour=9, minute=0, second=0, microsecond=0
)
st, body = call(
    "POST",
    "/api/events",
    {
        "title": "자료구조 및 알고리즘",
        "start_at": iso(start),
        "place": {
            "name": "서울대학교 관악캠퍼스",
            "lat": 37.4598,
            "lng": 126.9511,
            "address": "서울 관악구 관악로 1",
        },
        "tag_key": "class",
    },
    token=token_a,
)
event_id = body.get("id")
plan = body.get("alarm_plan") or {}
check(
    "일정 생성 201",
    st == 201 and event_id is not None,
    f"status={st} id={event_id} plan={plan.get('status')}",
)

planned_depart = parse(plan["depart_by"]) if plan.get("depart_by") else None
planned_arrive = parse(plan["arrive_at"]) if plan.get("arrive_at") else None
print(f"계획: depart_by={planned_depart} arrive_at={planned_arrive}\n")

# 4) 출발 관측 업로드
#    계획이 있으면 그보다 7분 늦게 관측했다고 보낸다. 지연이 정확히 +7 이어야 한다.
depart_observed = (planned_depart or start - timedelta(minutes=60)) + timedelta(minutes=7)
depart_uuid = str(uuid.uuid4())
st, body = call(
    "POST",
    "/api/observations/batch",
    {
        "observations": [
            observation(event_id, "depart", depart_observed, client_uuid=depart_uuid)
        ]
    },
    token=token_a,
)
check(
    "출발 관측 업로드 201",
    st == 201 and body.get("accepted") == 1 and body.get("duplicated") == 0,
    f"status={st} accepted={body.get('accepted')} duplicated={body.get('duplicated')}",
)

first = (body.get("results") or [{}])[0]

# 5) 계획 대비 지연이 부호까지 맞다
if planned_depart is not None:
    check(
        "출발 지연 +7분 계산",
        first.get("delay_minutes") == 7,
        f"delay_minutes={first.get('delay_minutes')} (계획 {planned_depart} → 관측 {depart_observed})",
    )
    check(
        "planned_at 이 계획의 depart_by 와 같음",
        first.get("planned_at") is not None
        and parse(first["planned_at"]) == planned_depart,
        f"planned_at={first.get('planned_at')} depart_by={plan.get('depart_by')}",
    )
else:
    check(
        "계획이 없으면 지연은 null",
        first.get("delay_minutes") is None and first.get("planned_at") is None,
        f"plan status={plan.get('status')} delay={first.get('delay_minutes')} "
        "(카카오 경로 조회가 실패해 계획이 없다)",
    )

# 6) **같은 client_uuid 재전송 — 행이 늘지 않는다**
st, body = call(
    "POST",
    "/api/observations/batch",
    {
        "observations": [
            observation(
                event_id,
                "depart",
                depart_observed + timedelta(minutes=99),  # 값이 달라도 무시돼야 한다
                client_uuid=depart_uuid,
            )
        ]
    },
    token=token_a,
)
check(
    "같은 client_uuid 재전송은 무시",
    st == 200 and body.get("accepted") == 0 and body.get("duplicated") == 1,
    f"status={st} accepted={body.get('accepted')} duplicated={body.get('duplicated')}",
)

st, body = call("GET", "/api/observations", token=token_a)
check(
    "재전송 후에도 1건",
    st == 200 and len(items_of(body)) == 1,
    f"개수={len(items_of(body))}",
)

# 7) 재전송이 기존 값을 덮어쓰지 않는다 (첫 판정을 유지)
kept = items_of(body)[0]
check(
    "재전송이 기존 값을 덮어쓰지 않음",
    parse(kept["observed_at"]) == depart_observed,
    f"observed_at={kept['observed_at']} (재전송은 +99분이었다)",
)

# 8) 도착 관측 — 계획보다 3분 이르게
arrive_observed = (planned_arrive or start - timedelta(minutes=10)) - timedelta(minutes=3)
st, body = call(
    "POST",
    "/api/observations/batch",
    {
        "observations": [
            observation(
                event_id, "arrive", arrive_observed, distance_m=18.0, accuracy_m=9.0
            )
        ]
    },
    token=token_a,
)
arrive_row = (body.get("results") or [{}])[0]
check("도착 관측 업로드 201", st == 201 and body.get("accepted") == 1, f"status={st}")

# 9) 이른 도착은 음수 지연이다 — 부호를 살린다
if planned_arrive is not None:
    check(
        "이른 도착은 음수 지연",
        arrive_row.get("delay_minutes") == -3,
        f"delay_minutes={arrive_row.get('delay_minutes')}",
    )
else:
    check(
        "계획 없으면 도착 지연도 null",
        arrive_row.get("delay_minutes") is None,
        f"plan status={plan.get('status')}",
    )

# 10) 배치로 여러 건 한 번에
st, body = call(
    "POST",
    "/api/observations/batch",
    {
        "observations": [
            observation(event_id, "depart", depart_observed - timedelta(days=1)),
            observation(event_id, "arrive", arrive_observed - timedelta(days=1)),
        ]
    },
    token=token_a,
)
check(
    "배치 2건 한 번에",
    st == 201 and body.get("accepted") == 2,
    f"status={st} accepted={body.get('accepted')}",
)

# 11) **오차가 큰 위치는 거부한다**
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed, accuracy_m=60.0)]},
    token=token_a,
)
check(
    "오차 60m 위치는 400",
    st == 400 and "반경 판정" in (err_message(body) or ""),
    f"status={st} message={err_message(body)}",
)

# 12) 음수 정확도 거부
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed, accuracy_m=-1.0)]},
    token=token_a,
)
check("음수 정확도는 400", st == 400, f"status={st} message={err_message(body)}")

# 13) 좌표 범위 초과 거부
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed, lat=91.0)]},
    token=token_a,
)
check("위도 91 은 400", st == 400, f"status={st} message={err_message(body)}")

# 14) client_uuid 없으면 거부
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed, client_uuid="")]},
    token=token_a,
)
check("빈 client_uuid 는 400", st == 400, f"status={st} message={err_message(body)}")

# 15) 잘못된 kind 거부
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "wander", depart_observed)]},
    token=token_a,
)
check("잘못된 kind 는 400", st == 400, f"status={st} message={err_message(body)}")

# 16) 빈 배치 거부
st, body = call(
    "POST", "/api/observations/batch", {"observations": []}, token=token_a
)
check("빈 배치는 400", st == 400, f"status={st} message={err_message(body)}")

# 17) 배치 상한 초과 거부
st, body = call(
    "POST",
    "/api/observations/batch",
    {
        "observations": [
            observation(event_id, "depart", depart_observed) for _ in range(101)
        ]
    },
    token=token_a,
)
check("101건 배치는 400", st == 400, f"status={st} message={err_message(body)}")

# 18) **B 가 A 의 일정에 관측을 심으려 하면 404**
st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed)]},
    token=token_b,
)
check(
    "B 가 A 일정에 관측하면 404",
    st == 404,
    f"status={st} message={err_message(body)}",
)

# 19) B 목록에 A 관측이 없다
st, body = call("GET", "/api/observations", token=token_b)
check(
    "B 목록에 A 관측 없음",
    st == 200 and len(items_of(body)) == 0,
    f"status={st} 개수={len(items_of(body))}",
)

# 20) 토큰 없이 접근하면 401
st, body = call("GET", "/api/observations")
check("토큰 없이 목록 401", st == 401, f"status={st}")

st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(event_id, "depart", depart_observed)]},
)
check("토큰 없이 업로드 401", st == 401, f"status={st}")

# 21) kind 필터
st, body = call("GET", "/api/observations?kind=arrive", token=token_a)
rows = items_of(body)
check(
    "kind=arrive 필터",
    st == 200 and len(rows) == 2 and all(r["kind"] == "arrive" for r in rows),
    f"status={st} 개수={len(rows)}",
)

# 22) event 필터 — 다른 일정을 만들어 섞인 뒤 걸러지는지 본다
st, other = call(
    "POST",
    "/api/events",
    {"title": "장소 없는 일정", "start_at": iso(start + timedelta(days=2))},
    token=token_a,
)
other_id = other.get("id")
other_plan = (other.get("alarm_plan") or {}).get("status")
check(
    "장소 없는 일정 생성 (계획 NO_PLACE)",
    st == 201 and other_plan == "no_place",
    f"status={st} plan={other_plan}",
)

st, body = call(
    "POST",
    "/api/observations/batch",
    {"observations": [observation(other_id, "depart", start)]},
    token=token_a,
)
no_plan_row = (body.get("results") or [{}])[0]
check(
    "계획 없는 일정의 관측은 지연 null",
    st == 201
    and no_plan_row.get("planned_at") is None
    and no_plan_row.get("delay_minutes") is None,
    f"status={st} planned_at={no_plan_row.get('planned_at')} "
    f"delay={no_plan_row.get('delay_minutes')}",
)

st, body = call(f"GET", f"/api/observations?event={event_id}", token=token_a)
rows = items_of(body)
check(
    "event 필터가 다른 일정을 제외",
    st == 200 and len(rows) == 4 and all(r["event"] == event_id for r in rows),
    f"status={st} 개수={len(rows)} (전체는 5건)",
)

# 23) 일정을 지우면 관측도 사라진다 — 비교 대상 없는 관측을 남기지 않는다
st, _ = call("DELETE", f"/api/events/{other_id}", token=token_a)
st2, body = call("GET", "/api/observations", token=token_a)
check(
    "일정 삭제 시 관측도 삭제",
    st == 204 and st2 == 200 and len(items_of(body)) == 4,
    f"delete={st} 남은 관측={len(items_of(body))}",
)

print("=" * 74)
print("요약")
print("=" * 74)
w = max(len(n) for n, _, _ in results)
for name, ok, _ in results:
    print(f"  {name:{w}}  {'OK' if ok else 'FAIL'}")
n_ok = sum(1 for _, ok, _ in results if ok)
print(f"\n  {n_ok}/{len(results)} 통과")
raise SystemExit(0 if n_ok == len(results) else 1)
