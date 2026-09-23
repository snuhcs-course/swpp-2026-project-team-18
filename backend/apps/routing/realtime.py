"""서울 버스·지하철 실시간 도착정보.

카카오 경로 API 는 경로와 정류장 순서를 주지만 **차가 언제 오는지는 주지
않는다.** 응답의 step 속성은 distance, guidance, stops, time, type, vehicles
뿐이다. 그래서 경로 후보를 만든 뒤 이 모듈이 승차 지점마다 다음 차와 그다음
차를 덧붙인다.

두 데이터원은 모양이 다르다.

- 지하철: 서울 열린데이터광장. 역 이름으로 바로 조회하며 ``barvlDt`` 가 초다.
- 버스: 서울시 버스정보시스템. 좌표로 ARS 정류소 번호를 먼저 찾고,
  ``exps1``/``exps2`` 에서 초 단위 도착예정을 받는다.

## 실패 정책

실시간 정보는 보조 정보다. 키가 없거나, 발급 직후 전파되지 않았거나, 외부
서버가 느려도 **경로 후보 자체는 반드시 보여야 한다.** 모든 공개 함수는 실패를
밖으로 던지지 않고 빈 결과를 돌려준다. 빈 결과면 앱은 체크포인트만 그리고
도착정보 두 줄을 숨긴다.

## 호출량

최종으로 보여 줄 후보만 보강한다. 같은 역·정류소가 여러 후보에 나오면 키로
묶어 한 번만 호출하고, 지하철 20초·버스 15초 캐시를 쓴다. 실시간 숫자를 오래
캐시하면 거짓말이 되고, 캐시가 없으면 경로 화면을 다시 열 때마다 일일 쿼터를
먹는다.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import logging
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from django.conf import settings
from django.core.cache import cache

logger = logging.getLogger(__name__)

SUBWAY_BASE_URL = "http://swopenapi.seoul.go.kr/api/subway"

# 버스는 data.go.kr 이 **서비스 단위**로 승인한다. 아래 두 호출
# (`stationinfo/getStationByPos`, `stationinfo/getStationByUid`)은 모두
# **정류소정보조회 서비스**에 속한다. 그 서비스를 활용신청하지 않으면 키가
# 정상이고 승인이 났어도 401 `등록되지 않은 서비스키` 가 돌아온다 — 메시지가
# "키가 없다" 처럼 읽혀 전파 지연과 구분되지 않는다. 실제로 그렇게 몇 시간을
# 썼다. 엔드포인트별 판별은 jit-tools/probe_bus_services.py 가 한다.
#
# `버스도착정보조회` 만으로는 대체가 안 된다. `arrive/getArrInfoByRouteAll` 이
# 필요한 필드를 다 주지만 입력이 `busRouteId` 이고, 노선명에서 그 값을 얻는
# `busRouteInfo/getBusRouteList` 는 또 다른 서비스(노선정보조회)다.
BUS_BASE_URL = "http://ws.bus.go.kr/api/rest"
REQUEST_TIMEOUT_SECONDS = 4
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
SUBWAY_CACHE_SECONDS = 20
BUS_CACHE_SECONDS = 15
STATION_CACHE_SECONDS = 24 * 60 * 60
NEGATIVE_CACHE_SECONDS = 60

# 서울 열린데이터광장 subwayId. subwayNm 이 정상적으로 오면 이름을 먼저
# 비교하지만, 이름이 비는 응답에서도 노선을 섞지 않기 위한 안전망이다.
SUBWAY_IDS = {
    "1호선": "1001",
    "2호선": "1002",
    "3호선": "1003",
    "4호선": "1004",
    "5호선": "1005",
    "6호선": "1006",
    "7호선": "1007",
    "8호선": "1008",
    "9호선": "1009",
    "경의중앙선": "1063",
    "공항철도": "1065",
    "경춘선": "1067",
    "수인분당선": "1075",
    "신분당선": "1077",
    "경강선": "1081",
    "우이신설선": "1092",
    "서해선": "1093",
    "신림선": "1094",
}


def format_arrival_seconds(seconds: int) -> str:
    """초를 ``3분 20초 뒤 도착`` 으로 바꾼다.

    0초는 숫자로 쓰면 이미 지나간 것처럼 보이므로 ``곧 도착`` 이다.
    """
    seconds = max(0, int(seconds))
    if seconds == 0:
        return "곧 도착"
    minutes, remain = divmod(seconds, 60)
    if minutes and remain:
        return f"{minutes}분 {remain}초 뒤 도착"
    if minutes:
        return f"{minutes}분 뒤 도착"
    return f"{remain}초 뒤 도착"


def _safe_int(value) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _safe_float(value, default: float = float("inf")) -> float:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return default


def _token(value: str | None) -> str:
    """이름 비교용. 띄어쓰기·괄호·하이픈 차이를 없앤다."""
    return re.sub(r"[^0-9A-Za-z가-힣]", "", (value or "").casefold())


def _station_token(value: str | None) -> str:
    """역 이름 비교용. ``서울대입구(관악구청)역`` → ``서울대입구``."""
    base = (value or "").split("(", 1)[0].strip()
    if base.endswith("역") and len(base) > 1:
        base = base[:-1]
    return _token(base)


def _line_token(value: str | None) -> str:
    value = value or ""
    for prefix in ("수도권전철", "수도권", "서울지하철", "서울도시철도", "서울"):
        value = value.replace(prefix, "")
    return _token(value)


def _fetch_bytes(url: str) -> bytes:
    """작은 외부 응답 하나. 키가 들어 있는 URL 은 절대 로그에 남기지 않는다."""
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json, application/xml, text/xml"},
    )
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError("실시간 도착정보 응답이 2MiB 를 넘었다")
    return body


# ---------------------------------------------------------------------------
# 지하철
# ---------------------------------------------------------------------------


def _subway_rows(station_name: str) -> list[dict] | None:
    key = getattr(settings, "SEOUL_SUBWAY_API_KEY", "").strip()
    if not key:
        return None

    station = (station_name or "").split("(", 1)[0].strip().removesuffix("역")
    if not station:
        return None

    cache_key = f"jit:rt:subway:{_station_token(station)}"
    cached = cache.get(cache_key)
    if cached is not None:
        return cached or None

    # 공식 엔드포인트가 HTTP 만 응답한다. HTTPS 는 현재 타임아웃이 난다.
    url = (
        f"{SUBWAY_BASE_URL}/{urllib.parse.quote(key, safe='')}"
        f"/json/realtimeStationArrival/0/20/{urllib.parse.quote(station, safe='')}"
    )
    try:
        data = json.loads(_fetch_bytes(url).decode("utf-8"))
        status = str((data.get("errorMessage") or {}).get("status", ""))
        rows = data.get("realtimeArrivalList") or []
        if status not in ("", "200") or not isinstance(rows, list):
            logger.info("지하철 실시간 응답 오류: status=%s", status or "unknown")
            cache.set(cache_key, [], NEGATIVE_CACHE_SECONDS)
            return None
        cache.set(cache_key, rows, SUBWAY_CACHE_SECONDS)
        return rows
    except Exception as exc:  # 외부 보조 API 는 경로 조회를 깨뜨리면 안 된다.
        logger.info("지하철 실시간 조회 실패: %s", type(exc).__name__)
        cache.set(cache_key, [], NEGATIVE_CACHE_SECONDS)
        return None


def _line_matches(row: dict, line_name: str) -> bool:
    expected = _line_token(line_name)
    actual = _line_token(row.get("subwayNm"))
    if expected and actual:
        return expected == actual

    expected_id = SUBWAY_IDS.get(expected)
    row_id = str(row.get("subwayId") or "")
    return bool(expected_id and row_id == expected_id)


def _direction_matches(row: dict, next_station: str) -> bool:
    """다음 역이 방면 문자열에 있는가.

    한 역 응답에는 양방향이 같이 온다. 방향을 못 맞추면 **아무것도 안 보여
    주는 쪽**을 택한다. 반대 방향 열차 시각은 정보가 없는 것보다 해롭다.
    """
    expected = _station_token(next_station)
    direction = _station_token(row.get("trainLineNm"))
    return bool(expected and direction and expected in direction)


def _seconds_from_message(message: str | None) -> int | None:
    """숫자 ETA 가 비었을 때 사람용 문구에서 초를 복구한다."""
    text = message or ""
    both = re.search(r"(\d+)\s*분\s*(\d+)\s*초\s*(?:후|뒤)", text)
    if both:
        return int(both.group(1)) * 60 + int(both.group(2))
    minutes = re.search(r"(\d+)\s*분\s*(?:후|뒤)", text)
    if minutes:
        return int(minutes.group(1)) * 60
    seconds = re.search(r"(\d+)\s*초\s*(?:후|뒤)", text)
    if seconds:
        return int(seconds.group(1))
    if "곧 도착" in text:
        return 0
    return None


def subway_arrivals(segment: dict) -> dict:
    """지하철 구간의 다음·그다음 열차.

    ``stops[0]`` 은 승차역, ``stops[1]`` 은 진행 방향을 확인할 다음 역이다.
    노선과 방향을 모두 맞춘 뒤 초가 있는 열차만 가까운 순으로 두 대 고른다.
    """
    if segment.get("region") != "metro_seoul":
        return {}
    stops = segment.get("stops") or []
    if len(stops) < 2:
        return {}

    rows = _subway_rows(stops[0])
    if not rows:
        return {}

    matched: list[dict] = []
    seen_trains: set[str] = set()
    for row in rows:
        if not _line_matches(row, str(segment.get("vehicle") or "")):
            continue
        if not _direction_matches(row, str(stops[1])):
            continue
        seconds = _safe_int(row.get("barvlDt"))
        if seconds is None or seconds <= 0:
            seconds = _seconds_from_message(row.get("arvlMsg2"))
        if seconds is None:
            continue
        train_id = str(row.get("btrainNo") or row.get("ordkey") or "")
        if train_id and train_id in seen_trains:
            continue
        if train_id:
            seen_trains.add(train_id)
        matched.append(
            {
                "seconds": seconds,
                "message": format_arrival_seconds(seconds),
                "source": "seoul_subway",
            }
        )

    matched.sort(key=lambda item: item["seconds"])
    return {"arrivals": matched[:2]} if matched else {}


# ---------------------------------------------------------------------------
# 버스
# ---------------------------------------------------------------------------


def _bus_keys() -> list[str]:
    """URL 에 바로 붙일 키. Encoding → Decoding 순서, 중복 제거."""
    values: list[str] = []
    encoded = getattr(settings, "SEOUL_BUS_API_KEY_ENCODING", "").strip()
    decoded = getattr(settings, "SEOUL_BUS_API_KEY_DECODING", "").strip()
    if encoded:
        values.append(encoded)
    if decoded:
        quoted = urllib.parse.quote(decoded, safe="")
        if quoted not in values:
            values.append(quoted)
    return values


def _bus_xml(path: str, params: dict) -> ET.Element | None:
    keys = _bus_keys()
    if not keys:
        return None

    query = urllib.parse.urlencode(params)
    for key in keys:
        # Encoding 키는 이미 URL 인코딩된 값이라 urlencoder 에 다시 넣지 않는다.
        url = f"{BUS_BASE_URL}/{path}?serviceKey={key}&{query}"
        try:
            root = ET.fromstring(_fetch_bytes(url).decode("utf-8", errors="replace"))
            code = _xml_first(root, "headerCd")
            if code not in (None, "", "0"):
                logger.info("버스 실시간 응답 오류: code=%s", code)
                continue
            return root
        except urllib.error.HTTPError as exc:
            # 발급 직후 서울시 서버에 전파되기 전에는 401 이다. 두 키 표현을
            # 모두 시험한 뒤 조용히 빈 결과로 돌아간다.
            logger.info("버스 실시간 HTTP 오류: %s", exc.code)
        except Exception as exc:
            logger.info("버스 실시간 조회 실패: %s", type(exc).__name__)
    return None


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _xml_first(root: ET.Element, name: str) -> str | None:
    for node in root.iter():
        if _local_name(node.tag) == name:
            return (node.text or "").strip()
    return None


def _xml_items(root: ET.Element) -> list[dict[str, str]]:
    """서울 버스 XML 의 반복 ``itemList`` 를 평평한 dict 로."""
    out: list[dict[str, str]] = []
    for node in root.iter():
        if _local_name(node.tag) not in ("itemList", "item"):
            continue
        nested_items = [c for c in node if _local_name(c.tag) == "item"]
        targets = nested_items or [node]
        for target in targets:
            item = {
                _local_name(child.tag): (child.text or "").strip()
                for child in target
                if len(child) == 0
            }
            if item and item not in out:
                out.append(item)
    return out


def _bus_station_ars(stop_name: str, lat: float, lng: float) -> str | None:
    """승차점 좌표·이름으로 서울 버스 ARS 번호를 찾는다."""
    stop_token = _token(stop_name)
    if not stop_token:
        return None
    cache_key = f"jit:rt:bus-station:{stop_token}:{lat:.4f}:{lng:.4f}"
    cached = cache.get(cache_key)
    if cached is not None:
        return str(cached) or None

    root = _bus_xml(
        "stationinfo/getStationByPos",
        {"tmX": f"{lng:.7f}", "tmY": f"{lat:.7f}", "radius": 200},
    )
    if root is None:
        cache.set(cache_key, "", NEGATIVE_CACHE_SECONDS)
        return None

    candidates = []
    for item in _xml_items(root):
        ars_id = (item.get("arsId") or "").strip()
        name = (item.get("stNm") or "").strip()
        actual = _token(name)
        if not ars_id or ars_id == "0" or not actual:
            continue
        if actual == stop_token:
            name_score = 0
        elif actual in stop_token or stop_token in actual:
            name_score = 1
        else:
            continue  # 200m 안의 다른 정류소를 고르면 반대편 버스가 나온다.
        candidates.append((name_score, _safe_float(item.get("dist")), ars_id))

    if not candidates:
        cache.set(cache_key, "", NEGATIVE_CACHE_SECONDS)
        return None
    ars_id = min(candidates)[2]
    cache.set(cache_key, ars_id, STATION_CACHE_SECONDS)
    return ars_id


def _bus_station_rows(ars_id: str) -> list[dict] | None:
    cache_key = f"jit:rt:bus-arrivals:{ars_id}"
    cached = cache.get(cache_key)
    if cached is not None:
        return cached or None

    root = _bus_xml("stationinfo/getStationByUid", {"arsId": ars_id})
    if root is None:
        cache.set(cache_key, [], NEGATIVE_CACHE_SECONDS)
        return None
    rows = _xml_items(root)
    cache.set(cache_key, rows, BUS_CACHE_SECONDS if rows else NEGATIVE_CACHE_SECONDS)
    return rows or None


def _bus_route_matches(item: dict, route_name: str) -> bool:
    expected = _token(route_name)
    names = (_token(item.get("busRouteAbrv")), _token(item.get("rtNm")))
    return bool(expected and expected in names)


def _bus_crowding(item: dict, suffix: str) -> str | None:
    # brerde_Div=4 일 때 brdrde_Num 이 혼잡도다: 3 여유 / 4 보통 / 5 혼잡.
    if str(item.get(f"brerde_Div{suffix}") or "") != "4":
        return None
    return {"3": "여유", "4": "보통", "5": "혼잡"}.get(
        str(item.get(f"brdrde_Num{suffix}") or "")
    )


def bus_arrivals(segment: dict) -> dict:
    """버스 구간의 다음·그다음 버스와 배차간격."""
    if segment.get("region") != "metro_seoul":
        return {}
    lat = _safe_float(segment.get("boarding_lat"), default=float("nan"))
    lng = _safe_float(segment.get("boarding_lng"), default=float("nan"))
    if lat != lat or lng != lng:  # NaN
        return {}
    stops = segment.get("stops") or []
    stop_name = str(stops[0]) if stops else ""
    ars_id = _bus_station_ars(stop_name, lat, lng)
    if not ars_id:
        return {}
    rows = _bus_station_rows(ars_id)
    if not rows:
        return {}

    route_name = str(segment.get("vehicle") or "")
    item = next((row for row in rows if _bus_route_matches(row, route_name)), None)
    if item is None:
        return {}

    arrivals = []
    for suffix in ("1", "2"):
        seconds = _safe_int(item.get(f"exps{suffix}"))
        if seconds is None or seconds < 0:
            seconds = _seconds_from_message(item.get(f"arrmsg{suffix}"))
        if seconds is None:
            continue
        arrivals.append(
            {
                "seconds": seconds,
                "message": format_arrival_seconds(seconds),
                "source": "seoul_bus",
                "crowding": _bus_crowding(item, suffix),
            }
        )

    result: dict = {}
    if arrivals:
        result["arrivals"] = arrivals[:2]
    headway = _safe_int(item.get("term"))
    if headway is not None and headway > 0:
        result["headway_minutes"] = headway
    return result


# ---------------------------------------------------------------------------
# 후보 보강
# ---------------------------------------------------------------------------


def _segment_key(segment: dict) -> tuple | None:
    kind = segment.get("kind")
    stops = segment.get("stops") or []
    if kind == "subway" and len(stops) >= 2 and segment.get("region") == "metro_seoul":
        return ("subway", segment.get("region"), _station_token(stops[0]), _line_token(segment.get("vehicle")), _station_token(stops[1]))
    if kind == "bus" and stops and segment.get("region") == "metro_seoul":
        return (
            "bus",
            segment.get("region"),
            _token(stops[0]),
            _token(segment.get("vehicle")),
            round(_safe_float(segment.get("boarding_lat"), 0.0), 4),
            round(_safe_float(segment.get("boarding_lng"), 0.0), 4),
        )
    return None


def enrich_candidates(candidates: list[dict]) -> None:
    """최종 경로 후보의 승차 구간에 실시간 정보를 제자리에서 붙인다.

    같은 역·노선·방향 조합은 한 번만 조회한다. 외부 실패는 로그만 남고 경로
    후보는 그대로 유지된다.
    """
    groups: dict[tuple, list[dict]] = {}
    for candidate in candidates:
        for segment in candidate.get("segments") or []:
            key = _segment_key(segment)
            if key is not None:
                groups.setdefault(key, []).append(segment)
    if not groups:
        return

    def load(sample: dict) -> dict:
        if sample.get("kind") == "subway":
            return subway_arrivals(sample)
        if sample.get("kind") == "bus":
            return bus_arrivals(sample)
        return {}

    workers = min(4, len(groups))
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="jit-realtime") as pool:
        future_groups = {
            pool.submit(load, segments[0]): segments for segments in groups.values()
        }
        for future in as_completed(future_groups):
            segments = future_groups[future]
            try:
                realtime = future.result()
            except Exception as exc:  # 방어선. provider 도 실패를 삼키지만 유지한다.
                logger.info("실시간 구간 보강 실패: %s", type(exc).__name__)
                continue
            if not realtime:
                continue
            for segment in segments:
                # list/dict 는 다음 단계에서 변경하지 않지만 후보 간 공유를 피한다.
                if "arrivals" in realtime:
                    segment["arrivals"] = [dict(item) for item in realtime["arrivals"]]
                if "headway_minutes" in realtime:
                    segment["headway_minutes"] = realtime["headway_minutes"]
