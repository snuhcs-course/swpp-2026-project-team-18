# Partly AI-generated (Claude)
"""알람 기록(db-schema.md A3). 앱이 올리는 울림·끄기·미루기와 서버가 남기는 스위치 변경.

이동 관측 배치와 같은 규칙이다. 같은 `client_uuid` 는 저장하지 않고 `duplicated` 로
세고, 남의 일정이면 배치 전체가 404 다. 올리기 전에 지운 일정의 기록은 일정 없이
저장한다(`unlinked`). 기록이 실패해도 일정 수정은 성공한다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from django.db import DatabaseError, connection
from django.test.utils import CaptureQueriesContext
from rest_framework.test import APIClient

from apps.events.models import Event
from apps.observations import alarm_logs
from apps.observations.models import AlarmLog
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

BATCH_URL = "/api/alarm-logs/batch"
START = datetime(2026, 10, 5, 9, 0, tzinfo=KST)
ALARM = datetime(2026, 10, 5, 7, 0, tzinfo=KST)


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="alarmlog@example.com", password="Jb6-marble-thicket-84", nickname="알람"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def event(user):
    e = Event.objects.create(user=user, title="수업", start_at=START)
    AlarmPlan.objects.create(
        user=user, event=e, status=AlarmPlan.Status.OK, alarm_at=ALARM,
        depart_by=START - timedelta(minutes=40), arrive_at=START - timedelta(minutes=5),
        prep_minutes=60, travel_minutes=35, buffer_minutes=5,
    )
    return e


def log(event, uid, kind, *, at=ALARM, **kw):
    body = {
        "event": event.pk,
        "kind": kind,
        "occurred_at": at.isoformat(),
        "alarm_at": ALARM.isoformat(),
        "client_uuid": uid,
    }
    body.update(kw)
    return body


@pytest.mark.integration
class TestBatch:
    def test_stores_one_morning(self, client, event):
        logs = [
            log(event, "s1", "scheduled", at=ALARM - timedelta(hours=9)),
            log(event, "f1", "fired"),
            log(event, "z1", "snoozed", at=ALARM + timedelta(seconds=20), snooze_minutes=5),
            log(event, "f2", "fired", at=ALARM + timedelta(minutes=5)),
            log(event, "q1", "silenced", at=ALARM + timedelta(minutes=10)),
            log(event, "x1", "dismissed", at=ALARM + timedelta(minutes=11)),
        ]
        res = client.post(BATCH_URL, {"logs": logs}, format="json")

        assert res.status_code == 201
        assert res.data["accepted"] == 6
        assert res.data["duplicated"] == 0
        rows = list(AlarmLog.objects.order_by("occurred_at").values_list("kind", "source"))
        assert [k for k, _ in rows] == ["scheduled", "fired", "snoozed", "fired", "silenced", "dismissed"]
        assert {s for _, s in rows} == {"app"}
        assert AlarmLog.objects.get(client_uuid="z1").snooze_minutes == 5
        assert AlarmLog.objects.get(client_uuid="f1").alarm_at == ALARM

    def test_resend_is_counted_not_stored(self, client, event):
        client.post(BATCH_URL, {"logs": [log(event, "f1", "fired")]}, format="json")
        res = client.post(
            BATCH_URL,
            {"logs": [log(event, "f1", "dismissed"), log(event, "x1", "dismissed")]},
            format="json",
        )

        assert res.status_code == 201
        assert (res.data["accepted"], res.data["duplicated"]) == (1, 1)
        # 첫 값을 덮어쓰지 않는다.
        assert AlarmLog.objects.get(client_uuid="f1").kind == "fired"

    def test_only_duplicates_is_200(self, client, event):
        client.post(BATCH_URL, {"logs": [log(event, "f1", "fired")]}, format="json")
        res = client.post(BATCH_URL, {"logs": [log(event, "f1", "fired")]}, format="json")
        assert res.status_code == 200
        assert AlarmLog.objects.count() == 1

    def test_someone_elses_event_is_404(self, client, django_user_model):
        other = django_user_model.objects.create_user(
            email="alarmlog2@example.com", password="Wq3-golden-canyon-62", nickname="타인"
        )
        theirs = Event.objects.create(user=other, title="타인 일정", start_at=START)
        res = client.post(BATCH_URL, {"logs": [log(theirs, "f1", "fired")]}, format="json")

        assert res.status_code == 404
        assert not AlarmLog.objects.exists()

    def test_someone_elses_event_rejects_the_whole_batch(self, client, event, django_user_model):
        """내 일정 기록이 섞여 있어도 일부만 저장하지 않는다."""
        other = django_user_model.objects.create_user(
            email="alarmlog3@example.com", password="Wq3-golden-canyon-62", nickname="타인"
        )
        theirs = Event.objects.create(user=other, title="타인 일정", start_at=START)
        res = client.post(
            BATCH_URL,
            {"logs": [log(event, "f1", "fired"), log(theirs, "f2", "fired")]},
            format="json",
        )

        assert res.status_code == 404
        assert not AlarmLog.objects.exists()

    def test_deleted_event_keeps_the_rest_of_the_batch(self, client, user, event):
        """올리기 전에 지운 일정 하나 때문에 다른 일정의 기상 반응을 버리지 않는다(리뷰 #3).

        앱은 400·404 면 보낸 배치를 통째로 버린다. 지운 일정의 기록은 일정 없이 남긴다 —
        올린 뒤에 지웠을 때(SET_NULL)와 같은 모양이다.
        """
        gone = Event.objects.create(user=user, title="취소된 일정", start_at=START + timedelta(days=1))
        gone_body = log(gone, "g1", "scheduled")
        gone.delete()

        res = client.post(
            BATCH_URL,
            {"logs": [log(event, "f1", "fired"), gone_body, log(event, "x1", "dismissed")]},
            format="json",
        )

        assert res.status_code == 201
        assert (res.data["accepted"], res.data["duplicated"], res.data["unlinked"]) == (3, 0, 1)
        assert AlarmLog.objects.get(client_uuid="g1").event_id is None
        assert set(
            AlarmLog.objects.filter(event=event).values_list("client_uuid", flat=True)
        ) == {"f1", "x1"}

    def test_event_lookup_is_one_query(self, client, event):
        """일정 확인은 기록 수와 상관없이 한 번에 한다(배치 상한 100건)."""
        logs = [log(event, f"f{i}", "fired", at=ALARM + timedelta(minutes=i)) for i in range(20)]
        with CaptureQueriesContext(connection) as ctx:
            res = client.post(BATCH_URL, {"logs": logs}, format="json")

        assert res.status_code == 201
        event_reads = [q["sql"] for q in ctx.captured_queries if '"events_event"' in q["sql"]]
        assert len(event_reads) == 1, event_reads

    def test_requires_login(self, event):
        res = APIClient().post(BATCH_URL, {"logs": [log(event, "f1", "fired")]}, format="json")
        assert res.status_code == 401

    @pytest.mark.parametrize("kind", ["enabled", "disabled", "auto", "울림"])
    def test_app_cannot_send_switch_or_unknown_kinds(self, client, event, kind):
        """켬/끔/자동은 서버가 일정 수정에서 직접 남긴다. 두 길로 들어오지 않게 막는다."""
        res = client.post(BATCH_URL, {"logs": [log(event, "k1", kind)]}, format="json")
        assert res.status_code == 400

    @pytest.mark.parametrize(
        "kind, minutes",
        [("snoozed", None), ("snoozed", 0), ("snoozed", 61), ("fired", 5), ("dismissed", 5)],
    )
    def test_snooze_minutes_only_on_snoozes(self, client, event, kind, minutes):
        body = log(event, "m1", kind)
        if minutes is not None:
            body["snooze_minutes"] = minutes
        res = client.post(BATCH_URL, {"logs": [body]}, format="json")
        assert res.status_code == 400

    def test_rejects_empty_and_blank_keys(self, client, event):
        assert client.post(BATCH_URL, {"logs": []}, format="json").status_code == 400
        res = client.post(BATCH_URL, {"logs": [log(event, "   ", "fired")]}, format="json")
        assert res.status_code == 400

    def test_alarm_at_is_optional(self, client, event):
        body = log(event, "f1", "fired")
        del body["alarm_at"]
        assert client.post(BATCH_URL, {"logs": [body]}, format="json").status_code == 201
        assert AlarmLog.objects.get(client_uuid="f1").alarm_at is None

    def test_rows_outlive_the_event(self, client, event):
        """일정이 지워져도 기상 반응은 남긴다(SET_NULL, 블록 관측과 같은 규칙)."""
        client.post(BATCH_URL, {"logs": [log(event, "f1", "fired")]}, format="json")
        event.delete()
        row = AlarmLog.objects.get(client_uuid="f1")
        assert row.event_id is None


class TestServerSwitch:
    def patch(self, client, event, value):
        res = client.patch(f"/api/events/{event.pk}", {"alarm_enabled": value}, format="json")
        assert res.status_code == 200
        return res

    @pytest.mark.integration
    def test_switch_changes_are_logged_by_the_server(self, client, event):
        self.patch(client, event, False)
        self.patch(client, event, True)
        self.patch(client, event, None)

        rows = list(AlarmLog.objects.order_by("id"))
        assert [r.kind for r in rows] == ["disabled", "enabled", "auto"]
        assert {r.source for r in rows} == {"server"}
        assert {r.alarm_at for r in rows} == {ALARM}
        assert all(r.client_uuid.startswith("srv-") for r in rows)

    @pytest.mark.integration
    def test_same_value_is_not_logged(self, client, event):
        self.patch(client, event, None)  # 처음부터 자동이다
        assert not AlarmLog.objects.exists()

    @pytest.mark.integration
    def test_other_edits_are_not_logged(self, client, event, monkeypatch):
        from apps.planning import services

        monkeypatch.setattr(services, "compute_and_store", lambda *a, **kw: None)
        res = client.patch(f"/api/events/{event.pk}", {"title": "바뀐 제목"}, format="json")
        assert res.status_code == 200
        assert not AlarmLog.objects.exists()

    @pytest.mark.integration
    def test_log_failure_keeps_the_edit(self, client, event, monkeypatch):
        """기록은 학습 재료다. 사용자가 지금 바꾸려는 스위치를 막지 않는다."""

        def broken(*args, **kwargs):
            raise DatabaseError("table missing")

        monkeypatch.setattr(AlarmLog.objects, "create", broken)
        res = self.patch(client, event, False)

        assert res.data["alarm_enabled"] is False
        event.refresh_from_db()
        assert event.alarm_enabled is False

    def test_switch_kind_mapping(self):
        assert alarm_logs.switch_kind(True) == AlarmLog.Kind.ENABLED
        assert alarm_logs.switch_kind(False) == AlarmLog.Kind.DISABLED
        assert alarm_logs.switch_kind(None) == AlarmLog.Kind.AUTO
