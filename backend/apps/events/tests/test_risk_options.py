"""Risk choices use the planning model and stored raw route, without upstream calls."""

from datetime import datetime, timedelta, timezone
from unittest.mock import Mock

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.planning import services
from apps.planning.models import PlanRevision
from apps.routing.models import RouteCorrection
from apps.routines.models import RoutineBlock

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
KST = timezone(timedelta(hours=9))
ROUTE = {"minutes": 20, "mode": "transit", "key": "transit:test", "source": "kakao"}


@pytest.fixture
def context(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(email="risk@test.com", password="test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="학교", lat=37.46, lng=126.95)
    event = Event.objects.create(
        user=user, place=place, title="수업",
        start_at=datetime(2026, 10, 19, 9, tzinfo=KST),
    )
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(ROUTE), False))
    client = APIClient()
    client.force_authenticate(user)
    return client, event


def get_options(client, event):
    response = client.get(f"/api/events/{event.pk}/risk-options")
    assert response.status_code == 200
    return response.json()


def add_variance(event, *, travel=True):
    RoutineBlock.objects.create(
        user=event.user, name="샤워", default_min_minutes=10, default_max_minutes=30,
    )
    if travel:
        RouteCorrection.objects.create(
            route_signature=ROUTE["key"], mode="transit", factor=1.5,
            sd_minutes=8, sample_count=20,
        )


def test_owned_and_authenticated(context, django_user_model):
    client, event = context
    client.force_authenticate(django_user_model.objects.create_user(email="other@test.com"))
    assert client.get(f"/api/events/{event.pk}/risk-options").status_code == 404
    client.force_authenticate(None)
    assert client.get(f"/api/events/{event.pk}/risk-options").status_code == 401


def test_missing_plan_and_raw_snapshot_are_explicit(context):
    client, event = context
    data = get_options(client, event)
    assert data["options"] == []
    assert data["unavailable_reason"]
    services.compute_and_store(event)
    PlanRevision.objects.filter(event=event).update(route_minutes_raw=None)
    data = get_options(client, event)
    assert data["options"] == []
    assert data["unavailable_reason"]


def test_no_variability_is_empty_and_never_fetches_route(context, monkeypatch):
    client, event = context
    services.compute_and_store(event)
    upstream = Mock(side_effect=AssertionError("risk GET must not fetch routes"))
    monkeypatch.setattr(services.clients, "best_route", upstream)
    monkeypatch.setattr(services.clients, "resolve_route", upstream)
    data = get_options(client, event)
    assert data["options"] == []
    assert data["distribution"] is None
    assert data["unavailable_reason"]
    upstream.assert_not_called()


def test_model_options_order_and_patch_parity(context, monkeypatch):
    client, event = context
    add_variance(event)
    plan = services.compute_and_store(event)
    revisions_before = PlanRevision.objects.filter(event=event).count()
    with monkeypatch.context() as no_network:
        upstream = Mock(side_effect=AssertionError("no upstream"))
        no_network.setattr(services.clients, "best_route", upstream)
        no_network.setattr(services.clients, "resolve_route", upstream)
        data = get_options(client, event)
        upstream.assert_not_called()
    options = data["options"]
    assert [(o["key"], o["tau"]) for o in options] == [
        ("safe", .97), ("normal", .90), ("gamble", .65),
    ]
    assert options[0]["alarm_at"] < options[1]["alarm_at"] < options[2]["alarm_at"]
    assert options[0]["probability"] >= options[1]["probability"] >= options[2]["probability"]
    assert data["selected_key"] == "normal"
    normal = options[1]
    assert datetime.fromisoformat(normal["alarm_at"]) == plan.alarm_at
    assert normal["probability"] == plan.on_time_probability
    assert PlanRevision.objects.filter(event=event).count() == revisions_before
    dist = data["distribution"]
    assert dist["source"] == "model" and dist["window_days"] is None
    assert dist["p50"] < dist["p90"]
    assert sum(b["mass"] for b in dist["bins"]) == pytest.approx(1, abs=.002)
    assert all("count" not in b for b in dist["bins"])
    saved = client.patch(f"/api/events/{event.pk}", {"tau_override": .97}, format="json")
    assert saved.status_code == 200
    assert datetime.fromisoformat(saved.json()["alarm_plan"]["alarm_at"]) == datetime.fromisoformat(options[0]["alarm_at"])
    assert get_options(client, event)["selected_key"] == "safe"


def test_custom_tau_is_unselected_and_metadata_tracks_actual_default(context):
    client, event = context
    add_variance(event)
    from apps.events.models import EventTag
    tag = EventTag.objects.create(key="risk-exam", label="시험", default_tau=.99)
    event.tag = tag
    event.save()
    services.compute_and_store(event)
    data = get_options(client, event)
    assert data["selected_key"] is None
    assert data["auto_adjusted"] is True
    assert "시험" in data["auto_reason"] and "0.99" in data["auto_reason"]
    event.tau_override = .99
    event.save()
    data = get_options(client, event)
    assert data["selected_key"] is None
    assert data["auto_adjusted"] is False and data["auto_reason"] is None


def test_partial_variance_keeps_unknown_probability(context):
    client, event = context
    add_variance(event, travel=False)
    services.compute_and_store(event)
    options = get_options(client, event)["options"]
    assert len(options) == 3
    assert all(o["probability"] is None for o in options)


def test_unmatched_latest_revision_does_not_reuse_an_old_raw_route(context):
    client, event = context
    add_variance(event)
    services.compute_and_store(event)
    PlanRevision.objects.filter(event=event).update(event_start_at=event.start_at - timedelta(hours=1))
    data = get_options(client, event)
    assert data["options"] == [] and data["unavailable_reason"]


def test_explicit_origin_uses_travel_model_without_home_preparation(context):
    client, event = context
    event.origin_lat, event.origin_lng = 37.49, 126.93
    event.save()
    add_variance(event)
    plan = services.compute_and_store(event)
    data = get_options(client, event)
    normal = next(option for option in data["options"] if option["key"] == "normal")
    assert plan.prep_minutes == 0
    assert datetime.fromisoformat(normal["alarm_at"]) == plan.alarm_at
    assert normal["probability"] == plan.on_time_probability


def test_selecting_same_automatic_route_keeps_risk_snapshot_available(context):
    client, event = context
    add_variance(event)
    plan = services.compute_and_store(event)
    assert len(get_options(client, event)["options"]) == 3

    selected = client.patch(
        f"/api/events/{event.pk}", {"route_key": ROUTE["key"]}, format="json",
    )
    assert selected.status_code == 200
    assert datetime.fromisoformat(selected.json()["alarm_plan"]["alarm_at"]) == plan.alarm_at
    assert len(get_options(client, event)["options"]) == 3
    latest = event.plan_revisions.first()
    assert latest.requested_route_key == ROUTE["key"]
    assert event.plan_revisions.count() == 2

    # Repeated identical recomputations must neither lose that snapshot nor add rows.
    assert client.post(f"/api/events/{event.pk}/recompute").status_code == 200
    assert len(get_options(client, event)["options"]) == 3
    assert event.plan_revisions.count() == 2


def test_raw_route_change_refreshes_preview_even_if_rounded_decision_is_identical(context, monkeypatch):
    client, event = context
    add_variance(event)
    # At this factor, raw 20 -> 21 changes the modeled mean by .25 minute while
    # the current tau's allocated preparation/travel minutes round identically.
    RouteCorrection.objects.filter(route_signature=ROUTE["key"]).update(factor=.25)
    route = dict(ROUTE)
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    old_plan = services.compute_and_store(event)
    decision_fields = [field for field in PlanRevision.DECISION_FIELDS if field != "event_start_at"]
    old_decision = [getattr(old_plan, field) for field in decision_fields]
    old_distribution = get_options(client, event)["distribution"]

    route["minutes"] = 21
    new_plan = services.compute_and_store(event)
    assert [getattr(new_plan, field) for field in decision_fields] == old_decision
    new_distribution = get_options(client, event)["distribution"]
    assert new_distribution != old_distribution
    assert event.plan_revisions.first().route_minutes_raw == 21
    assert event.plan_revisions.count() == 2
