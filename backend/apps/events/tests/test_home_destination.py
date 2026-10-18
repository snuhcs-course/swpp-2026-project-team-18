"""BE-18: home is a current profile destination, never a copied Place."""

from datetime import timedelta
from unittest.mock import Mock

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.planning import services

pytestmark = pytest.mark.django_db
HOME = (37.48, 126.93)
ORIGIN = (37.50, 126.96)
NEW_HOME = (37.46, 126.95)


@pytest.fixture
def user(django_user_model):
    user = django_user_model.objects.create_user(email="home-target@example.com", password="Test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = HOME
    profile.home_label = "우리 집"
    profile.save()
    return user


@pytest.fixture
def api(user):
    api = APIClient()
    api.force_authenticate(user)
    return api


@pytest.fixture
def route(monkeypatch):
    def resolve(*args, **kwargs):
        return {
            "minutes": 20, "mode": "walk", "source": "estimate", "key": "walk",
            "path": [[kwargs["start_lat"], kwargs["start_lng"]], [kwargs["end_lat"], kwargs["end_lng"]]],
        }, False

    spy = Mock(side_effect=resolve)
    monkeypatch.setattr(services.clients, "best_route", spy)
    monkeypatch.setattr(services.clients, "resolve_route", spy)
    return spy


def payload(**extra):
    return {
        "title": "귀가", "start_at": (timezone.now() + timedelta(days=1)).isoformat(),
        "destination_is_home": True, "origin_lat": ORIGIN[0], "origin_lng": ORIGIN[1],
        **extra,
    }


@pytest.mark.integration
def test_create_home_destination_computes_route_without_place_row(api, route):
    response = api.post("/api/events", payload(), format="json")
    assert response.status_code == 201
    assert response.data["alarm_plan"]["status"] == "ok"
    assert response.data["destination_is_home"] is True
    assert response.data["place"] == {
        "id": None, "name": "우리 집", "address": "", "lat": HOME[0], "lng": HOME[1], "kakao_place_id": None,
    }
    assert Place.objects.count() == 0
    assert Event.objects.get(pk=response.data["id"]).place_id is None
    assert route.call_args.kwargs["end_lat"] == HOME[0]


@pytest.mark.integration
def test_profile_move_recomputes_home_destination_and_response(api, route):
    created = api.post("/api/events", payload(), format="json")
    response = api.patch("/api/profile", {"home_lat": NEW_HOME[0], "home_lng": NEW_HOME[1], "home_label": "새 집"}, format="json")
    assert response.status_code == 200
    event = api.get(f'/api/events/{created.data["id"]}').data
    assert event["place"] is not None
    assert (event["place"]["lat"], event["place"]["lng"], event["place"]["name"]) == (*NEW_HOME, "새 집")
    assert event["alarm_plan"]["route_path"][-1] == list(NEW_HOME)
    assert route.call_args.kwargs["end_lat"] == NEW_HOME[0]
    assert Place.objects.count() == 0


@pytest.mark.integration
@pytest.mark.parametrize("extra,field", [
    ({"place": {"name": "별도 장소", "lat": 37.45, "lng": 126.94}}, "destination_is_home"),
    ({"origin_lat": HOME[0], "origin_lng": HOME[1]}, "destination"),
])
def test_home_destination_rejects_conflict_or_close_origin_before_writes(api, route, extra, field):
    response = api.post("/api/events", payload(**extra), format="json")
    assert response.status_code == 400
    assert field in response.data["error"]["details"]
    assert Event.objects.count() == Place.objects.count() == 0
    route.assert_not_called()


@pytest.mark.integration
def test_missing_home_rejects_only_home_target_creation(api, user, route):
    profile = user.profile
    profile.home_lat = profile.home_lng = None
    profile.save()
    response = api.post("/api/events", payload(), format="json")
    assert response.status_code == 400
    assert "destination_is_home" in response.data["error"]["details"]
    assert Event.objects.count() == Place.objects.count() == 0
    route.assert_not_called()
    # Existing draft behavior stays available when no home destination is selected.
    draft = api.post("/api/events", payload(destination_is_home=False), format="json")
    assert draft.status_code == 201
    assert draft.data["alarm_plan"]["status"] == "no_place"


@pytest.mark.integration
def test_patch_home_clears_old_place_then_explicit_place_clears_flag(api, route):
    place = {"name": "직장", "lat": 37.45, "lng": 126.94}
    created = api.post("/api/events", payload(destination_is_home=False, place=place), format="json")
    event_id = created.data["id"]
    home = api.patch(f"/api/events/{event_id}", {"destination_is_home": True}, format="json")
    assert home.status_code == 200
    assert home.data["destination_is_home"] is True
    assert Event.objects.get(pk=event_id).place_id is None
    assert Place.objects.count() == 1
    explicit = api.patch(f"/api/events/{event_id}", {"place": place}, format="json")
    assert explicit.status_code == 200
    assert explicit.data["destination_is_home"] is False
    assert explicit.data["place"]["id"] == created.data["place"]["id"]
    assert Place.objects.count() == 1


@pytest.mark.integration
def test_home_patch_merges_saved_origin_for_distance_validation(api, route):
    created = api.post("/api/events", payload(), format="json")
    response = api.patch(f'/api/events/{created.data["id"]}', {"origin_lat": HOME[0], "origin_lng": HOME[1]}, format="json")
    assert response.status_code == 400
    assert "destination" in response.data["error"]["details"]
    route.reset_mock()
    # Home flags do not turn an alarm switch into a route recomputation.
    switched = api.patch(f'/api/events/{created.data["id"]}', {"alarm_enabled": False}, format="json")
    assert switched.status_code == 200
    route.assert_not_called()


@pytest.mark.integration
@pytest.mark.parametrize("home,status", [(None, "no_home"), (ORIGIN, "route_failed")])
def test_profile_change_clears_invalid_plan_and_route_without_upstream(api, route, home, status):
    created = api.post("/api/events", payload(), format="json")
    route.reset_mock()
    response = api.patch("/api/profile", {"home_lat": home[0] if home else None, "home_lng": home[1] if home else None}, format="json")
    assert response.status_code == 200
    assert response.data["recomputed_plans"] == 1
    event = api.get(f'/api/events/{created.data["id"]}').data
    plan = event["alarm_plan"]
    assert plan["status"] == status
    assert plan["alarm_at"] is None
    assert plan["route_path"] == plan["alt_route_path"] == []
    assert plan["route_distance_m"] is None
    assert Place.objects.count() == 0
    if home is None:
        live = api.post("/api/routes/live", {"event_id": event["id"], "lat": ORIGIN[0], "lng": ORIGIN[1]}, format="json")
        assert live.status_code == 400
        assert live.data["error"]["code"] == "no_home"
    route.assert_not_called()


@pytest.mark.integration
def test_live_route_uses_current_home_and_enforces_ownership(api, route, django_user_model):
    created = api.post("/api/events", payload(route_key="walk"), format="json")
    api.patch("/api/profile", {"home_lat": NEW_HOME[0], "home_lng": NEW_HOME[1]}, format="json")
    route.reset_mock()
    body = {"event_id": created.data["id"], "lat": ORIGIN[0], "lng": ORIGIN[1]}
    live = api.post("/api/routes/live", body, format="json")
    assert live.status_code == 200
    assert route.call_args.kwargs["end_lat"] == NEW_HOME[0]
    other = django_user_model.objects.create_user(email="other-home@example.com", password="Test12345")
    api.force_authenticate(other)
    route.reset_mock()
    assert api.get(f'/api/events/{created.data["id"]}').status_code == 404
    assert api.patch(f'/api/events/{created.data["id"]}', {"destination_is_home": False}, format="json").status_code == 404
    assert api.post("/api/routes/live", body, format="json").status_code == 404
    assert api.get("/api/events").data["results"] == []
    route.assert_not_called()


@pytest.mark.integration
def test_calendar_reimport_preserves_manual_home_target_without_resolving_place(api, route):
    start_at = payload()["start_at"]
    imported = api.post("/api/events/import", {"events": [{"external_id": "calendar-home", "title": "귀가", "start_at": start_at}]}, format="json")
    event_id = imported.data["results"][0]["id"]
    changed = api.patch(f"/api/events/{event_id}", {"destination_is_home": True, "origin_lat": ORIGIN[0], "origin_lng": ORIGIN[1]}, format="json")
    assert changed.status_code == 200
    route.reset_mock()
    response = api.post("/api/events/import", {"events": [{"external_id": "calendar-home", "title": "귀가", "start_at": start_at, "place": {"name": "캘린더의 옛 장소", "lat": 37.45, "lng": 126.94}}]}, format="json")
    assert response.status_code == 200
    assert response.data["unchanged"] == 1
    assert response.data["results"][0]["destination_is_home"] is True
    assert Event.objects.get(pk=event_id).place_id is None
    assert Place.objects.count() == 0
    route.assert_not_called()


def test_legacy_insert_keeps_false_database_default_and_home_place_constraint(user):
    from django.db import IntegrityError, connection, transaction

    now = timezone.now()
    with connection.cursor() as cursor:
        cursor.execute(
            'INSERT INTO events_event (user_id, source, title, start_at, created_at, updated_at) VALUES (%s, %s, %s, %s, %s, %s)',
            [user.pk, "manual", "old writer", now, now, now],
        )
    event = Event.objects.get(title="old writer")
    assert event.destination_is_home is False
    place = Place.objects.create(name="명시적 장소", lat=NEW_HOME[0], lng=NEW_HOME[1])
    with pytest.raises(IntegrityError), transaction.atomic():
        Event.objects.filter(pk=event.pk).update(destination_is_home=True, place=place)
