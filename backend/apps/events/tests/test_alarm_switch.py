"""알람 자동 선택·사용자 재지정·하루 경계와 API 계약."""

from datetime import datetime, timedelta
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import pytest
from django.db import connection
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event
from apps.observations import alarm_logs
from apps.observations.models import AlarmLog
from apps.planning import services
from apps.planning.models import AlarmPlan

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(email="alarms@example.com", password="test")


@pytest.fixture
def client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


@pytest.fixture
def event(user):
    def create(start="2026-10-05T09:00:00+09:00", alarm=None, enabled=None, status="ok", owner=None):
        start_at = datetime.fromisoformat(start)
        instance = Event.objects.create(
            user=owner or user, title="일정", start_at=start_at, alarm_enabled=enabled,
        )
        if status is not None:
            AlarmPlan.objects.create(
                user=instance.user, event=instance, status=status,
                alarm_at=datetime.fromisoformat(alarm) if alarm else start_at - timedelta(hours=1),
                depart_by=start_at - timedelta(minutes=30), arrive_at=start_at,
                prep_minutes=30, travel_minutes=20, buffer_minutes=10,
            )
        return instance
    return create


def states(client, query=""):
    response = client.get("/api/events" + query)
    assert response.status_code == 200
    return {row["id"]: (row["alarm_on"], row["is_first_alarm"]) for row in response.data["results"]}


@pytest.mark.integration
def test_automatic_uses_alarm_time_not_event_start(client, event):
    earlier_start = event(alarm="2026-10-05T08:00:00+09:00")
    earlier_alarm = event(start="2026-10-05T10:00:00+09:00", alarm="2026-10-05T07:00:00+09:00")
    assert states(client) == {earlier_start.pk: (False, False), earlier_alarm.pk: (True, True)}
    response = client.get(f"/api/events/{earlier_alarm.pk}")
    assert response.data["alarm_enabled"] is None
    assert response.data["alarm_on"] is True
    assert response.data["is_first_alarm"] is True


@pytest.mark.integration
def test_equal_alarm_times_use_id_even_if_start_order_differs(client, event):
    first = event(start="2026-10-05T10:00:00+09:00", alarm="2026-10-05T07:00:00+09:00")
    second = event(alarm="2026-10-05T07:00:00+09:00")
    assert states(client) == {first.pk: (True, True), second.pk: (False, False)}


@pytest.mark.integration
def test_turning_off_cascades_and_null_restores_automatic(client, event, monkeypatch):
    first = event()
    second = event(start="2026-10-05T10:00:00+09:00")
    manual = event(start="2026-10-05T11:00:00+09:00", enabled=True)
    compute = Mock(side_effect=AssertionError("알람 토글은 경로를 재계산하면 안 된다"))
    monkeypatch.setattr(services, "compute_and_store", compute)
    assert states(client) == {first.pk: (True, True), second.pk: (False, False), manual.pk: (True, False)}
    for target, expected_first in ((first, second), (second, manual)):
        response = client.patch(f"/api/events/{target.pk}", {"alarm_enabled": False}, format="json")
        assert response.status_code == 200
        assert response.data["id"] == target.pk  # PATCH는 수정한 한 건만 반환한다.
        assert response.data["alarm_enabled"] is False
        assert response.data["alarm_on"] is False
        assert states(client)[expected_first.pk] == (True, True)
    response = client.patch(f"/api/events/{first.pk}", {"alarm_enabled": None}, format="json")
    assert response.status_code == 200
    first.refresh_from_db()
    assert first.alarm_enabled is None
    assert states(client) == {first.pk: (True, True), second.pk: (False, False), manual.pk: (True, False)}
    compute.assert_not_called()


@pytest.mark.integration
def test_explicit_on_can_coexist_with_earlier_automatic(client, event):
    automatic = event()
    later = event(start="2026-10-05T10:00:00+09:00")
    response = client.patch(f"/api/events/{later.pk}", {"alarm_enabled": True}, format="json")
    assert response.status_code == 200
    assert response.data["alarm_enabled"] is True
    assert response.data["alarm_on"] is True
    assert response.data["is_first_alarm"] is False
    assert states(client) == {automatic.pk: (True, True), later.pk: (True, False)}


@pytest.mark.integration
def test_all_explicit_off_has_no_first_alarm(client, event):
    first = event(enabled=False)
    second = event(start="2026-10-05T10:00:00+09:00", enabled=False)
    assert states(client) == {first.pk: (False, False), second.pk: (False, False)}


@pytest.mark.integration
@pytest.mark.parametrize("status", [None, "no_home", "no_place", "route_failed"])
def test_unschedulable_is_off_even_when_explicitly_enabled(client, event, status):
    invalid = event(enabled=True, status=status)
    valid = event(start="2026-10-05T10:00:00+09:00")
    assert states(client) == {invalid.pk: (False, False), valid.pk: (True, True)}


@pytest.mark.integration
def test_missing_alarm_timestamp_is_not_eligible(client, event):
    invalid = event(enabled=True, status="no_home")
    AlarmPlan.objects.filter(event=invalid).update(alarm_at=None)
    assert states(client) == {invalid.pk: (False, False)}


@pytest.mark.integration
@pytest.mark.parametrize("tz,first_start,second_start,first_alarm,second_alarm,second_on", [
    ("Asia/Seoul", "2026-10-05T23:30:00+09:00", "2026-10-06T00:30:00+09:00",
     "2026-10-05T23:00:00+09:00", "2026-10-05T23:30:00+09:00", True),
    ("America/New_York", "2026-11-01T01:45:00-04:00", "2026-11-01T01:30:00-05:00",
     "2026-11-01T01:00:00-04:00", "2026-11-01T01:00:00-05:00", False),
    ("America/New_York", "2026-10-05T23:30:00-04:00", "2026-10-06T00:30:00-04:00",
     "2026-10-05T22:30:00-04:00", "2026-10-05T23:30:00-04:00", True),
])
def test_group_by_profile_timezone_event_start_date(
    client, event, user, tz, first_start, second_start, first_alarm, second_alarm, second_on,
):
    user.profile.timezone = tz
    user.profile.save()
    first = event(start=first_start, alarm=first_alarm)
    second = event(start=second_start, alarm=second_alarm)
    assert states(client) == {first.pk: (True, True), second.pk: (second_on, second_on)}


@pytest.mark.integration
def test_invalid_saved_timezone_falls_back_to_server_timezone(client, event, user):
    user.profile.timezone = "not/a-zone"
    user.profile.save()
    first = event()
    assert states(client)[first.pk] == (True, True)


@pytest.mark.integration
def test_filtered_list_keeps_hidden_first_alarm(client, event):
    event()
    later = event(start="2026-10-05T10:00:00+09:00")
    assert states(client, "?from=2026-10-05T01:00:00Z") == {later.pk: (False, False)}
    detail = client.get(f"/api/events/{later.pk}")
    assert detail.data["alarm_on"] is False
    assert detail.data["is_first_alarm"] is False


@pytest.mark.integration
def test_paginated_list_keeps_first_from_other_page(client, event):
    event()
    later = event(start="2026-10-05T10:00:00+09:00")
    assert states(client, "?limit=1&offset=1") == {later.pk: (False, False)}


@pytest.mark.integration
def test_other_user_does_not_affect_selection_and_cannot_toggle(client, event, django_user_model):
    owner = django_user_model.objects.create_user(email="other@example.com", password="test")
    foreign = event(start="2026-10-05T07:00:00+09:00", owner=owner)
    mine = event()
    assert states(client) == {mine.pk: (True, True)}
    assert client.get(f"/api/events/{foreign.pk}").status_code == 404
    assert client.patch(f"/api/events/{foreign.pk}", {"alarm_enabled": False}, format="json").status_code == 404
    foreign.refresh_from_db()
    assert foreign.alarm_enabled is None


@pytest.mark.integration
@pytest.mark.parametrize("enabled", [None, True, False])
def test_create_accepts_override(client, monkeypatch, enabled):
    monkeypatch.setattr(services, "compute_and_store", Mock())
    response = client.post("/api/events", {
        "title": "일정", "start_at": "2026-10-05T09:00:00+09:00", "alarm_enabled": enabled,
    }, format="json")
    assert response.status_code == 201
    assert response.data["alarm_enabled"] is enabled
    assert Event.objects.get(pk=response.data["id"]).alarm_enabled is enabled
    assert response.data["alarm_on"] is False  # 아직 계산된 계획 없음.


@pytest.mark.integration
def test_create_without_override_defaults_to_automatic(client, monkeypatch):
    monkeypatch.setattr(services, "compute_and_store", Mock())
    response = client.post("/api/events", {
        "title": "일정", "start_at": "2026-10-05T09:00:00+09:00",
    }, format="json")
    assert response.status_code == 201
    assert response.data["alarm_enabled"] is None


@pytest.mark.integration
@pytest.mark.parametrize("zone,day", [
    ("Asia/Seoul", "2026-10-05"),
    ("America/New_York", "2026-11-01"),  # 25시간인 날도 현지 날짜로 묶는다.
])
def test_be25_creation_resets_only_owners_same_local_day(
    client, event, user, django_user_model, monkeypatch, zone, day,
):
    user.profile.timezone = zone
    user.profile.save()
    midnight = datetime.fromisoformat(day).replace(tzinfo=ZoneInfo(zone))
    first = event(start=(midnight + timedelta(hours=1)).isoformat(), enabled=False)
    later = event(start=(midnight + timedelta(hours=23)).isoformat(), enabled=True)
    outside = [event(start=at.isoformat(), enabled=False) for at in (
        midnight - timedelta(seconds=1), midnight + timedelta(days=1),
    )]
    other = django_user_model.objects.create_user(email="reset-other@example.com", password="test")
    foreign = event(start=midnight.isoformat(), enabled=False, owner=other)
    original_log = alarm_logs.record_switch(first, None, False)
    compute = Mock()
    monkeypatch.setattr(services, "compute_and_store", compute)

    response = client.post("/api/events", {
        "title": "새 일정", "start_at": (midnight + timedelta(hours=12)).isoformat(),
    }, format="json")

    assert response.status_code == 201
    first.refresh_from_db()
    later.refresh_from_db()
    assert first.alarm_enabled is None and later.alarm_enabled is None
    current = states(client)
    assert current[first.pk] == (True, True)
    assert current[later.pk] == (False, False)
    for untouched in [*outside, foreign]:
        untouched.refresh_from_db()
        assert untouched.alarm_enabled is False
    assert AlarmLog.objects.filter(pk=original_log.pk).exists()
    assert set(AlarmLog.objects.filter(kind=AlarmLog.Kind.AUTO).values_list("event_id", flat=True)) == {
        first.pk, later.pk,
    }
    compute.assert_called_once()  # 기존 일정 경로는 재조회하지 않는다.


@pytest.mark.integration
@pytest.mark.parametrize("failure", ["validation", "planning"])
def test_be25_failed_creation_preserves_existing_choices(client, event, monkeypatch, failure):
    original = event(enabled=False)
    monkeypatch.setattr(services, "compute_and_store", Mock(side_effect=RuntimeError("plan failure")))
    body = {"title": "" if failure == "validation" else "새 일정", "start_at": original.start_at.isoformat()}
    if failure == "validation":
        assert client.post("/api/events", body, format="json").status_code == 400
    else:
        with pytest.raises(RuntimeError, match="plan failure"):
            client.post("/api/events", body, format="json")
    original.refresh_from_db()
    assert original.alarm_enabled is False
    assert Event.objects.count() == 1
    assert not AlarmLog.objects.filter(kind=AlarmLog.Kind.AUTO).exists()


@pytest.mark.integration
def test_be25_calendar_import_preserves_manual_choices(client, event, monkeypatch):
    original = event(start=(timezone.now() + timedelta(days=2)).isoformat(), enabled=False)
    monkeypatch.setattr(services, "compute_and_store", Mock())
    response = client.post("/api/events/import", {"events": [{
        "external_id": "no-reset", "title": "캘린더 일정", "start_at": original.start_at.isoformat(),
    }]}, format="json")
    assert response.status_code == 200
    original.refresh_from_db()
    assert original.alarm_enabled is False


@pytest.mark.integration
def test_invalid_override_is_rejected_without_changing_event(client, event):
    instance = event()
    response = client.patch(f"/api/events/{instance.pk}", {"alarm_enabled": "unknown"}, format="json")
    assert response.status_code == 400
    assert "alarm_enabled" in response.data["error"]["details"]
    instance.refresh_from_db()
    assert instance.alarm_enabled is None


@pytest.mark.integration
@pytest.mark.parametrize("data", [
    {"start_at": "2026-10-05T12:00:00+09:00"},
    {"alarm_enabled": False, "start_at": "2026-10-05T12:00:00+09:00"},
    {"alarm_enabled": False, "place": None},
])
def test_regular_and_mixed_updates_still_recompute(client, event, monkeypatch, data):
    instance = event()
    compute = Mock()
    monkeypatch.setattr(services, "compute_and_store", compute)
    assert client.patch(f"/api/events/{instance.pk}", data, format="json").status_code == 200
    compute.assert_called_once()
    assert compute.call_args.args[0].pk == instance.pk


@pytest.mark.integration
def test_list_query_count_does_not_grow_per_event(client, event, django_assert_num_queries):
    # 반복 규칙 조회 1회 + 목록/count/그룹 판정. 일정 수가 늘어도 조회는 일정하다.
    event()
    with django_assert_num_queries(4):
        states(client)
    for hour in range(10, 15):
        event(start=f"2026-10-05T{hour}:00:00+09:00")
    with django_assert_num_queries(4):
        states(client)


def test_old_insert_without_alarm_column_stays_automatic(user):
    # 컬럼을 모르는 구버전 INSERT도 마이그레이션 뒤 계속 동작해야 한다.
    now = timezone.now()
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO events_event (user_id, source, title, start_at, created_at, updated_at) "
            "VALUES (%s, %s, %s, %s, %s, %s)",
            [user.pk, "manual", "구버전", now, now, now],
        )
    assert Event.objects.get(user=user, title="구버전").alarm_enabled is None


@pytest.mark.integration
def test_recomputed_alarm_order_updates_first_flag(client, event, monkeypatch):
    first = event()
    later = event(start="2026-10-05T10:00:00+09:00")

    def compute(instance, **kwargs):  # trigger= 를 받는다(계획 이력). (Partly AI-generated (Claude))
        AlarmPlan.objects.filter(event=instance).update(
            alarm_at=instance.start_at - timedelta(hours=1),
        )

    monkeypatch.setattr(services, "compute_and_store", compute)
    response = client.patch(
        f"/api/events/{later.pk}", {"start_at": "2026-10-05T08:00:00+09:00"}, format="json",
    )
    assert response.status_code == 200
    assert response.data["alarm_on"] is True
    assert response.data["is_first_alarm"] is True
    assert states(client) == {first.pk: (False, False), later.pk: (True, True)}
