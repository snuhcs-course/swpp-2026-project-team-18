# Partly AI-generated (Claude)
"""일정 "안 감" 표시(`Event.skipped_at`, `POST/DELETE /api/events/{id}/skip`).

안 간 아침은 도착 기록이 없어서 리포트에 "측정 못 함" 으로 섞였고, 그날 눌린 블록
기록·출발 관측이 학습에 들어갔다. 표시한 일정은 리포트·학습·버퍼 조정에서 빠지고
알람이 울리지 않는다. 계획과 관측은 지우지 않는다(되돌리면 다시 쓴다).

계약 문서는 docs/iter3/skip-event-api.md.
"""

from __future__ import annotations

from datetime import datetime, timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.alarms import alarm_states
from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import buffers, history, prep_observations, services
from apps.planning.models import AlarmPlan, PlanRevision
from apps.prediction import learning
from apps.reports import services as reports
from apps.routines.models import BlockObservation, RoutineBlock

pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 · 20분",
    "key": "transit:2호선",
    "detail": "2호선",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="skip@example.com", password="Rv4-amber-thicket-62", nickname="안감"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.timezone = "Asia/Seoul"
    p.save()
    return u


@pytest.fixture
def client(user):
    api = APIClient()
    api.force_authenticate(user)
    return api


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture
def route(monkeypatch):
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(ROUTE), False

    def resolve_route(key, **kw):
        calls.append("resolve")
        return dict(ROUTE, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return calls


def planned(user, place, start: datetime, *, title="수업", probability=95, **kw) -> Event:
    """`start` 에 시작하는 일정 + OK 계획(알람 1시간 10분 전, 버퍼 10분)."""
    event = Event.objects.create(user=user, place=place, title=title, start_at=start, **kw)
    AlarmPlan.objects.create(
        user=user, event=event, status=AlarmPlan.Status.OK,
        alarm_at=start - timedelta(minutes=70), depart_by=start - timedelta(minutes=30),
        arrive_at=start - timedelta(minutes=10), prep_minutes=40, travel_minutes=20,
        buffer_minutes=10, tau_used=0.9, on_time_probability=probability,
    )
    return event


def skip(event: Event) -> Event:
    Event.objects.filter(pk=event.pk).update(skipped_at=timezone.now())
    event.refresh_from_db()
    return event


def observe(event, kind, at, *, detector="gps", accuracy=10.0):
    return TripObservation.objects.create(
        user=event.user, event=event, kind=kind, detector=detector, observed_at=at,
        lat=37.46, lng=126.95, accuracy_m=accuracy, distance_m=30.0,
        client_uuid=f"{kind}-{event.pk}",
    )


def tomorrow(hour: int) -> datetime:
    day = timezone.localdate() + timedelta(days=1)
    return timezone.make_aware(datetime.combine(day, datetime.min.time())) + timedelta(hours=hour)


def days_ago(days: int, hour: int = 9) -> datetime:
    return (timezone.now() - timedelta(days=days)).replace(
        hour=hour, minute=0, second=0, microsecond=0
    )


def last_week(day: int, hour: int = 9) -> datetime:
    monday, _ = reports.week_bounds(timezone.localdate() - timedelta(weeks=1))
    return monday + timedelta(days=day, hours=hour)


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------


@pytest.mark.integration
class TestApi:
    def test_marks_and_returns_the_event(self, client, user, place):
        event = planned(user, place, tomorrow(9))
        before = timezone.now()

        response = client.post(f"/api/events/{event.pk}/skip")

        assert response.status_code == 200
        assert response.data["id"] == event.pk
        assert response.data["skipped"] is True
        assert response.data["alarm_on"] is False
        marked = datetime.fromisoformat(response.data["skipped_at"])
        assert before <= marked <= timezone.now()
        assert Event.objects.get(pk=event.pk).skipped_at == marked

    def test_twice_keeps_the_first_time(self, client, user, place):
        """앱의 재시도·두 번 누름. 처음 표시한 시각이 남는다."""
        event = planned(user, place, days_ago(1))
        first = client.post(f"/api/events/{event.pk}/skip").data["skipped_at"]
        second = client.post(f"/api/events/{event.pk}/skip")
        assert second.status_code == 200
        assert second.data["skipped_at"] == first

    def test_delete_undoes_and_is_repeatable(self, client, user, place):
        event = planned(user, place, tomorrow(9))
        client.post(f"/api/events/{event.pk}/skip")

        undone = client.delete(f"/api/events/{event.pk}/skip")
        again = client.delete(f"/api/events/{event.pk}/skip")

        assert undone.status_code == again.status_code == 200
        assert (undone.data["skipped"], undone.data["skipped_at"]) == (False, None)
        assert undone.data["alarm_on"] is True
        assert again.data["skipped"] is False
        assert Event.objects.get(pk=event.pk).skipped_at is None

    def test_other_users_event_is_404(self, client, place, django_user_model):
        other = django_user_model.objects.create_user(
            email="other-skip@example.com", password="Rv4-amber-thicket-63", nickname="남"
        )
        event = planned(other, place, tomorrow(9))
        assert client.post(f"/api/events/{event.pk}/skip").status_code == 404
        assert client.delete(f"/api/events/{event.pk}/skip").status_code == 404
        assert Event.objects.get(pk=event.pk).skipped_at is None

    def test_unknown_event_is_404(self, client):
        assert client.post("/api/events/999999/skip").status_code == 404

    def test_needs_login(self, user, place):
        event = planned(user, place, tomorrow(9))
        assert APIClient().post(f"/api/events/{event.pk}/skip").status_code == 401
        assert Event.objects.get(pk=event.pk).skipped_at is None

    def test_other_methods_are_not_allowed(self, client, user, place):
        event = planned(user, place, tomorrow(9))
        assert client.get(f"/api/events/{event.pk}/skip").status_code == 405
        assert client.patch(f"/api/events/{event.pk}/skip", {}).status_code == 405

    def test_patch_cannot_set_it(self, client, user, place, route):
        """바꾸는 곳은 skip 엔드포인트뿐이다. 수정 요청에 실려 와도 무시한다."""
        event = planned(user, place, tomorrow(9))
        response = client.patch(
            f"/api/events/{event.pk}",
            {"skipped": True, "skipped_at": timezone.now().isoformat()},
            format="json",
        )
        assert response.status_code == 200
        assert response.data["skipped"] is False
        assert Event.objects.get(pk=event.pk).skipped_at is None

    def test_list_shows_both_fields(self, client, user, place):
        going = planned(user, place, tomorrow(9))
        skipped = skip(planned(user, place, tomorrow(13)))
        rows = {row["id"]: row for row in client.get("/api/events").data["results"]}
        assert (rows[going.pk]["skipped"], rows[going.pk]["skipped_at"]) == (False, None)
        assert rows[skipped.pk]["skipped"] is True
        assert rows[skipped.pk]["skipped_at"] is not None

    def test_keeps_the_plan_and_history(self, client, user, place):
        """되돌리면 같은 계획을 그대로 쓴다. 다시 계산하지 않는다(카카오 0회)."""
        event = planned(user, place, tomorrow(9))
        plan = AlarmPlan.objects.get(event=event)
        revisions = PlanRevision.objects.filter(event=event).count()

        client.post(f"/api/events/{event.pk}/skip")
        client.delete(f"/api/events/{event.pk}/skip")

        after = AlarmPlan.objects.get(event=event)
        assert (after.pk, after.alarm_at, after.computed_at) == (
            plan.pk, plan.alarm_at, plan.computed_at,
        )
        assert PlanRevision.objects.filter(event=event).count() == revisions

    def test_observations_for_a_skipped_event_are_still_accepted(self, client, user, place):
        """앱 큐가 나중에 올리는 관측을 거절하면 배치가 통째로 막힌다. 받고, 읽을 때 뺀다."""
        event = skip(planned(user, place, days_ago(1)))
        response = client.post("/api/observations/batch", {"observations": [{
            "event": event.pk, "kind": "depart", "detector": "gps",
            "observed_at": (event.start_at - timedelta(minutes=30)).isoformat(),
            "lat": 37.48, "lng": 126.93, "accuracy_m": 10.0, "distance_m": 60.0,
            "client_uuid": "dep-skipped",
        }]}, format="json")
        assert response.status_code == 201
        assert response.data["accepted"] == 1


# ---------------------------------------------------------------------------
# 알람
# ---------------------------------------------------------------------------


class TestAlarm:
    def test_skipped_event_does_not_ring_and_the_next_one_becomes_first(self, user, place):
        morning = planned(user, place, tomorrow(9))
        afternoon = planned(user, place, tomorrow(13))
        assert alarm_states(user)[morning.pk] == (True, True)
        assert alarm_states(user)[afternoon.pk] == (False, False)

        skip(morning)

        states = alarm_states(user)
        assert morning.pk not in states
        assert states[afternoon.pk] == (True, True)

    def test_explicitly_enabled_alarm_is_also_silenced(self, user, place):
        event = skip(planned(user, place, tomorrow(9), alarm_enabled=True))
        assert event.pk not in alarm_states(user)

    def test_undo_restores_the_rule(self, user, place):
        morning = skip(planned(user, place, tomorrow(9)))
        afternoon = planned(user, place, tomorrow(13))
        Event.objects.filter(pk=morning.pk).update(skipped_at=None)
        states = alarm_states(user)
        assert states[morning.pk] == (True, True)
        assert states[afternoon.pk] == (False, False)


# ---------------------------------------------------------------------------
# 리포트·버퍼
# ---------------------------------------------------------------------------


class TestReport:
    def test_weekly_leaves_skipped_mornings_out_and_counts_them(self, user, place):
        went = planned(user, place, last_week(0))
        observe(went, TripObservation.Kind.ARRIVE, went.start_at - timedelta(minutes=5))
        # 안 갔다. 도착 기록이 없어 지금까지는 "측정 못 함" 으로 섞였다.
        skip(planned(user, place, last_week(1)))
        # 나갔다가 돌아와서 늦은 도착이 찍힌 아침. 지각으로 세면 안 된다.
        turned_back = skip(planned(user, place, last_week(2)))
        observe(turned_back, TripObservation.Kind.ARRIVE, turned_back.start_at + timedelta(minutes=40))

        week = reports.weekly(user, anchor=last_week(0).date())

        assert week["event_count"] == 1
        assert week["unobserved_count"] == 0
        assert week["late_count"] == 0
        assert week["skipped_count"] == 2
        assert [row["event"] for row in week["outcomes"]] == [went.pk]
        assert week["calibration"]["scored_count"] == 1

    def test_skipped_count_only_counts_past_events_of_that_week(self, user, place):
        skip(planned(user, place, last_week(1)))
        skip(planned(user, place, last_week(-1)))  # 그 앞 주 일요일
        upcoming = skip(planned(user, place, timezone.now() + timedelta(hours=2)))

        assert reports.weekly(user, anchor=last_week(0).date())["skipped_count"] == 1
        # 그 앞 주는 일요일 것 하나만. 다음 주(지난 주) 것은 세지 않는다.
        before = reports.weekly(user, anchor=(last_week(0) - timedelta(weeks=1)).date())
        assert before["skipped_count"] == 1
        this_week = reports.weekly(user, anchor=timezone.localtime(upcoming.start_at).date())
        assert this_week["skipped_count"] == 0

    def test_calibration_and_causes_ignore_skipped(self, user, place):
        late = skip(planned(user, place, days_ago(3)))
        observe(late, TripObservation.Kind.ARRIVE, late.start_at + timedelta(minutes=15))
        assert reports.calibration(user)["scored_count"] == 0
        assert reports.lateness_causes(user) == []

    def test_previous_week_ignores_skipped(self, user, place):
        skipped = skip(planned(user, place, last_week(1)))
        observe(skipped, TripObservation.Kind.ARRIVE, skipped.start_at + timedelta(minutes=15))
        anchor = (last_week(0) + timedelta(weeks=1)).date()
        assert reports.weekly(user, anchor=anchor)["previous_week"] is None

    def test_buffer_ignores_skipped_mornings(self, user, place):
        for days in range(1, 4):
            event = planned(user, place, days_ago(days))
            observe(event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=20))
        assert len(buffers.recent_mornings(user, None)) == 3
        skip(Event.objects.filter(user=user).first())
        assert len(buffers.recent_mornings(user, None)) == 2


@pytest.mark.integration
def test_weekly_api_has_skipped_count(client, user, place):
    skip(planned(user, place, last_week(1)))
    monday = last_week(0).date().isoformat()
    response = client.get(f"/api/reports/weekly?week={monday}")
    assert response.status_code == 200
    assert response.data["skipped_count"] == 1
    assert response.data["event_count"] == 0


# ---------------------------------------------------------------------------
# 학습
# ---------------------------------------------------------------------------


def trip(user, place, depart_at: datetime, *, actual=25, predicted=20) -> Event:
    """경로 원값 이력과 GPS 출발·도착 쌍이 있는 아침(이동 학습 표본 하나)."""
    event = Event.objects.create(
        user=user, place=place, title="이동", start_at=depart_at + timedelta(minutes=predicted + 30)
    )
    plan = AlarmPlan.objects.create(
        user=user, event=event, status=AlarmPlan.Status.OK,
        alarm_at=depart_at - timedelta(minutes=30), depart_by=depart_at,
        arrive_at=depart_at + timedelta(minutes=predicted), prep_minutes=30,
        travel_minutes=predicted, buffer_minutes=10, travel_mode="transit", route_key="transit:2호선",
    )
    plan.computed_at = depart_at - timedelta(minutes=1)
    history.record_revision(event, plan, route_minutes_raw=predicted)
    observe(event, TripObservation.Kind.DEPART, depart_at)
    observe(event, TripObservation.Kind.ARRIVE, depart_at + timedelta(minutes=actual))
    return event


def blocks(user, event, *minutes: float, name="샤워", slack=None) -> RoutineBlock:
    block, _ = RoutineBlock.objects.get_or_create(
        user=user, name=name, defaults={"default_min_minutes": 10, "default_max_minutes": 20}
    )
    for index, value in enumerate(minutes):
        BlockObservation.objects.create(
            user=user, block=block, event=event, observed_on=timezone.localdate(),
            duration_minutes=value, slack_minutes=slack,
            client_uuid=f"blk-{event.pk if event else 'none'}-{name}-{index}",
            client_recorded_at=timezone.now(),
        )
    return block


class TestLearning:
    def test_travel_samples_leave_skipped_trips_out_and_say_why(self, user, place):
        trip(user, place, days_ago(2, 8))
        skip(trip(user, place, days_ago(1, 8), actual=70))

        samples, dropped = learning.collect_travel_samples(user=user)

        assert [s.actual_minutes for s in samples] == [25.0]
        assert dropped["안 감"] == 2  # 출발·도착 관측 두 건

    def test_gps_prep_leaves_skipped_mornings_out(self, user, place):
        for days, prep in ((2, 35), (1, 90)):
            event = planned(user, place, days_ago(days))
            AlarmLog.objects.create(
                user=user, event=event, kind=AlarmLog.Kind.DISMISSED,
                occurred_at=event.start_at - timedelta(minutes=120), client_uuid=f"dis-{days}",
            )
            observe(event, TripObservation.Kind.DEPART,
                    event.start_at - timedelta(minutes=120 - prep))
        assert sorted(prep_observations.gps_prep_samples(user.pk)) == [35.0, 90.0]
        skip(Event.objects.get(user=user, start_at=days_ago(1)))
        assert prep_observations.gps_prep_samples(user.pk) == [35.0]

    def test_block_records_of_skipped_mornings_are_left_out(self, user, place):
        went = planned(user, place, days_ago(2))
        skipped = skip(planned(user, place, days_ago(1)))
        block = blocks(user, went, 12, 14)
        blocks(user, skipped, 19)

        stats = services.prep_inputs(user.pk)[0]
        assert stats[block.pk][0] == pytest.approx(13.0)
        assert stats[block.pk][2] == 2
        assert learning.block_stats(user=user)["샤워"]["n"] == 2
        assert learning.prep_total_stats(user=user)["n"] == 1  # 같은 날 두 일정의 합 하나
        kept = prep_observations.usable_block_observations(BlockObservation.objects.all())
        assert set(kept.values_list("event_id", flat=True)) == {went.pk}

    def test_records_without_an_event_are_kept(self, user):
        blocks(user, None, 15)
        kept = prep_observations.usable_block_observations(BlockObservation.objects.all())
        assert kept.count() == 1

    def test_slack_coefficient_ignores_skipped_mornings(self, user, place):
        went = planned(user, place, days_ago(2))
        for index in range(8):
            blocks(user, went, 10 + index, name=f"블록{index}", slack=float(index))
        coef = learning.estimate_slack_coef(user=user)
        skipped = skip(planned(user, place, days_ago(1)))
        blocks(user, skipped, 20, name="블록0", slack=100.0)
        assert learning.estimate_slack_coef(user=user) == coef
