# Partly AI-generated (Claude)
"""대중교통 후보를 다양하게 고르기(사용자 요청 10-29).

후보 칸은 5개다. 기준별 대표(최단·환승·도보·지하철만·버스만·섞음·요금)를 먼저 담고, 남는
칸은 이미 고른 경로에 없는 노선이 많은 것부터, 너무 느린 것은 빼고 채운다. 고른 경로는
화면 목록에서 빠져도 카카오가 아직 주면 알람 계산에 그대로 쓴다.
"""

from unittest.mock import Mock

import pytest

from apps.routing import clients

START, END = (37.48, 126.93), (37.50, 126.95)

KIND = {"bus": "BUS", "subway": "SUBWAY"}


def route(legs, seconds=1200, fare=1500, transfers=0, walk=0):
    """`legs` = [("bus", "5511"), ("subway", "2호선")]. `walk` 초만큼 환승 도보를 붙인다."""
    ride = (seconds - 120 - walk) // len(legs)
    steps = []
    for i, (kind, name) in enumerate(legs):
        step = {
            "properties": {
                "type": KIND[kind],
                "time": ride,
                "vehicles": [{"name": name, "type": "지선" if kind == "bus" else ""}],
            }
        }
        if i == 0:
            # 첫 승차점이 출발지라 앞 도보는 0 이다.
            step["path"] = {"points": [[START[1], START[0]], [END[1], END[0]]]}
        steps.append(step)
    if walk:
        steps.append({"properties": {"type": "WALKING", "time": walk}})
    return {
        "properties": {
            "totalTime": seconds,
            "totalDistance": 3000,
            "transfers": transfers,
            "fare": {"value": fare},
        },
        "steps": steps,
    }


@pytest.fixture
def upstream(monkeypatch):
    data = {"status": "OK", "routes": []}
    get = Mock(return_value=(data, False))
    monkeypatch.setattr(clients, "_get", get)
    return data, get


def pick(upstream, routes) -> list[str]:
    upstream[0]["routes"] = routes
    items, degraded = clients._transit_candidates(*START, *END)
    assert not degraded
    return [item["key"].removeprefix("transit:") for item in items]


def test_five_slots_hold_one_route_per_axis(upstream):
    routes = [
        route([("bus", "M"), ("subway", "2호선")], 1200, 1550, 1, walk=600),  # 최단(섞음 몫도)
        route([("bus", "D")], 1700, 1500, 0, walk=600),
        route([("subway", "S")], 1500, 1650, 0, walk=60),  # 환승 최소(동률 중 빠른)·도보·지하철만
        route([("bus", "C"), ("bus", "C2")], 1650, 1400, 1, walk=600),  # 버스만·요금 최소
        route([("bus", "F1"), ("subway", "F2")], 1210, 1550, 1, walk=600),
    ]
    keys = pick(upstream, routes)
    # 남는 두 칸은 새 노선이 둘인 F1>F2, 그다음 D 다.
    assert keys == ["M>2호선", "S", "C>C2", "F1>F2", "D"]
    upstream[1].assert_called_once()


def test_axis_order_is_fastest_transfers_walk_subway_bus_mix_fare(upstream):
    routes = [
        route([("subway", "A"), ("bus", "a")], 1200, 1550, 2, walk=600),  # 최단(섞음)
        route([("bus", "B")], 2000, 1550, 0, walk=600),  # 환승 최소
        route([("subway", "W"), ("subway", "w")], 1900, 1550, 1, walk=30),  # 도보 최소
        route([("subway", "S")], 1300, 1550, 1, walk=600),  # 지하철만 최단
        route([("bus", "U"), ("bus", "u")], 1400, 1550, 1, walk=600),  # 버스만 최단
        route([("bus", "X"), ("subway", "x")], 1250, 1550, 2, walk=600),  # 섞음 2등
        route([("bus", "P"), ("subway", "p")], 1800, 1000, 2, walk=600),  # 요금 최소
    ]
    # 다섯 칸이 다 차면 뒤 기준(섞음 2등은 원래 몫이 없고, 요금)은 못 들어간다.
    assert pick(upstream, routes) == ["A>a", "B", "W>w", "S", "U>u"]


def test_axis_representatives_are_not_cut_for_being_slow(upstream):
    # 2배 걸려도 환승 없는 직행 버스는 고르는 사람이 있다.
    routes = [
        route([("subway", "2호선"), ("bus", "5511")], 1200, 1550, 1),
        route([("bus", "직행")], 2400, 1500, 0),
    ]
    assert "직행" in pick(upstream, routes)


def test_fill_skips_routes_that_are_much_slower(upstream):
    fast = route([("bus", "A")], 1200)
    # 기준 대표가 모두 A 라 남는 칸 4개를 채운다. 1.5배(1800초) 이상은 넣지 않는다.
    routes = [fast, route([("bus", "B")], 1790), route([("bus", "C")], 1800)]
    assert pick(upstream, routes) == ["A", "B"]


def test_fill_also_skips_routes_twenty_minutes_slower(upstream):
    # 1시간 경로면 1.5배는 90분이지만, 20분(80분) 이상 더 걸리면 뺀다.
    routes = [
        route([("bus", "A")], 3600),
        route([("bus", "B")], 3600 + 1199),
        route([("bus", "C")], 3600 + 1200),
    ]
    assert pick(upstream, routes) == ["A", "B"]


def test_fill_prefers_lines_not_yet_shown_over_speed(upstream):
    # 빠른 순으로 채우면 X 를 탄 경로의 뒤 노선만 바꾼 것들이 칸을 다 차지한다.
    routes = [
        route([("bus", "X"), ("bus", "Y")], 1200),
        route([("bus", "X"), ("bus", "Z")], 1210),
        route([("bus", "X"), ("bus", "V")], 1220),
        route([("bus", "X"), ("bus", "U")], 1230),
        route([("bus", "X"), ("bus", "T")], 1240),
        route([("bus", "P"), ("bus", "Q")], 1500),
    ]
    keys = pick(upstream, routes)
    assert keys[:2] == ["X>Y", "P>Q"]
    assert "X>T" not in keys
    assert len(keys) == clients.CANDIDATE_LIMITS["transit"] == 5


def test_walking_badge_goes_to_the_least_walking_route(upstream):
    upstream[0]["routes"] = [
        route([("bus", "A")], 1200, walk=600),
        route([("bus", "B")], 1500, walk=120),
    ]
    items, _ = clients._transit_candidates(*START, *END)
    badges = {item["key"]: item["badges"] for item in items}
    assert "최소 도보" not in badges["transit:A"]
    assert "최소 도보" in badges["transit:B"]


def test_badges_are_capped_at_three(upstream):
    upstream[0]["routes"] = [route([("bus", "A")], 1200, 1000, 0), route([("bus", "B")], 1500)]
    items, _ = clients._transit_candidates(*START, *END)
    assert items[0]["badges"] == ["최단 시간", "최소 비용", "최소 환승"]
    assert items[0]["reason"] == "최단 시간"


def test_chosen_route_outside_the_shown_list_is_still_used(upstream):
    # 화면 목록에는 없는(남는 칸에서 느려서 빠진) 경로도, 카카오가 주면 그대로 계산한다.
    upstream[0]["routes"] = [route([("bus", "A")], 1200), route([("bus", "SLOW")], 2000)]
    shown, _ = clients._transit_candidates(*START, *END)
    assert "transit:SLOW" not in {item["key"] for item in shown}

    item, degraded = clients.resolve_route("transit:SLOW", *START, *END)
    assert not degraded
    assert item["key"] == "transit:SLOW"
    assert item["minutes"] == 33
    assert item["alternative"]["key"] == "transit:A"
    assert item["alternative"]["faster_minutes"] == 13


def test_chosen_route_gone_from_kakao_falls_back_to_fastest(upstream):
    upstream[0]["routes"] = [route([("bus", "A")], 1200), route([("bus", "B")], 1300)]
    item, degraded = clients.resolve_route("transit:GONE", *START, *END)
    assert not degraded
    assert item["key"] == "transit:A"
    assert "alternative" not in item


def test_best_route_still_takes_the_fastest_transit(upstream, monkeypatch):
    monkeypatch.setattr(clients, "_single_route_candidate", lambda *a, **k: None)
    upstream[0]["routes"] = [route([("bus", "B")], 1300), route([("bus", "A")], 1200)]
    item, degraded = clients.best_route(*START, *END)
    assert not degraded
    assert item["key"] == "transit:A"
