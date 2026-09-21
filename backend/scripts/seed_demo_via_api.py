"""배포 서버에 데모 계정을 HTTP 로 만든다.

`manage.py seed_demo` 는 서버 셸이 필요하다. 이 스크립트는 공개 API 만 쓰므로
로컬에서 배포 서버를 향해 실행할 수 있다.

    python scripts/seed_demo_via_api.py
    python scripts/seed_demo_via_api.py --base https://다른주소

멱등하다. 계정이 이미 있으면 로그인해서 집 위치만 맞춘다.

**비밀번호를 바꾸지 않는다.** 공용 서버에 두는 공개 계정이므로 팀 전체가
같은 값을 알고 있어야 한다. 이미 있는 계정의 비밀번호를 API 로 바꿀 수는
없으므로, 로그인이 안 되면 그 사실만 알리고 끝낸다.
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

EMAIL = "demo@demo.com"
PASSWORD = "demo1234"
NICKNAME = "데모"

# manage.py seed_demo 와 같은 값을 쓴다. 두 경로가 다른 상태를 만들면
# "어느 쪽으로 만들었나" 에 따라 화면이 달라진다.
HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
PREP_MINUTES = 30


def call(base, path, method="GET", body=None, token=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return 0, {"_error": f"{type(e).__name__}: {e}"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="https://justintime-api.onrender.com")
    base = parser.parse_args().base.rstrip("/")

    print(f"대상: {base}")

    st, body = call(base, "/api/health")
    if st != 200:
        print(f"서버에 닿지 못했다 (status={st}). 무료 플랜이면 잠들어 있을 수 있다.")
        return 1

    # 1. 이미 있는지 로그인으로 확인한다.
    st, body = call(base, "/api/auth/token", "POST", {"email": EMAIL, "password": PASSWORD})
    token = body.get("access") if st == 200 else None

    if token:
        print(f"이미 존재하고 로그인된다: {EMAIL}")
    else:
        st, body = call(
            base,
            "/api/auth/register",
            "POST",
            {
                "email": EMAIL,
                "nickname": NICKNAME,
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
        )
        if st == 201:
            token = body.get("access")
            print(f"생성했다: {EMAIL} / {PASSWORD}")
        else:
            print(f"생성 실패 (status={st}): {str(body)[:300]}")
            print(
                "\n계정이 이미 있는데 비밀번호가 다른 경우일 수 있다. "
                "그때는 서버 셸에서 `python manage.py seed_demo` 를 돌려야 한다."
            )
            return 1

    if not token:
        print("토큰을 얻지 못했다.")
        return 1

    # 2. 집 위치. 없으면 일정을 넣어도 계획이 no_home 으로만 나온다.
    st, body = call(
        base,
        "/api/profile",
        "PATCH",
        {
            "home_lat": HOME["lat"],
            "home_lng": HOME["lng"],
            "home_label": HOME["label"],
            "onboarding_prep_min": PREP_MINUTES,
        },
        token=token,
    )
    if st == 200:
        print(f"집 위치 설정: {body.get('home_label')} / 준비 {body.get('onboarding_prep_min')}분")
    else:
        print(f"집 위치 설정 실패 (status={st}): {str(body)[:200]}")
        return 1

    # 3. 일정. 없으면 홈 화면이 비어 보여서 보여줄 것이 없다.
    st, body = call(base, "/api/events", token=token)
    existing = {e.get("title") for e in ((body or {}).get("results") or [])} if st == 200 else set()
    print(f"현재 일정 {len(existing)}건")

    created = 0
    for spec in DEMO_EVENTS:
        if spec["title"] in existing:
            continue
        place = resolve_place(base, token, spec["place_query"], spec["place_fallback"])
        if place is None:
            print(f"  장소를 찾지 못해 건너뜀: {spec['title']}")
            continue

        start_at = next_weekday_at(spec["days_ahead"], spec["hour"], spec["minute"])
        st, ev = call(
            base,
            "/api/events",
            "POST",
            {
                "title": spec["title"],
                "start_at": start_at,
                "place": place,
                "tag_key": spec["tag_key"],
            },
            token=token,
        )
        if st == 201:
            plan = (ev or {}).get("alarm_plan") or {}
            print(
                f"  + {spec['title']}  alarm_at={plan.get('alarm_at')} "
                f"status={plan.get('status')}"
            )
            created += 1
        else:
            print(f"  일정 생성 실패 ({st}): {str(ev)[:200]}")

    if created:
        print(f"일정 {created}건 추가")

    print("\n완료. 앱에서 이 계정으로 로그인할 수 있다.")
    return 0


# 데모용 일정.
#
# 제목 길이를 일부러 섞었다. 긴 제목("Algorithms Lecture")은 태그 칩이 세로로
# 접히던 버그를 재현하던 값이라, 홈 목록을 열면 그 수정이 유지되는지 눈으로
# 확인할 수 있다.
DEMO_EVENTS = [
    {
        "title": "Algorithms Lecture",
        "place_query": "서울대학교 관악캠퍼스",
        "place_fallback": {"name": "서울대학교 관악캠퍼스", "lat": 37.459882, "lng": 126.951905},
        "tag_key": "class",
        "days_ahead": 1,
        "hour": 9,
        "minute": 0,
    },
    {
        "title": "시험 준비",
        "place_query": "서울대학교 중앙도서관",
        "place_fallback": {"name": "서울대학교 관악캠퍼스", "lat": 37.459882, "lng": 126.951905},
        "tag_key": "exam",
        "days_ahead": 2,
        "hour": 14,
        "minute": 30,
    },
    {
        "title": "저녁 약속",
        "place_query": "압구정로데오거리",
        "place_fallback": {"name": "압구정로데오거리", "lat": 37.527100, "lng": 127.039000},
        "tag_key": "meetup",
        "days_ahead": 3,
        "hour": 18,
        "minute": 0,
    },
]


def resolve_place(base, token, query, fallback):
    """장소를 서버 검색으로 해석한다. 실패하면 고정 좌표로 떨어진다.

    검색을 먼저 쓰는 이유는 kakao_place_id 가 붙어야 같은 장소가 한 행으로
    합쳐지기 때문이다. 좌표만 넣으면 Place 행이 계속 쌓인다.
    """
    st, body = call(base, f"/api/places/search?q={urllib.parse.quote(query)}", token=token)
    if st == 200:
        results = (body or {}).get("results") or []
        if results:
            r = results[0]
            return {
                "name": r["name"],
                "lat": r["lat"],
                "lng": r["lng"],
                "address": r.get("address"),
                "kakao_place_id": r.get("kakao_place_id"),
            }
    return fallback


def next_weekday_at(days_ahead: int, hour: int, minute: int) -> str:
    """지금부터 N일 뒤 해당 시각(KST). 항상 미래여야 알람이 등록된다."""
    kst = timezone(timedelta(hours=9))
    when = (datetime.now(kst) + timedelta(days=days_ahead)).replace(
        hour=hour, minute=minute, second=0, microsecond=0
    )
    return when.isoformat()


if __name__ == "__main__":
    raise SystemExit(main())
