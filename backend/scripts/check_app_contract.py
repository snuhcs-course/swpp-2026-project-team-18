"""앱 DTO 필드 이름이 서버 응답과 실제로 맞는지 확인한다.

## 왜 별도 검사가 필요한가

앱은 **Gson** 으로 역직렬화한다. Gson 은 JSON 에 없는 필드를 조용히 null 로
두고, 이름이 어긋난 필드도 조용히 버린다. **예외가 나지 않는다.** 그래서
`@SerializedName("prep_breakdown")` 을 `prepBreakdown` 으로 잘못 적어도 앱은
정상 동작하는 것처럼 보이고, 화면에만 값이 비어 보인다. 그 증상은 "서버가 아직
안 주는 것" 과 구별되지 않는다.

실제로 이 프로젝트에서 `confidence_basis` 와 `prep_breakdown` 이 서버에는
있는데 앱 DTO 에는 없어서 몇 주 동안 버려지고 있었다. 근거 카드가 비어 있는
이유를 "학습이 덜 됐다" 로 오해했다.

## 무엇을 하는가

1. 앱의 Kotlin 파일에서 DTO 의 **와이어 이름**을 뽑는다
   (`@SerializedName("x")` 가 있으면 그 값, 없으면 프로퍼티 이름).
2. 실제 서버 응답에 그 이름이 **모두** 있는지 본다.
3. 반대로 서버가 주는데 앱이 안 읽는 필드도 알려준다(경고).
4. 앱이 **보내는** 필드 이름이 서버에 실제로 먹히는지 확인한다.

## 쓰는 법

    python scripts/check_app_contract.py
    python scripts/check_app_contract.py --base https://justintime-api.onrender.com
"""

from __future__ import annotations

# --- 공용 DB 보호 -----------------------------------------------------------
# 이 스크립트는 검증용 계정·블록·일정을 만든다. 공용 DB 에서 돌리면 팀 전체가
# 보는 목록이 테스트 데이터로 채워진다. 근거는 scripts/_local_guard.py 상단.
import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parent))
from _local_guard import require_local_database  # noqa: E402

require_local_database()
# ---------------------------------------------------------------------------

import argparse  # noqa: E402
import json  # noqa: E402
import re  # noqa: E402
import uuid  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402

import requests  # noqa: E402

KST = timezone(timedelta(hours=9))

REPO = _Path(__file__).resolve().parents[2]
APP_SRC = REPO / "APP" / "app" / "src" / "main" / "java" / "com" / "swpp" / "wakeup"

HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEST = {"name": "서울대학교 관악캠퍼스", "lat": 37.459786, "lng": 126.951124}

passed = failed = skipped = 0
_notes: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> bool:
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
    return ok


def skip(label: str, why: str):
    global skipped
    skipped += 1
    print(f"[SKIP] {label}  ({why})")


def warn(label: str, detail: str = ""):
    print(f"[WARN] {label}")
    if detail:
        print(f"       {detail}")


# ---------------------------------------------------------------------------
# Kotlin DTO 파서
# ---------------------------------------------------------------------------

# `@SerializedName("wire") val kotlinName: Type` 과 `val kotlinName: Type` 를
# 모두 잡는다. 주석 줄과 기본값 안의 괄호는 아래 깊이 추적으로 걸러낸다.
_SERIALIZED = re.compile(r'@SerializedName\(\s*"([^"]+)"\s*\)')
_PROPERTY = re.compile(r"\bva[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*:")


def _strip_comments(text: str) -> str:
    """블록·행 주석을 지운다. 주석 안의 예시 JSON 이 필드로 잡히면 안 된다."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def dto_wire_names(path: _Path, class_name: str) -> list[str]:
    """`data class <class_name>(...)` 의 와이어 이름을 선언 순서대로 돌려준다."""
    source = _strip_comments(path.read_text(encoding="utf-8"))

    anchor = re.search(rf"\bdata class\s+{re.escape(class_name)}\s*\(", source)
    if anchor is None:
        raise SystemExit(f"{path.name} 에서 data class {class_name} 를 찾지 못했다.")

    # 여는 괄호부터 짝이 맞는 닫는 괄호까지. 기본값의 `emptyList()` 때문에
    # 단순 검색으로는 끊을 수 없다.
    start = anchor.end() - 1
    depth = 0
    end = start
    for i in range(start, len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    body = source[start + 1 : end]

    names: list[str] = []
    # 파라미터를 최상위 콤마로 나눈다. 기본값 안의 콤마를 피하려고 깊이를 센다.
    depth = 0
    buf: list[str] = []
    chunks: list[str] = []
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            chunks.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    if "".join(buf).strip():
        chunks.append("".join(buf))

    for chunk in chunks:
        annotated = _SERIALIZED.search(chunk)
        prop = _PROPERTY.search(chunk)
        if annotated:
            names.append(annotated.group(1))
        elif prop:
            names.append(prop.group(1))
    return names


def compare(label: str, expected: list[str], payload: dict, *, ignore: set[str] = frozenset()):
    """앱이 기대하는 필드가 응답에 모두 있는지. 남는 필드는 경고만."""
    want = [n for n in expected if n not in ignore]
    missing = [n for n in want if n not in payload]
    check(
        f"{label}: 앱이 읽는 {len(want)}개 필드가 응답에 모두 있음",
        not missing,
        f"빠진 필드={missing}" if missing else f"필드={want}",
    )

    extra = [k for k in payload if k not in expected]
    if extra:
        warn(
            f"{label}: 서버가 주지만 앱이 읽지 않는 필드 {len(extra)}개",
            f"{extra} — Gson 이 조용히 버린다. 필요하면 DTO 에 추가할 것",
        )


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


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


def register(api: Api) -> str:
    email = f"ctrchk_{uuid.uuid4().hex[:8]}@example.com"
    # 이메일과 닮지 않은 비밀번호. 닮으면 UserAttributeSimilarityValidator 가
    # 400 을 내고 검증이 무작위로 깜빡인다.
    st, body = api(
        "/api/auth/register",
        "POST",
        {
            "email": email,
            "nickname": "계약확인",
            "password": "Jq4-amber-lantern-77",
            "password_confirm": "Jq4-amber-lantern-77",
        },
        auth=False,
    )
    if st != 201:
        raise SystemExit(f"계정 생성 실패 {st}: {body}")
    api.token = body["access"]
    return email


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8000")
    base = parser.parse_args().base

    print("=" * 74)
    print(f"앱 ↔ 서버 필드 계약 확인: {base}")
    print("=" * 74)

    events_api = APP_SRC / "data" / "remote" / "EventsApi.kt"
    routines_api = APP_SRC / "data" / "remote" / "RoutinesApi.kt"
    for path in (events_api, routines_api):
        if not path.exists():
            raise SystemExit(f"앱 소스를 찾지 못했다: {path}")

    # --- 0. DTO 선언 읽기 -------------------------------------------------
    print("\n[0] 앱 DTO 선언")
    alarm_fields = dto_wire_names(events_api, "AlarmPlanDto")
    prep_fields = dto_wire_names(events_api, "PrepBlockDto")
    event_fields = dto_wire_names(events_api, "EventDto")
    block_fields = dto_wire_names(routines_api, "RoutineBlockDto")
    block_write = dto_wire_names(routines_api, "RoutineBlockWriteRequest")
    event_blocks = dto_wire_names(routines_api, "EventBlocksResponse")

    check("AlarmPlanDto 파싱", len(alarm_fields) >= 20, f"{len(alarm_fields)}개 {alarm_fields}")
    check("PrepBlockDto 파싱", len(prep_fields) >= 8, f"{len(prep_fields)}개 {prep_fields}")
    check("RoutineBlockDto 파싱", len(block_fields) >= 12, f"{len(block_fields)}개 {block_fields}")
    check("RoutineBlockWriteRequest 파싱", len(block_write) >= 6, f"{block_write}")

    # 근거 카드가 이 두 필드로 동작한다. 이름이 빠지면 화면이 조용히 빈다.
    check(
        "AlarmPlanDto 가 confidence_basis 를 읽는다",
        "confidence_basis" in alarm_fields,
        f"{alarm_fields}",
    )
    check(
        "AlarmPlanDto 가 prep_breakdown 을 읽는다",
        "prep_breakdown" in alarm_fields,
        f"{alarm_fields}",
    )

    api = Api(base)
    st, _ = api("/api/health", auth=False)
    if not check("health 200", st == 200, f"status={st}"):
        return 1

    register(api)
    api(
        "/api/profile",
        "PATCH",
        {
            "home_lat": HOME["lat"],
            "home_lng": HOME["lng"],
            "home_label": HOME["label"],
            "onboarding_prep_min": 30,
        },
    )

    # --- 1. 블록 쓰기 필드가 먹히는가 -------------------------------------
    print("\n[1] 블록 생성 — 앱이 보내는 이름이 서버에 먹히는가")
    payload = {
        "name": "샤워",
        "default_min_minutes": 12,
        "default_max_minutes": 18,
        "drop_cost": "large",
        "parallelizable": False,
        "included_by_default": True,
    }
    check(
        "앱 쓰기 DTO 의 이름이 요청 본문과 일치",
        set(payload) <= set(block_write),
        f"앱={sorted(block_write)} 요청={sorted(payload)}",
    )
    st, shower = api("/api/routines/blocks", "POST", payload)
    if not check("블록 생성 201", st == 201, f"status={st} body={str(shower)[:200]}"):
        return 1
    shower_id = shower["id"]

    # 서버가 값을 **그대로** 받았는지. 이름만 맞고 값이 무시되면 더 나쁘다.
    check(
        "drop_cost 가 저장됨",
        shower.get("drop_cost") == "large",
        f"drop_cost={shower.get('drop_cost')}",
    )
    check(
        "included_by_default 가 저장됨",
        shower.get("included_by_default") is True,
        f"{shower.get('included_by_default')}",
    )

    st, _ = api(
        "/api/routines/blocks",
        "POST",
        {
            "name": "아침 식사",
            "default_min_minutes": 8,
            "default_max_minutes": 15,
            "drop_cost": "none",
        },
    )
    check("두 번째 블록 생성 201", st == 201, f"status={st}")

    # 병렬 블록. 앱이 "병렬" 칩을 그리는 경로다.
    st, laundry = api(
        "/api/routines/blocks",
        "POST",
        {
            "name": "세탁기",
            "default_min_minutes": 5,
            "default_max_minutes": 5,
            "parallelizable": True,
            "drop_cost": "small",
        },
    )
    check("병렬 블록 생성 201", st == 201, f"status={st}")
    check(
        "parallelizable 가 저장됨",
        (laundry or {}).get("parallelizable") is True,
        f"{(laundry or {}).get('parallelizable')}",
    )

    # --- 2. 블록 목록 응답 모양 -------------------------------------------
    print("\n[2] 블록 목록 — RoutineBlockDto")
    st, blocks = api("/api/routines/blocks")
    check("목록 200", st == 200, f"status={st}")
    check(
        "생 배열이다 (Paged 아님)",
        isinstance(blocks, list),
        f"type={type(blocks).__name__} — 앱은 Response<List<RoutineBlockDto>> 로 받는다",
    )
    if isinstance(blocks, list) and blocks:
        # checked/explicit 는 일정 문맥에서만 온다. 목록에서는 없는 것이 정상이라
        # 앱 DTO 도 기본값 null 로 두었다.
        compare(
            "블록 목록",
            block_fields,
            blocks[0],
            ignore={"checked", "explicit"},
        )
    else:
        skip("블록 목록 필드 비교", "목록이 비었다")

    # --- 3. 일정 생성 + 알람 계획 -----------------------------------------
    print("\n[3] 알람 계획 — AlarmPlanDto / PrepBlockDto")
    start_at = (datetime.now(KST) + timedelta(days=3)).replace(
        hour=9, minute=0, second=0, microsecond=0
    )
    st, event = api(
        "/api/events",
        "POST",
        {
            "title": "계약 확인 일정",
            "start_at": start_at.isoformat(),
            "place": DEST,
            "tag_key": "class",
        },
    )
    if not check("일정 생성 201", st == 201, f"status={st} body={str(event)[:200]}"):
        return 1
    event_id = event["id"]

    compare("일정", event_fields, event)

    plan = event.get("alarm_plan") or {}
    check("alarm_plan 이 있다", bool(plan), f"{str(plan)[:120]}")
    compare("알람 계획", alarm_fields, plan)

    # 앱이 문구를 분기하는 값이다. 서버가 모르는 값을 주면 앱이 기본 문구로
    # 떨어지면서 "이유를 알려준다" 는 약속이 조용히 깨진다.
    app_bases = {
        "observed",
        "point_estimate",
        "travel_variance_unknown",
        "prep_variance_unknown",
    }
    app_prep_sources = {
        "observed",
        "declared_range",
        "declared_point",
        "onboarding",
        "fixed",
    }

    if plan.get("status") == "ok":
        check(
            "confidence_basis 가 앱이 아는 4종 중 하나",
            plan.get("confidence_basis") in app_bases,
            f"basis={plan.get('confidence_basis')} 앱이 아는 값={sorted(app_bases)}",
        )
        check(
            "prep_source 가 앱이 아는 5종 중 하나",
            plan.get("prep_source") in app_prep_sources,
            f"prep_source={plan.get('prep_source')} 앱이 아는 값={sorted(app_prep_sources)}",
        )
        check(
            "확률이 null 이면 basis 가 이유를 말한다",
            plan.get("on_time_probability") is not None
            or plan.get("confidence_basis") != "observed",
            f"prob={plan.get('on_time_probability')} basis={plan.get('confidence_basis')}",
        )

        rows = plan.get("prep_breakdown") or []
        check(
            "prep_breakdown 이 블록 수만큼 있다",
            len(rows) == 3,
            f"n={len(rows)} rows={str(rows)[:200]}",
        )
        if rows:
            compare("준비 블록 내역", prep_fields, rows[0])
            check(
                "minutes 가 수치다",
                isinstance(rows[0].get("minutes"), (int, float)),
                f"minutes={rows[0].get('minutes')!r} — 앱은 Double 로 받는다",
            )
            check(
                "source 가 observed/declared 중 하나",
                all(r.get("source") in {"observed", "declared"} for r in rows),
                f"sources={[r.get('source') for r in rows]}",
            )
    else:
        skip("알람 계획 값 검증", f"status={plan.get('status')} — 카카오 키 확인 필요")

    # --- 4. 일정별 블록 응답 ----------------------------------------------
    print("\n[4] 일정별 블록 — EventBlocksResponse")
    st, body = api(f"/api/events/{event_id}/blocks")
    check("일정별 블록 200", st == 200, f"status={st}")
    compare("일정별 블록 껍데기", event_blocks, body if isinstance(body, dict) else {})

    rows = (body or {}).get("blocks") or []
    if rows:
        # 여기서는 checked/explicit 가 **있어야** 한다. 없으면 앱이 모든 블록을
        # 기본 포함값으로 그려서 사용자가 이 일정에서 끈 항목이 켜져 보인다.
        compare("일정별 블록 행", block_fields, rows[0])
        check(
            "checked 가 있다",
            all("checked" in r for r in rows),
            f"{[sorted(r.keys()) for r in rows][:1]}",
        )
        check(
            "explicit 가 있다",
            all("explicit" in r for r in rows),
            f"{[sorted(r.keys()) for r in rows][:1]}",
        )
    else:
        skip("일정별 블록 행 비교", "행이 없다")

    # --- 5. 체크 저장 → 재계산된 일정이 돌아오는가 -------------------------
    print("\n[5] 체크 저장 — 앱은 응답의 alarm_plan 을 그대로 쓴다")
    before = plan.get("prep_minutes")
    st, updated = api(
        f"/api/events/{event_id}/blocks",
        "PUT",
        {"selections": [{"block": shower_id, "checked": False}]},
    )
    check("체크 저장 200", st == 200, f"status={st} body={str(updated)[:160]}")
    check(
        "응답이 일정 객체다 (앱이 EventDto 로 받는다)",
        isinstance(updated, dict) and "alarm_plan" in updated,
        f"keys={sorted((updated or {}).keys())}",
    )
    new_plan = (updated or {}).get("alarm_plan") or {}
    compare("저장 응답의 알람 계획", alarm_fields, new_plan)

    if before and new_plan.get("prep_minutes"):
        check(
            "체크를 끄면 준비 시간이 줄어든다",
            new_plan["prep_minutes"] < before,
            f"{before}분 -> {new_plan['prep_minutes']}분",
        )
        check(
            "재계산된 alarm_at 이 응답에 있다",
            bool(new_plan.get("alarm_at")),
            f"alarm_at={new_plan.get('alarm_at')} — 없으면 앱이 화면을 갱신할 수 없다",
        )
        check(
            "내역에서도 그 블록이 빠졌다",
            all(r.get("block_id") != shower_id for r in (new_plan.get("prep_breakdown") or [])),
            f"breakdown={[r.get('name') for r in (new_plan.get('prep_breakdown') or [])]}",
        )
    else:
        skip("재계산 비교", "계획 값이 없다")

    # --- 6. 부분 수정이 다른 필드를 지우지 않는가 -------------------------
    print("\n[6] 부분 수정 — Gson 이 null 을 빼는 성질에 기댄다")
    st, patched = api(
        f"/api/routines/blocks/{shower_id}",
        "PATCH",
        {"included_by_default": False},
    )
    check("한 필드만 PATCH 200", st == 200, f"status={st}")
    check(
        "이름이 보존됨",
        (patched or {}).get("name") == "샤워",
        f"name={(patched or {}).get('name')}",
    )
    check(
        "범위가 보존됨",
        (patched or {}).get("default_min_minutes") == 12
        and (patched or {}).get("default_max_minutes") == 18,
        f"{(patched or {}).get('default_min_minutes')}~"
        f"{(patched or {}).get('default_max_minutes')}",
    )
    check(
        "drop_cost 가 보존됨",
        (patched or {}).get("drop_cost") == "large",
        f"drop_cost={(patched or {}).get('drop_cost')}",
    )
    check(
        "바꾼 필드만 반영됨",
        (patched or {}).get("included_by_default") is False,
        f"{(patched or {}).get('included_by_default')}",
    )

    # --- 6.5 캘린더 가져오기 ----------------------------------------------
    print("\n[6.5] 캘린더 가져오기 — CalendarEventInput / CalendarImportResponse")
    import_req = dto_wire_names(events_api, "CalendarEventInput")
    import_res = dto_wire_names(events_api, "CalendarImportResponse")

    check(
        "앱이 external_id 를 보낸다",
        "external_id" in import_req,
        f"{import_req} — 없으면 동기화마다 사본이 쌓인다",
    )
    check(
        "앱이 external_id 를 읽는다",
        "external_id" in event_fields,
        f"{event_fields} — 없으면 '이미 가져온 일정' 을 구분할 수 없다",
    )

    cal_start = (datetime.now(KST) + timedelta(days=5)).replace(
        hour=10, minute=30, second=0, microsecond=0
    )
    payload = {
        "events": [
            {
                "external_id": "ctr-cal-1:1",
                "title": "가져온 수업",
                "start_at": cal_start.isoformat(),
                "place": DEST,
            }
        ]
    }
    # 앱이 실제로 보내는 필드 이름만 담았는지.
    sent = set(payload["events"][0])
    check(
        "앱 요청 DTO 이름이 본문과 일치",
        sent <= set(import_req),
        f"앱={sorted(import_req)} 요청={sorted(sent)}",
    )

    st, body = api("/api/events/import", "POST", payload)
    if check("가져오기 200", st == 200, f"status={st} body={str(body)[:200]}"):
        compare("가져오기 응답", import_res, body)
        check(
            "created=1",
            body.get("created") == 1,
            f"created={body.get('created')} updated={body.get('updated')}",
        )
        check(
            "results 가 일정 객체 목록",
            isinstance(body.get("results"), list)
            and body["results"]
            and "alarm_plan" in body["results"][0],
            f"results={str(body.get('results'))[:160]}",
        )
        check(
            "external_id 가 응답에 실려 온다",
            (body.get("results") or [{}])[0].get("external_id") == "ctr-cal-1:1",
            f"{(body.get('results') or [{}])[0].get('external_id')}",
        )
        check(
            "source=calendar",
            (body.get("results") or [{}])[0].get("source") == "calendar",
            f"{(body.get('results') or [{}])[0].get('source')}",
        )

        # 멱등성과 쿼터. 같은 요청을 다시 보내면 행이 늘지 않고 재계산도 없다.
        st2, again = api("/api/events/import", "POST", payload)
        check("재전송 200", st2 == 200, f"status={st2}")
        check(
            "재전송은 행을 늘리지 않는다",
            again.get("created") == 0 and again.get("unchanged") == 1,
            f"created={again.get('created')} unchanged={again.get('unchanged')}",
        )
        check(
            "변경이 없으면 재계산하지 않는다 (카카오 쿼터)",
            again.get("recomputed") == 0,
            f"recomputed={again.get('recomputed')} — 0 이어야 한다",
        )

    st, _ = api(
        "/api/events/import",
        "POST",
        {
            "events": [
                {
                    "external_id": "ctr-cal-past",
                    "title": "지난 일정",
                    "start_at": (datetime.now(KST) - timedelta(days=1)).isoformat(),
                }
            ]
        },
    )
    check("지난 일정은 400", st == 400, f"status={st}")

    st, _ = api("/api/events/import", "POST", {"events": []}, auth=True)
    check("빈 배치는 400", st == 400, f"status={st}")

    st, _ = api("/api/events/import", "POST", payload, auth=False)
    check("토큰 없이 401", st == 401, f"status={st}")

    # --- 6.7 주간 리포트 --------------------------------------------------
    print("\n[6.7] 주간 리포트 — WeeklyReportDto / CalibrationDto")
    reports_api = APP_SRC / "data" / "remote" / "ReportsApi.kt"
    if not reports_api.exists():
        raise SystemExit(f"앱 소스를 찾지 못했다: {reports_api}")

    weekly_fields = dto_wire_names(reports_api, "WeeklyReportDto")
    calib_fields = dto_wire_names(reports_api, "CalibrationDto")
    bucket_fields = dto_wire_names(reports_api, "CalibrationBucketDto")
    cause_fields = dto_wire_names(reports_api, "LateCauseDto")
    weekday_fields = dto_wire_names(reports_api, "WeekdayRowDto")

    check("WeeklyReportDto 파싱", len(weekly_fields) >= 12, f"{weekly_fields}")
    check("CalibrationDto 파싱", len(calib_fields) >= 7, f"{calib_fields}")

    # 이 화면의 값어치는 "앱이 과신하는지" 를 보여주는 데 있다. 판정과 표본
    # 부족 표시가 빠지면 화면이 앱을 변호하는 쪽으로 기운다.
    check(
        "앱이 verdict 를 읽는다",
        "verdict" in calib_fields,
        f"{calib_fields} — 없으면 과신 판정을 보여줄 수 없다",
    )
    check(
        "앱이 버킷의 reliable 을 읽는다",
        "reliable" in bucket_fields,
        f"{bucket_fields} — 없으면 표본 1건을 계통 오차로 보고한다",
    )
    check(
        "앱이 unobserved_count 를 읽는다",
        "unobserved_count" in weekly_fields,
        f"{weekly_fields} — 없으면 정시율이 실제보다 좋아 보인다",
    )
    check(
        "앱이 원인 label 을 읽는다",
        "label" in cause_fields,
        f"{cause_fields} — primary 만 읽으면 '측정 못 함' 과 '계획이 짧음' 이 섞인다",
    )

    st, weekly_body = api("/api/reports/weekly")
    if check("주간 리포트 200", st == 200, f"status={st} body={str(weekly_body)[:200]}"):
        compare("주간 리포트", weekly_fields, weekly_body)
        calib = weekly_body.get("calibration") or {}
        check("calibration 이 중첩돼 있다", bool(calib), f"{str(calib)[:160]}")
        if calib:
            compare("주간 캘리브레이션", calib_fields, calib)
        rows = weekly_body.get("by_weekday") or []
        check("요일 7칸", len(rows) == 7, f"n={len(rows)}")
        if rows:
            compare("요일 행", weekday_fields, rows[0])

    st, calib_body = api("/api/reports/calibration")
    if check("캘리브레이션 200", st == 200, f"status={st}"):
        compare("전체 캘리브레이션", calib_fields, calib_body)
        app_verdicts = {"calibrated", "overconfident", "conservative", "insufficient"}
        check(
            "verdict 가 앱이 아는 4종 중 하나",
            calib_body.get("verdict") in app_verdicts,
            f"verdict={calib_body.get('verdict')} 앱이 아는 값={sorted(app_verdicts)}",
        )
        check(
            "표본이 없으면 판정하지 않는다",
            calib_body.get("scored_count") != 0
            or calib_body.get("verdict") == "insufficient",
            f"scored={calib_body.get('scored_count')} verdict={calib_body.get('verdict')}",
        )
        check(
            "빈 버킷을 0 으로 내리지 않는다",
            all(
                b.get("actual_rate") is not None or b.get("total") == 0
                for b in (calib_body.get("buckets") or [])
            ),
            f"buckets={str(calib_body.get('buckets'))[:160]}",
        )

    st, _ = api("/api/reports/weekly", auth=False)
    check("리포트도 토큰 없이 401", st == 401, f"status={st}")

    st, body = api("/api/reports/weekly?week=notadate")
    check(
        "잘못된 week 는 400 이 아니라 기본 주로 떨어진다",
        st == 200 and bool(body.get("week_start")),
        f"status={st} week_start={body.get('week_start')}",
    )

    # --- 7. 오류 상세가 앱 칸으로 갈 수 있는가 ----------------------------
    print("\n[7] 오류 상세 — 입력칸 아래에 붙일 수 있어야 한다")
    st, err = api(
        "/api/routines/blocks",
        "POST",
        {"name": "샤워", "default_min_minutes": 1, "default_max_minutes": 2},
    )
    check("이름 중복은 400 (500 아님)", st == 400, f"status={st} body={str(err)[:200]}")
    details = ((err or {}).get("error") or {}).get("details") or {}
    check(
        "details 에 필드 이름이 있다",
        "name" in details,
        f"details={details} — 앱이 ApiClient.parseErrorFields 로 읽는다",
    )
    if "name" in details:
        value = details["name"]
        check(
            "필드 값이 문자열이거나 문자열 배열",
            isinstance(value, str)
            or (isinstance(value, list) and all(isinstance(v, str) for v in value)),
            f"type={type(value).__name__} value={value!r}",
        )

    st, err = api(
        "/api/routines/blocks",
        "POST",
        {"name": "뒤집힘", "default_min_minutes": 20, "default_max_minutes": 10},
    )
    check("뒤집힌 범위 400", st == 400, f"status={st}")
    details = ((err or {}).get("error") or {}).get("details") or {}
    check(
        "범위 오류가 default_max_minutes 로 온다",
        "default_max_minutes" in details,
        f"details={details} — 앱이 '최대' 칸 아래에 붙인다",
    )

    # --- 정리 -------------------------------------------------------------
    print("\n" + "=" * 74)
    print(f"통과 {passed} · 실패 {failed} · 건너뜀 {skipped}")
    if _notes:
        print("\n실패 항목:")
        for note in _notes:
            print(f"  - {note}")
    print("=" * 74)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
