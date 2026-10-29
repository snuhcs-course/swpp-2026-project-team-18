# Partly AI-generated (Claude)
"""버스 번호만 다른 대중교통 경로를 한 후보로 합치기(사용자 요청 10-29).

정류장은 이름이 **완전히 같을 때만** 같은 곳이다(사용자 10-29). 좌표·시간은 2026-10-29 카카오
실측(신림역→서울대, 신림역→강남역)에서 가져왔다. 실측에서 5516 은 "신림사거리", 5528 은
"신림사거리.신림역"(200m)에서 탔다. 이름이 다르니 따로 둔다.
"""

from unittest.mock import Mock

import pytest
from django.core.cache import cache

from apps.routing import clients, realtime

START, END = (37.48427, 126.92975), (37.459786, 126.951124)

신림사거리 = (37.48264, 126.92974)
신림사거리_신림역 = (37.48388, 126.92807)  # 신림사거리에서 약 200m
관악우체국 = (37.48450, 126.93306)
서울대학교 = (37.46671, 126.94793)
서울대학교_건너편 = (37.46694, 126.94880)  # 약 80m


def leg(kind, vehicles, board, alight, seconds, board_at, alight_at):
    """탑승 구간 하나. `vehicles` = ["5516/지선", "6514/지선"]."""
    return {
        "properties": {
            "type": kind.upper(),
            "time": seconds,
            "vehicles": [
                {"name": v.split("/")[0], "type": (v.split("/") + [""])[1]} for v in vehicles
            ],
            "stops": [{"name": board}, {"name": alight}],
        },
        "path": {"points": [[board_at[1], board_at[0]], [alight_at[1], alight_at[0]]]},
    }


def walk(seconds):
    return {"properties": {"type": "WALKING", "time": seconds}}


def route(total, *steps, fare=1500, transfers=0):
    return {
        "properties": {
            "totalTime": total,
            "totalDistance": 4000,
            "transfers": transfers,
            "fare": {"value": fare},
        },
        "steps": list(steps),
    }


# 실측 9번: 5516|6514 신림사거리 → 서울대학교, 탑승 866초, 전체 2069초.
R5516 = route(2069, leg("bus", ["5516/지선", "6514/지선"], "신림사거리", "서울대학교", 866, 신림사거리, 서울대학교))
# 5528 이 5516 과 같은 정류장에서 타고 내린다고 둔 것.
R5528 = route(2172, leg("bus", ["5528/지선"], "신림사거리", "서울대학교", 982, 신림사거리, 서울대학교_건너편))
# 실측 11번: 5528 은 실제로는 "신림사거리.신림역" 에서 탄다.
R5528_실측 = route(2172, leg("bus", ["5528/지선"], "신림사거리.신림역", "서울대학교", 982, 신림사거리_신림역, 서울대학교_건너편))
# 실측 13번: 6515|6512 관악우체국 → 서울대학교.
R6515 = route(2348, leg("bus", ["6515/지선", "6512/지선"], "관악우체국", "서울대학교", 874, 관악우체국, 서울대학교))
# 실측 1번: 2호선 → 5513. 가장 빠르고 모양이 다르다.
R2호선 = route(
    1374,
    leg("subway", ["2호선/일반"], "신림", "서울대입구(관악구청)", 210, (37.48427, 126.92975), (37.48128, 126.95271)),
    walk(181),
    leg("bus", ["5513/지선"], "서울대입구역3번출구", "자연대.행정관입구", 676, (37.48051, 126.95243), (37.46035, 126.94895)),
    fare=1550,
    transfers=1,
)


@pytest.fixture
def upstream(monkeypatch):
    data = {"status": "OK", "routes": []}
    get = Mock(return_value=(data, False))
    monkeypatch.setattr(clients, "_get", get)
    return data, get


def pool(upstream, *routes):
    upstream[0]["routes"] = list(routes)
    items, durations, degraded = clients._transit_pool(*START, *END)
    assert not degraded
    return items


def bus_leg(item, index=0):
    return [s for s in item["segments"] if s["kind"] in ("bus", "subway")][index]


def test_same_stops_with_another_bus_become_one_candidate(upstream):
    (item,) = pool(upstream, R5528, R5516)
    # 가장 빠른 5516 이 대표다. 5528 은 같은 구간의 대체 노선이 된다. 좌표는 보지 않는다.
    assert item["key"] == "transit:5516"
    assert item["member_keys"] == ["transit:5516", "transit:5528"]
    assert bus_leg(item)["alt_vehicles"] == [
        {"name": "6514", "vehicle_type": "지선"},
        {"name": "5528", "vehicle_type": "지선"},
    ]
    assert item["detail"] == "5516 (대체 2개)"
    assert (item["minutes"], item["minutes_max"]) == (34, 36)


def test_stops_with_different_names_stay_apart_even_when_close(upstream):
    # 같은 교차로 200m 거리라도 이름이 다르면 다른 정류장이다.
    keys = [item["key"] for item in pool(upstream, R5516, R5528_실측, R6515)]
    assert keys == ["transit:5516", "transit:5528", "transit:6515"]


def test_alighting_stop_must_match_too(upstream):
    other = route(2100, leg("bus", ["5528/지선"], "신림사거리", "서울대정문", 900, 신림사거리, 서울대학교))
    assert len(pool(upstream, R5516, other)) == 2


def test_legs_without_stop_names_are_not_merged(upstream):
    def nameless(bus, total):
        step = leg("bus", [f"{bus}/지선"], "", "", 866, 신림사거리, 서울대학교)
        step["properties"]["stops"] = []
        return route(total, step)

    assert len(pool(upstream, nameless("5516", 2069), nameless("5528", 2100))) == 2


def test_bus_that_rides_much_longer_stays_apart(upstream):
    detour = route(2400, leg("bus", ["750/간선"], "신림사거리", "서울대학교", 866 + 301, 신림사거리, 서울대학교))
    near = route(2300, leg("bus", ["751/간선"], "신림사거리", "서울대학교", 866 + 300, 신림사거리, 서울대학교))
    items = pool(upstream, R5516, detour, near)
    assert [item["key"] for item in items] == ["transit:5516", "transit:750"]
    assert items[0]["member_keys"] == ["transit:5516", "transit:751"]


def test_subway_needs_the_same_line(upstream):
    a = route(1500, leg("subway", ["2호선/일반"], "신림", "강남", 1080, START, (37.49809, 127.02800)))
    b = route(1600, leg("subway", ["신분당선/일반"], "신림", "강남", 1080, START, (37.49809, 127.02800)))
    assert len(pool(upstream, a, b)) == 2


def test_every_leg_must_match(upstream):
    # 실측(신림역→강남역) 6·9번: 5413 은 같고 둘째 구간 광역버스만 다르다. 9번은 내리는 곳이
    # "삼성서초역삼세무서" 라 따로다. 같은 곳에서 내리는 3200 을 하나 더 둔다.
    first = leg("bus", ["5413/지선"], "신림사거리.신림역", "서초아트자이아파트", 2050, 신림사거리_신림역, (37.48579, 127.01085))
    현대타워 = (37.49668, 127.02795)

    def second(buses, alight, seconds, at=현대타워):
        return leg("bus", buses, "서초아트자이아파트", alight, seconds, (37.48518, 127.01117), at)

    a = route(3185, first, walk(64), second(["9200/광역", "M6405/광역"], "강남역서초현대타워앞", 757), transfers=1)
    same = route(3200, first, walk(64), second(["3200/직행", "3300/직행"], "강남역서초현대타워앞", 770), transfers=1)
    b = route(3261, first, walk(64), second(["3400/직행"], "삼성서초역삼세무서", 784, (37.49844, 127.02973)), transfers=1)
    items = pool(upstream, a, same, b)
    assert [item["key"] for item in items] == ["transit:5413>9200", "transit:5413>3400"]
    assert [v["name"] for v in bus_leg(items[0], 0)["alt_vehicles"]] == []
    assert [v["name"] for v in bus_leg(items[0], 1)["alt_vehicles"]] == ["M6405", "3200", "3300"]


def test_same_chain_from_the_same_stops_adds_its_buses(upstream):
    # 노선 조합이 같은 경로는 전처럼 빠른 것 하나만 남기되, 카카오가 거기에만 준 대체 버스는 더한다.
    a = route(2069, leg("bus", ["5516/지선"], "신림사거리", "서울대학교", 866, 신림사거리, 서울대학교))
    b = route(2100, leg("bus", ["5516/지선", "N75/간선"], "신림사거리", "서울대학교", 880, 신림사거리, 서울대학교))
    (item,) = pool(upstream, a, b)
    assert item["member_keys"] == ["transit:5516"]
    assert bus_leg(item)["alt_vehicles"] == [{"name": "N75", "vehicle_type": "간선"}]


def test_merged_route_takes_one_slot(upstream):
    upstream[0]["routes"] = [R5516, R5528, R2호선]
    items, degraded = clients._transit_candidates(*START, *END)
    assert not degraded
    # 5528 은 5516 카드 안에 들어가서 따로 칸을 차지하지 않는다.
    assert sorted(item["key"] for item in items) == ["transit:2호선>5513", "transit:5516"]


def test_fill_counts_merged_buses_as_already_shown(upstream):
    # X 카드에 Y 가 합쳐져 있다. Y 를 다시 타는 경로보다 아예 새 노선(P, R)이 먼저다.
    x = route(2000, leg("bus", ["X/지선"], "신림사거리", "서울대학교", 866, 신림사거리, 서울대학교))
    y = route(2050, leg("bus", ["Y/지선"], "신림사거리", "서울대학교", 880, 신림사거리, 서울대학교))
    서원역 = (37.47788, 126.93271)
    again = route(
        2100,
        leg("bus", ["Y/지선"], "관악우체국", "서원역", 400, 관악우체국, 서원역),
        leg("bus", ["Q/지선"], "서원역", "서울대학교", 500, 서원역, 서울대학교),
        transfers=1,
    )
    fresh = route(
        2200,
        leg("bus", ["P/지선"], "봉천로", "난곡로", 400, (37.4890, 126.9300), (37.4760, 126.9400)),
        leg("bus", ["R/지선"], "난곡로", "서울대학교", 500, (37.4760, 126.9400), 서울대학교),
        transfers=1,
    )
    upstream[0]["routes"] = [x, y, again, fresh]
    items, _ = clients._transit_candidates(*START, *END)
    assert [item["key"] for item in items] == ["transit:X", "transit:P>R", "transit:Y>Q"]


def test_route_chosen_as_another_bus_resolves_to_the_merged_route(upstream):
    upstream[0]["routes"] = [R5516, R5528, R2호선]
    item, degraded = clients.resolve_route("transit:5528", *START, *END)
    assert not degraded
    # 고른 key 를 그대로 돌려준다. 계산은 합친 후보(가장 빠른 5516) 기준이다.
    assert item["key"] == "transit:5528"
    assert item["minutes"] == 34
    assert item["alternative"]["key"] == "transit:2호선>5513"
    assert item["alternative"]["faster_minutes"] == 34 - 23


def test_route_chosen_as_the_fastest_merged_route_has_no_alternative(upstream):
    upstream[0]["routes"] = [R5516, R5528]
    item, _ = clients.resolve_route("transit:5528", *START, *END)
    assert item["key"] == "transit:5528"
    assert "alternative" not in item


# --- 실시간 -------------------------------------------------------------------


@pytest.fixture
def no_cache():
    cache.clear()
    yield
    cache.clear()


def seoul_bus_leg(**updates):
    value = {
        "kind": "bus",
        "region": "metro_seoul",
        "vehicle": "5516",
        "vehicle_type": "지선",
        "stops": ["신림사거리", "서울대학교"],
        "boarding_lat": 신림사거리[0],
        "boarding_lng": 신림사거리[1],
        "alt_vehicles": [
            {"name": "6514", "vehicle_type": "지선"},
            {"name": "5528", "vehicle_type": "지선"},
        ],
    }
    value.update(updates)
    return value


def test_alternative_buses_get_arrivals_from_the_same_stop(monkeypatch, no_cache):
    calls = []

    def rows(ars):
        calls.append(ars)
        return [
            {"busRouteAbrv": "5516", "traTime1": "300", "traTime2": "900"},
            {"busRouteAbrv": "6514", "traTime1": "120", "traTime2": "0", "arrmsg2": "운행종료"},
            {"busRouteAbrv": "5528", "traTime1": "60"},
        ]

    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21123")
    monkeypatch.setattr(realtime, "_bus_station_rows", rows)
    candidates = [{"segments": [seoul_bus_leg()]}]

    realtime.enrich_candidates(candidates)

    segment = candidates[0]["segments"][0]
    assert [a["seconds"] for a in segment["arrivals"]] == [300, 900]
    six, other = segment["alt_vehicles"]
    assert [a["seconds"] for a in six["arrivals"]] == [120]
    assert [a["seconds"] for a in other["arrivals"]] == [60]
    # 대체 버스는 같은 정류장 응답에서 읽는다. 호출은 한 번이다.
    assert calls == ["21123"]


def test_alternatives_still_get_arrivals_when_the_main_bus_is_missing(monkeypatch, no_cache):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21123")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [
            {"busRouteAbrv": "6514", "traTime1": "120"},
            {"busRouteAbrv": "5528", "traTime1": "60"},
        ],
    )
    result = realtime.bus_arrivals(seoul_bus_leg())
    assert "arrivals" not in result
    assert set(result["alt_arrivals"]) == {"6514", "5528"}
    assert [a["seconds"] for a in result["alt_arrivals"]["6514"]] == [120]


def test_candidates_sharing_a_stop_collect_every_alternative(monkeypatch, no_cache):
    seen = []

    def fake(segment):
        seen.append([v["name"] for v in segment["alt_vehicles"]])
        return {"alt_arrivals": {"6514": [{"seconds": 120}], "643": [{"seconds": 30}]}}

    monkeypatch.setattr(realtime, "bus_arrivals", fake)
    a = seoul_bus_leg()
    b = seoul_bus_leg(alt_vehicles=[{"name": "643", "vehicle_type": "간선"}])
    candidates = [{"segments": [a]}, {"segments": [b]}]

    realtime.enrich_candidates(candidates)

    assert seen == [["6514", "5528", "643"]]
    assert a["alt_vehicles"][0]["arrivals"] == [{"seconds": 120}]
    assert b["alt_vehicles"][0]["arrivals"] == [{"seconds": 30}]
