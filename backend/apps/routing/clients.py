"""카카오 경로·로컬 검색 클라이언트.

back-spec.md 7절 규칙 — **예외를 밖으로 던지지 않는다.** 모든 함수가
`(값, degraded)` 를 돌려준다. `degraded=True` 면 값이 없거나 대체값이다.

확정된 엔드포인트 (checklist "API 호출 검증 결과", 2026-09-16 실측):

    대중교통  GET https://dapi.kakao.com/v2/routing/publictraffic
    도보      GET https://dapi.kakao.com/v2/routing/walk
    로컬검색  GET https://dapi.kakao.com/v2/local/search/keyword.json

**좌표 파라미터 이름이 수단별로 다르다.** 경로 API 는 start_x/start_y/end_x/end_y,
자동차(모빌리티)만 origin/destination 이다. 여기서는 경로 API 만 쓴다.
"""

from __future__ import annotations

import json
import logging
import ssl
import urllib.error
import urllib.parse
import urllib.request

from django.conf import settings

logger = logging.getLogger(__name__)

TIMEOUT_SECONDS = 5
LOCAL_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
TRANSIT_URL = "https://dapi.kakao.com/v2/routing/publictraffic"
WALK_URL = "https://dapi.kakao.com/v2/routing/walk"
BICYCLE_URL = "https://dapi.kakao.com/v2/routing/bicycle"
CAR_URL = "https://apis-navi.kakaomobility.com/v1/directions"

# 도보만으로 갈 만한 거리 기준. 이보다 짧으면 대중교통이 오히려 느리다.
WALK_ONLY_METERS = 1200

# 도보를 후보로 제시할 상한. 이보다 멀면 목록만 어지럽힌다.
WALK_CANDIDATE_MAX_MINUTES = 40

# 후보 목록에 담을 최대 개수.
CANDIDATE_LIMIT = 6


def _get(url: str) -> tuple[dict | None, bool]:
    """(json, degraded). 실패해도 예외를 던지지 않는다."""
    key = settings.KAKAO_REST_API_KEY
    if not key:
        logger.warning("KAKAO_REST_API_KEY 가 비어 있다. 경로 조회를 건너뛴다.")
        return None, True

    req = urllib.request.Request(url)
    req.add_header("Authorization", f"KakaoAK {key}")
    try:
        with urllib.request.urlopen(
            req, timeout=TIMEOUT_SECONDS, context=ssl.create_default_context()
        ) as resp:
            return json.loads(resp.read().decode("utf-8")), False
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:200]
        logger.warning("카카오 API %s 실패 status=%s body=%s", url.split("?")[0], e.code, body)
        return None, True
    except Exception as e:
        logger.warning("카카오 API %s 예외 %s: %s", url.split("?")[0], type(e).__name__, e)
        return None, True


def search_places(query: str, size: int = 10) -> tuple[list[dict], bool]:
    """장소 검색. 일정 추가 화면의 장소 선택에 쓴다.

    반환 항목은 클라이언트가 그대로 저장할 수 있는 형태로 정규화한다.
    """
    if not query.strip():
        return [], False

    qs = urllib.parse.urlencode({"query": query.strip(), "size": max(1, min(size, 15))})
    data, degraded = _get(f"{LOCAL_SEARCH_URL}?{qs}")
    if degraded or not data:
        return [], True

    places = []
    for d in data.get("documents", []):
        try:
            places.append(
                {
                    "kakao_place_id": d.get("id"),
                    "name": d.get("place_name") or "",
                    "address": d.get("road_address_name") or d.get("address_name") or "",
                    "lat": float(d["y"]),
                    "lng": float(d["x"]),
                    "category": d.get("category_name") or "",
                }
            )
        except (KeyError, TypeError, ValueError):
            # 좌표가 없는 항목은 장소로 쓸 수 없다. 조용히 건너뛴다.
            continue
    return places, False


def transit_minutes(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> tuple[dict | None, bool]:
    """대중교통 소요시간.

    **응답에 변동성 정보가 없다.** step 속성은 distance/guidance/stops/time/
    type/vehicles 뿐이고 `totalTime` 은 점추정치 하나다. 확률을 만들 재료가
    아니므로 여기서는 분(minute)과 요약 문자열만 돌려준다.
    checklist "BE-P0-06 결론" 참고.
    """
    qs = urllib.parse.urlencode(
        {
            "start_x": f"{start_lng}",
            "start_y": f"{start_lat}",
            "end_x": f"{end_lng}",
            "end_y": f"{end_lat}",
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    data, degraded = _get(f"{TRANSIT_URL}?{qs}")
    if degraded or not data:
        return None, True

    status = data.get("status")
    if status != "OK":
        # STARTNODES_NULL 등. 출발·도착지 근처에 정류장이 없는 경우가 대부분이다.
        logger.info("대중교통 경로 없음 status=%s", status)
        return None, True

    routes = data.get("routes") or []
    if not routes:
        return None, True

    # 가장 빠른 경로를 고른다. 카카오가 정렬을 보장하지 않는다.
    best = min(routes, key=lambda r: (r.get("properties") or {}).get("totalTime", 1 << 30))
    props = best.get("properties") or {}
    total_seconds = props.get("totalTime")
    if not total_seconds:
        return None, True

    modes = []
    for step in best.get("steps") or []:
        t = (step.get("properties") or {}).get("type")
        if t == "SUBWAY" and "지하철" not in modes:
            modes.append("지하철")
        elif t == "BUS" and "버스" not in modes:
            modes.append("버스")
        elif t == "WALKING" and "도보" not in modes:
            modes.append("도보")

    return (
        {
            "minutes": max(1, round(total_seconds / 60)),
            "distance_m": props.get("totalDistance"),
            "transfers": props.get("transfers", 0),
            "fare": (props.get("fare") or {}).get("value"),
            "mode": "+".join(modes) if modes else "대중교통",
            "alternatives": len(routes),
        },
        False,
    )


def walk_minutes(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> tuple[dict | None, bool]:
    """도보 소요시간. 응답 구조가 대중교통과 다르다 — `route` 단수 + `legs`."""
    qs = urllib.parse.urlencode(
        {
            "start_x": f"{start_lng}",
            "start_y": f"{start_lat}",
            "end_x": f"{end_lng}",
            "end_y": f"{end_lat}",
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    data, degraded = _get(f"{WALK_URL}?{qs}")
    if degraded or not data or data.get("status") != "OK":
        return None, True

    props = ((data.get("route") or {}).get("properties")) or {}
    total_seconds = props.get("totalTime")
    if not total_seconds:
        return None, True

    return (
        {
            "minutes": max(1, round(total_seconds / 60)),
            "distance_m": props.get("totalDistance"),
            "transfers": 0,
            "fare": 0,
            "mode": "도보",
            "alternatives": 1,
        },
        False,
    )


# ---------------------------------------------------------------------------
# 경로 후보
# ---------------------------------------------------------------------------
#
# 카카오 대중교통은 요청 파라미터와 무관하게 **항상 routes 15개**를 준다
# (scripts/probe_route_alternatives.py 로 확인. priority·alternatives·size 를
# 넣어도 15개다). 그런데 15개가 서로 크게 다르지 않다 — 같은 버스의 다른
# 환승 조합이 대부분이다. 전부 보여주면 고를 수 없으니 **의미가 다른 것만**
# 추려서 내린다.
#
# 추리는 기준은 사용자가 실제로 저울질하는 축이다.
#   - 가장 빠른 것
#   - 환승이 없는 것 중 가장 빠른 것   (4분 더 걸려도 환승 없는 쪽을 택하는 사람이 있다)
#   - 지하철을 쓰는 것 중 가장 빠른 것  (버스보다 정시성이 좋다)
#   - 가장 싼 것
# 여기에 도보·자전거·자동차를 각각 하나씩 붙인다.


def _vehicle_chain(route: dict) -> tuple[list[str], int]:
    """탑승 구간별 대표 노선과 대체 노선 수.

    **`step.properties.vehicles` 는 이동 순서가 아니라 같은 구간을 지나는 대체
    노선 목록이다.** 처음에는 전부 이어 붙였다가 "5516 → 6514 → N75 → 5511"
    처럼 환승 1회인데 노선이 4개인 표기가 나왔다. 구간마다 첫 노선만 대표로
    쓰고, 나머지는 개수만 센다.

    반환: `(["2호선", "5513"], 3)` — 대표 노선 순서와 대체 노선 총 개수.
    """
    chain: list[str] = []
    alternatives = 0
    for step in route.get("steps") or []:
        vehicles = (step.get("properties") or {}).get("vehicles") or []
        if not vehicles:
            continue
        first = vehicles[0]
        name = first.get("name") or first.get("busNo") or ""
        if name:
            chain.append(str(name))
            alternatives += max(0, len(vehicles) - 1)
    return chain, alternatives


def _mode_labels(route: dict) -> list[str]:
    """수단 라벨 순서. `["지하철", "도보", "버스"]` 형태."""
    labels = []
    for step in route.get("steps") or []:
        t = (step.get("properties") or {}).get("type")
        label = {"SUBWAY": "지하철", "BUS": "버스", "WALKING": "도보"}.get(t)
        if label and (not labels or labels[-1] != label):
            labels.append(label)
    return labels


def _summary_bits(minutes, distance_m, transfers, fare) -> str:
    bits = [f"{minutes}분"]
    if distance_m:
        bits.append(f"{distance_m / 1000:.1f}km")
    if transfers:
        bits.append(f"환승 {transfers}회")
    if fare:
        bits.append(f"{fare:,}원")
    return " · ".join(bits)


def _transit_candidates(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> tuple[list[dict], bool]:
    """대중교통 후보. 중복을 합치고 축별 대표만 남긴다."""
    qs = urllib.parse.urlencode(
        {
            "start_x": f"{start_lng}",
            "start_y": f"{start_lat}",
            "end_x": f"{end_lng}",
            "end_y": f"{end_lat}",
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    data, degraded = _get(f"{TRANSIT_URL}?{qs}")
    if degraded or not data or data.get("status") != "OK":
        return [], True

    # 1) 정규화 + 같은 교통수단 조합은 가장 빠른 것 하나로 합친다.
    by_chain: dict[str, dict] = {}
    for route in data.get("routes") or []:
        props = route.get("properties") or {}
        seconds = props.get("totalTime")
        if not seconds:
            continue
        chain, alt_count = _vehicle_chain(route)
        if not chain:
            continue
        chain_key = ">".join(chain)
        labels = _mode_labels(route)
        minutes = max(1, round(seconds / 60))
        detail = " → ".join(chain)
        if alt_count:
            detail += f" (대체 {alt_count}개)"
        item = {
            "key": f"transit:{chain_key}",
            "kind": "transit",
            "mode": "+".join(labels) if labels else "대중교통",
            "minutes": minutes,
            "distance_m": props.get("totalDistance"),
            "transfers": props.get("transfers") or 0,
            # 일부 후보는 요금 계산이 비어 온다. 0 으로 위조하지 않고 None 을 유지한다.
            "fare": (props.get("fare") or {}).get("value"),
            "detail": detail,
            "source": "kakao_transit",
        }
        item["summary"] = _summary_bits(
            minutes, item["distance_m"], item["transfers"], item["fare"]
        )
        prev = by_chain.get(chain_key)
        if prev is None or minutes < prev["minutes"]:
            by_chain[chain_key] = item

    pool = list(by_chain.values())
    if not pool:
        return [], True

    # 2) 축별 대표를 뽑는다. 같은 항목이 여러 축에서 뽑히면 한 번만 담는다.
    picked: list[dict] = []
    seen: set[str] = set()

    def take(item: dict | None, reason: str) -> None:
        if item is None or item["key"] in seen:
            return
        seen.add(item["key"])
        picked.append({**item, "reason": reason})

    take(min(pool, key=lambda c: c["minutes"]), "가장 빠름")

    no_transfer = [c for c in pool if c["transfers"] == 0]
    if no_transfer:
        take(min(no_transfer, key=lambda c: c["minutes"]), "환승 없음")

    subway = [c for c in pool if "지하철" in c["mode"]]
    if subway:
        take(min(subway, key=lambda c: c["minutes"]), "지하철 이용")

    priced = [c for c in pool if c["fare"]]
    if priced:
        take(min(priced, key=lambda c: (c["fare"], c["minutes"])), "가장 저렴")

    # 3) 자리가 남으면 빠른 순으로 채운다.
    for c in sorted(pool, key=lambda c: c["minutes"]):
        if len(picked) >= CANDIDATE_LIMIT - 1:  # 도보·자전거·자동차 자리를 남긴다
            break
        take(c, "")

    return picked, False


def _single_route_candidate(
    url: str,
    key: str,
    mode: str,
    start_lat: float,
    start_lng: float,
    end_lat: float,
    end_lng: float,
) -> dict | None:
    """도보·자전거처럼 `route` 단수 + `properties` 구조인 응답을 후보 하나로."""
    qs = urllib.parse.urlencode(
        {
            "start_x": f"{start_lng}",
            "start_y": f"{start_lat}",
            "end_x": f"{end_lng}",
            "end_y": f"{end_lat}",
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    data, degraded = _get(f"{url}?{qs}")
    if degraded or not data or data.get("status") != "OK":
        return None

    props = ((data.get("route") or {}).get("properties")) or {}
    seconds = props.get("totalTime")
    if not seconds:
        return None

    minutes = max(1, round(seconds / 60))
    return {
        "key": key,
        "kind": key,
        "mode": mode,
        "minutes": minutes,
        "distance_m": props.get("totalDistance"),
        "transfers": 0,
        "fare": 0,
        "detail": "",
        "summary": _summary_bits(minutes, props.get("totalDistance"), 0, None),
        "source": f"kakao_{key}",
        "reason": "",
    }


def _car_candidate(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> dict | None:
    """자동차. 모빌리티 API 는 호스트·파라미터 이름이 다르다(origin/destination).

    요금은 택시 예상액을 쓴다. 자기 차가 있는 사용자는 통행료만 보면 되지만
    이 앱의 맥락(늦었을 때의 대안)에서는 택시비가 판단 근거다.
    """
    qs = urllib.parse.urlencode(
        {
            "origin": f"{start_lng},{start_lat}",
            "destination": f"{end_lng},{end_lat}",
            "priority": "RECOMMEND",
        }
    )
    data, degraded = _get(f"{CAR_URL}?{qs}")
    if degraded or not data:
        return None

    routes = data.get("routes") or []
    if not routes:
        return None
    summary = routes[0].get("summary") or {}
    seconds = summary.get("duration")
    if not seconds:
        return None

    minutes = max(1, round(seconds / 60))
    taxi = (summary.get("fare") or {}).get("taxi")
    return {
        "key": "car",
        "kind": "car",
        "mode": "자동차",
        "minutes": minutes,
        "distance_m": summary.get("distance"),
        "transfers": 0,
        "fare": taxi,
        "detail": "택시 예상액" if taxi else "",
        "summary": _summary_bits(minutes, summary.get("distance"), 0, taxi),
        "source": "kakao_car",
        "reason": "",
    }


def route_candidates(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> tuple[list[dict], bool]:
    """사용자가 고를 만한 경로 후보 목록.

    **쿼터** — 한 번에 최대 4번 외부 호출한다(대중교통·도보·자전거·자동차).
    사용자가 일정을 추가하면서 명시적으로 요청할 때만 부른다. 알람 재계산에서는
    [resolve_route] 로 선택된 수단 하나만 조회한다.

    도보가 [WALK_CANDIDATE_MAX_MINUTES] 를 넘으면 목록에서 뺀다. 64분 걸어가는
    선택지는 아무도 고르지 않고 자리만 차지한다.
    """
    candidates: list[dict] = []

    walk = _single_route_candidate(
        WALK_URL, "walk", "도보", start_lat, start_lng, end_lat, end_lng
    )

    # 걸어서 갈 만한 거리면 대중교통을 조회하지 않는다. 쿼터를 아끼고,
    # 짧은 거리에서 대중교통은 대기·환승 때문에 오히려 느리다.
    if walk and (walk.get("distance_m") or 0) <= WALK_ONLY_METERS:
        walk["reason"] = "걸어갈 수 있는 거리"
        return [walk], False

    transit, transit_degraded = _transit_candidates(
        start_lat, start_lng, end_lat, end_lng
    )
    candidates.extend(transit)

    if walk and walk["minutes"] <= WALK_CANDIDATE_MAX_MINUTES:
        candidates.append(walk)

    bicycle = _single_route_candidate(
        BICYCLE_URL, "bicycle", "자전거", start_lat, start_lng, end_lat, end_lng
    )
    if bicycle:
        candidates.append(bicycle)

    car = _car_candidate(start_lat, start_lng, end_lat, end_lng)
    if car:
        candidates.append(car)

    if not candidates:
        return [], True

    # 빠른 순. 같으면 환승 적은 순.
    candidates.sort(key=lambda c: (c["minutes"], c["transfers"]))
    candidates = candidates[:CANDIDATE_LIMIT]

    # 이유 라벨을 전체 목록 기준으로 다시 매긴다. `_transit_candidates` 는
    # 대중교통끼리만 비교했으므로 도보·자전거·자동차가 섞이면 "가장 빠름" 이
    # 틀릴 수 있다.
    for c in candidates:
        if c["reason"] == "가장 빠름":
            c["reason"] = ""
    candidates[0]["reason"] = "가장 빠름"

    # 돈이 드는 수단 중 가장 싼 것에 표시. 도보·자전거(0원)는 제외한다 —
    # "가장 저렴" 이 늘 도보가 되면 정보가 없다.
    priced = [c for c in candidates if c["fare"]]
    if len(priced) > 1:
        cheapest = min(priced, key=lambda c: (c["fare"], c["minutes"]))
        if not cheapest["reason"]:
            cheapest["reason"] = "가장 저렴"

    return candidates, transit_degraded and not transit


def resolve_route(
    route_key: str,
    start_lat: float,
    start_lng: float,
    end_lat: float,
    end_lng: float,
) -> tuple[dict | None, bool]:
    """선택된 경로 하나를 다시 조회한다.

    알람 재계산 때 쓴다. 후보 전체를 다시 받지 않고 **해당 수단만** 조회하므로
    호출이 1회다. 클라이언트가 보낸 소요시간을 그대로 신뢰하지 않는다 —
    조작 가능하고, 배차가 바뀌면 낡은 값이 된다.

    선택한 경로가 더 이상 없으면 `(None, True)` 를 돌려준다. 호출자가
    [best_route] 로 되돌릴지 판단한다.
    """
    if route_key == "walk":
        item = _single_route_candidate(
            WALK_URL, "walk", "도보", start_lat, start_lng, end_lat, end_lng
        )
        return (item, False) if item else (None, True)

    if route_key == "bicycle":
        item = _single_route_candidate(
            BICYCLE_URL, "bicycle", "자전거", start_lat, start_lng, end_lat, end_lng
        )
        return (item, False) if item else (None, True)

    if route_key == "car":
        item = _car_candidate(start_lat, start_lng, end_lat, end_lng)
        return (item, False) if item else (None, True)

    if route_key.startswith("transit:"):
        items, degraded = _transit_candidates(start_lat, start_lng, end_lat, end_lng)
        if degraded:
            return None, True
        for item in items:
            if item["key"] == route_key:
                return item, False
        # 추려낸 목록에 없을 수 있다. 그때는 가장 빠른 대중교통으로 대체한다.
        logger.info("선택 경로 %s 가 사라졌다. 대중교통 최단으로 대체한다.", route_key)
        return (items[0], False) if items else (None, True)

    logger.warning("알 수 없는 route_key: %s", route_key)
    return None, True


def best_route(
    start_lat: float, start_lng: float, end_lat: float, end_lng: float
) -> tuple[dict | None, bool]:
    """도보와 대중교통 중 빠른 쪽을 고른다.

    사용자가 경로를 고르지 않았을 때의 기본값이다.

    짧은 거리에서는 대중교통이 대기·환승 때문에 오히려 느리다. 둘을 모두
    조회해 비교하면 정확하지만 쿼터를 두 배로 쓴다. 그래서 먼저 도보를 보고,
    도보 거리가 [WALK_ONLY_METERS] 를 넘을 때만 대중교통을 조회한다.

    반환 형태는 [route_candidates] 의 항목과 같다(`key` 포함). 선택 경로와 기본
    경로가 같은 모양이어야 저장·표시 코드가 갈라지지 않는다.
    """
    walk = _single_route_candidate(
        WALK_URL, "walk", "도보", start_lat, start_lng, end_lat, end_lng
    )

    if walk and (walk.get("distance_m") or 0) <= WALK_ONLY_METERS:
        return walk, False

    transit, transit_degraded = _transit_candidates(
        start_lat, start_lng, end_lat, end_lng
    )

    pool = list(transit)
    if walk:
        pool.append(walk)

    if not pool:
        return None, True

    return min(pool, key=lambda c: c["minutes"]), False
