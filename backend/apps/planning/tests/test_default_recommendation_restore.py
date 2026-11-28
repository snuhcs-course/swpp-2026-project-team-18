"""Iteration 5 P15: restore the actual tag/profile default after a quick pick."""

from datetime import datetime, timedelta

import pytest

from apps.events.models import EventTag
from apps.planning import services
from apps.planning.models import AlarmPlan
from apps.planning.tests.test_alarm_control_api import ctx, iso, plan_of, recommended, url

pytestmark = [pytest.mark.django_db, pytest.mark.integration]


def _forbid_provider(*args, **kwargs):
    raise AssertionError("restoring the default must reuse stored route inputs")


@pytest.mark.parametrize("profile_tau,tag_tau", [(.9, None), (.86, None), (.9, .97), (.97, .8)])
def test_restore_undoes_gamble_tau_and_uses_actual_default(ctx, monkeypatch, profile_tau, tag_tau):
    client, event, _ = ctx
    profile = event.user.profile
    profile.default_tau = profile_tau
    profile.save()
    if tag_tau is not None:
        event.tag = EventTag.objects.create(key="restore_test", label="복원 시험", default_tau=tag_tau)
    event.tau_override = None
    event.save()
    services.compute_and_store(event, trigger="update")
    baseline = recommended(event)
    expected_tau = tag_tau if tag_tau is not None else profile_tau

    # A prior quick pick leaves tau_override in the event even after entering a custom clock.
    event.tau_override = .65
    event.save()
    services.compute_and_store(event, trigger="update")
    picked = recommended(event) + timedelta(minutes=5)
    plan_of(client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json"))

    monkeypatch.setattr(services.clients, "best_route", _forbid_provider)
    monkeypatch.setattr(services.clients, "resolve_route", _forbid_provider)
    restored = plan_of(client.delete(url(event, "time")))
    event.refresh_from_db()
    assert event.tau_override is None
    assert restored["tau_used"] == expected_tau
    assert restored["effective_alarm_at"] == restored["recommended_alarm_at"]
    assert recommended(event) == baseline
    assert restored["alarm_source"] == "recommended"
    assert restored["alarm_confirmed"] is True
    assert restored["user_alarm_at"] is None

    # Retrying DELETE is stable and still does not query any route provider.
    again = plan_of(client.delete(url(event, "time")))
    assert again["effective_alarm_at"] == restored["effective_alarm_at"]
    assert again["tau_used"] == expected_tau


def test_restore_rolls_back_tau_and_user_choice_if_saved_route_is_unavailable(ctx, monkeypatch):
    client, event, _ = ctx
    event.tau_override = .65
    event.save()
    services.compute_and_store(event, trigger="update")
    picked = recommended(event) + timedelta(minutes=5)
    before = plan_of(client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json"))
    event.plan_revisions.all().delete()
    monkeypatch.setattr(services.clients, "best_route", _forbid_provider)
    monkeypatch.setattr(services.clients, "resolve_route", _forbid_provider)

    response = client.delete(url(event, "time"))
    assert response.status_code == 409
    assert response.data["error"]["code"] == "alarm_recommendation_unavailable"
    event.refresh_from_db()
    assert event.tau_override == .65
    plan = AlarmPlan.objects.get(event=event)
    assert plan.user_alarm_at == datetime.fromisoformat(before["user_alarm_at"])
    assert plan.user_alarm_at == picked


def test_restoring_without_manual_clock_also_undoes_a_previous_quick_pick(ctx, monkeypatch):
    client, event, _ = ctx
    baseline = recommended(event)
    event.tau_override = .65
    event.save()
    services.compute_and_store(event, trigger="update")
    monkeypatch.setattr(services.clients, "best_route", _forbid_provider)
    monkeypatch.setattr(services.clients, "resolve_route", _forbid_provider)

    restored = plan_of(client.delete(url(event, "time")))
    event.refresh_from_db()
    assert event.tau_override is None
    assert restored["tau_used"] == event.user.profile.default_tau
    assert recommended(event) == baseline
