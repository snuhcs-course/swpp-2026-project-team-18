"""BE-13: walking estimates share the existing candidate summary display."""

from unittest.mock import Mock

import pytest

from apps.routing import clients

START, END = (37.48, 126.93), (37.50, 126.95)


@pytest.fixture
def upstream(monkeypatch):
    props = {"totalTime": 960, "totalDistance": 1199}
    get = Mock(return_value=({"status": "OK", "route": {"properties": props}}, False))
    monkeypatch.setattr(clients, "_get", get)
    return props, get


def test_walk_estimates_are_in_candidate_fields_and_summary(upstream):
    items, degraded = clients.route_candidates(*START, *END)

    assert not degraded
    assert len(items) == 1
    assert items[0]["steps"] == 1599
    assert items[0]["kcal"] == 60
    assert items[0]["summary"] == "16분 · 1.2km · 1,599걸음 · 60kcal (거리 기반 추정)"
    upstream[1].assert_called_once()


@pytest.mark.parametrize("distance", [None, 0, -100, float("nan"), float("inf"), "1200", True])
def test_unusable_walk_distance_has_null_estimates(upstream, distance):
    if distance is None:
        upstream[0].pop("totalDistance")
    else:
        upstream[0]["totalDistance"] = distance

    items, degraded = clients.route_candidates(*START, *END)

    assert not degraded
    assert items[0]["distance_m"] is None
    assert items[0]["steps"] is None
    assert items[0]["kcal"] is None
    assert items[0]["summary"] == "16분"
    upstream[1].assert_called_once()


def test_bicycle_candidate_has_no_walking_estimates(upstream):
    item = clients._single_route_candidate(
        clients.BICYCLE_URL, "bicycle", "자전거", *START, *END
    )

    assert "steps" not in item
    assert "kcal" not in item
    assert item["summary"] == "16분 · 1.2km"
    upstream[1].assert_called_once()


# ---------------------------------------------------------------------------
# 자전거도로 비율 추정(BE-14) — Partly AI-generated (Claude)
# ---------------------------------------------------------------------------


def bicycle_response(steps, total=2000):
    return {
        "status": "OK",
        "route": {
            "properties": {"totalTime": 600, "totalDistance": total},
            "legs": [{"steps": [
                {"properties": {"distance": d, "time": 60, "guidance": g}} for d, g in steps
            ]}],
        },
    }


def bicycle_item(monkeypatch, data):
    monkeypatch.setattr(clients, "_get", Mock(return_value=(data, False)))
    return clients._single_route_candidate(clients.BICYCLE_URL, "bicycle", "자전거", *START, *END)


def test_bike_road_percent_sums_bike_road_steps(monkeypatch):
    item = bicycle_item(monkeypatch, bicycle_response([
        (300, "출발"),
        (1000, "자전거전용도로 진입 후 도림천자전거길 1.0km 이동"),
        (400, "자전거 도로 따라 400m 이동"),
        (300, "관악로로 우회전"),
    ]))
    assert item["bike_road_percent"] == 70


def test_no_bike_road_is_zero_and_no_guidance_is_unknown(monkeypatch):
    none = bicycle_item(monkeypatch, bicycle_response([(2000, "관악로 직진")]))
    assert none["bike_road_percent"] == 0
    unknown = bicycle_item(monkeypatch, bicycle_response([(2000, "")]))
    assert unknown["bike_road_percent"] is None


def test_no_steps_is_unknown_and_walk_has_no_field(upstream):
    item = clients._single_route_candidate(clients.BICYCLE_URL, "bicycle", "자전거", *START, *END)
    assert item["bike_road_percent"] is None
    walk = clients._single_route_candidate(clients.WALK_URL, "walk", "도보", *START, *END)
    assert "bike_road_percent" not in walk


def test_percent_is_capped_and_falls_back_to_step_total(monkeypatch):
    over = bicycle_item(monkeypatch, bicycle_response([(2500, "자전거길 이동")], total=2000))
    assert over["bike_road_percent"] == 100
    no_total = bicycle_response([(500, "자전거길 이동"), (1500, "도로 이동")])
    no_total["route"]["properties"].pop("totalDistance")
    assert bicycle_item(monkeypatch, no_total)["bike_road_percent"] == 25
