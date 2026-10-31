# Partly AI-generated (Claude)
"""이동 예측에 곱하는 새 계수(P2 A-1 시간대, A-2 걷는 속도).

    예측 = (원값 + 걷는 분 × (걷는 속도 − 1)) × 시간대 계수 × 경로 보정

두 계수 모두 1.0 에서 시작해 표본 수만큼 기록 쪽으로 옮긴다(n / (n + 5)). 시간대 구간은
계획한 출발 시각(시작 − 버퍼 − 카카오 원값)으로 고른다. 학습 쪽은
`apps/prediction/tests/test_learning.py` 에 있다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from django.utils import timezone as dj_tz

from apps.events.models import Event, Place
from apps.planning import refresh, services
from apps.planning.estimators import estimate_travel
from apps.planning.models import AlarmPlan, PlanRevision
from apps.routing.models import (
    GLOBAL_SIGNATURE,
    RouteCorrection,
    UserTravelProfile,
    shrink_to_one,
    time_bucket,
    travel_kind,
    walk_factor,
    walk_factor_for,
)

pytestmark = pytest.mark.django_db

KST = timezone(timedelta(hours=9))

# 대중교통 30분 = 걷기 4분 + 버스 20분 + 걷기 6분.
TRANSIT = {
    "minutes": 30,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "5513 · 30분",
    "key": "transit:5513",
    "detail": "5513",
    "segments": [
        {"kind": "walk", "seconds": 240, "label": "도보"},
        {"kind": "bus", "seconds": 1200, "label": "5513"},
        {"kind": "walk", "seconds": 360, "label": "도보"},
    ],
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="travel-factors@example.com", password="Hk7-violet-canyon-42", nickname="계수"
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
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(TRANSIT), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(TRANSIT, key=key), False)
    )


def event_at(user, place, when: datetime) -> Event:
    return Event.objects.create(user=user, place=place, title="수업", start_at=when)


def time_row(bucket: int, factor: float, n: int, mode="transit"):
    return RouteCorrection.objects.create(
        route_signature=GLOBAL_SIGNATURE, mode=mode, hour_bucket=bucket,
        weekday_type=RouteCorrection.WeekdayType.WEEKDAY,
        factor=factor, sd_minutes=0.0, sample_count=n,
    )


def global_row(factor=1.0, n=100):
    return RouteCorrection.objects.create(
        route_signature=GLOBAL_SIGNATURE, mode="transit", hour_bucket=-1,
        weekday_type=RouteCorrection.WeekdayType.ANY,
        factor=factor, sd_minutes=1.0, sample_count=n,
    )


class TestRules:
    @pytest.mark.parametrize(
        "hour, bucket",
        [(0, 20), (5, 20), (6, 6), (8, 6), (9, 9), (16, 9), (17, 17), (19, 17), (20, 20), (23, 20)],
    )
    def test_four_time_buckets(self, hour, bucket):
        """출근 6~9, 낮 9~17, 퇴근 17~20, 밤 20~6."""
        assert time_bucket(hour) == bucket

    def test_shrink_to_one_starts_at_one(self):
        assert shrink_to_one(3.0, 0) == 1.0
        assert shrink_to_one(1.4, 5) == pytest.approx(1.2)
        assert shrink_to_one(0.8, 5) == pytest.approx(0.9)
        assert shrink_to_one(1.4, 45) == pytest.approx(1.36)

    def test_walk_factor_is_shrunk_then_clipped(self):
        assert walk_factor(1.4, 5) == pytest.approx(1.2)
        assert walk_factor(3.0, 1000) == pytest.approx(1.5)
        assert walk_factor(0.3, 1000) == pytest.approx(0.7)

    @pytest.mark.parametrize(
        "key, mode, kind",
        [
            ("walk", "도보", "walk"),
            ("car", "자동차", "car"),
            ("bicycle", "자전거", "bicycle"),
            ("transit:2호선>5513", "버스+지하철", "transit"),
            ("transit:5513", "버스", "transit"),
            ("transit:5513", "", "transit"),
            ("", "도보", "walk"),
            ("", "지하철", "transit"),
            ("", "transit", "transit"),
            ("", "", ""),
        ],
    )
    def test_travel_kind_comes_from_the_key(self, key, mode, kind):
        """저장된 수단은 화면용 이름("도보", "버스+지하철")이다. 종류는 key 로 정한다."""
        assert travel_kind(key, mode) == kind

    def test_walk_factor_for_a_user(self, user):
        assert walk_factor_for(user.pk) == 1.0
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)
        assert walk_factor_for(user.pk) == pytest.approx(1.2)


class TestWalkAdjustment:
    WHEN = datetime(2026, 11, 2, 14, 0, tzinfo=KST)

    def test_walk_route_is_multiplied_whole(self):
        est = estimate_travel(20.0, "walk", "walk", self.WHEN, walk_minutes=20.0, walk_factor=1.2)
        assert est.distribution.mean == pytest.approx(24.0)
        assert est.raw_minutes == 20.0
        assert est.source == "kakao"

    def test_transit_multiplies_only_walk_minutes(self):
        est = estimate_travel(30.0, "transit:5513", "transit", self.WHEN, walk_minutes=10.0, walk_factor=1.2)
        assert est.distribution.mean == pytest.approx(32.0)
        fast = estimate_travel(30.0, "transit:5513", "transit", self.WHEN, walk_minutes=10.0, walk_factor=0.8)
        assert fast.distribution.mean == pytest.approx(28.0)

    def test_walk_minutes_are_capped_at_the_route(self):
        est = estimate_travel(30.0, "transit:5513", "transit", self.WHEN, walk_minutes=50.0, walk_factor=1.2)
        assert est.walk_minutes == 30.0
        assert est.distribution.mean == pytest.approx(36.0)

    def test_walk_factor_goes_under_the_route_correction(self):
        """걷는 속도로 고친 예측에 경로 보정·시간대 계수를 곱한다."""
        global_row(factor=1.1)
        time_row(9, 1.4, 5)  # → 1.2
        est = estimate_travel(30.0, "transit:5513", "transit", self.WHEN, walk_minutes=10.0, walk_factor=1.2)
        assert est.distribution.mean == pytest.approx(32.0 * 1.1 * 1.2)


class TestWalkRawMinutes:
    def test_by_mode(self):
        # 실제 경로 후보는 key 가 종류이고 mode 가 화면용 이름이다.
        assert services.walk_raw_minutes({"minutes": 18, "key": "walk", "mode": "도보"}) == 18
        assert services.walk_raw_minutes({"minutes": 18, "key": "car", "mode": "자동차"}) == 0
        assert services.walk_raw_minutes({"minutes": 18, "mode": "walk"}) == 18
        assert services.walk_raw_minutes({"minutes": 18, "mode": "car"}) == 0
        assert services.walk_raw_minutes({"minutes": 18, "mode": "bicycle"}) == 0
        assert services.walk_raw_minutes(TRANSIT) == 10

    def test_transit_without_segments_is_unknown(self):
        assert services.walk_raw_minutes({"minutes": 30, "mode": "transit"}) is None


class TestEstimateComponents:
    MONDAY_9_30 = datetime(2026, 11, 2, 9, 30, tzinfo=KST)

    def test_bucket_follows_the_planned_departure(self, user, place):
        """9시 30분 일정, 버퍼 10분, 40분 경로 → 8시 40분 출발 → 출근 구간.

        일정 시작(9시 30분)으로 고르면 낮 구간이 되어 아침 지연을 놓친다.
        """
        global_row()
        time_row(6, 1.5, 5)  # 출근 → 1.25
        time_row(9, 0.8, 5)  # 낮 → 0.9
        event = event_at(user, place, self.MONDAY_9_30)

        _, rush = services.estimate_components(event, {"minutes": 40, "mode": "transit", "key": ""})
        assert rush.time_factor == pytest.approx(1.25)

        _, day = services.estimate_components(event, {"minutes": 20, "mode": "transit", "key": ""})
        assert day.time_factor == pytest.approx(0.9)

    def test_buffer_moves_the_planned_departure(self, user, place):
        global_row()
        time_row(6, 1.5, 5)
        event = event_at(user, place, datetime(2026, 11, 2, 9, 20, tzinfo=KST))
        route = {"minutes": 5, "mode": "transit", "key": ""}

        # 9:20 − 15 − 5 = 9:00 → 낮
        _, at_nine = services.estimate_components(event, route, buffer_minutes=15)
        assert at_nine.time_factor == 1.0
        # 9:20 − 16 − 5 = 8:59 → 출근
        _, before_nine = services.estimate_components(event, route, buffer_minutes=16)
        assert before_nine.time_factor == pytest.approx(1.25)

    def test_users_walk_speed_reaches_the_estimate(self, user, place):
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)  # → 1.2
        event = event_at(user, place, self.MONDAY_9_30)

        _, travel = services.estimate_components(event, dict(TRANSIT))
        assert travel.walk_factor == pytest.approx(1.2)
        assert travel.walk_minutes == 10.0
        assert travel.distribution.mean == pytest.approx(32.0)

    def test_real_labels_reach_both_factors(self, user, place):
        """카카오 후보 모양 그대로(`mode` 가 "도보"·"버스+지하철")여도 두 계수가 적용된다."""
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)  # → 1.2
        time_row(6, 1.5, 5, mode="transit")  # → 1.25
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY, factor=1.0, sd_minutes=1.0, sample_count=50,
        )
        event = event_at(user, place, self.MONDAY_9_30)

        _, walk = services.estimate_components(event, {"minutes": 20, "mode": "도보", "key": "walk"})
        assert walk.walk_factor == pytest.approx(1.2)
        assert walk.distribution.mean == pytest.approx(24.0)

        _, transit = services.estimate_components(
            event, dict(TRANSIT, minutes=40, mode="버스+지하철", key="transit:2호선>5513")
        )
        assert transit.time_factor == pytest.approx(1.25)
        assert transit.walk_minutes == 10.0

    def test_stored_walk_minutes_win_over_segments(self, user, place):
        """이력에서 다시 계산할 때는 구간이 없다. 저장한 도보 원값을 쓴다."""
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)
        event = event_at(user, place, self.MONDAY_9_30)

        _, transit = services.estimate_components(
            event, {"minutes": 30, "walk_minutes": 10, "mode": "transit", "key": ""}
        )
        assert transit.distribution.mean == pytest.approx(32.0)
        # 예전 이력(도보 원값 없음)의 도보 경로는 원값 전체가 걷는 분이다.
        _, walk = services.estimate_components(
            event, {"minutes": 20, "walk_minutes": None, "mode": "도보", "key": "walk"}
        )
        assert walk.distribution.mean == pytest.approx(24.0)
        # 예전 이력의 대중교통은 걷는 분을 모르니 고치지 않는다.
        _, unknown = services.estimate_components(
            event, {"minutes": 30, "walk_minutes": None, "mode": "transit", "key": ""}
        )
        assert unknown.distribution.mean == pytest.approx(30.0)


class TestStoredPlans:
    def planned(self, user, place) -> Event:
        start = (dj_tz.now() + timedelta(hours=24)).replace(second=0, microsecond=0)
        event = event_at(user, place, start)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
        return event

    def test_walk_minutes_are_recorded_with_the_raw_route(self, user, place, route):
        event = self.planned(user, place)
        revision = event.plan_revisions.get()
        assert revision.route_minutes_raw == 30
        assert revision.walk_minutes_raw == 10

    def test_learned_walk_speed_reaches_alarms_without_a_route_lookup(self, user, place, route, monkeypatch):
        event = self.planned(user, place)
        before = AlarmPlan.objects.get(event=event)
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)

        def no_lookup(*a, **kw):
            raise AssertionError("재계산이 경로를 조회했다")

        monkeypatch.setattr(services.clients, "best_route", no_lookup)
        monkeypatch.setattr(services.clients, "resolve_route", no_lookup)
        refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.LEARNING)

        cheap = AlarmPlan.objects.get(event=event)
        assert cheap.travel_minutes == before.travel_minutes + 2
        assert cheap.alarm_at == before.alarm_at - timedelta(minutes=2)
        latest = event.plan_revisions.order_by("-computed_at", "-id").first()
        assert latest.walk_minutes_raw == 10

    def test_refresh_matches_a_full_recompute(self, user, place, route):
        event = self.planned(user, place)
        global_row(factor=1.1, n=40)
        UserTravelProfile.objects.create(user=user, walk_ratio=1.3, walk_samples=8)
        bucket = time_bucket(dj_tz.localtime(event.start_at - timedelta(minutes=40)).hour)
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="transit", hour_bucket=bucket,
            weekday_type=(
                RouteCorrection.WeekdayType.WEEKEND
                if dj_tz.localtime(event.start_at).weekday() >= 5
                else RouteCorrection.WeekdayType.WEEKDAY
            ),
            factor=1.2, sd_minutes=0.0, sample_count=10,
        )

        refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.LEARNING)
        cheap = AlarmPlan.objects.get(event=event)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.RECOMPUTE)
        full = AlarmPlan.objects.get(event=event)

        for name in ("alarm_at", "depart_by", "travel_minutes", "tau_used", "travel_source"):
            assert getattr(cheap, name) == getattr(full, name), name

    def test_risk_options_use_the_same_walk_minutes(self, user, place, route):
        """위험별 선택지도 저장한 도보 원값과 걷는 속도를 쓴다. 지금 τ 의 선택지 = 지금 알람."""
        from apps.planning.risk_options import build_risk_options

        global_row(factor=1.0, n=40)
        event = self.planned(user, place)
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)
        refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.LEARNING)
        plan = AlarmPlan.objects.get(event=event)

        # 화면처럼 일정을 새로 읽는다(재계산 전 계획이 붙어 있지 않게).
        options = build_risk_options(Event.objects.get(pk=event.pk))
        chosen = [o for o in options["options"] if o["key"] == options["selected_key"]]
        assert chosen, options["unavailable_reason"]
        assert datetime.fromisoformat(chosen[0]["alarm_at"]) == plan.alarm_at
