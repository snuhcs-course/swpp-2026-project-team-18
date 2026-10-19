"""BE-20: weekly rules materialize independent, stable seven-day occurrences."""

from datetime import datetime, timedelta
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event
from apps.planning import services

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
KST = ZoneInfo("Asia/Seoul")


@pytest.fixture
def clock(monkeypatch):
    value = [datetime(2026, 10, 19, 8, tzinfo=KST)]  # Monday
    monkeypatch.setattr(timezone, "now", lambda: value[0])
    return value


@pytest.fixture
def client(django_user_model, clock, monkeypatch):
    user = django_user_model.objects.create_user(email="repeat@example.com", password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.4842, 126.9297
    profile.timezone = "Asia/Seoul"
    profile.save()
    route = {"minutes": 20, "mode": "walk", "source": "kakao_walk", "key": "walk"}
    monkeypatch.setattr(services.clients, "best_route", Mock(return_value=(route, False)))
    api = APIClient()
    api.force_authenticate(user)
    api.owner = user
    return api


def create(client, **changes):
    body = {"title": "반복 수업", "start_at": "2026-10-19T09:00:00+09:00",
            "repeat_weekdays": [0, 2], "place": {"name": "학교", "lat": 37.46, "lng": 126.95}}
    return client.post("/api/events", body | changes, format="json")


def rows(client):
    response = client.get("/api/events")
    assert response.status_code == 200
    return response.data["results"]


def test_weekdays_have_independent_plans_and_repeated_get_does_not_recompute(client):
    response = create(client)
    assert response.status_code == 201
    found = rows(client)
    assert len(found) == 2
    assert [datetime.fromisoformat(r["start_at"]).astimezone(KST).weekday() for r in found] == [0, 2]
    assert all(r["repeat_weekdays"] == [0, 2] and r["recurrence_id"] for r in found)
    assert all(r["alarm_plan"]["status"] == "ok" and r["alarm_on"] for r in found)
    assert found[0]["alarm_plan"]["alarm_at"] != found[1]["alarm_plan"]["alarm_at"]
    services.clients.best_route.reset_mock()
    assert [r["id"] for r in rows(client)] == [r["id"] for r in found]
    services.clients.best_route.assert_not_called()


def test_only_one_occurrence_changes_and_deleted_date_stays_deleted(client, clock):
    create(client, repeat_weekdays=list(range(7)))
    original = rows(client)
    assert len(original) == 7
    disabled, deleted = original[:2]
    assert client.patch(f"/api/events/{disabled['id']}", {"alarm_enabled": False}, format="json").status_code == 200
    assert client.delete(f"/api/events/{deleted['id']}").status_code == 204
    found = rows(client)
    assert len(found) == 6
    assert next(r for r in found if r["id"] == disabled["id"])["alarm_on"] is False
    assert all(r["alarm_on"] for r in found if r["id"] != disabled["id"])
    assert client.get(f"/api/events/{deleted['id']}").status_code == 404
    clock[0] += timedelta(days=1)
    found = rows(client)
    assert deleted["id"] not in {r["id"] for r in found}
    assert Event.objects.filter(user=client.owner).count() == 7  # one new day, no resurrection
    assert max(datetime.fromisoformat(r["start_at"]).astimezone(KST).date() for r in found).isoformat() == "2026-10-26"
    # A late switch on an archived occurrence must not erase its historical plan.
    for update, body in (
        (client.patch, {"alarm_enabled": False}),
        (client.put, {"title": disabled["title"], "start_at": disabled["start_at"], "alarm_enabled": False}),
    ):
        archived = update(f"/api/events/{disabled['id']}", body, format="json")
        assert archived.data["alarm_plan"]["status"] == "ok"


def test_rollover_preserves_other_manual_switches_and_is_user_scoped(client, clock, django_user_model):
    create(client, repeat_weekdays=list(range(7)))
    target = rows(client)[-1]
    client.patch(f"/api/events/{target['id']}", {"alarm_enabled": False}, format="json")
    clock[0] += timedelta(days=1)
    other = APIClient()
    other.force_authenticate(django_user_model.objects.create_user(email="other-repeat@example.com", password="test"))
    assert rows(other) == []
    assert other.delete(f"/api/events/{target['id']}").status_code == 404
    assert Event.objects.filter(user=client.owner).count() == 7
    assert next(r for r in rows(client) if r["id"] == target["id"])["alarm_enabled"] is False


def test_first_occurrence_follows_selected_weekday_and_future_anchor_waits_for_window(client, clock):
    response = create(client, start_at="2026-11-02T09:00:00+09:00", repeat_weekdays=[2])
    assert response.status_code == 201
    assert datetime.fromisoformat(response.data["start_at"]).astimezone(KST).date().isoformat() == "2026-11-04"
    assert response.data["alarm_plan"] is None
    assert rows(client) == []
    services.clients.best_route.assert_not_called()
    clock[0] = datetime(2026, 10, 30, 8, tzinfo=KST)
    found = rows(client)
    assert len(found) == 1 and found[0]["id"] == response.data["id"]
    assert found[0]["alarm_plan"]["status"] == "ok"


def test_daily_first_alarm_rule_includes_occurrences(client):
    early = client.post("/api/events", {"title": "먼저", "start_at": "2026-10-19T08:30:00+09:00",
        "place": {"name": "학교", "lat": 37.46, "lng": 126.95}}, format="json")
    create(client)
    found = rows(client)
    monday = next(r for r in found if r["title"] == "반복 수업" and r["start_at"].startswith("2026-10-19"))
    assert monday["alarm_on"] is False
    client.patch(f"/api/events/{early.data['id']}", {"alarm_enabled": False}, format="json")
    assert next(r for r in rows(client) if r["id"] == monday["id"])["alarm_on"] is True


@pytest.mark.parametrize("weekdays", [[-1], [7], "mon", [True]])
def test_invalid_weekdays_do_not_create_anything(client, weekdays):
    response = create(client, repeat_weekdays=weekdays)
    assert response.status_code == 400 and "repeat_weekdays" in response.data["error"]["details"]
    assert Event.objects.count() == 0


def test_occurrence_edit_keeps_original_slot_and_rule_is_not_silently_changed(client, clock):
    response = create(client)
    target = response.data["id"]
    assert client.patch(f"/api/events/{target}", {"start_at": "2026-10-20T10:00:00+09:00"}, format="json").status_code == 200
    assert len(rows(client)) == 2
    assert client.patch(f"/api/events/{target}", {"repeat_weekdays": [1]}, format="json").status_code == 400
    assert client.patch(f"/api/events/{target}", {"start_at": "2026-11-02T10:00:00+09:00"}, format="json").status_code == 200
    assert client.get(f"/api/events/{target}").data["alarm_plan"] is None
    clock[0] = datetime(2026, 10, 30, 8, tzinfo=KST)
    moved = next(r for r in rows(client) if r["id"] == target)
    assert moved["alarm_plan"]["status"] == "ok"


def test_archived_occurrences_do_not_hide_upcoming_rows_behind_pagination(client, clock):
    create(client, repeat_weekdays=list(range(7)))
    rule = Event.objects.first().recurrence
    rule.start_at -= timedelta(days=220)
    rule.save(update_fields=["start_at"])
    Event.objects.bulk_create([
        Event(user=client.owner, recurrence=rule,
              recurrence_date=(rule.start_at + timedelta(days=n)).date(),
              start_at=rule.start_at + timedelta(days=n), title="지난 반복")
        for n in range(200)
    ])
    found = rows(client)
    assert len(found) == 7
    assert all(datetime.fromisoformat(r["start_at"]) >= clock[0] for r in found)
    assert Event.objects.count() == 207


def test_materialization_failure_rolls_back_the_entire_creation(client, monkeypatch):
    real_compute = services.compute_and_store
    calls = []
    def compute(event, **kwargs):
        calls.append(event.pk)
        if len(calls) == 2:
            raise RuntimeError("second occurrence failed")
        return real_compute(event, **kwargs)
    monkeypatch.setattr(services, "compute_and_store", compute)
    with pytest.raises(RuntimeError, match="second occurrence"):
        create(client)
    assert Event.objects.count() == 0


def test_weekdays_and_wall_clock_follow_saved_timezone_across_dst(client, clock):
    profile = client.owner.profile
    profile.timezone = "America/New_York"
    profile.save()
    clock[0] = datetime(2026, 10, 30, 8, tzinfo=ZoneInfo(profile.timezone))
    response = create(client, start_at="2026-10-30T09:00:00-04:00", repeat_weekdays=list(range(7)))
    assert response.status_code == 201
    found = rows(client)
    local = [datetime.fromisoformat(r["start_at"]).astimezone(ZoneInfo(profile.timezone)) for r in found]
    assert len(local) == 7 and all(d.hour == 9 for d in local)
    assert local[0].utcoffset() != local[-1].utcoffset()
