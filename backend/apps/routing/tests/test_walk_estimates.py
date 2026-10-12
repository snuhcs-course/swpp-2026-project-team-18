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
