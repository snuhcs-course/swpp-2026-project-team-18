# Partly AI-generated (Claude)
"""지각 이력으로 안전 버퍼 조정(`planning.buffers`).

버퍼는 지각 횟수가 아니라 **계획이 약속한 정시 확률과 실제의 차이**를 본다. 약속한 만큼의
지각은 τ 가 맡은 몫이라 버퍼를 움직이지 않는다.
"""

from __future__ import annotations

from datetime import timedelta
from io import StringIO

import pytest
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import buffers, services
from apps.planning.buffers import Buffer, Morning
from apps.planning.models import AlarmPlan, BufferAdjustment, PlanRevision
from apps.reports import services as reports

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
FIXED = Buffer(10, buffers.SOURCE_FIXED, None)


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="buffer@example.com", password="Kp5-silver-orchard-27", nickname="버퍼"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture
def route(monkeypatch):
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


def morning(user, place, *, days_ago, slack, probability=95, buffer=10, decided_days_ago=None):
    """지난 아침. 결정 이력·알람 울림·도착을 남긴다. `slack` 은 일정 시작 − 도착(분)."""
    start = (timezone.now() - timedelta(days=days_ago)).replace(
        hour=9, minute=0, second=0, microsecond=0
    )
    event = Event.objects.create(user=user, place=place, title="수업", start_at=start)
    alarm_at = start - timedelta(minutes=60 + buffer)
    fields = dict(
        status=AlarmPlan.Status.OK, alarm_at=alarm_at, depart_by=start - timedelta(minutes=20 + buffer),
        arrive_at=start - timedelta(minutes=buffer), prep_minutes=40, travel_minutes=20,
        buffer_minutes=buffer, tau_used=0.9, on_time_probability=probability,
    )
    AlarmPlan.objects.create(user=user, event=event, **fields)
    decided_at = (
        alarm_at - timedelta(hours=1)
        if decided_days_ago is None
        else timezone.now() - timedelta(days=decided_days_ago)
    )
    PlanRevision.objects.create(
        user=user, event=event, computed_at=decided_at, event_start_at=start, **fields
    )
    AlarmLog.objects.create(
        user=user, event=event, kind=AlarmLog.Kind.FIRED, occurred_at=alarm_at,
        client_uuid=f"fired-{event.pk}",
    )
    TripObservation.objects.create(
        user=user, event=event, kind=TripObservation.Kind.ARRIVE,
        detector=TripObservation.Detector.GPS, observed_at=start - timedelta(minutes=slack),
        lat=37.46, lng=126.95, accuracy_m=10.0, distance_m=30.0,
        client_uuid=f"arrive-{event.pk}",
    )
    return event


def mornings(slacks, *, promise=0.95, buffer=10):
    return [Morning(slack=s, buffer=buffer, promise=promise) for s in slacks]


def adjusted(user, *, minutes, days_ago=0, previous=10):
    row = BufferAdjustment.objects.create(
        user=user, previous_minutes=previous, minutes=minutes,
        reason=(
            BufferAdjustment.Reason.LATE_MORE_THAN_PROMISED
            if minutes > previous else BufferAdjustment.Reason.BUFFER_UNUSED
        ),
        sample_count=6, late_count=3, expected_late=0.3, mean_slack_minutes=4.2,
        tightest_slack_minutes=-6.0, median_late_minutes=4.0,
    )
    if days_ago:
        BufferAdjustment.objects.filter(pk=row.pk).update(
            created_at=timezone.now() - timedelta(days=days_ago)
        )
        row.refresh_from_db()
    return row


def future_event(user, place, *, hours_ahead=30) -> Event:
    start = (timezone.now() + timedelta(hours=hours_ahead)).replace(second=0, microsecond=0)
    event = Event.objects.create(user=user, place=place, title="내일 수업", start_at=start)
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    return event


class TestCurrent:
    def test_default_is_the_fixed_ten_minutes(self, user):
        assert buffers.current(user.pk) == FIXED

    def test_latest_adjustment_wins(self, user):
        adjusted(user, minutes=14, days_ago=3)
        latest = adjusted(user, minutes=12, previous=14)

        buffer = buffers.current(user.pk)

        assert (buffer.minutes, buffer.source, buffer.since) == (
            12, buffers.SOURCE_LEARNED, latest.created_at,
        )


class TestDecide:
    def test_too_few_mornings_keep_the_buffer(self):
        decision = buffers.decide(FIXED, mornings([-10, -10]))

        assert (decision.outcome, decision.minutes) == (buffers.KEEP_FEW, 10)

    def test_more_lateness_than_promised_raises_by_the_median_lateness(self):
        decision = buffers.decide(FIXED, mornings([-4, -6, -2, 12, 15, 11]))

        assert decision.outcome == buffers.INCREASED
        assert decision.minutes == 14
        assert decision.late_count == 3
        assert decision.expected_late == pytest.approx(0.3)
        assert decision.median_late == 4
        assert decision.tightest_slack == -6
        assert decision.mean_slack == pytest.approx(4.3)

    def test_lateness_the_model_promised_is_left_to_tau(self):
        """정시 70%를 약속한 10번 중 3번 지각은 약속대로다. 버퍼가 또 막으면 이중 반영이다."""
        decision = buffers.decide(
            FIXED, mornings([-3, -5, -2, 4, 8, 12, 9, 15, 6, 11], promise=0.7)
        )

        assert decision.outcome == buffers.KEEP_ON_TRACK
        assert decision.minutes == 10
        assert decision.expected_late == pytest.approx(3.0)

    def test_one_late_morning_never_raises(self):
        decision = buffers.decide(FIXED, mornings([-20, 12, 15, 11, 13], promise=0.99))

        assert decision.outcome == buffers.KEEP_ON_TRACK

    def test_raise_is_capped_per_step_and_overall(self):
        step = buffers.decide(FIXED, mornings([-9, -12, -9, 12, 15]))
        top = buffers.decide(Buffer(13, buffers.SOURCE_LEARNED, None), mornings([-9, -12, -9, 12, 15]))
        stuck = buffers.decide(Buffer(15, buffers.SOURCE_LEARNED, None), mornings([-9, -12, -9, 12, 15]))

        assert (step.outcome, step.minutes) == (buffers.INCREASED, 15)
        assert (top.outcome, top.minutes) == (buffers.INCREASED, buffers.MAX_MINUTES)
        assert (stuck.outcome, stuck.minutes) == (buffers.KEEP_AT_LIMIT, 15)

    def test_a_buffer_never_used_shrinks(self):
        """모든 아침이 계획한 도착(시작 − 10분)보다 먼저 왔다."""
        decision = buffers.decide(FIXED, mornings([12, 14, 20, 11, 10]))

        assert (decision.outcome, decision.minutes) == (buffers.DECREASED, 8)

    def test_three_mornings_are_enough(self):
        raised = buffers.decide(FIXED, mornings([-3, -5, 12]))
        lowered = buffers.decide(FIXED, mornings([12, 14, 11]))

        assert (raised.outcome, raised.minutes) == (buffers.INCREASED, 14)
        assert (lowered.outcome, lowered.minutes) == (buffers.DECREASED, 8)

    def test_one_late_morning_blocks_shrinking(self):
        """나머지가 다 넉넉해도 한 번 늦었으면 줄이지 않는다(늘리지도 않는다)."""
        decision = buffers.decide(FIXED, mornings([-2, 20, 18, 25, 15]))

        assert (decision.outcome, decision.minutes) == (buffers.KEEP_ON_TRACK, 10)

    def test_a_buffer_used_once_stays(self):
        """지각은 없지만 한 번은 버퍼 안으로 들어왔다(시작 6분 전 도착)."""
        decision = buffers.decide(FIXED, mornings([12, 14, 6, 11, 10]))

        assert decision.outcome == buffers.KEEP_ON_TRACK

    def test_shrink_stops_at_four_minutes(self):
        five = buffers.decide(Buffer(5, buffers.SOURCE_LEARNED, None), mornings([8] * 5, buffer=5))
        floor = buffers.decide(Buffer(4, buffers.SOURCE_LEARNED, None), mornings([8] * 5, buffer=4))

        assert (five.outcome, five.minutes) == (buffers.DECREASED, 4)
        assert (floor.outcome, floor.minutes) == (buffers.KEEP_AT_LIMIT, 4)


class TestRecentMornings:
    def test_uses_the_plan_the_user_followed(self, user, place):
        """알람이 울린 뒤 다시 계산된 계획은 사용자가 따른 계획이 아니다."""
        event = morning(user, place, days_ago=1, slack=-5, probability=95)
        PlanRevision.objects.create(
            user=user, event=event, computed_at=event.start_at - timedelta(minutes=10),
            event_start_at=event.start_at, status=AlarmPlan.Status.OK,
            buffer_minutes=10, on_time_probability=40, tau_used=0.9,
        )
        AlarmPlan.objects.filter(event=event).update(on_time_probability=40)

        [found] = buffers.recent_mornings(user, None)

        assert found == Morning(slack=-5, buffer=10, promise=0.95)

    def test_without_a_probability_tau_is_the_promise(self, user, place):
        event = morning(user, place, days_ago=1, slack=3, probability=None)

        [found] = buffers.recent_mornings(user, None)

        assert found.promise == 0.9
        assert event.pk

    def test_newest_first_and_at_most_ten(self, user, place):
        for day in range(1, 13):
            morning(user, place, days_ago=day, slack=day)

        found = buffers.recent_mornings(user, None)

        assert [m.slack for m in found] == list(range(1, 11))

    def test_mornings_without_an_arrival_are_skipped(self, user, place):
        morning(user, place, days_ago=1, slack=3)
        TripObservation.objects.filter(user=user).delete()

        assert buffers.recent_mornings(user, None) == []

    def test_only_mornings_decided_after_the_last_adjustment(self, user, place):
        """같은 지각으로 밤마다 버퍼를 또 늘리지 않는다."""
        for day in range(4, 10):
            morning(user, place, days_ago=day, slack=-6)
        adjusted(user, minutes=14, days_ago=3)
        morning(user, place, days_ago=1, slack=-6, buffer=14)

        found = buffers.recent_mornings(user, buffers.current(user.pk).since)
        decision = buffers.evaluate(user)

        assert [m.buffer for m in found] == [14]
        assert (decision.outcome, decision.minutes) == (buffers.KEEP_FEW, 14)

    def test_a_morning_decided_before_the_adjustment_is_skipped(self, user, place):
        """조정 뒤에 시작했어도 계획을 그 전에 정했으면 옛 버퍼의 아침이다.

        밤 재계산은 3시간 안에 울릴 알람을 건드리지 않는다. 그런 아침은 옛 버퍼로 울린다.
        """
        adjusted(user, minutes=14, days_ago=3)
        morning(user, place, days_ago=1, slack=-6, decided_days_ago=4)
        morning(user, place, days_ago=2, slack=-3, buffer=14)

        found = buffers.recent_mornings(user, buffers.current(user.pk).since)

        assert [m.slack for m in found] == [-3]


class TestAdjustAll:
    def test_lateness_raises_the_buffer_and_the_next_alarm_uses_it(self, user, place, route):
        for day, slack in enumerate([-4, -6, -2, 12, 15, 11], start=1):
            morning(user, place, days_ago=day, slack=slack)

        outcomes = buffers.adjust_all()
        event = future_event(user, place)

        row = BufferAdjustment.objects.get(user=user)
        plan = AlarmPlan.objects.get(event=event)
        assert outcomes == {buffers.INCREASED: 1}
        assert (row.previous_minutes, row.minutes, row.reason) == (
            10, 14, BufferAdjustment.Reason.LATE_MORE_THAN_PROMISED,
        )
        assert (row.sample_count, row.late_count, row.median_late_minutes) == (6, 3, 4)
        assert plan.buffer_minutes == 14
        assert plan.buffer_source == buffers.SOURCE_LEARNED
        assert plan.arrive_at == event.start_at - timedelta(minutes=14)

    def test_dry_run_saves_nothing(self, user, place):
        for day, slack in enumerate([-4, -6, -2, 12, 15, 11], start=1):
            morning(user, place, days_ago=day, slack=slack)

        assert buffers.adjust_all(dry_run=True) == {buffers.INCREASED: 1}
        assert not BufferAdjustment.objects.exists()

    def test_unchanged_buffer_leaves_no_row(self, user, place):
        for day in range(1, 3):
            morning(user, place, days_ago=day, slack=-6)

        assert buffers.adjust_all() == {buffers.KEEP_FEW: 1}
        assert not BufferAdjustment.objects.exists()

    def test_users_without_arrivals_are_not_visited(self, user):
        assert buffers.adjust_all() == {}

    def test_plans_say_fixed_until_adjusted(self, user, place, route):
        plan = AlarmPlan.objects.get(event=future_event(user, place))

        assert (plan.buffer_minutes, plan.buffer_source) == (10, buffers.SOURCE_FIXED)


class TestTrainModels:
    def test_training_adjusts_then_refreshes_upcoming_alarms(self, user, place, route):
        event = future_event(user, place)
        before = AlarmPlan.objects.get(event=event)
        for day, slack in enumerate([-4, -6, -2, 12, 15, 11], start=1):
            morning(user, place, days_ago=day, slack=slack)

        out = StringIO()
        call_command("train_models", stdout=out)

        after = AlarmPlan.objects.get(event=event)
        text = out.getvalue()
        assert "[4] 안전 버퍼 조정(지각 이력)" in text
        assert f"{buffers.INCREASED} 1명 / {buffers.DECREASED} 0명" in text
        assert "[5] 앞으로 7일 알람 재계산" in text
        assert "바뀜 1건" in text
        assert after.alarm_at == before.alarm_at - timedelta(minutes=4)
        assert after.buffer_source == buffers.SOURCE_LEARNED
        # 공개 로그다. 사용자 정보를 쓰지 않는다.
        assert user.email not in text

    def test_routes_only_leaves_the_buffer(self, user, place):
        for day, slack in enumerate([-4, -6, -2, 12, 15, 11], start=1):
            morning(user, place, days_ago=day, slack=slack)

        out = StringIO()
        call_command("train_models", "--routes-only", stdout=out)

        assert "안전 버퍼" not in out.getvalue()
        assert not BufferAdjustment.objects.exists()


class TestReport:
    def test_weekly_report_says_why_the_buffer_moved(self, user):
        # 주간 리포트는 기본으로 지난주다. 7일 전은 늘 지난주 안이다.
        row = adjusted(user, minutes=14, days_ago=7)

        section = reports.weekly(user)["buffer"]

        assert section["current_minutes"] == 14
        assert section["current_source"] == buffers.SOURCE_LEARNED
        [item] = section["adjustments"]
        assert item["previous_minutes"] == 10
        assert item["minutes"] == 14
        assert item["reason"] == BufferAdjustment.Reason.LATE_MORE_THAN_PROMISED
        assert item["adjusted_at"] == reports._iso(row.created_at)
        assert item["reason_text"] == (
            "최근 6번 중 3번 늦었음. 알람이 약속한 정시 확률로는 0.3번 정도였음. "
            "늦은 시간의 중앙값 4분을 보고 버퍼를 10분에서 14분으로 늘림."
        )

    def test_shrink_reason(self, user):
        row = BufferAdjustment.objects.create(
            user=user, previous_minutes=10, minutes=8,
            reason=BufferAdjustment.Reason.BUFFER_UNUSED, sample_count=5, late_count=0,
            expected_late=0.25, mean_slack_minutes=13.4, tightest_slack_minutes=10.4,
        )

        assert buffers.reason_text(row) == (
            "최근 5번 모두 계획한 도착 시각보다 먼저 도착했음(가장 빠듯했을 때도 10분 일찍 도착). "
            "버퍼를 10분에서 8분으로 줄임."
        )

    def test_other_weeks_do_not_show_the_adjustment(self, user):
        adjusted(user, minutes=14, days_ago=14)

        section = reports.weekly(user)["buffer"]

        assert section["adjustments"] == []
        assert section["current_minutes"] == 14

    def test_no_adjustment_reports_the_fixed_default(self, user):
        assert reports.weekly(user)["buffer"] == {
            "current_minutes": 10, "current_source": buffers.SOURCE_FIXED, "adjustments": [],
        }


@pytest.mark.integration
class TestApi:
    def test_plan_says_learned_after_an_adjustment(self, user, place, route):
        adjusted(user, minutes=14)
        event = future_event(user, place)
        client = APIClient()
        client.force_authenticate(user)

        res = client.get(f"/api/events/{event.pk}")

        assert res.status_code == 200
        assert res.data["alarm_plan"]["buffer_minutes"] == 14
        assert res.data["alarm_plan"]["buffer_source"] == "learned"

    def test_weekly_endpoint_carries_the_buffer(self, user):
        adjusted(user, minutes=14, days_ago=7)
        client = APIClient()
        client.force_authenticate(user)

        res = client.get("/api/reports/weekly")

        assert res.status_code == 200
        assert res.data["buffer"]["adjustments"][0]["minutes"] == 14
