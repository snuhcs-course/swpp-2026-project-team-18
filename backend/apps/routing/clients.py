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
COORD_TO_ADDRESS_URL = "https://dapi.kakao.com/v2/local/geo/coord2address.json"
TRANSIT_URL = "https://dapi.kakao.com/v2/routing/publictraffic"
WALK_URL = "https://dapi.kakao.com/v2/routing/walk"
BICYCLE_URL = "https://dapi.kakao.com/v2/routing/bicycle"
CAR_URL = "https://apis-navi.kakaomobility.com/v1/directions"

# 도보만으로 갈 만한 거리 기준. 이보다 짧으면 대중교통이 오히려 느리다.
WALK_ONLY_METERS = 1200

# --- 장소 검색 한계 (실측: jit-tools/probe_local2.py) -----------------------
#
# 카카오 로컬 키워드 검색은 한 번에 15건, 45페이지까지만 받는다. 그보다 크게
# 보내면 400 이다. 그런데 `pageable_count` 는 항상 45 로 와서, 실제로 받아 볼
# 수 있는 것은 **45건**이다(3페이지에서 is_end=true).
#
# `total_count` 는 이와 다르다 — "카페" 는 142,759 가 온다. 화면에 그 숫자를
# 쓰면 45건에서 끝나는 목록 옆에 14만이 적혀 거짓말이 된다.
MAX_PAGE_SIZE = 15
MAX_PAGE = 45

SORT_ACCURACY = "accuracy"
SORT_DISTANCE = "distance"

# --- 정적 지도 (실측: jit-tools/calibrate_staticmap.py) ---------------------
STATIC_MAP_URL = "https://dapi.kakao.com/v2/maps/staticmap"

# 정적 지도 요청 한계.
STATIC_MAP_MAX_W = 2048
STATIC_MAP_MAX_H = 1024
STATIC_MAP_MAX_MARKERS = 5
STATIC_MAP_MIN_LEVEL = 1
STATIC_MAP_MAX_LEVEL = 15

# 줌 레벨 하나가 담는 거리(요청 size 한 단위당 미터).
#
# **응답에 축척이 없어서 직접 재야 했다.** 같은 이미지에 마커 두 개를 알려진
# 위도 차로 찍고 픽셀 간격을 재는 방식으로 측정했다(두 이미지를 비교하는
# 방법은 안 된다 — 팔레트 PNG 라서 마커가 하나 늘면 지도 전체 색이 흔들린다).
#
# 측정값: lv1~4 = 1.00, lv7 = 8.03, lv10 = 64.15. lv4 아래는 더 확대되지 않고,
# lv4 부터는 한 레벨이 정확히 두 배다.
#
# `scale` 은 해상도만 바꾸고 범위는 건드리지 않는다(실측 확인). 그래서 이 값은
# **요청 size 기준**이다.
STATIC_MAP_BASE_LEVEL = 4
STATIC_MAP_BASE_METERS_PER_UNIT = 1.0


def meters_per_unit(level: int) -> float:
    """줌 레벨 하나가 요청 size 한 단위에 담는 거리(m)."""
    step = max(level, STATIC_MAP_BASE_LEVEL) - STATIC_MAP_BASE_LEVEL
    return STATIC_MAP_BASE_METERS_PER_UNIT * (2.0**step)

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


def short_category(document: dict) -> str:
    """업종 한 마디. 화면의 칩에 넣는다.

    `category_group_name` 을 먼저 쓴다. 다만 **이 값은 자주 빈다** — 카카오가
    그룹 코드를 부여한 업종(카페·편의점·지하철역 등)만 채워지고, PC방처럼
    그룹이 없는 업종은 빈 문자열이다(실측 확인).

    비면 `category_name` 의 마지막 조각을 쓴다. 이 값은 항상 있다.
    "가정,생활 > 여가시설 > 게임방,PC방" → "게임방,PC방"

    전체 경로를 그대로 칩에 넣지 않는 이유는 길이다. 한 줄에 이름과 함께
    들어가야 하므로 마지막 조각이 가장 쓸모 있다.
    """
    group = (document.get("category_group_name") or "").strip()
    if group:
        return group
    full = (document.get("category_name") or "").strip()
    if not full:
        return ""
    return full.split(">")[-1].strip()


def _place_item(d: dict) -> dict | None:
    """카카오 문서 하나를 앱이 쓰는 모양으로 바꾼다. 좌표가 없으면 None."""
    try:
        lat, lng = float(d["y"]), float(d["x"])
    except (KeyError, TypeError, ValueError):
        # 좌표가 없는 항목은 장소로 쓸 수 없다.
        return None

    # 거리는 x/y 를 함께 보냈을 때만 온다. 없으면 빈 문자열이다.
    raw_distance = (d.get("distance") or "").strip()
    try:
        distance_m = int(raw_distance) if raw_distance else None
    except ValueError:
        distance_m = None

    return {
        "kakao_place_id": d.get("id"),
        "name": d.get("place_name") or "",
        # 도로명이 없는 지역이 있어 지번으로 폴백한다.
        "address": d.get("road_address_name") or d.get("address_name") or "",
        # 지번 주소도 따로 내려 준다. 도로명만으로 못 찾는 곳이 있다.
        "jibun_address": d.get("address_name") or "",
        "lat": lat,
        "lng": lng,
        "category": d.get("category_name") or "",
        "category_group": short_category(d),
        "distance_m": distance_m,
        "phone": d.get("phone") or "",
        # 카카오맵 장소 페이지. **평점·사진·영업시간이 있는 유일한 곳이다.**
        # 로컬 API 응답에는 그 값들이 없어서(실측: 필드 12개에 없음) 화면에
        # 별을 그릴 수 없다. 지어내지 않고 이 링크로 보낸다.
        "place_url": d.get("place_url") or "",
    }


def search_places(
    query: str,
    size: int = 15,
    page: int = 1,
    lat: float | None = None,
    lng: float | None = None,
    sort: str = SORT_ACCURACY,
    rect: str | None = None,
) -> tuple[dict, bool]:
    """장소 검색. 일정 추가·집 주소·출발지 선택이 모두 이 경로를 쓴다.

    **[lat]·[lng] 를 주면 결과에 거리가 붙는다.** 카카오는 기준 좌표를 함께
    받았을 때만 `distance` 를 채운다. 사용자가 "여기서 얼마나 먼가" 를 볼 수
    있어야 어느 장소인지 고를 수 있으므로 가능하면 항상 보낸다.

    [rect] 는 지도 영역 재검색이다. `minLng,minLat,maxLng,maxLat` 순서이고,
    **순서를 틀리면 카카오가 에러 없이 0건을 준다.** 그래서 여기서 검사한다.

    반환은 항목 목록과 페이징 상태를 함께 담은 dict 다. 앱이 "더 보기" 를
    그릴지 판단하려면 [is_end] 가 필요하다.
    """
    empty = {
        "results": [],
        "page": 1,
        "total_count": 0,
        "reachable_count": 0,
        "is_end": True,
        "sort": SORT_ACCURACY,
    }
    if not query.strip():
        return empty, False

    size = max(1, min(int(size), MAX_PAGE_SIZE))
    page = max(1, min(int(page), MAX_PAGE))

    params: dict[str, object] = {"query": query.strip(), "size": size, "page": page}

    has_origin = lat is not None and lng is not None
    if has_origin:
        # 카카오는 경도를 x, 위도를 y 로 받는다. 바꿔 보내면 결과가 엉뚱해진다.
        params["x"] = f"{lng}"
        params["y"] = f"{lat}"

    # 거리순 정렬은 기준 좌표가 필수다. 없이 보내면 400 이 온다(실측).
    # 요청을 거부하지 않고 정확도순으로 내린다 — 위치 권한이 없는 사용자에게
    # 검색 자체를 막을 이유가 없다. 대신 무엇이 적용됐는지 응답에 적는다.
    applied_sort = SORT_ACCURACY
    if sort == SORT_DISTANCE and has_origin:
        params["sort"] = SORT_DISTANCE
        applied_sort = SORT_DISTANCE

    if rect:
        valid = _valid_rect(rect)
        if valid is None:
            logger.warning("rect 형식이 올바르지 않아 무시한다: %r", rect)
        else:
            params["rect"] = valid

    data, degraded = _get(f"{LOCAL_SEARCH_URL}?{urllib.parse.urlencode(params)}")
    if degraded or not data:
        return empty, True

    places = [item for d in data.get("documents", []) if (item := _place_item(d))]
    meta = data.get("meta") or {}

    return (
        {
            "results": places,
            "page": page,
            # 카카오가 말하는 전체 건수. "카페" 는 14만이 나온다.
            "total_count": int(meta.get("total_count") or 0),
            # **실제로 받아 볼 수 있는 건수.** total_count 와 다르다 — 카카오는
            # 45건까지만 페이지로 내려 준다. 화면에 total_count 를 그대로 쓰면
            # 45건에서 멈추는 목록 옆에 14만이 적혀 거짓말이 된다.
            "reachable_count": int(meta.get("pageable_count") or 0),
            "is_end": bool(meta.get("is_end", True)),
            "sort": applied_sort,
        },
        False,
    )


def _valid_rect(rect: str) -> str | None:
    """`minLng,minLat,maxLng,maxLat` 인지 확인하고 정규화한다.

    **순서가 틀리면 카카오는 에러 대신 0건을 준다.** 위경도를 뒤바꿔 보내면
    "검색 결과가 없다" 로 보여서 원인을 찾기 어렵다. 그래서 범위로 걸러낸다.
    """
    parts = [p.strip() for p in rect.split(",")]
    if len(parts) != 4:
        return None
    try:
        min_lng, min_lat, max_lng, max_lat = (float(p) for p in parts)
    except ValueError:
        return None

    if not (-180 <= min_lng <= 180 and -180 <= max_lng <= 180):
        return None
    if not (-90 <= min_lat <= 90 and -90 <= max_lat <= 90):
        return None
    if min_lng >= max_lng or min_lat >= max_lat:
        return None

    return f"{min_lng},{min_lat},{max_lng},{max_lat}"


def static_map(
    lat: float,
    lng: float,
    level: int,
    width: int,
    height: int,
    markers: list[tuple[float, float]] | None = None,
    scale: int = 2,
) -> tuple[bytes | None, str, bool]:
    """정적 지도 이미지. (바이트, content-type, degraded)

    **앱에 지도 SDK 를 넣지 않는 이유.** 카카오지도 안드로이드 SDK 는 네이티브
    앱 키를 APK 에 넣고 서명 키 해시를 등록해야 한다. 이 REST API 는 서버가
    가진 키로 같은 지도를 그려 주므로 그 절차가 전부 사라진다. 다른 카카오
    호출과 같은 규정(back-spec.md 5.3)이기도 하다.

    마커는 **다섯 개까지**다. 그리고 `markers` 파라미터를 **반복해서** 보내야
    한다 — 한 값 안에서 `&` 로 이어 붙이면 쿼리 구분자로 먹혀 첫 마커만
    그려진다(실측). `|` 로 잇는 것은 400 이다.

    응답에 카카오 CI 로고가 박히고 제거할 수 없다. 위치만 고를 수 있다.
    """
    width = max(1, min(int(width), STATIC_MAP_MAX_W))
    height = max(1, min(int(height), STATIC_MAP_MAX_H))
    level = max(STATIC_MAP_MIN_LEVEL, min(int(level), STATIC_MAP_MAX_LEVEL))
    scale = 2 if int(scale) != 1 else 1

    parts = [
        f"size={width}x{height}",
        f"scale={scale}",
        f"lv={level}",
        "format=png",
        # 로고는 지울 수 없다. 하단 시트가 가리지 않는 쪽에 둔다.
        "logo_pos=BOTTOM_LEFT",
        f"center={lng},{lat}",
    ]
    for m_lat, m_lng in (markers or [])[:STATIC_MAP_MAX_MARKERS]:
        parts.append(f"markers=location:{m_lng},{m_lat}")

    key = settings.KAKAO_REST_API_KEY
    if not key:
        logger.warning("KAKAO_REST_API_KEY 가 비어 있다. 정적 지도를 건너뛴다.")
        return None, "", True

    req = urllib.request.Request(f"{STATIC_MAP_URL}?{'&'.join(parts)}")
    req.add_header("Authorization", f"KakaoAK {key}")
    try:
        with urllib.request.urlopen(
            req, timeout=TIMEOUT_SECONDS * 2, context=ssl.create_default_context()
        ) as resp:
            content_type = resp.headers.get("Content-Type", "image/png")
            if not content_type.startswith("image/"):
                logger.warning("정적 지도가 이미지가 아니다: %s", content_type)
                return None, "", True
            return resp.read(), content_type, False
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:200]
        logger.warning("정적 지도 실패 status=%s body=%s", e.code, body)
        return None, "", True
    except Exception as e:
        logger.warning("정적 지도 예외 %s: %s", type(e).__name__, e)
        return None, "", True


def coord_to_address(lat: float, lng: float) -> tuple[dict | None, bool]:
    """좌표 → 주소. 경로 선택 화면의 출발지 기본값(현재 위치)에 쓴다.

    GPS 는 좌표만 준다. 화면에 `37.4808, 126.9526` 을 띄우면 사용자는 그게
    어디인지 알 수 없으므로 사람이 읽는 주소로 바꾼다.

    카카오는 도로명(`road_address`)과 지번(`address`) 을 함께 준다. 도로명이
    없는 지역이 있어서 지번으로 폴백한다. 반환 모양은 [search_places] 항목과
    같게 맞춘다 — 앱이 같은 `PlaceSearchItem` 으로 받아 출발지 선택 UI를
    재사용한다. `kakao_place_id` 는 없다(장소가 아니라 좌표라서).
    """
    qs = urllib.parse.urlencode({"x": f"{lng}", "y": f"{lat}"})
    data, degraded = _get(f"{COORD_TO_ADDRESS_URL}?{qs}")
    if degraded or not data:
        return None, True

    docs = data.get("documents") or []
    if not docs:
        # 바다나 국외 좌표면 문서가 빈다. 실패가 아니라 "주소 없음" 이다.
        return None, False

    doc = docs[0]
    road = doc.get("road_address") or {}
    jibun = doc.get("address") or {}

    address = road.get("address_name") or jibun.get("address_name") or ""
    # 이름은 건물명 > 도로명 주소 > 지번 주소 순으로 고른다.
    name = road.get("building_name") or address or "현재 위치"

    return (
        {
            "kakao_place_id": None,
            "name": name,
            "address": address,
            "lat": lat,
            "lng": lng,
            "category": "",
        },
        False,
    )


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


# 도보 속도. 4.5km/h = 75m/분.
#
# 앞뒤 도보 시간을 직접 주지 않으므로 거리에서 환산한다. 카카오 도보 API 를
# 다시 부르면 정확하지만 후보마다 두 번씩, 후보가 6개면 12번을 더 불러야 해서
# 하루 1,000건 쿼터가 후보 조회 80번에 마른다.
WALK_METERS_PER_MINUTE = 75.0

# ---------------------------------------------------------------------------
# 지역 판단
# ---------------------------------------------------------------------------
#
# **왜 필요한가.** 카카오는 노선 이름만 준다 — 서울 2호선이
# `{"name": "2호선", "type": "일반"}` 이고 **부산 1호선도 `{"name": "1호선"}`** 이다
# (scripts 밖의 jit-tools/probe_subway.py 로 2026-09-23 실측). 이름만으로는
# 두 도시의 1호선을 구분할 수 없어서, 그대로 두면 앱이 부산 1호선(주황)을
# 서울 1호선(파랑)으로 칠한다.
#
# 응답에 지역 필드가 없으므로 **좌표로 판단한다.** 도시철도는 몇 개 광역권에만
# 있으니 권역 상자로 충분하다. 상자를 서로 겹치지 않게 잡았고, 어느 상자에도
# 들지 않으면 [REGION_UNKNOWN] 이다 — 그때 앱은 노선색을 쓰지 않고 중립색으로
# 그린다. **틀린 색으로 확신을 주는 것보다 중립이 낫다.**
#
# 상자는 (위도 최소, 위도 최대, 경도 최소, 경도 최대).
REGION_UNKNOWN = "unknown"

_REGION_BOXES = (
    # 수도권 전철. 천안·아산(36.8)에서 연천(38.1), 인천(126.4)에서 춘천(127.8)까지
    # 뻗어 있어 상자가 크다.
    ("metro_seoul", 36.70, 38.30, 126.00, 127.90),
    # 부산·김해·양산.
    ("metro_busan", 34.90, 35.45, 128.70, 129.40),
    # 대구.
    ("metro_daegu", 35.60, 36.10, 128.30, 128.80),
    # 대전.
    ("metro_daejeon", 36.20, 36.50, 127.20, 127.60),
    # 광주.
    ("metro_gwangju", 35.05, 35.30, 126.60, 127.00),
)


def region_for(lat: float, lng: float) -> str:
    """좌표가 속한 도시철도 권역. 모르면 [REGION_UNKNOWN].

    상자 밖이면 지어내지 않는다. 앱이 중립색으로 그린다.
    """
    for name, lat_min, lat_max, lng_min, lng_max in _REGION_BOXES:
        if lat_min <= lat <= lat_max and lng_min <= lng <= lng_max:
            return name
    return REGION_UNKNOWN


def _step_endpoints(step: dict) -> tuple[tuple[float, float] | None, tuple[float, float] | None]:
    """step 의 시작·끝 좌표 `(lat, lng)`.

    `path` 는 `{"points": [[lng, lat], ...]}` 다. **리스트가 아니라 dict** 이고
    좌표 순서가 경도-위도라 그대로 쓰면 지구 반대편이 된다.
    """
    points = ((step.get("path") or {}).get("points")) or []
    if len(points) < 1:
        return None, None

    def to_latlng(p):
        if isinstance(p, (list, tuple)) and len(p) >= 2:
            return (float(p[1]), float(p[0]))
        if isinstance(p, dict) and "x" in p and "y" in p:
            return (float(p["y"]), float(p["x"]))
        return None

    return to_latlng(points[0]), to_latlng(points[-1])


def _segments(
    route: dict,
    total_seconds: int,
    start_lat: float,
    start_lng: float,
    end_lat: float,
    end_lng: float,
) -> list[dict]:
    """이동을 눈에 보이는 구간으로 쪼갠다. 앱이 가로 막대로 그린다.

    ## 왜 steps 를 그대로 쓸 수 없나

    `steps` 에는 **탑승 구간과 환승 도보만** 들어 있다. 집에서 첫 정류장까지,
    마지막 정류장에서 목적지까지의 도보가 빠져 있어서 `steps` 의 시간 합이
    `totalTime` 보다 작다 — 실측으로 25분 경로에서 9분 30초가 비었다.

    그 차이는 **앞 도보 + 차를 기다린 시간 + 뒤 도보**가 섞인 값이고 API 가
    쪼개 주지 않는다. 그래서 이렇게 한다.

      1. `path.points` 의 첫 점·끝 점으로 앞뒤 도보 **거리**를 구한다.
      2. 거리를 도보 속도로 나눠 앞뒤 도보 시간을 잡는다.
      3. 남은 시간은 **대기**다. 첫 탑승 구간 바로 앞에 붙인다 — 정류장에서
         기다리는 것이 맞고, 지어낸 위치가 아니다.

    합은 항상 `totalTime` 과 같게 맞춘다. 막대의 총 길이가 제목의 소요시간과
    다르면 사용자가 둘 중 어느 것을 믿어야 할지 알 수 없다.

    ## 반환 모양

        [{"kind": "walk"|"bus"|"subway"|"wait", "seconds": 240,
          "label": "도보", "vehicle": "5511", "vehicle_type": "지선"}]

    `kind` 는 색을 고르는 열쇠이고 `vehicle_type` 은 버스 색을 가른다
    (지선 녹색, 간선 파란색 …). 색은 **앱이 정한다** — 서버가 hex 를 내리면
    다크 모드를 바꿀 때마다 서버를 배포해야 한다.
    """
    steps = route.get("steps") or []
    if not steps:
        return []

    # 출발 좌표로 권역을 정한다. 도시철도 경로가 권역을 넘는 경우는 없다 —
    # 수도권과 부산을 지하철로 잇는 노선이 없다.
    region = region_for(start_lat, start_lng)

    body: list[dict] = []
    for step in steps:
        props = step.get("properties") or {}
        seconds = props.get("time") or 0
        if seconds <= 0:
            continue
        kind = {"BUS": "bus", "SUBWAY": "subway", "WALKING": "walk"}.get(props.get("type"))
        if kind is None:
            continue

        seg = {"kind": kind, "seconds": int(seconds), "label": ""}

        # 실시간 버스 도착정보는 ARS 정류소 번호가 필요하다. 카카오는 번호를
        # 주지 않지만 path.points[0] 이 승차점 좌표라, 이 좌표로 200m 안의
        # 서울 정류소를 찾을 수 있다. 지하철은 역 이름으로 바로 조회되지만
        # 같은 모양을 유지하려고 좌표도 함께 둔다.
        boarding, alighting = _step_endpoints(step)
        if boarding is not None:
            seg["boarding_lat"], seg["boarding_lng"] = boarding
        if alighting is not None:
            seg["alighting_lat"], seg["alighting_lng"] = alighting

        # 정류장·역 이름을 순서대로 담는다. 앱이 이걸로 체크포인트 목록을 그린다.
        # 첫 항목이 승차 지점, 마지막이 하차 지점이다. 중간은 지나치는 곳이라
        # 개수만 쓴다(전부 그리면 카드가 화면을 넘는다).
        stops = [
            str((st or {}).get("name") or "").strip()
            for st in (props.get("stops") or [])
        ]
        seg["stops"] = [s for s in stops if s]

        if kind == "walk":
            seg["label"] = "도보"
        else:
            vehicles = props.get("vehicles") or []
            first = vehicles[0] if vehicles else {}
            name = str(first.get("name") or first.get("busNo") or "").strip()
            seg["vehicle"] = name
            # 지하철은 "2호선" 이 곧 색이고, 버스는 "지선" 같은 종류가 색이다.
            seg["vehicle_type"] = str(first.get("type") or "").strip()
            seg["label"] = name or ("지하철" if kind == "subway" else "버스")
            # "2호선 (신림 > 강남)" 형태. 사람이 읽는 한 줄이라 그대로 넘긴다.
            guidance = props.get("guidance")
            if guidance:
                seg["guidance"] = str(guidance).strip()
            # **노선 색을 고르는 데 필요하다.** 이름만으로는 서울 1호선과
            # 부산 1호선을 구분할 수 없다. 근거는 [region_for] 주석에 있다.
            seg["region"] = region
        body.append(seg)

    if not body:
        return []

    inner = sum(s["seconds"] for s in body)
    leftover = max(0, int(total_seconds) - inner)

    # 앞뒤 도보 거리 → 시간. 좌표가 없으면 0 이 되고 전부 대기로 남는다.
    first_point, _ = _step_endpoints(steps[0])
    _, last_point = _step_endpoints(steps[-1])

    def walk_seconds(a: tuple[float, float] | None, b: tuple[float, float] | None) -> int:
        if a is None or b is None:
            return 0
        meters = _haversine_meters(a[0], a[1], b[0], b[1])
        return int(round(meters / WALK_METERS_PER_MINUTE * 60))

    access = walk_seconds((start_lat, start_lng), first_point)
    egress = walk_seconds(last_point, (end_lat, end_lng))

    # 환산값이 남은 시간보다 크면 비율로 줄인다. 합이 총시간을 넘으면 막대가
    # 제목과 어긋난다.
    if access + egress > leftover and (access + egress) > 0:
        scale = leftover / (access + egress)
        access = int(access * scale)
        egress = leftover - access
    wait = leftover - access - egress

    out: list[dict] = []
    if access > 0:
        out.append({"kind": "walk", "seconds": access, "label": "도보"})
    if wait > 0:
        out.append({"kind": "wait", "seconds": wait, "label": "대기"})
    out.extend(body)
    if egress > 0:
        out.append({"kind": "walk", "seconds": egress, "label": "도보"})

    return out


def _haversine_meters(a_lat: float, a_lng: float, b_lat: float, b_lng: float) -> float:
    """두 점 사이 거리(m).

    앱의 `TripGeofence.distanceMeters` 와 같은 식이다. 수 백 m 범위의 도보
    거리에 쓰므로 정밀도는 충분하다.
    """
    import math

    radius = 6_371_008.8
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    d_lat = p2 - p1
    d_lng = math.radians(b_lng - a_lng)
    h = math.sin(d_lat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(d_lng / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(h)))


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
            # 앱이 가로 막대로 그린다. 합은 totalTime 과 같다.
            "segments": _segments(
                route, seconds, start_lat, start_lng, end_lat, end_lng
            ),
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
        # 처음부터 끝까지 한 수단이다. 구간이 하나여도 내려 준다 — 앱이
        # "구간이 있는 후보" 와 "없는 후보" 를 따로 그리지 않아도 되게.
        "segments": [{"kind": key, "seconds": int(seconds), "label": mode}],
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
        "segments": [{"kind": "car", "seconds": int(seconds), "label": "자동차"}],
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

    # 실시간 정보는 경로 자체와 수명이 다르다. 최종 목록을 고른 뒤에만 붙여야
    # 카카오가 준 15개 후보 전부에 버스·지하철 API 를 호출하지 않는다.
    # 키가 없거나 외부 API 가 실패해도 함수가 후보를 그대로 둔다.
    from apps.routing.realtime import enrich_candidates

    enrich_candidates(candidates)
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
