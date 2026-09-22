"""배포된 공용 서버에서 전 기능을 확인한다.

로컬 `check_*.py` 들과 달리 **Django 를 import 하지 않는다.** HTTP 만 쓰므로
파이썬만 있으면 어디서든 돌릴 수 있고, 배포 서버가 실제로 무엇을 하는지만 본다.

    python scripts/check_deployed.py
    python scripts/check_deployed.py --base https://다른주소

무료 플랜은 15분 무응답이면 잠든다. 첫 요청이 30~60초 걸릴 수 있어
타임아웃을 넉넉히 잡았다.

만든 계정은 끝에 지우지 않는다 - 사용자 삭제 API 가 없다. 이메일에
`depcheck_` 접두를 붙여 나중에 admin 에서 골라낼 수 있게 한다.
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))

# 신림역 -> 서울대 관악캠퍼스. 실제로 대중교통 경로가 나오는 좌표다.
HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"lat": 37.459882, "lng": 126.951905, "name": "서울대학교 관악캠퍼스"}
# 집과 충분히 떨어진 곳. 출발지를 바꾸면 소요시간이 달라져야 한다.
ORIGIN = {"lat": 37.5665, "lng": 126.9780, "label": "서울시청"}

passed = failed = skipped = 0
_notes: list[str] = []


def check(label, ok, note=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}  {note}")
        _notes.append(f"{label}: {note}")


def skip(label, why):
    global skipped
    skipped += 1
    print(f"  [SKIP] {label}  ({why})")


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def __call__(self, path, method="GET", body=None, timeout=120, auth=True):
        data = json.dumps(body).encode() if body is not None else None
        # 쿼리에 한글이 들어가면 urllib 가 ascii 로 인코딩하려다 터진다.
        # 경로와 쿼리를 나눠 값만 퍼센트 인코딩한다.
        head, sep, query = path.partition("?")
        if sep:
            pairs = urllib.parse.parse_qsl(query, keep_blank_values=True)
            path = head + "?" + urllib.parse.urlencode(pairs)
        req = urllib.request.Request(f"{self.base}{path}", data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if auth and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
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
    base = parser.parse_args().base
    api = Api(base)

    print("=" * 74)
    print(f"배포 서버 전 기능 확인: {base}")
    print("=" * 74)

    # --- 1. 서버가 살아 있는가 --------------------------------------------
    print("\n[1] 기본")
    st, body = api("/api/health", auth=False)
    check("health 200", st == 200, f"status={st} body={str(body)[:160]}")
    if st != 200:
        print("\n서버에 닿지 못했다. 이후 확인을 중단한다.")
        return 1
    check("version 응답", bool(body.get("version")), str(body))

    # --- 2. 계정 ----------------------------------------------------------
    print("\n[2] 계정")
    email = f"depcheck_{uuid.uuid4().hex[:8]}@example.com"
    # **이메일과 닮지 않은 비밀번호를 쓴다.** 전에는 "depcheck12345" 였는데
    # Django 의 UserAttributeSimilarityValidator 가 이메일과 비교하므로
    # 무작위 접미사에 따라 유사도가 임계값(0.7)을 넘을 때가 있었다. 그러면
    # 회원가입이 400 이 되어 **검증이 깜빡인다** — 같은 코드가 어떤 날은
    # 통과하고 어떤 날은 실패했다. 접두와 무관한 문자열로 고정한다.
    password = "Tf7-quiet-lantern-92"
    st, body = api(
        "/api/auth/register",
        "POST",
        {
            "email": email,
            "nickname": "배포확인",
            "password": password,
            "password_confirm": password,
        },
        auth=False,
    )
    check("회원가입 201", st == 201, f"status={st} body={str(body)[:200]}")
    if st != 201:
        print("\n계정을 만들 수 없어 이후 확인을 중단한다.")
        return 1
    api.token = body.get("access")
    check("가입 응답에 access 토큰", bool(api.token))

    st, body = api("/api/auth/token", "POST", {"email": email, "password": password}, auth=False)
    check("로그인 200", st == 200, f"status={st}")
    if st == 200 and body.get("access"):
        api.token = body["access"]

    st, body = api("/api/auth/me")
    check("me 200", st == 200, f"status={st}")
    check("me 이메일 일치", (body or {}).get("email") == email, str(body)[:160])

    st, body = api("/api/auth/me", auth=False)
    check("토큰 없으면 401", st == 401, f"status={st}")

    # --- 3. 프로필 / 집 위치 ----------------------------------------------
    print("\n[3] 프로필")
    st, body = api("/api/profile")
    check("프로필 200", st == 200, f"status={st}")
    check("새 계정은 집 없음", (body or {}).get("has_home") is False, str(body)[:160])

    st, body = api(
        "/api/profile",
        "PATCH",
        {
            "home_lat": HOME["lat"],
            "home_lng": HOME["lng"],
            "home_label": HOME["label"],
            "onboarding_prep_min": 30,
        },
    )
    check("집 위치 설정 200", st == 200, f"status={st} body={str(body)[:200]}")
    check("has_home true", (body or {}).get("has_home") is True, str(body)[:160])
    check("한글 라벨 왕복", (body or {}).get("home_label") == HOME["label"], str(body)[:160])

    # --- 4. 태그 ----------------------------------------------------------
    print("\n[4] 태그")
    st, tags = api("/api/events/tags")
    check("태그 목록 200", st == 200, f"status={st}")
    labels = sorted(t.get("label", "") for t in (tags or []))
    check("태그 6종", len(labels) == 6, f"{labels}")
    check("한글 태그 보존", "수업" in labels, f"{labels}")

    # --- 5. 장소 검색 (카카오 키 필요) ------------------------------------
    print("\n[5] 장소 검색 — 카카오 키 의존")
    st, body = api("/api/places/search?q=서울대학교")
    check("장소 검색 200", st == 200, f"status={st} body={str(body)[:160]}")
    results = (body or {}).get("results") or []
    kakao_ok = st == 200 and not (body or {}).get("degraded") and len(results) > 0
    check(
        "카카오 키 동작 (결과 1건 이상)",
        kakao_ok,
        f"degraded={(body or {}).get('degraded')} n={len(results)} "
        "— 실패면 Render 에 KAKAO_REST_API_KEY 가 없거나 쿼터 초과",
    )

    # --- 6. 좌표 -> 주소 (이번에 추가) ------------------------------------
    print("\n[6] 좌표→주소 (신규)")
    st, body = api(f"/api/places/reverse?lat={ORIGIN['lat']}&lng={ORIGIN['lng']}")
    check("reverse 200", st == 200, f"status={st} body={str(body)[:200]}")
    if st == 200:
        r = (body or {}).get("result") or {}
        check(
            "주소 1건 반환",
            bool(r.get("address")),
            f"degraded={(body or {}).get('degraded')} result={r}",
        )
        check(
            "좌표는 보낸 값 그대로",
            abs((r.get("lat") or 0) - ORIGIN["lat"]) < 1e-6,
            str(r)[:160],
        )
    st, body = api("/api/places/reverse?lat=999&lng=0")
    check("좌표 범위 밖 400", st == 400, f"status={st}")

    # --- 7. 경로 후보 -----------------------------------------------------
    print("\n[7] 경로 후보")
    st, body = api(f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}")
    check("집 기준 후보 200", st == 200, f"status={st} body={str(body)[:200]}")
    home_minutes = None
    picked_key = None
    if st == 200:
        origin_echo = (body or {}).get("origin") or {}
        check("origin 이 집", origin_echo.get("label") == HOME["label"], str(origin_echo))
        cands = (body or {}).get("results") or []
        check("후보 1건 이상", len(cands) >= 1, f"n={len(cands)}")
        if cands:
            picked_key = cands[0].get("key")
            home_minutes = cands[0].get("minutes")

    # 출발지 지정 (이번에 추가)
    st, body = api(
        f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}"
        f"&origin_lat={ORIGIN['lat']}&origin_lng={ORIGIN['lng']}&origin_label={ORIGIN['label']}"
    )
    check("출발지 지정 후보 200", st == 200, f"status={st} body={str(body)[:200]}")
    origin_minutes = None
    if st == 200:
        origin_echo = (body or {}).get("origin") or {}
        check(
            "origin 이 지정값",
            abs((origin_echo.get("lat") or 0) - ORIGIN["lat"]) < 1e-6,
            str(origin_echo),
        )
        cands = (body or {}).get("results") or []
        if cands:
            origin_minutes = cands[0].get("minutes")

    if home_minutes is not None and origin_minutes is not None:
        check(
            "출발지를 바꾸면 소요시간이 달라진다",
            home_minutes != origin_minutes,
            f"집={home_minutes}분 지정={origin_minutes}분",
        )
    else:
        skip("출발지별 소요시간 비교", "후보를 받지 못했다")

    st, _ = api(
        f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}"
        f"&origin_lat={ORIGIN['lat']}"
    )
    check("반쪽 좌표 400", st == 400, f"status={st}")

    st, _ = api(
        f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}"
        "&origin_lat=48.8584&origin_lng=2.2945"
    )
    check("국외 출발지 400", st == 400, f"status={st}")

    st, _ = api("/api/routes/candidates?dest_lat=999&dest_lng=0")
    check("목적지 범위 밖 400", st == 400, f"status={st}")

    # --- 8. 일정 + 알람 계산 ----------------------------------------------
    print("\n[8] 일정 · 알람 계산")
    start_at = (datetime.now(KST) + timedelta(days=3)).replace(
        hour=18, minute=0, second=0, microsecond=0
    )
    payload = {
        "title": "배포 확인 일정",
        "start_at": start_at.isoformat(),
        "place": {"name": DEST["name"], "lat": DEST["lat"], "lng": DEST["lng"]},
        "tag_key": "class",
    }
    if picked_key:
        payload["route_key"] = picked_key
    st, created = api("/api/events", "POST", payload)
    check("일정 생성 201", st == 201, f"status={st} body={str(created)[:250]}")
    event_id = (created or {}).get("id")
    plan = (created or {}).get("alarm_plan") or {}
    check(
        "알람 계획 status=ok",
        plan.get("status") == "ok",
        f"status={plan.get('status')} label={plan.get('status_label')!r}",
    )
    check("alarm_at 계산됨", bool(plan.get("alarm_at")), str(plan)[:200])
    check("한글 제목 왕복", (created or {}).get("title") == "배포 확인 일정", str(created)[:160])
    if plan.get("status") == "ok":
        print(
            f"         alarm_at={plan.get('alarm_at')} depart_by={plan.get('depart_by')} "
            f"travel={plan.get('travel_minutes')}분 prep={plan.get('prep_minutes')}분 "
            f"buffer={plan.get('buffer_minutes')}분"
        )
        check(
            "확률은 관측 전까지 null",
            plan.get("on_time_probability") is None,
            f"p={plan.get('on_time_probability')} — 임의값을 넣으면 안 된다",
        )

    # 출발지를 일정에 저장 (이번에 추가)
    st, with_origin = api(
        "/api/events",
        "POST",
        {
            "title": "출발지 지정 일정",
            "start_at": start_at.isoformat(),
            "place": {"name": DEST["name"], "lat": DEST["lat"], "lng": DEST["lng"]},
            "tag_key": "class",
            "origin_lat": ORIGIN["lat"],
            "origin_lng": ORIGIN["lng"],
            "origin_label": ORIGIN["label"],
        },
    )
    check("출발지 포함 일정 201", st == 201, f"status={st} body={str(with_origin)[:250]}")
    check(
        "응답에 출발지 유지",
        abs(((with_origin or {}).get("origin_lat") or 0) - ORIGIN["lat"]) < 1e-6,
        f"origin_lat={(with_origin or {}).get('origin_lat')}",
    )
    plan2 = (with_origin or {}).get("alarm_plan") or {}
    check("출발지 지정 일정도 status=ok", plan2.get("status") == "ok", str(plan2)[:200])
    if plan.get("travel_minutes") and plan2.get("travel_minutes"):
        check(
            "저장된 출발지가 계산에 반영됨",
            plan["travel_minutes"] != plan2["travel_minutes"],
            f"집={plan['travel_minutes']}분 지정={plan2['travel_minutes']}분 "
            "— 같으면 계산기가 출발지를 무시하고 있다",
        )

    st, _ = api(
        "/api/events",
        "POST",
        {
            "title": "반쪽 좌표",
            "start_at": start_at.isoformat(),
            "origin_lat": ORIGIN["lat"],
        },
    )
    check("일정에 반쪽 좌표 400", st == 400, f"status={st}")

    # --- 9. 목록 / 재계산 -------------------------------------------------
    print("\n[9] 목록 · 재계산")
    st, body = api("/api/events")
    check("일정 목록 200", st == 200, f"status={st}")
    check("페이지 객체 응답", isinstance(body, dict) and "results" in body, str(body)[:160])
    check("내 일정 2건", len((body or {}).get("results") or []) == 2, f"n={len((body or {}).get('results') or [])}")

    if event_id:
        st, body = api(f"/api/events/{event_id}/recompute", "POST")
        check("재계산 200", st == 200, f"status={st}")
        check(
            "재계산 후에도 ok",
            ((body or {}).get("alarm_plan") or {}).get("status") == "ok",
            str((body or {}).get("alarm_plan"))[:160],
        )

    # --- 10. 관측 (GPS 출발·도착) -----------------------------------------
    print("\n[10] 관측 업로드")
    if event_id:
        depart = start_at - timedelta(minutes=40)
        arrive = start_at - timedelta(minutes=5)
        batch = {
            "observations": [
                {
                    "event": event_id,
                    "kind": "depart",
                    "detector": "gps",
                    "observed_at": depart.isoformat(),
                    "lat": HOME["lat"],
                    "lng": HOME["lng"],
                    "accuracy_m": 15.0,
                    "distance_m": 120.0,
                    "client_uuid": f"depchk-{uuid.uuid4().hex[:12]}-dep",
                },
                {
                    "event": event_id,
                    "kind": "arrive",
                    "detector": "gps",
                    "observed_at": arrive.isoformat(),
                    "lat": DEST["lat"],
                    "lng": DEST["lng"],
                    "accuracy_m": 12.0,
                    "distance_m": 8.0,
                    "client_uuid": f"depchk-{uuid.uuid4().hex[:12]}-arr",
                },
            ]
        }
        st, body = api("/api/observations/batch", "POST", batch)
        check("관측 배치 2xx", st in (200, 201), f"status={st} body={str(body)[:300]}")
        if st in (200, 201):
            check(
                "2건 적재",
                len((body or {}).get("results") or []) == 2,
                f"n={len((body or {}).get('results') or [])}",
            )

        # 같은 client_uuid 를 다시 보내도 중복 적재되지 않아야 한다.
        # 오프라인 큐가 재전송하면 같은 건이 여러 번 온다.
        st2, body2 = api("/api/observations/batch", "POST", batch)
        check("같은 관측 재전송 2xx", st2 in (200, 201), f"status={st2}")

        st, body = api("/api/observations")
        check("관측 목록 200", st == 200, f"status={st}")
        n = len((body or {}).get("results") or []) if isinstance(body, dict) else -1
        check("재전송 후에도 2건 (멱등)", n == 2, f"n={n} — 4건이면 중복 적재된다")
    else:
        skip("관측", "일정 생성 실패")

    # --- 11. 남의 데이터 격리 ---------------------------------------------
    print("\n[11] 격리")
    other = f"depcheck_{uuid.uuid4().hex[:8]}@example.com"
    st, body = api(
        "/api/auth/register",
        "POST",
        {
            "email": other,
            "nickname": "타인",
            "password": password,
            "password_confirm": password,
        },
        auth=False,
    )
    if st == 201 and body.get("access"):
        other_api = Api(base)
        other_api.token = body["access"]
        st, body = other_api("/api/events")
        check(
            "남의 일정은 안 보인다",
            st == 200 and len((body or {}).get("results") or []) == 0,
            f"status={st} n={len((body or {}).get('results') or [])}",
        )
        if event_id:
            st, _ = other_api(f"/api/events/{event_id}")
            check("남의 일정 직접 조회 404", st == 404, f"status={st}")
    else:
        skip("격리 확인", "두 번째 계정 생성 실패")

    # --- 결과 ------------------------------------------------------------
    print("\n" + "=" * 74)
    print(f"통과 {passed} / 실패 {failed} / 건너뜀 {skipped}")
    if _notes:
        print("\n실패 항목:")
        for n in _notes:
            print(f"  - {n}")
    print(f"\n만든 계정: {email}")
    print("=" * 74)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
