"""BE-7/12: the real candidates endpoint keeps legacy rows and adds grouped guides."""

import pytest
from rest_framework.test import APIClient

from apps.routing import clients
from apps.routing.tests.test_transit_display import END, START, route

pytestmark = [pytest.mark.django_db, pytest.mark.integration]


@pytest.fixture
def api(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(
        email="candidates@example.com", password="Test12345"
    )
    user.profile.home_lat, user.profile.home_lng = START
    user.profile.home_label = "신림역"
    user.profile.save()
    client = APIClient()
    client.force_authenticate(user)
    monkeypatch.setattr("apps.routing.realtime.enrich_candidates", lambda items: None)
    return client


@pytest.fixture
def upstream(monkeypatch):
    calls = []
    responses = {
        clients.TRANSIT_URL: {
            "status": "OK",
            "routes": [route(f"551{i}", 1200 + i * 60) for i in range(6)],
        },
        clients.WALK_URL: {
            "status": "OK",
            "route": {"properties": {"totalTime": 2400, "totalDistance": 2500}},
        },
        clients.BICYCLE_URL: {
            "status": "OK",
            "route": {"properties": {"totalTime": 1100, "totalDistance": 2800}},
        },
        clients.CAR_URL: {
            "routes": [{
                "summary": {"duration": 600, "distance": 3000, "fare": {"taxi": 7000}},
                "sections": [{"roads": [
                    {"name": "신림로"}, {"name": ""}, {"name": "신림로"},
                    {"name": "남부순환로"},
                ]}],
            }],
        },
    }

    def get(url):
        calls.append(url)
        data = responses.get(url.split("?")[0])
        return data, data is None

    monkeypatch.setattr(clients, "_get", get)
    return responses, calls


def test_candidates_are_grouped_without_losing_other_modes(api, upstream):
    response = api.get("/api/routes/candidates", {"dest_lat": END[0], "dest_lng": END[1]})
    assert response.status_code == 200
    body = response.data
    assert {mode: len(body[mode]) for mode in ("car", "transit", "walk", "bicycle")} == {
        "car": 1, "transit": 3, "walk": 1, "bicycle": 1,
    }
    assert len(body["results"]) == 6
    assert body["fastest_key"] == body["results"][0]["key"] == "car"
    assert body["degraded"] is False
    for mode in ("car", "transit", "walk", "bicycle"):
        assert body[mode] == [item for item in body["results"] if item["kind"] == mode]
    assert len(upstream[1]) == 4


def test_mode_caps_allow_all_nine_candidates_without_a_global_cutoff(api, monkeypatch):
    candidates = [
        {
            "key": f"{mode}:{i}" if mode in {"car", "transit"} else mode,
            "kind": mode, "mode": mode, "minutes": minutes + i, "transfers": 0,
        }
        for mode, count, minutes in (("car", 5, 10), ("transit", 4, 20), ("walk", 2, 30), ("bicycle", 2, 40))
        for i in range(count)
    ]
    monkeypatch.setattr(clients, "route_candidates", lambda **kwargs: (candidates, False))
    response = api.get("/api/routes/candidates", {"dest_lat": END[0], "dest_lng": END[1]})
    assert response.status_code == 200
    body = response.data
    assert [len(body[mode]) for mode in ("car", "transit", "walk", "bicycle")] == [4, 3, 1, 1]
    assert len(body["results"]) == 9
    assert body["fastest_key"] == "car:0"


def test_guides_use_labels_and_actual_route_details_without_extra_calls(api, upstream):
    response = api.get("/api/routes/candidates", {
        "dest_lat": END[0], "dest_lng": END[1], "dest_label": "서울대학교",
    })
    assert response.status_code == 200
    items = response.data["results"]
    assert all(item["guide"].startswith("신림역 출발 → 서울대학교까지") for item in items)
    car = next(item for item in items if item["key"] == "car")
    assert car["guide"] == (
        "신림역 출발 → 서울대학교까지 자동차 3.0km, 신림로, 남부순환로 경유 예상 소요 10분"
    )
    transit = next(item for item in items if item["kind"] == "transit")
    assert "5510" in transit["guide"]
    assert "신림로" not in transit["guide"]
    assert len(upstream[1]) == 4


def test_short_walk_has_a_guide_and_fastest_key_with_generic_destination(api, upstream):
    upstream[0][clients.WALK_URL]["route"]["properties"] = {
        "totalTime": 600, "totalDistance": 800,
    }
    response = api.get("/api/routes/candidates", {"dest_lat": END[0], "dest_lng": END[1]})
    assert response.status_code == 200
    body = response.data
    assert body["fastest_key"] == "walk"
    assert body["car"] == body["transit"] == body["bicycle"] == []
    assert body["walk"] == body["results"]
    assert body["walk"][0]["guide"] == "신림역 출발 → 도착지까지 도보 0.8km, 예상 소요 10분"
    assert body["degraded"] is False
    assert len(upstream[1]) == 1


@pytest.mark.parametrize("keep_car", [False, True])
def test_empty_and_degraded_candidates_have_a_valid_fastest_key(api, upstream, keep_car):
    for mode in (clients.TRANSIT_URL, clients.WALK_URL, clients.BICYCLE_URL):
        upstream[0][mode] = None
    if not keep_car:
        upstream[0][clients.CAR_URL] = None
    response = api.get("/api/routes/candidates", {"dest_lat": END[0], "dest_lng": END[1]})
    assert response.status_code == 200
    body = response.data
    assert body["fastest_key"] == ("car" if keep_car else None)
    assert body["transit"] == body["walk"] == body["bicycle"] == []
    assert body["car"] == body["results"]
    assert body["degraded"] is True
    assert len(upstream[1]) == 4
