"""Iteration 5 E2E 서버 확인: 0.21.0 · 0.22.0 기능을 배포 서버에 HTTP 로 확인한다.

check_deployed.py 와 같은 방식이다. Django 를 import 하지 않고 표준 라이브러리만 쓴다.

    python check_e2e_iter5.py                      # 공용 서버
    python check_e2e_iter5.py --base http://127.0.0.1:8000
    python check_e2e_iter5.py --skip-inquiry       # 팀 메일함에 문의 메일을 보내지 않는다

만드는 것: 테스트 계정 2개(`depcheck_` 접두, purge_test_accounts.py 로 지울 수 있다),
일정 몇 개, 저장한 집 1개, 문의 1건(제목 "[E2E 테스트]", 팀 메일함에 메일 1통).
한 계정은 로그인 잠금 확인 때문에 15분 동안 잠긴다(테스트 계정이라 상관없다).

결과는 표준 출력과 --out 으로 준 JSON 파일에 남긴다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"lat": 37.459882, "lng": 126.951905, "name": "서울대학교 관악캠퍼스"}
OTHER_HOME = {"lat": 37.5665, "lng": 126.9780, "name": "서울시청"}
OTHER_HOME2 = {"lat": 37.5172, "lng": 127.0473, "name": "강남구청"}
PASSWORD = "Tf7-quiet-lantern-92"

results: list[dict] = []
messages_seen: list[tuple[str, str]] = []  # (어디서, error.message)


def check(at: str, label: str, ok: bool, note: str = "") -> None:
    results.append({"at": at, "label": label, "result": "OK" if ok else "FAIL", "note": note})
    print(f"  [{'OK' if ok else 'FAIL':4}] {at:8} {label}" + ("" if ok else f"  -- {note}"))


def skip(at: str, label: str, why: str) -> None:
    results.append({"at": at, "label": label, "result": "SKIP", "note": why})
    print(f"  [SKIP] {at:8} {label}  ({why})")


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None
        self.last_headers: dict = {}

    def __call__(self, path, method="GET", body=None, auth=True, timeout=120):
        data = json.dumps(body).encode() if body is not None else None
        head, sep, query = path.partition("?")
        if sep:
            path = head + "?" + urllib.parse.urlencode(urllib.parse.parse_qsl(query, keep_blank_values=True))
        req = urllib.request.Request(f"{self.base}{path}", data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", "jit-e2e-check")
        if auth and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                self.last_headers = dict(r.headers)
                raw = r.read().decode("utf-8")
                return r.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as e:
            self.last_headers = dict(e.headers)
            raw = e.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"_raw": raw[:300]}
            msg = ((payload or {}).get("error") or {}).get("message") if isinstance(payload, dict) else None
            if msg:
                messages_seen.append((f"{method} {head}", msg))
            return e.code, payload
        except Exception as e:  # 네트워크
            return 0, {"_error": f"{type(e).__name__}: {e}"}


def err(body) -> tuple[str | None, str | None, dict]:
    e = (body or {}).get("error") or {} if isinstance(body, dict) else {}
    return e.get("code"), e.get("message"), e.get("details") or {}


def short(body, n=200) -> str:
    return json.dumps(body, ensure_ascii=False)[:n]


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def parse(ts: str | None) -> datetime | None:
    return datetime.fromisoformat(ts) if ts else None


def register(api: Api, nick: str) -> str | None:
    email = f"depcheck_e2e_{uuid.uuid4().hex[:8]}@example.com"
    st, body = api("/api/auth/register", "POST", {
        "email": email, "nickname": nick, "password": PASSWORD,
        "password_confirm": PASSWORD, "terms_agreed": True,
    }, auth=False)
    if st != 201:
        print(f"  가입 실패 status={st} {short(body)}")
        return None
    api.token = body.get("access")
    return email


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="https://justintime-api.onrender.com")
    ap.add_argument("--out", default="e2e_result.json")
    ap.add_argument("--skip-inquiry", action="store_true")
    args = ap.parse_args()
    api = Api(args.base)
    started = datetime.now(KST)
    print("=" * 78)
    print(f"Iteration 5 E2E 서버 확인  {args.base}  {iso(started)}")
    print("=" * 78)

    # ---------------------------------------------------------------- 0. 서버
    print("\n[0] 서버 버전")
    st, health = api("/api/health", auth=False, timeout=180)
    check("health", "health 200", st == 200, f"status={st} {short(health)}")
    if st != 200:
        return finish(args, started, health, [])
    version = health.get("version", "")
    check("health", f"서버 버전 {version} (0.22.0 이상이면 back-iter4 배포됨)",
          tuple(int(x) for x in re.findall(r"\d+", version)[:3]) >= (0, 22, 0), f"version={version}")
    social = health.get("social") or {}
    check("AT-41", "소셜 로그인 키 설정 (kakao · google)", all(social.get(k) for k in ("kakao", "google")),
          f"social={social}")

    # ---------------------------------------------------------------- 1. 계정 · 안내
    print("\n[1] 계정 · 첫 실행 안내 (AT-61)")
    email = register(api, "E2E확인")
    check("setup", "테스트 계정 가입 201", bool(email))
    if not email:
        return finish(args, started, health, [])
    st, prof = api("/api/profile", "PATCH", {"home_lat": HOME["lat"], "home_lng": HOME["lng"],
                                               "home_label": HOME["label"], "onboarding_prep_min": 30})
    check("setup", "집 · 준비 시간 저장 200", st == 200, f"status={st} {short(prof)}")
    seen = iso(datetime.now(KST))
    st, prof = api("/api/profile", "PATCH", {"intro_seen_at": seen})
    check("AT-61", "intro_seen_at 저장 200", st == 200 and bool((prof or {}).get("intro_seen_at")),
          f"status={st} {short(prof)}")
    check("AT-61", "안내 칸만 바꾸면 알람 다시 계산 안 함 (recomputed_plans 0)",
          (prof or {}).get("recomputed_plans") == 0, f"{(prof or {}).get('recomputed_plans')!r}")
    st, prof = api("/api/profile")
    check("AT-61", "다시 읽어도 intro_seen_at 유지 (다른 기기에서도 안 보임)",
          bool((prof or {}).get("intro_seen_at")) and "coachmark_seen_at" in (prof or {}), short(prof))
    st, _ = api("/api/profile", "PATCH", {"coachmark_seen_at": "not-a-time"})
    check("AT-61", "시각이 아닌 값 400", st == 400, f"status={st}")

    # ---------------------------------------------------------------- 2. 일정 · 알람 제어
    print("\n[2] 일정 · 직접 정한 알람 · 확인 (AT-64 ~ AT-66)")
    start_at = (datetime.now(KST) + timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0)
    st, cands = api(f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}")
    route_key = None
    if st == 200 and (cands or {}).get("results"):
        route_key = cands["results"][0].get("key")
    check("setup", "경로 후보 200 (카카오 키 동작)", st == 200 and bool(route_key), f"status={st} {short(cands)}")
    body = {"title": "E2E 확인 일정", "start_at": iso(start_at), "tag_key": "class",
            "place": {"name": DEST["name"], "lat": DEST["lat"], "lng": DEST["lng"]},
            "client_request_id": f"e2e-{uuid.uuid4().hex[:12]}"}
    if route_key:
        body["route_key"] = route_key
    st, ev = api("/api/events", "POST", body)
    check("setup", "일정 생성 201", st == 201, f"status={st} {short(ev)}")
    event_id = (ev or {}).get("id")
    plan = (ev or {}).get("alarm_plan") or {}
    ok_plan = plan.get("status") == "ok"
    check("setup", "알람 계획 status=ok", ok_plan, f"status={plan.get('status')} {short(plan)}")

    st2, again = api("/api/events", "POST", body)
    check("AT-53", "같은 client_request_id 재전송 → 200 · 같은 일정",
          st2 == 200 and (again or {}).get("id") == event_id, f"status={st2} id={(again or {}).get('id')}")

    if event_id and ok_plan:
        check("AT-66", "새 일정은 확인 전 (alarm_confirmed false)", plan.get("alarm_confirmed") is False,
              f"{plan.get('alarm_confirmed')!r}")
        check("AT-65", "울릴 시각 = 추천 시각, alarm_source recommended",
              plan.get("alarm_source") == "recommended" and plan.get("effective_alarm_at") == plan.get("alarm_at"),
              f"source={plan.get('alarm_source')} eff={plan.get('effective_alarm_at')} rec={plan.get('alarm_at')}")
        for f in ("effective_prep_minutes", "prep_margin_minutes", "on_time_probability_precise"):
            check("0.22.0", f"alarm_plan 에 {f}", f in plan, f"keys={sorted(plan)[:40]}")
        rec = parse(plan.get("alarm_at"))
        # AT-66 확인: 다른 시각을 보내면 409, 맞는 시각이면 200
        st, b = api(f"/api/events/{event_id}/alarm/confirm", "POST",
                    {"alarm_at": iso(rec - timedelta(minutes=3))})
        check("AT-66", "다른 시각으로 확인 → 409 alarm_changed", st == 409 and err(b)[0] == "alarm_changed",
              f"status={st} {short(b)}")
        st, b = api(f"/api/events/{event_id}/alarm/confirm", "POST", {"alarm_at": plan.get("effective_alarm_at")})
        check("AT-66", "화면 시각으로 확인 → 200 · 확인함",
              st == 200 and ((b or {}).get("alarm_plan") or {}).get("alarm_confirmed") is True,
              f"status={st} {short((b or {}).get('alarm_plan'))}")
        # AT-65 직접 정한 시각
        mine = rec - timedelta(minutes=10)
        st, b = api(f"/api/events/{event_id}/alarm/time", "PUT", {"alarm_at": iso(mine)})
        p = (b or {}).get("alarm_plan") or {}
        check("AT-65", "추천 10분 전으로 저장 → alarm_source user · effective = 저장한 시각",
              st == 200 and p.get("alarm_source") == "user" and parse(p.get("effective_alarm_at")) == mine,
              f"status={st} {short(p)}")
        check("AT-65", "직접 정한 시각 확률 필드 (user_alarm_probability)", "user_alarm_probability" in p, short(p))
        st, b = api(f"/api/events/{event_id}/recompute", "POST")
        p = (b or {}).get("alarm_plan") or {}
        check("AT-65", "다시 계산해도 직접 정한 시각 유지",
              st == 200 and parse(p.get("effective_alarm_at")) == mine, f"status={st} eff={p.get('effective_alarm_at')}")
        cases = [
            ("alarm_time_past", datetime.now(KST) - timedelta(minutes=5)),
            ("alarm_time_too_late", start_at - timedelta(minutes=3)),
            ("alarm_time_too_early", start_at - timedelta(hours=25)),
        ]
        for code, when in cases:
            st, b = api(f"/api/events/{event_id}/alarm/time", "PUT", {"alarm_at": iso(when)})
            check("AT-65", f"{code} → 400", st == 400 and err(b)[0] == code, f"status={st} {short(b)}")
        st, b = api(f"/api/events/{event_id}/alarm/time", "DELETE")
        p = (b or {}).get("alarm_plan") or {}
        check("AT-65", "추천 시각으로 되돌리기 → recommended",
              st == 200 and p.get("alarm_source") == "recommended", f"status={st} {short(p)}")
        st, b = api(f"/api/events/{event_id}/alarm/proposal/accept", "POST")
        check("AT-67", "제안이 없을 때 수락 → 4xx (500 아님)", 400 <= st < 500, f"status={st} {short(b)}")
        skip("AT-67", "변경 제안이 실제로 뜨는지", "경로·학습이 5분 넘게 바뀌어야 해서 서버만으로 못 만든다 → 폰 체크리스트 P-10")

        # risk-options (AT-64, 0.22.0 probability_table)
        st, ro = api(f"/api/events/{event_id}/risk-options")
        check("AT-64", "risk-options 200 · recommended_key", st == 200 and "recommended_key" in (ro or {}),
              f"status={st} {short(ro)}")
        check("0.22.0", "risk-options 에 probability_table 키 (BE-H3c)", "probability_table" in (ro or {}),
              f"keys={sorted(ro or {})}")
        probs = [o.get("probability") for o in (ro or {}).get("options", []) if isinstance(o, dict)]
        check("BE-P100", "세 칸 확률에 100 없음", all(p is None or p <= 99 for p in probs), f"{probs}")
    else:
        skip("AT-65", "직접 정한 시각 · 확인", "알람 계획이 없다")

    # arrive_by (0.22.0 BE-11)
    st, c2 = api(f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}&arrive_by={iso(start_at)}")
    first = ((c2 or {}).get("results") or [{}])[0]
    check("0.22.0", "arrive_by 후보에 depart_at · arrive_at (BE-11)",
          st == 200 and "depart_at" in first and "arrive_at" in first, f"status={st} keys={sorted(first)}")
    st, b = api(f"/api/routes/candidates?dest_lat={DEST['lat']}&dest_lng={DEST['lng']}&arrive_by=2001-01-01T09:00:00%2B09:00")
    check("0.22.0", "지난 arrive_by → 400 invalid_arrive_by", st == 400, f"status={st} {short(b)}")
    check("0.22.0", "후보 응답에 preferred_match (BE-PREF)", "preferred_match" in (cands or {}), f"keys={sorted(cands or {})}")

    # ---------------------------------------------------------------- 3. 반복 범위
    print("\n[3] 반복 일정 이 일정만 / 이후 모두 (AT-68)")
    rep_start = (datetime.now(KST) + timedelta(days=1)).replace(hour=15, minute=0, second=0, microsecond=0)
    st, rep = api("/api/events", "POST", {
        "title": "E2E 반복 수업", "start_at": iso(rep_start), "tag_key": "class",
        "repeat_weekdays": list(range(7)),
    })
    check("AT-68", "매일 반복 일정 생성 201", st == 201, f"status={st} {short(rep)}")
    rule = (rep or {}).get("recurrence_id")
    st, lst = api("/api/events?page_size=100")
    occ = sorted([e for e in (lst or {}).get("results", []) if e.get("recurrence_id") == rule and rule],
                 key=lambda e: e["start_at"])
    check("AT-68", "회차가 여러 개 펼쳐짐", len(occ) >= 3, f"n={len(occ)}")
    if len(occ) >= 3:
        mid = occ[1]
        st, b = api(f"/api/events/{occ[0]['id']}", "PATCH", {"title": "E2E 이 회차만", "scope": "this"})
        check("AT-68", "이 일정만 수정 200", st == 200, f"status={st} {short(b)}")
        st, b = api(f"/api/events/{mid['id']}", "PATCH", {"title": "E2E 이후 모두", "scope": "following"})
        check("AT-68", "이후 일정 모두 수정 200 · 새 규칙", st == 200 and (b or {}).get("recurrence_id") != rule,
              f"status={st} {short(b)}")
        st, lst = api("/api/events?page_size=100")
        rows = {e["id"]: e for e in (lst or {}).get("results", [])}
        titles = [rows.get(e["id"], {}).get("title") for e in occ]
        check("AT-68", "첫 회차는 '이 회차만', 둘째부터 '이후 모두'",
              titles[0] == "E2E 이 회차만" and all(t == "E2E 이후 모두" for t in titles[1:]), f"{titles}")
        st, b = api(f"/api/events/{mid['id']}", "PATCH", {"title": "x", "scope": "bogus"})
        check("AT-68", "모르는 scope → 400", st == 400 and "scope" in err(b)[2], f"status={st} {short(b)}")
        st, _ = api(f"/api/events/{mid['id']}?scope=following", "DELETE")
        check("AT-68", "이후 일정 모두 삭제 204/200", st in (200, 204), f"status={st}")
        st, lst = api("/api/events?page_size=100")
        left = [e["id"] for e in (lst or {}).get("results", []) if e["id"] in {o["id"] for o in occ}]
        check("AT-68", "삭제 뒤 첫 회차만 남음", left == [occ[0]["id"]], f"left={left}")

    # ---------------------------------------------------------------- 4. 저장한 집 이름
    print("\n[4] 저장한 집 이름 (AT-72)")
    st, b = api("/api/saved-places/", "POST", {"label": "본가", "lat": OTHER_HOME["lat"], "lng": OTHER_HOME["lng"],
                                                "name": OTHER_HOME["name"], "client_uuid": str(uuid.uuid4())})
    check("AT-72", "'본가' 추가 201", st == 201, f"status={st} {short(b)}")
    st, b = api("/api/saved-places/", "POST", {"label": " 본가 ", "lat": OTHER_HOME2["lat"], "lng": OTHER_HOME2["lng"],
                                                "name": OTHER_HOME2["name"], "client_uuid": str(uuid.uuid4())})
    check("AT-72", "' 본가 ' (공백) → 409 saved_place_label_duplicate",
          st == 409 and err(b)[0] == "saved_place_label_duplicate", f"status={st} {short(b)}")
    st, b = api("/api/saved-places/", "POST", {"label": "집", "lat": OTHER_HOME2["lat"], "lng": OTHER_HOME2["lng"],
                                                "name": OTHER_HOME2["name"], "client_uuid": str(uuid.uuid4())})
    check("AT-72", "'집' → 409", st == 409, f"status={st} {short(b)}")
    st, b = api("/api/saved-places/", "POST", {"label": "", "lat": OTHER_HOME2["lat"], "lng": OTHER_HOME2["lng"],
                                                "name": OTHER_HOME2["name"], "client_uuid": str(uuid.uuid4())})
    check("AT-72", "빈 이름 → 400", st == 400, f"status={st} {short(b)}")

    # ---------------------------------------------------------------- 5. 최근 경로
    print("\n[5] 최근 · 자주 쓰는 경로 (AT-76)")
    for sort in ("recent", "frequent"):
        st, b = api(f"/api/routes/history?sort={sort}&limit=5")
        check("AT-76", f"routes/history sort={sort} 200", st == 200, f"status={st} {short(b)}")
    st, b = api("/api/routes/history?sort=bogus")
    check("AT-76", "모르는 sort → 400", st == 400, f"status={st}")
    st, b = api("/api/routes/history?limit=99")
    check("AT-76", "limit 범위 밖 → 400", st == 400, f"status={st}")

    # ---------------------------------------------------------------- 6. 리포트
    print("\n[6] 주간 리포트 (AT-35, AT-59, AT-75)")
    st, wk = api("/api/reports/weekly")
    check("AT-59", "주간 리포트 200", st == 200, f"status={st}")
    summary = (wk or {}).get("summary") or ""
    check("AT-35", "summary_source llm/template", (wk or {}).get("summary_source") in ("llm", "template"),
          f"{(wk or {}).get('summary_source')!r}")
    check("AT-75", "요약 문장이 해요체로 끝남", (not summary) or summary.rstrip().endswith(("요.", "요")),
          f"summary={summary[:120]!r}")
    st, cal = api("/api/reports/calibration")
    check("0.22.0", "calibration 에 samples_needed (BE-LLM1)", "samples_needed" in (cal or {}), f"keys={sorted(cal or {})}")

    # ---------------------------------------------------------------- 7. 문의
    print("\n[7] 문의하기 · 문제 신고 (AT-63, N59)")
    st, b = api("/api/support/inquiries", "POST", {"message": "짧아요", "reply_email": email,
                                                    "app_version": "e2e", "device": "e2e-script"})
    check("AT-63", "10자 미만 → 400 + 칸별 이유", st == 400 and "message" in err(b)[2], f"status={st} {short(b)}")
    st, b = api("/api/support/inquiries", "POST", {"message": "E2E 확인용 문의예요. 열 글자 이상이에요.",
                                                    "reply_email": '"quoted"@example.com', "app_version": "e2e", "device": "e2e-script"})
    check("AT-63", "따옴표 로컬 부분 이메일 → 400", st == 400 and "reply_email" in err(b)[2], f"status={st} {short(b)}")
    if args.skip_inquiry:
        skip("AT-63", "문의 저장 201 · 팀 메일", "--skip-inquiry")
    else:
        st, b = api("/api/support/inquiries", "POST", {
            "message": f"[E2E 테스트] Iteration 5 E2E 확인 스크립트가 보낸 문의예요. 답하지 않아도 돼요. ({iso(started)})",
            "reply_email": email, "app_version": "e2e-check", "device": "check_e2e_iter5.py"})
        check("AT-63", "문의 저장 201 (팀 메일함에 '[E2E 테스트]' 메일 1통이 와야 함)",
              st == 201 and "id" in (b or {}), f"status={st} {short(b)}")

    # ---------------------------------------------------------------- 8. 기록 초기화
    print("\n[8] 기록 초기화 (AT-70, N58)")
    st, b = api("/api/auth/me/learning/reset", "POST")
    reset_at = parse((b or {}).get("reset_at"))
    check("AT-70", "초기화 200 · reset_at", st == 200 and reset_at is not None, f"status={st} {short(b)}")
    if event_id and reset_at:
        old = reset_at - timedelta(minutes=5)
        st, b = api("/api/observations/batch", "POST", {"observations": [{
            "event": event_id, "kind": "depart", "detector": "gps", "observed_at": iso(old),
            "lat": HOME["lat"], "lng": HOME["lng"], "accuracy_m": 15.0, "distance_m": 160.0,
            "client_uuid": f"e2e-{uuid.uuid4().hex[:12]}"}]})
        check("AT-70", "초기화 전 시각의 기록 업로드 → ignored 1 (학습에 안 씀)",
              st in (200, 201) and (b or {}).get("ignored") == 1, f"status={st} {short(b)}")
    st, b = api("/api/auth/me/learning/reset", "POST", auth=False)
    check("AT-70", "토큰 없이 401", st == 401, f"status={st}")

    # ---------------------------------------------------------------- 9. 로그인 잠금 (별도 계정)
    print("\n[9] 로그인 잠금 (AT-69, N54) — 두 번째 테스트 계정")
    locker = Api(args.base)
    lock_email = register(locker, "E2E잠금")
    check("setup", "잠금용 계정 가입 201", bool(lock_email))
    if lock_email:
        remain = []
        for _ in range(4):
            st, b = locker("/api/auth/token", "POST", {"email": lock_email, "password": "wrong-password-1"}, auth=False)
            remain.append((st, err(b)[2].get("remaining_attempts")))
        check("AT-69", "틀린 비밀번호 4번 → 401, remaining_attempts 4→1",
              [r[0] for r in remain] == [401] * 4 and [r[1] for r in remain] == [4, 3, 2, 1], f"{remain}")
        st, b = locker("/api/auth/token", "POST", {"email": lock_email, "password": "wrong-password-1"}, auth=False)
        code, msg, det = err(b)
        check("AT-69", "5번째 → 423 account_locked + locked_until + Retry-After",
              st == 423 and code == "account_locked" and bool(det.get("locked_until"))
              and bool(locker.last_headers.get("Retry-After")), f"status={st} {short(b)} RA={locker.last_headers.get('Retry-After')}")
        st, b = locker("/api/auth/token", "POST", {"email": lock_email, "password": PASSWORD}, auth=False)
        check("AT-69", "잠긴 동안 맞는 비밀번호도 423", st == 423, f"status={st}")
        ghost = f"depcheck_ghost_{uuid.uuid4().hex[:8]}@example.com"
        st, b = locker("/api/auth/token", "POST", {"email": ghost, "password": "wrong-password-1"}, auth=False)
        check("AT-69", "없는 이메일도 똑같이 401 + remaining_attempts 4 (이메일 존재 숨김)",
              st == 401 and err(b)[2].get("remaining_attempts") == 4, f"status={st} {short(b)}")

    # ---------------------------------------------------------------- 10. 문구 해요체
    print("\n[10] 서버 문구 해요체 (AT-75, N56)")
    bad = [(w, m) for w, m in messages_seen if not re.search(r"요[.!?]?\s*$", m.strip())]
    check("AT-75", f"이번 실행에서 받은 오류 문구 {len(messages_seen)}개가 모두 해요체",
          not bad, "; ".join(f"{w}: {m}" for w, m in bad[:6]))

    return finish(args, started, health, [email, lock_email])


def finish(args, started, health, emails) -> int:
    ok = sum(r["result"] == "OK" for r in results)
    fail = sum(r["result"] == "FAIL" for r in results)
    sk = sum(r["result"] == "SKIP" for r in results)
    print("\n" + "=" * 78)
    print(f"통과 {ok} / 실패 {fail} / 건너뜀 {sk}")
    for r in results:
        if r["result"] == "FAIL":
            print(f"  FAIL {r['at']} {r['label']}: {r['note']}")
    print(f"만든 계정: {[e for e in emails if e]}")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"base": args.base, "started": iso(started), "health": health,
                   "accounts": [e for e in emails if e], "summary": {"ok": ok, "fail": fail, "skip": sk},
                   "results": results, "error_messages": messages_seen}, f, ensure_ascii=False, indent=2)
    print(f"결과 파일: {args.out}")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
