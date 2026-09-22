"""루틴 블록 API 를 살아 있는 서버에 대고 확인한다.

## 이 스크립트가 없으면 놓치는 것

`pytest` 는 프로세스 안에서 돈다. 그래서 URL 라우팅, 미들웨어, 실제 직렬화,
스로틀 같은 **배포 경로**를 지나지 않는다. 실제로 `apps.routines.urls` 를
`config/urls.py` 에 붙이는 것을 빼먹으면 pytest 는 통과하고 서버만 404 가 된다.

## 쓰는 법

    # 로컬 서버 (기본)
    python scripts/check_routines_api.py

    # 배포 서버
    python scripts/check_routines_api.py --base https://justintime-api.onrender.com

로컬 대상일 때는 공용 DB 보호 가드가 돈다. 배포 서버를 볼 때는 `--base` 를
주므로 이 프로세스의 DB 설정과 무관하다.
"""

from __future__ import annotations

# --- 공용 DB 보호 -----------------------------------------------------------
# 이 스크립트는 검증용 계정과 블록을 만든다. 공용 DB 에서 돌리면 팀 전체가
# 보는 목록이 테스트 데이터로 채워진다. 근거는 scripts/_local_guard.py 상단.
import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parent))
from _local_guard import require_local_database  # noqa: E402

require_local_database()
# ---------------------------------------------------------------------------

import argparse  # noqa: E402
import json  # noqa: E402
import uuid  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402

import requests  # noqa: E402

KST = timezone(timedelta(hours=9))

HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"name": "서울대학교 관악캠퍼스", "lat": 37.459786, "lng": 126.951124}

passed = failed = skipped = 0
_notes: list[str] = []


def check(label: str, ok: bool, detail: str = ""):
    global passed, failed
    if ok:
        passed += 1
        print(f"[ OK ] {label}")
    else:
        failed += 1
        _notes.append(label)
        print(f"[FAIL] {label}")
    if detail:
        print(f"       {detail}")


def skip(label: str, why: str):
    global skipped
    skipped += 1
    print(f"[SKIP] {label}  ({why})")


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def __call__(self, path: str, method: str = "GET", body=None, auth: bool = True):
        headers = {"Content-Type": "application/json"}
        if auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        res = requests.request(
            method,
            f"{self.base}{path}",
            headers=headers,
            data=json.dumps(body) if body is not None else None,
            timeout=120,
        )
        try:
            return res.status_code, res.json()
        except ValueError:
            return res.status_code, {"_raw": res.text[:300]}


def register(api: Api, tag: str) -> str:
    email = f"blkchk_{tag}_{uuid.uuid4().hex[:6]}@example.com"
    # 이메일 접두와 닮지 않은 비밀번호. 닮으면
    # UserAttributeSimilarityValidator 가 400 을 내고 검증이 깜빡인다.
    st, body = api("/api/auth/register", "POST", {
        "email": email,
        "nickname": "블록확인",
        "password": "Wp6-golden-thicket-54",
        "password_confirm": "Wp6-golden-thicket-54",
    }, auth=False)
    if st != 201:
        raise SystemExit(f"계정 생성 실패 {st}: {body}")
    api.token = body["access"]
    return email


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8000")
    base = parser.parse_args().base

    api = Api(base)
    print("=" * 74)
    print(f"루틴 블록 API 확인: {base}")
    print("=" * 74)

    st, _ = api("/api/health", auth=False)
    check("health 200", st == 200, f"status={st}")
    if st != 200:
        return 1

    email = register(api, "a")
    api("/api/profile", "PATCH", {
        "home_lat": HOME["lat"], "home_lng": HOME["lng"],
        "home_label": HOME["label"], "onboarding_prep_min": 30,
    })

    # --- 1. 경로가 붙어 있는가 -------------------------------------------
    print("\n[1] 라우팅")
    st, body = api("/api/routines/blocks")
    check("블록 목록 200", st == 200, f"status={st} body={str(body)[:160]}")
    check("새 계정은 블록 없음", body == [], f"{body}")
    check("페이지네이션 없음(배열)", isinstance(body, list), f"type={type(body).__name__}")

    st, _ = api("/api/routines/blocks", auth=False)
    check("토큰 없이 401", st == 401, f"status={st}")

    # --- 2. 생성·수정·삭제 -----------------------------------------------
    print("\n[2] CRUD")
    st, shower = api("/api/routines/blocks", "POST", {
        "name": "샤워", "default_min_minutes": 12, "default_max_minutes": 18,
        "order": 1,
    })
    check("블록 생성 201", st == 201, f"status={st} body={str(shower)[:200]}")
    shower_id = shower.get("id")

    st, hair = api("/api/routines/blocks", "POST", {
        "name": "머리 손질", "default_min_minutes": 5, "default_max_minutes": 12,
        "order": 2, "precondition": shower_id,
    })
    check("선행 블록 지정 201", st == 201, f"status={st} body={str(hair)[:200]}")
    check("선행 블록이 응답에 유지", hair.get("precondition") == shower_id, f"{hair}")

    st, body = api(f"/api/routines/blocks/{shower_id}", "PATCH",
                   {"default_max_minutes": 22})
    check("블록 수정 200", st == 200, f"status={st}")
    check("수정 반영", body.get("default_max_minutes") == 22, f"{body}")

    st, body = api("/api/routines/blocks")
    check("목록 2건", len(body) == 2, f"n={len(body)}")
    check("한글 이름 보존", {b["name"] for b in body} == {"샤워", "머리 손질"}, f"{body}")

    # --- 3. 입력 검증 -----------------------------------------------------
    print("\n[3] 입력 검증")
    st, _ = api("/api/routines/blocks", "POST", {
        "name": "뒤집힘", "default_min_minutes": 20, "default_max_minutes": 10,
    })
    check("뒤집힌 범위 400", st == 400, f"status={st}")

    st, _ = api("/api/routines/blocks", "POST", {
        "name": "너무 김", "default_min_minutes": 0, "default_max_minutes": 9999,
    })
    check("과도한 소요 400", st == 400, f"status={st}")

    st, _ = api("/api/routines/blocks", "POST", {
        "name": "샤워", "default_min_minutes": 1, "default_max_minutes": 2,
    })
    check("이름 중복 400 (500 아님)", st == 400, f"status={st}")

    st, _ = api(f"/api/routines/blocks/{shower_id}", "PATCH",
                {"precondition": shower_id})
    check("자기참조 400", st == 400, f"status={st}")

    st, _ = api(f"/api/routines/blocks/{shower_id}", "PATCH",
                {"precondition": hair.get("id")})
    check("순환 참조 400", st == 400, f"status={st}")

    # --- 4. 일정별 블록 체크 + 재계산 -------------------------------------
    print("\n[4] 일정별 체크 · 재계산")
    start_at = (datetime.now(KST) + timedelta(days=3)).replace(
        hour=9, minute=0, second=0, microsecond=0
    )
    st, event = api("/api/events", "POST", {
        "title": "블록 확인 일정",
        "start_at": start_at.isoformat(),
        "place": DEST,
        "tag_key": "class",
    })
    check("일정 생성 201", st == 201, f"status={st} body={str(event)[:200]}")
    event_id = event.get("id")
    plan = (event or {}).get("alarm_plan") or {}

    if plan.get("status") == "ok":
        check(
            "블록이 준비 시간에 반영됨",
            plan.get("prep_minutes", 0) >= 17,
            f"prep={plan.get('prep_minutes')}분 source={plan.get('prep_source')} "
            "— 온보딩 30분이 아니라 블록 합계여야 한다",
        )
        check(
            "prep_source 가 declared_range",
            plan.get("prep_source") == "declared_range",
            f"prep_source={plan.get('prep_source')}",
        )
        check(
            "확률은 여전히 null (이동 변동성 미지)",
            plan.get("on_time_probability") is None,
            f"prob={plan.get('on_time_probability')} basis={plan.get('confidence_basis')}",
        )
        check(
            "confidence_basis 가 이유를 알려줌",
            plan.get("confidence_basis") == "travel_variance_unknown",
            f"basis={plan.get('confidence_basis')}",
        )
        check(
            "prep_breakdown 이 블록별 내역을 담음",
            isinstance(plan.get("prep_breakdown"), list)
            and len(plan.get("prep_breakdown") or []) == 2,
            f"breakdown={plan.get('prep_breakdown')}",
        )
    else:
        skip("블록 반영 확인", f"알람 계획이 {plan.get('status')} — 카카오 키 문제")

    st, body = api(f"/api/events/{event_id}/blocks")
    check("일정별 블록 상태 200", st == 200, f"status={st}")
    rows = (body or {}).get("blocks") or []
    check("블록 2건 반환", len(rows) == 2, f"n={len(rows)}")
    check("기본 포함이 checked=true", all(r.get("checked") for r in rows), f"{rows}")
    check("아직 explicit=false", not any(r.get("explicit") for r in rows), f"{rows}")

    before = plan.get("prep_minutes")
    st, body = api(f"/api/events/{event_id}/blocks", "PUT", {
        "selections": [{"block": shower_id, "checked": False}]
    })
    check("체크 변경 200", st == 200, f"status={st} body={str(body)[:200]}")
    after_plan = (body or {}).get("alarm_plan") or {}
    if before and after_plan.get("prep_minutes"):
        check(
            "체크를 끄면 준비 시간이 줄어든다",
            after_plan["prep_minutes"] < before,
            f"{before}분 -> {after_plan['prep_minutes']}분",
        )
    else:
        skip("재계산 비교", "계획 값이 없다")

    st, body = api(f"/api/events/{event_id}/blocks")
    row = next((r for r in (body.get("blocks") or []) if r["id"] == shower_id), {})
    check("explicit=true 로 바뀜", row.get("explicit") is True, f"{row}")

    # --- 5. 관측 업로드 ---------------------------------------------------
    print("\n[5] 블록 관측")
    uid = f"blkobs-{uuid.uuid4().hex[:12]}"
    batch = {"observations": [{
        "block": shower_id,
        "event": event_id,
        "observed_on": start_at.date().isoformat(),
        "duration_minutes": 21.0,
        "slack_minutes": 4.0,
        "was_parallel": False,
        "client_uuid": uid,
        "client_recorded_at": start_at.isoformat(),
    }]}
    st, body = api("/api/routines/observations/batch", "POST", batch)
    check("관측 업로드 201", st == 201, f"status={st} body={str(body)[:200]}")
    check("1건 적재", (body or {}).get("accepted") == 1, f"{body}")

    st, body = api("/api/routines/observations/batch", "POST", batch)
    check("재전송은 무시(멱등)", (body or {}).get("duplicated") == 1, f"status={st} {body}")

    st, blocks = api("/api/routines/blocks")
    row = next((b for b in blocks if b["id"] == shower_id), {})
    check("관측 수가 응답에 보임", row.get("observation_count") == 1, f"{row}")
    check(
        "실측 평균이 응답에 보임",
        row.get("observed_mean_minutes") == 21.0,
        f"observed_mean_minutes={row.get('observed_mean_minutes')}",
    )

    st, _ = api("/api/routines/observations/batch", "POST", {"observations": []})
    check("빈 배치 400", st == 400, f"status={st}")

    # --- 6. 격리 ----------------------------------------------------------
    print("\n[6] 격리")
    other = Api(base)
    register(other, "b")

    st, body = other("/api/routines/blocks")
    check("남의 블록은 안 보인다", st == 200 and body == [], f"status={st} {body}")

    st, _ = other(f"/api/routines/blocks/{shower_id}")
    check("남의 블록 직접 조회 404", st == 404, f"status={st}")

    st, _ = other(f"/api/routines/blocks/{shower_id}", "PATCH", {"name": "탈취"})
    check("남의 블록 수정 404", st == 404, f"status={st}")

    st, _ = other(f"/api/routines/blocks/{shower_id}", "DELETE")
    check("남의 블록 삭제 404", st == 404, f"status={st}")

    st, _ = other(f"/api/events/{event_id}/blocks")
    check("남의 일정 블록 조회 404", st == 404, f"status={st}")

    # 남의 블록을 자기 일정에 붙이려는 시도
    st, oevent = other("/api/events", "POST", {
        "title": "남의 일정",
        "start_at": start_at.isoformat(),
        "place": DEST,
    })
    if st == 201:
        st, _ = other(f"/api/events/{oevent['id']}/blocks", "PUT", {
            "selections": [{"block": shower_id, "checked": True}]
        })
        check("남의 블록을 내 일정에 붙이기 400", st == 400, f"status={st}")

        st, _ = other("/api/routines/observations/batch", "POST", {
            "observations": [{
                "block": shower_id,
                "observed_on": start_at.date().isoformat(),
                "duration_minutes": 10.0,
                "client_uuid": f"x-{uuid.uuid4().hex[:10]}",
                "client_recorded_at": start_at.isoformat(),
            }]
        })
        check("남의 블록에 관측 업로드 400", st == 400, f"status={st}")
    else:
        skip("타 계정 일정 확인", f"일정 생성 실패 {st}")

    # --- 7. 삭제 정리 -----------------------------------------------------
    print("\n[7] 삭제")
    st, _ = api(f"/api/routines/blocks/{shower_id}", "DELETE")
    check("블록 삭제 204", st == 204, f"status={st}")
    st, blocks = api("/api/routines/blocks")
    remaining = next((b for b in blocks if b["name"] == "머리 손질"), {})
    check(
        "의존 블록의 선행이 풀린다",
        remaining.get("precondition") is None,
        f"{remaining}",
    )

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
