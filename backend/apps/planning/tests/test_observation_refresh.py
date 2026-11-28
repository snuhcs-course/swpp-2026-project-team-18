"""Uploaded preparation evidence must update the saved alarm before risk previews.

Both block timings and the GPS departure/dismissal pair are read immediately by
the estimator. The upload order of those two queues must not leave a stale plan.
"""

from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import refresh, services
from apps.planning.models import AlarmPlan, PlanRevision
from apps.routines.models import RoutineBlock
from apps.routing.models import RouteCorrection

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
EVIDENCE_KINDS = ("block", "trip", "dismissed_log")


@pytest.fixture
def context(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(email="alarm-evidence@example.invalid")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="Fixture destination", lat=37.46, lng=126.95)
    block = RoutineBlock.objects.create(
        user=user, name="Fixture routine", default_min_minutes=20, default_max_minutes=40,
    )
    raw_route = {"minutes": 20, "mode": "transit", "key": "transit:evidence", "source": "kakao"}
    RouteCorrection.objects.create(
        route_signature=raw_route["key"], mode="transit", factor=1.0,
        sd_minutes=8, sample_count=20,
    )
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(raw_route), False))
    historical = Event.objects.create(
        user=user, place=place, title="Previous morning",
        start_at=(timezone.now() - timedelta(days=1)).replace(second=0, microsecond=0),
    )
    event = Event.objects.create(
        user=user, place=place, title="Upcoming alarm",
        start_at=(timezone.now() + timedelta(days=2)).replace(second=0, microsecond=0),
    )
    client = APIClient()
    client.force_authenticate(user)
    return SimpleNamespace(
        user=user, block=block, historical=historical, event=event, client=client,
        monkeypatch=monkeypatch,
    )


def evidence(context, kind):
    """The other half of the GPS pair is present before calculating the plan."""
    morning = context.historical
    dismissed_at = morning.start_at - timedelta(minutes=100)
    departed_at = dismissed_at + timedelta(minutes=45)
    if kind == "block":
        return "/api/routines/observations/batch", {"observations": [
            {
                "block": context.block.pk, "event": None,
                "observed_on": dismissed_at.date().isoformat(),
                "duration_minutes": 60, "client_uuid": f"block-evidence-{index}",
                "client_recorded_at": (dismissed_at + timedelta(minutes=index)).isoformat(),
            }
            for index in range(8)
        ]}
    if kind == "trip":
        AlarmLog.objects.create(
            user=context.user, event=morning, kind="dismissed",
            occurred_at=dismissed_at, client_uuid="existing-dismissal",
        )
        return "/api/observations/batch", {"observations": [{
            "event": morning.pk, "kind": "depart", "detector": "gps",
            "observed_at": departed_at.isoformat(), "accuracy_m": 10,
            "distance_m": 65, "client_uuid": "new-departure",
        }]}
    TripObservation.objects.create(
        user=context.user, event=morning, kind="depart", detector="gps",
        observed_at=departed_at, accuracy_m=10, distance_m=65,
        client_uuid="existing-departure",
    )
    return "/api/alarm-logs/batch", {"logs": [{
        "event": morning.pk, "kind": "dismissed", "occurred_at": dismissed_at.isoformat(),
        "client_uuid": "new-dismissal",
    }]}


def calculate(context):
    before = services.compute_and_store(context.event, trigger=PlanRevision.Trigger.CREATE)
    forbidden = Mock(side_effect=AssertionError("Evidence upload must reuse the stored raw route"))
    context.monkeypatch.setattr(services.clients, "best_route", forbidden)
    context.monkeypatch.setattr(services.clients, "resolve_route", forbidden)
    return before


def normal_option(context):
    response = context.client.get(f"/api/events/{context.event.pk}/risk-options")
    assert response.status_code == 200
    assert response.data["recommended_key"] == "normal"
    return next(option for option in response.data["options"] if option["key"] == "normal")


@pytest.mark.parametrize("kind", EVIDENCE_KINDS)
def test_new_evidence_updates_saved_alarm_and_matches_the_normal_recommendation(context, kind):
    url, body = evidence(context, kind)
    before = calculate(context)
    assert datetime.fromisoformat(normal_option(context)["alarm_at"]) == before.alarm_at

    response = context.client.post(url, body, format="json")

    assert response.status_code == 201
    assert response.data["accepted"] == (8 if kind == "block" else 1)
    after = AlarmPlan.objects.get(event=context.event)
    assert after.alarm_at < before.alarm_at
    assert after.computed_at > before.computed_at
    assert after.prep_source == "observed"
    assert after.route_key == before.route_key
    assert context.event.plan_revisions.first().trigger == PlanRevision.Trigger.LEARNING
    normal = normal_option(context)
    assert datetime.fromisoformat(normal["alarm_at"]) == after.alarm_at
    assert normal["probability"] == after.on_time_probability
    # A preview remains read-only; consistency is established by the upload.
    assert AlarmPlan.objects.get(event=context.event).computed_at == after.computed_at
    shown = context.client.get(f"/api/events/{context.event.pk}").data["alarm_plan"]
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == after.alarm_at


@pytest.mark.parametrize("kind", EVIDENCE_KINDS)
def test_retried_batches_do_not_recalculate_or_write_another_revision(context, kind):
    url, body = evidence(context, kind)
    calculate(context)
    assert context.client.post(url, body, format="json").status_code == 201
    before = AlarmPlan.objects.get(event=context.event)
    revisions = context.event.plan_revisions.count()
    refresh_spy = Mock(wraps=refresh.refresh_upcoming)
    context.monkeypatch.setattr(refresh, "refresh_upcoming", refresh_spy)

    response = context.client.post(url, body, format="json")

    assert response.status_code == 200
    assert response.data["accepted"] == 0
    assert response.data["duplicated"] == (8 if kind == "block" else 1)
    refresh_spy.assert_not_called()
    assert AlarmPlan.objects.get(event=context.event).computed_at == before.computed_at
    assert context.event.plan_revisions.count() == revisions


@pytest.mark.parametrize("kind", EVIDENCE_KINDS)
def test_ignored_pre_reset_evidence_does_not_refresh_any_alarm(context, kind):
    url, body = evidence(context, kind)
    calculate(context)
    profile = context.user.profile
    profile.learning_reset_at = timezone.now()
    profile.save()
    refresh_spy = Mock(wraps=refresh.refresh_upcoming)
    context.monkeypatch.setattr(refresh, "refresh_upcoming", refresh_spy)

    response = context.client.post(url, body, format="json")

    assert response.status_code == 200
    assert response.data["accepted"] == 0
    assert response.data["ignored"] == (8 if kind == "block" else 1)
    refresh_spy.assert_not_called()


@pytest.mark.parametrize("started", ("fired", "departed"))
def test_evidence_preserves_the_plan_of_an_already_started_morning(context, started):
    url, body = evidence(context, "block")
    before = calculate(context)
    if started == "fired":
        AlarmLog.objects.create(
            user=context.user, event=context.event, kind="fired",
            occurred_at=timezone.now(), client_uuid="already-fired",
        )
    else:
        TripObservation.objects.create(
            user=context.user, event=context.event, kind="depart", detector="manual",
            observed_at=timezone.now(), client_uuid="already-departed",
        )

    assert context.client.post(url, body, format="json").status_code == 201

    after = AlarmPlan.objects.get(event=context.event)
    assert after.alarm_at == before.alarm_at
    assert after.computed_at == before.computed_at


def test_evidence_preserves_a_manual_alarm_while_refreshing_its_recommendation(context):
    url, body = evidence(context, "block")
    before = calculate(context)
    picked = before.alarm_at + timedelta(minutes=10)
    selected = context.client.put(
        f"/api/events/{context.event.pk}/alarm/time", {"alarm_at": picked.isoformat()}, format="json",
    )
    assert selected.status_code == 200

    assert context.client.post(url, body, format="json").status_code == 201

    after = AlarmPlan.objects.get(event=context.event)
    assert after.alarm_at < before.alarm_at
    assert after.user_alarm_at == picked
    shown = context.client.get(f"/api/events/{context.event.pk}").data["alarm_plan"]
    assert shown["alarm_source"] == "user"
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == picked
    assert datetime.fromisoformat(normal_option(context)["alarm_at"]) == after.alarm_at


def test_evidence_asks_before_replacing_a_confirmed_alarm(context):
    url, body = evidence(context, "block")
    before = calculate(context)
    confirmed = context.client.post(f"/api/events/{context.event.pk}/alarm/confirm", {}, format="json")
    assert confirmed.status_code == 200

    assert context.client.post(url, body, format="json").status_code == 201

    after = AlarmPlan.objects.get(event=context.event)
    assert after.alarm_at < before.alarm_at - timedelta(minutes=5)
    shown = context.client.get(f"/api/events/{context.event.pk}").data["alarm_plan"]
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == before.alarm_at
    assert datetime.fromisoformat(shown["alarm_proposal"]["proposed_alarm_at"]) == after.alarm_at


@pytest.mark.parametrize("kind", ("fired", "snoozed", "enabled", "disabled"))
def test_non_dismissal_alarm_logs_do_not_refresh_the_preparation_model(context, kind):
    calculate(context)
    refresh_spy = Mock(wraps=refresh.refresh_upcoming)
    context.monkeypatch.setattr(refresh, "refresh_upcoming", refresh_spy)
    item = {
        "event": context.historical.pk, "kind": kind,
        "occurred_at": context.historical.start_at.isoformat(), "client_uuid": f"log-{kind}",
    }
    if kind == "snoozed":
        item["snooze_minutes"] = 5

    response = context.client.post("/api/alarm-logs/batch", {"logs": [item]}, format="json")

    # Enabled/disabled are server-only; app uploads cannot create them.
    assert response.status_code == (400 if kind in ("enabled", "disabled") else 201)
    refresh_spy.assert_not_called()
