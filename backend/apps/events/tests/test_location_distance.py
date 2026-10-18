"""BE-19: real API requests, boundary distances, PATCH merging and no side effects."""

import math
from datetime import timedelta
from unittest.mock import Mock

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.common.geo import distance_meters
from apps.events.models import Event, Place
from apps.planning import services

pytestmark = pytest.mark.django_db
HOME = (37.48, 126.93)
DEST = (37.49, 126.94)


def north(meters):
    return HOME[0] + math.degrees(meters / 6_371_008.8), HOME[1]


def payload(destination=DEST, **extra):
    return {
        "title": "거리 검증",
        "start_at": (timezone.now() + timedelta(hours=2)).isoformat(),
        "place": {"name": "목적지", "lat": destination[0], "lng": destination[1]},
        **extra,
    }


@pytest.fixture
def user(django_user_model):
    user = django_user_model.objects.create_user(
        email="distance@example.com", password="Test12345"
    )
    profile = user.profile
    profile.home_lat, profile.home_lng = HOME
    profile.save()
    return user


@pytest.fixture
def api(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


@pytest.fixture
def compute(monkeypatch):
    spy = Mock()
    monkeypatch.setattr(services, "compute_and_store", spy)
    return spy


@pytest.fixture
def event(user):
    place = Place.objects.create(name="목적지", lat=DEST[0], lng=DEST[1])
    return Event.objects.create(
        user=user, place=place, title="기존 일정", start_at=timezone.now()
    )


@pytest.mark.integration
@pytest.mark.parametrize("meters", [0, 1, 49.9])
@pytest.mark.parametrize("custom_origin", [False, True])
def test_rejects_close_creation_without_rows_or_route_calls(
    api, compute, meters, custom_origin
):
    extra = {"origin_lat": HOME[0], "origin_lng": HOME[1]} if custom_origin else {}
    response = api.post("/api/events", payload(north(meters), **extra), format="json")
    assert response.status_code == 400
    assert "destination" in response.data["error"]["details"]
    assert "50m" in response.data["error"]["message"]
    assert Event.objects.count() == Place.objects.count() == 0
    compute.assert_not_called()


@pytest.mark.integration
@pytest.mark.parametrize("meters", [50.1, 100])
def test_accepts_valid_distance(api, compute, meters):
    response = api.post("/api/events", payload(north(meters)), format="json")
    assert response.status_code == 201
    compute.assert_called_once()


@pytest.mark.integration
def test_custom_origin_takes_precedence_over_home(api, compute):
    response = api.post(
        "/api/events",
        payload(HOME, origin_lat=DEST[0], origin_lng=DEST[1]),
        format="json",
    )
    assert response.status_code == 201


@pytest.mark.integration
def test_patch_destination_uses_saved_origin(api, event, compute):
    event.origin_lat, event.origin_lng = DEST
    event.save()
    before = Place.objects.count()
    response = api.patch(
        f"/api/events/{event.pk}", {"place": payload()["place"]}, format="json"
    )
    assert response.status_code == 400
    event.refresh_from_db()
    assert Place.objects.count() == before
    compute.assert_not_called()


@pytest.mark.integration
@pytest.mark.parametrize(
    "place",
    [
        {},
        {"name": "이름"},
        {"name": "이름", "lat": DEST[0]},
        {"name": "이름", "lng": DEST[1]},
        {"lat": DEST[0], "lng": DEST[1]},
    ],
)
def test_incomplete_nested_place_patch_is_400_without_writes(
    api, event, compute, place
):
    before = Place.objects.count()
    response = api.patch(f"/api/events/{event.pk}", {"place": place}, format="json")
    assert response.status_code == 400
    assert "place" in response.data["error"]["details"]
    event.refresh_from_db()
    assert (event.place.lat, event.place.lng) == DEST
    assert Place.objects.count() == before
    compute.assert_not_called()


@pytest.mark.integration
def test_patch_origin_uses_saved_destination(api, event, compute):
    response = api.patch(
        f"/api/events/{event.pk}",
        {"origin_lat": DEST[0], "origin_lng": DEST[1]},
        format="json",
    )
    assert response.status_code == 400
    event.refresh_from_db()
    assert event.origin_lat is None
    compute.assert_not_called()


@pytest.mark.integration
def test_patch_one_coordinate_merges_saved_pair(api, event, compute):
    event.origin_lat, event.origin_lng = HOME[0], DEST[1]
    event.save()
    response = api.patch(
        f"/api/events/{event.pk}", {"origin_lat": DEST[0]}, format="json"
    )
    assert response.status_code == 400
    event.refresh_from_db()
    assert event.origin_lat == HOME[0]
    compute.assert_not_called()


@pytest.mark.integration
def test_reset_origin_to_home_validates_distance(api, event, compute):
    event.place.lat, event.place.lng = HOME
    event.place.save()
    event.origin_lat, event.origin_lng = DEST
    event.save()
    response = api.patch(
        f"/api/events/{event.pk}",
        {"origin_lat": None, "origin_lng": None},
        format="json",
    )
    assert response.status_code == 400
    compute.assert_not_called()


@pytest.mark.integration
def test_valid_patch_moves_both_endpoints_together(api, event, compute):
    event.origin_lat, event.origin_lng = HOME
    event.save()
    response = api.patch(
        f"/api/events/{event.pk}",
        {"place": payload(HOME)["place"], "origin_lat": DEST[0], "origin_lng": DEST[1]},
        format="json",
    )
    assert response.status_code == 200
    compute.assert_called_once()


@pytest.mark.integration
def test_existing_close_schedule_can_still_be_disabled(api, event, compute):
    event.place.lat, event.place.lng = HOME
    event.place.save()
    response = api.patch(
        f"/api/events/{event.pk}", {"alarm_enabled": False}, format="json"
    )
    assert response.status_code == 200
    compute.assert_not_called()


@pytest.mark.integration
def test_missing_home_or_destination_does_not_block_draft(api, user, compute):
    user.profile.home_lat = user.profile.home_lng = None
    user.profile.save()
    assert api.post("/api/events", payload(HOME), format="json").status_code == 201
    assert (
        api.post("/api/events", payload(place=None), format="json").status_code == 201
    )


@pytest.mark.integration
@pytest.mark.parametrize("meters,status", [(0, 400), (49.9, 400), (50.1, 200)])
@pytest.mark.parametrize("custom_origin", [False, True])
def test_candidate_lookup_uses_same_boundary_without_upstream(
    api, monkeypatch, meters, status, custom_origin
):
    spy = Mock(return_value=([], False))
    monkeypatch.setattr("apps.events.views.clients.route_candidates", spy)
    lat, lng = north(meters)
    params = {"dest_lat": lat, "dest_lng": lng}
    if custom_origin:
        params.update(origin_lat=HOME[0], origin_lng=HOME[1])
    response = api.get("/api/routes/candidates", params)
    assert response.status_code == status
    if status == 400:
        assert "destination" in response.data["error"]["details"]
        spy.assert_not_called()
    else:
        spy.assert_called_once()


def test_distance_is_stable_at_the_documented_boundary():
    assert distance_meters(*HOME, *north(50)) == pytest.approx(50)
