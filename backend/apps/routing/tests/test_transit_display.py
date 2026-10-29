"""BE-9/10: badge ranking, alternative vehicles and the app's wire contract."""

from unittest.mock import Mock

import pytest
from rest_framework.test import APIClient

from apps.routing import clients

START, END = (37.48, 126.93), (37.50, 126.95)


def route(name, seconds=1200, fare=1500, transfers=0, alternatives=(), distance=3000, walk=0):
    """버스 한 구간짜리 경로. `walk` 초만큼 환승 도보를 뒤에 붙인다(좌표가 없어 앞뒤 도보는 0)."""
    steps = [
        {
            "properties": {
                "type": "BUS",
                "time": seconds - 120 - walk,
                "vehicles": [{"name": name, "type": "지선"}, *alternatives],
                "stops": [{"name": "승차 정류장"}, {"name": "하차 정류장"}],
            },
            "path": {"points": [[START[1], START[0]], [END[1], END[0]]]},
        }
    ]
    if walk:
        steps.append({"properties": {"type": "WALKING", "time": walk}})
    return {
        "properties": {
            "totalTime": seconds,
            "totalDistance": distance,
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
    # [route] 는 모두 같은 정류장에서 타고 내려서 그대로면 한 후보로 합쳐진다. 이 파일은 서로 다른
    # 경로 사이의 배지·표시만 본다. 합치기는 test_transit_merge.py 가 본다.
    monkeypatch.setattr(clients, "_same_shape", lambda a, b: False)
    return data, get


def candidates(upstream, routes):
    upstream[0]["routes"] = routes
    items, degraded = clients._transit_candidates(*START, *END)
    assert not degraded
    return {item["key"].removeprefix("transit:"): item for item in items}


def test_one_candidate_can_satisfy_all_three_criteria(upstream):
    # 네 기준을 모두 만족해도 배지는 카드 한 줄에 들어가는 3개까지다.
    items = candidates(upstream, [route("5511"), route("501", 1500, 1800, 1, walk=300)])
    assert items["5511"]["badges"] == ["최단 시간", "최소 비용", "최소 환승"]
    assert items["5511"]["reason"] == "최단 시간"
    assert items["501"]["badges"] == []
    assert items["501"]["reason"] == ""
    upstream[1].assert_called_once()


def test_same_winner_does_not_give_cheapest_badge_to_runner_up(upstream):
    items = candidates(
        upstream, [route("A", 1200, 1000, 1, walk=300), route("B", 1800, 2000, 0)]
    )
    assert items["A"]["badges"] == ["최단 시간", "최소 비용"]
    assert items["B"]["badges"] == ["최소 환승", "최소 도보"]


def test_fewest_transfers_works_without_a_direct_route(upstream):
    items = candidates(upstream, [route("A", 1200, 2000, 2), route("B", 1800, 2000, 1)])
    assert "최소 환승" not in items["A"]["badges"]
    assert "최소 환승" in items["B"]["badges"]


def test_tied_candidates_each_get_the_satisfied_badges(upstream):
    items = candidates(upstream, [route("A"), route("B")])
    assert (
        items["A"]["badges"]
        == items["B"]["badges"]
        == ["최단 시간", "최소 비용", "최소 환승"]
    )


def test_fastest_uses_seconds_before_rounding_to_display_minutes(upstream):
    items = candidates(upstream, [route("A", 1201), route("B", 1200)])
    assert items["A"]["minutes"] == items["B"]["minutes"] == 20
    assert "최단 시간" not in items["A"]["badges"]
    assert "최단 시간" in items["B"]["badges"]


def test_same_chain_keeps_shortest_actual_time(upstream):
    items = candidates(
        upstream, [route("A", 1201, distance=4000), route("A", 1200, distance=3000)]
    )
    assert len(items) == 1
    assert items["A"]["distance_m"] == 3000


def test_unknown_fare_is_not_zero_or_the_cheapest(upstream):
    items = candidates(upstream, [route("A", fare=None), route("B", 1800, 1500)])
    assert items["A"]["fare"] is None
    assert "최소 비용" not in items["A"]["badges"]
    assert "최소 비용" in items["B"]["badges"]


def test_known_zero_fare_is_a_valid_cheapest_candidate(upstream):
    items = candidates(upstream, [route("A", fare=0), route("B", 1500, 1500)])
    assert "최소 비용" in items["A"]["badges"]
    assert "최소 비용" not in items["B"]["badges"]


def test_all_unknown_fares_have_no_cost_badge(upstream):
    items = candidates(upstream, [route("A", fare=None), route("B", 1800, None)])
    assert all("최소 비용" not in item["badges"] for item in items.values())


def test_failure_keeps_the_existing_degraded_contract(upstream):
    upstream[1].return_value = (None, True)
    assert clients._transit_candidates(*START, *END) == ([], True)


def test_representatives_still_include_each_best_axis(upstream):
    routes = [route(f"fast{i}", 1200 + i * 60, 3000, 2) for i in range(8)]
    routes += [route("fewest", 2400, 2500, 0), route("cheapest", 3000, 1000, 1)]
    items = candidates(upstream, routes)
    assert len(items) <= clients.CANDIDATE_LIMITS["transit"]
    assert {"fast0", "fewest", "cheapest"} <= items.keys()


def test_alternative_bus_names_types_order_and_count(upstream):
    raw = route(
        "5511",
        alternatives=[
            {"name": "5513", "type": "지선"},
            {"name": "643", "type": "간선"},
        ],
    )
    item = candidates(upstream, [raw])["5511"]
    bus = next(segment for segment in item["segments"] if segment["kind"] == "bus")
    assert bus["alt_vehicles"] == [
        {"name": "5513", "vehicle_type": "지선"},
        {"name": "643", "vehicle_type": "간선"},
    ]
    assert bus["vehicle"] == "5511"
    assert bus["stops"] == ["승차 정류장", "하차 정류장"]
    assert "(대체 2개)" in item["detail"]
    assert sum(segment["seconds"] for segment in item["segments"]) == 1200


def test_alternatives_remove_primary_duplicates_blanks_and_invalid_rows(upstream):
    alternatives = [
        {"name": "5511"},
        {"name": " 5513 ", "type": " 지선 "},
        {"name": "5513", "type": "다른 종류"},
        {"name": " "},
        {},
        None,
        {"busNo": "643", "type": "간선"},
    ]
    item = candidates(upstream, [route("5511", alternatives=alternatives)])["5511"]
    bus = next(segment for segment in item["segments"] if segment["kind"] == "bus")
    assert bus["alt_vehicles"] == [
        {"name": "5513", "vehicle_type": "지선"},
        {"name": "643", "vehicle_type": "간선"},
    ]
    assert "(대체 2개)" in item["detail"]


@pytest.mark.parametrize("vehicles", [None, [], [{"name": "5511", "type": "지선"}]])
def test_missing_alternatives_are_an_empty_array(vehicles):
    raw = route("5511")
    raw["steps"][0]["properties"]["vehicles"] = vehicles
    bus = next(
        segment
        for segment in clients._segments(raw, 1200, *START, *END)
        if segment["kind"] == "bus"
    )
    assert bus["alt_vehicles"] == []


def test_missing_vehicle_type_stays_empty(upstream):
    item = candidates(upstream, [route("A", alternatives=[{"busNo": "B"}])])["A"]
    bus = next(segment for segment in item["segments"] if segment["kind"] == "bus")
    assert bus["alt_vehicles"] == [{"name": "B", "vehicle_type": ""}]


def test_alternatives_are_scoped_to_each_leg():
    raw = route("A", alternatives=[{"name": "B", "type": "지선"}])
    raw["steps"] += route("C", alternatives=[{"name": "D", "type": "간선"}])["steps"]
    segments = [
        s for s in clients._segments(raw, 2400, *START, *END) if s["kind"] == "bus"
    ]
    assert [s["alt_vehicles"][0]["name"] for s in segments] == ["B", "D"]
    assert clients._vehicle_chain(raw) == ["A", "C"]


def test_alternative_count_includes_leg_with_missing_representative(upstream):
    raw = route("A", alternatives=[{"name": "B"}])
    second = route("ignored", alternatives=[{"name": "C"}])["steps"][0]
    second["properties"]["vehicles"][0] = None
    raw["steps"].append(second)
    raw["properties"]["totalTime"] = 2400
    item = candidates(upstream, [raw])["A"]
    alternatives = [
        vehicle["name"]
        for segment in item["segments"]
        for vehicle in segment.get("alt_vehicles", [])
    ]
    assert alternatives == ["B", "C"]
    assert "(대체 2개)" in item["detail"]


@pytest.mark.parametrize(
    "kind,seconds", [("BUS", 0), ("BUS", -1), ("UNKNOWN", 120), ("WALKING", 120)]
)
def test_alternative_count_only_includes_visible_transit_segments(
    upstream, kind, seconds
):
    raw = route("A", alternatives=[{"name": "B"}])
    hidden = route("ignored", alternatives=[{"name": "C"}])["steps"][0]
    hidden["properties"].update(type=kind, time=seconds)
    raw["steps"].append(hidden)
    item = next(iter(candidates(upstream, [raw]).values()))
    alternatives = [
        vehicle["name"]
        for segment in item["segments"]
        for vehicle in segment.get("alt_vehicles", [])
    ]
    assert alternatives == ["B"]
    assert "(대체 1개)" in item["detail"]


def test_walk_segments_do_not_acquire_vehicle_fields():
    raw = {"steps": [{"properties": {"type": "WALKING", "time": 300}}]}
    segments = clients._segments(raw, 300, *START, *END)
    assert all("alt_vehicles" not in segment for segment in segments)


@pytest.mark.integration
@pytest.mark.django_db
def test_candidates_api_keeps_transit_badges_when_car_is_faster(
    monkeypatch, django_user_model
):
    data = {
        "status": "OK",
        "routes": [
            route("5511", alternatives=[{"name": "643", "type": "간선"}]),
            route("501", 1800, 1800, 1),
        ],
    }
    calls = []

    def get(url):
        calls.append(url)
        if url.startswith(clients.TRANSIT_URL):
            return data, False
        if url.startswith(clients.CAR_URL):
            return {
                "routes": [
                    {
                        "summary": {
                            "duration": 600,
                            "distance": 3000,
                            "fare": {"taxi": 7000},
                        }
                    }
                ]
            }, False
        return {
            "status": "OK",
            "route": {"properties": {"totalTime": 2400, "totalDistance": 2500}},
        }, False

    monkeypatch.setattr(clients, "_get", get)
    monkeypatch.setattr("apps.routing.realtime.enrich_candidates", lambda items: None)
    user = django_user_model.objects.create_user(
        email="display@example.com", password="Test12345"
    )
    user.profile.home_lat, user.profile.home_lng = START
    user.profile.save()
    api = APIClient()
    api.force_authenticate(user)
    response = api.get(
        "/api/routes/candidates", {"dest_lat": END[0], "dest_lng": END[1]}
    )
    assert response.status_code == 200
    item = next(c for c in response.data["results"] if c["key"] == "transit:5511")
    assert item["badges"] == ["최단 시간", "최소 비용", "최소 환승"]
    assert item["reason"] == "최단 시간"
    bus = next(segment for segment in item["segments"] if segment["kind"] == "bus")
    assert bus["alt_vehicles"] == [{"name": "643", "vehicle_type": "간선"}]
    assert len(calls) == 4, "표시값 생성은 추가 외부 호출을 만들지 않는다"
