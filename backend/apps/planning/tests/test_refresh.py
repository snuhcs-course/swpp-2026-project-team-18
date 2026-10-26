# Partly AI-generated (Claude)
"""경로를 다시 조회하지 않는 알람 재계산(`planning.refresh`).

블록 정의가 바뀌거나 밤 학습이 끝나면 지금 계획의 카카오 원값을 그대로 두고 준비·이동
분포만 다시 섞는다. 카카오 경로는 모킹하고, 재계산이 부르지 않는지 센다.
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
from apps.planning import refresh, services
from apps.planning.models import AlarmPlan, PlanRevision
from apps.routines.models import RoutineBlock
from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="refresh@example.com", password="Zr4-quiet-meadow-18", nickname="재계산"
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
    """경로 조회를 고정값으로. `calls` 로 호출 횟수를 센다."""
    current = dict(ROUTE)
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(current), False

    def resolve_route(key, **kw):
        calls.append("resolve")
        return dict(current, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return {"current": current, "calls": calls}


def planned(user, place, *, hours_ahead=24, **kw) -> Event:
    """계획까지 계산된 앞으로의 일정."""
    start = (timezone.now() + timedelta(hours=hours_ahead)).replace(second=0, microsecond=0)
    event = Event.objects.create(user=user, place=place, title="수업", start_at=start, **kw)
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    return event


def plan_of(event) -> AlarmPlan:
    return AlarmPlan.objects.get(event=event)


def set_prep(user, minutes):
    profile = user.profile
    profile.onboarding_prep_min = minutes
    profile.save()


def latest_revision(event) -> PlanRevision:
    return event.plan_revisions.order_by("-computed_at", "-id").first()


def run_refresh(user, **kw):
    kw.setdefault("trigger", PlanRevision.Trigger.ROUTINE)
    return refresh.refresh_upcoming(user, **kw)


class TestRefreshUpcoming:
    def test_prep_change_moves_the_alarm_without_a_route_lookup(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        calls = len(route["calls"])
        set_prep(user, 45)
        # 경로를 다시 조회했다면 이 값이 들어간다.
        route["current"]["minutes"] = 99

        outcomes = run_refresh(user)

        after = plan_of(event)
        assert outcomes == {refresh.CHANGED: 1}
        assert len(route["calls"]) == calls
        assert after.alarm_at == before.alarm_at - timedelta(minutes=15)
        assert after.prep_minutes == before.prep_minutes + 15
        assert after.travel_minutes == before.travel_minutes
        assert after.computed_at > before.computed_at
        # 경로 필드는 직전 값 그대로다.
        assert after.route_path == before.route_path
        assert after.route_summary == before.route_summary
        assert after.travel_mode == before.travel_mode

        rev = latest_revision(event)
        assert rev.trigger == PlanRevision.Trigger.ROUTINE
        assert rev.route_minutes_raw == 20
        assert rev.alarm_at == after.alarm_at

    def test_matches_a_full_recompute_with_the_same_route(self, user, place, route):
        event = planned(user, place)
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, factor=1.3, sd_minutes=4.0, sample_count=12
        )
        set_prep(user, 40)

        run_refresh(user)
        cheap = plan_of(event)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.RECOMPUTE)
        full = plan_of(event)

        for name in (
            "alarm_at", "depart_by", "arrive_at", "prep_minutes", "travel_minutes",
            "buffer_minutes", "tau_used", "on_time_probability", "confidence_basis",
            "prep_source", "prep_breakdown", "total_quantile_minutes",
            "travel_sample_count", "travel_source",
        ):
            assert getattr(cheap, name) == getattr(full, name), name

    def test_learned_route_correction_reaches_the_alarm(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, factor=1.5, sd_minutes=3.0, sample_count=10
        )

        run_refresh(user, trigger=PlanRevision.Trigger.LEARNING)

        after = plan_of(event)
        assert after.travel_minutes > before.travel_minutes
        assert after.alarm_at < before.alarm_at
        assert after.travel_source == "kakao_transit+observed_global"
        assert after.travel_sample_count == 10
        assert latest_revision(event).trigger == PlanRevision.Trigger.LEARNING

    def test_nothing_new_leaves_the_plan_alone(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        revisions = event.plan_revisions.count()

        outcomes = run_refresh(user)

        assert outcomes == {refresh.UNCHANGED: 1}
        assert plan_of(event).computed_at == before.computed_at
        assert event.plan_revisions.count() == revisions

    def test_dry_run_counts_without_saving(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        set_prep(user, 45)

        outcomes = run_refresh(user, dry_run=True)

        assert outcomes == {refresh.CHANGED: 1}
        assert plan_of(event).alarm_at == before.alarm_at

    def test_fired_alarm_is_left_alone(self, user, place, route):
        event = planned(user, place)
        AlarmLog.objects.create(
            user=user, event=event, kind=AlarmLog.Kind.FIRED,
            occurred_at=timezone.now(), client_uuid="f1",
        )
        set_prep(user, 45)

        assert run_refresh(user) == {refresh.SKIP_STARTED: 1}

    def test_departed_trip_is_left_alone(self, user, place, route):
        event = planned(user, place)
        TripObservation.objects.create(
            user=user, event=event, kind=TripObservation.Kind.DEPART, detector="gps",
            observed_at=timezone.now(), lat=37.48, lng=126.93, accuracy_m=10.0,
            distance_m=160.0, client_uuid="d1",
        )
        set_prep(user, 45)

        assert run_refresh(user) == {refresh.SKIP_STARTED: 1}

    def test_protect_window_skips_alarms_about_to_ring(self, user, place, route):
        # 시작 2시간 뒤 → 알람은 대략 1시간 뒤다.
        planned(user, place, hours_ahead=2)
        set_prep(user, 45)

        assert run_refresh(user, protect=timedelta(hours=3)) == {refresh.SKIP_SOON: 1}
        assert run_refresh(user) == {refresh.CHANGED: 1}

    def test_plan_without_raw_minutes_is_skipped(self, user, place, route):
        event = planned(user, place)
        event.plan_revisions.all().delete()
        set_prep(user, 45)

        assert run_refresh(user) == {refresh.SKIP_NO_RAW: 1}

    def test_changed_route_choice_needs_a_full_recompute(self, user, place, route):
        """고른 경로가 계획과 다르면 원값을 다시 쓸 수 없다."""
        event = planned(user, place)
        Event.objects.filter(pk=event.pk).update(route_key="walk")
        set_prep(user, 45)

        assert run_refresh(user) == {refresh.SKIP_NO_RAW: 1}

    def test_failed_plan_is_skipped(self, user, place, route):
        event = planned(user, place)
        AlarmPlan.objects.filter(event=event).update(
            status=AlarmPlan.Status.ROUTE_FAILED, alarm_at=None, depart_by=None,
            arrive_at=None, prep_minutes=None, travel_minutes=None, buffer_minutes=None,
        )

        assert run_refresh(user) == {refresh.SKIP_NOT_OK: 1}

    def test_home_removed_is_skipped(self, user, place, route):
        planned(user, place)
        profile = user.profile
        profile.home_lat = profile.home_lng = None
        profile.save()

        assert run_refresh(user) == {refresh.SKIP_NO_ENDPOINT: 1}

    def test_past_events_are_not_touched(self, user, place, route):
        event = planned(user, place)
        Event.objects.filter(pk=event.pk).update(start_at=timezone.now() - timedelta(hours=1))
        set_prep(user, 45)

        assert run_refresh(user) == {}


class TestAfterLearning:
    def test_only_the_next_seven_days(self, user, place, route):
        near = planned(user, place, hours_ahead=24)
        far = planned(user, place, hours_ahead=24 * 10)
        far_before = plan_of(far)
        set_prep(user, 45)

        outcomes = refresh.refresh_after_learning()

        assert outcomes == {refresh.CHANGED: 1}
        assert latest_revision(near).trigger == PlanRevision.Trigger.LEARNING
        assert plan_of(far).alarm_at == far_before.alarm_at

    def test_alarms_within_three_hours_are_left_to_the_app(self, user, place, route):
        planned(user, place, hours_ahead=2)
        set_prep(user, 45)

        assert refresh.refresh_after_learning() == {refresh.SKIP_SOON: 1}

    def test_every_user_is_refreshed(self, user, place, route, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="Zr4-quiet-meadow-19", nickname="다른"
        )
        p = other.profile
        p.home_lat, p.home_lng, p.onboarding_prep_min = 37.4842, 126.9297, 30
        p.save()
        planned(user, place)
        planned(other, place)
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, factor=1.5, sd_minutes=3.0, sample_count=10
        )

        assert refresh.refresh_after_learning() == {refresh.CHANGED: 2}


class TestTrainModels:
    def run(self, *args) -> str:
        out = StringIO()
        call_command("train_models", *args, stdout=out)
        return out.getvalue()

    def test_training_refreshes_upcoming_alarms(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        set_prep(user, 45)

        out = self.run()

        assert "[4] 앞으로 7일 알람 재계산" in out
        assert "바뀜 1건 / 그대로 0건" in out
        assert plan_of(event).alarm_at == before.alarm_at - timedelta(minutes=15)
        assert latest_revision(event).trigger == PlanRevision.Trigger.LEARNING

    def test_routes_only_still_refreshes(self, user, place, route):
        planned(user, place)
        set_prep(user, 45)

        assert "바뀜 1건" in self.run("--routes-only")

    def test_dry_run_reports_without_saving(self, user, place, route):
        event = planned(user, place)
        before = plan_of(event)
        set_prep(user, 45)

        out = self.run("--dry-run")

        assert "바뀜 1건" in out
        assert plan_of(event).alarm_at == before.alarm_at

    def test_skips_are_reported_with_reason(self, user, place, route):
        planned(user, place, hours_ahead=2)

        assert f"건너뜀: {refresh.SKIP_SOON} 1건" in self.run()


@pytest.mark.integration
class TestRoutineDefinitionChange:
    """블록 정의를 바꾸면 앞으로의 알람이 경로 조회 없이 따라온다."""

    URL = "/api/routines/blocks"

    @pytest.fixture
    def client(self, user):
        c = APIClient()
        c.force_authenticate(user=user)
        return c

    @pytest.fixture
    def block(self, user):
        return RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=10, default_max_minutes=10
        )

    def test_editing_a_block_moves_the_alarm(self, client, user, place, route, block):
        event = planned(user, place)
        before = plan_of(event)
        calls = len(route["calls"])

        res = client.patch(
            f"{self.URL}/{block.pk}",
            {"default_min_minutes": 30, "default_max_minutes": 30},
            format="json",
        )

        assert res.status_code == 200, res.data
        after = plan_of(event)
        assert after.alarm_at == before.alarm_at - timedelta(minutes=20)
        assert len(route["calls"]) == calls
        assert latest_revision(event).trigger == PlanRevision.Trigger.ROUTINE

    def test_adding_a_block_moves_the_alarm(self, client, user, place, route, block):
        event = planned(user, place)
        before = plan_of(event)

        res = client.post(
            self.URL,
            {"name": "아침", "default_min_minutes": 15, "default_max_minutes": 15},
            format="json",
        )

        assert res.status_code == 201, res.data
        assert plan_of(event).alarm_at == before.alarm_at - timedelta(minutes=15)

    def test_deleting_a_block_moves_the_alarm(self, client, user, place, route, block):
        RoutineBlock.objects.create(
            user=user, name="아침", default_min_minutes=15, default_max_minutes=15
        )
        event = planned(user, place)
        before = plan_of(event)

        res = client.delete(f"{self.URL}/{block.pk}")

        assert res.status_code == 204
        assert plan_of(event).alarm_at == before.alarm_at + timedelta(minutes=10)
