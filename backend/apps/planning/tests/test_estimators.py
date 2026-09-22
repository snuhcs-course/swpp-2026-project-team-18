"""준비·이동 추정기 테스트.

여기서 고정하는 가장 중요한 규칙은 **확신도를 말할 수 있는 조건**이다.
준비만 변동성이 있을 때 확률을 내면 이동의 불확실성을 0 으로 치므로
확신도를 과대 보고한다. 그 경우 `on_time_probability` 가 None 이어야 한다.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone

import pytest

from apps.planning import distributions as dist
from apps.planning.estimators import (
    PrepEstimate,
    TravelEstimate,
    block_distribution,
    compute_alarm_math,
    estimate_prep,
    estimate_travel,
    route_signature,
)
from apps.routines.models import RoutineBlock

KST = timezone(timedelta(hours=9))

pytestmark = pytest.mark.django_db


# ---------------------------------------------------------------------------
# 픽스처
# ---------------------------------------------------------------------------


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="blocks@example.com", password="test12345", nickname="블록"
    )


@pytest.fixture
def event(user):
    from apps.events.models import Event

    return Event.objects.create(
        user=user,
        title="테스트 일정",
        start_at=datetime(2026, 9, 20, 9, 0, tzinfo=KST),
    )


def make_block(user, name, lo, hi, *, parallel=False, order=0, default=True, pre=None):
    return RoutineBlock.objects.create(
        user=user,
        name=name,
        default_min_minutes=lo,
        default_max_minutes=hi,
        parallelizable=parallel,
        included_by_default=default,
        order=order,
        precondition=pre,
    )


# ---------------------------------------------------------------------------
# 블록 → 분포
# ---------------------------------------------------------------------------


class TestBlockDistribution:
    def test_range_becomes_mean_and_sd(self, user):
        """12~18분 → 평균 15, sd (18-12)/4 = 1.5.

        범위를 약 95% 구간(±2σ)으로 본다. 사용자가 신고한 변동성이므로
        만들어낸 값이 아니다.
        """
        b = make_block(user, "샤워", 12, 18)
        d = block_distribution(b)
        assert isinstance(d, dist.Normal)
        assert d.mean_min == pytest.approx(15.0)
        assert d.sd_min == pytest.approx(1.5)

    def test_point_range_has_no_variance(self, user):
        """"정확히 10분" 이라고 답하면 변동성을 붙이지 않는다."""
        b = make_block(user, "양치", 10, 10)
        d = block_distribution(b)
        assert d.sd_min == 0.0
        assert d.quantile(0.99) == pytest.approx(10.0)

    def test_observations_blend_toward_personal(self, user):
        """관측 5회에서 신고값과 50:50 (back-spec 6.1 S2, k=5)."""
        b = make_block(user, "샤워", 12, 18)  # 신고 평균 15
        d = block_distribution(b, observation_mean=21.0, observation_sd=2.0,
                               observation_count=5)
        assert d.mean_min == pytest.approx(18.0)  # 0.5*21 + 0.5*15

    def test_many_observations_dominate(self, user):
        b = make_block(user, "샤워", 12, 18)
        d = block_distribution(b, observation_mean=21.0, observation_sd=2.0,
                               observation_count=95)
        assert d.mean_min == pytest.approx(20.7)  # w=0.95

    def test_single_observation_keeps_declared_spread(self, user):
        """표본 1건으로 sd 를 계산하면 0 이 나온다. 그걸 쓰면 안 된다.

        "한 번 재봤으니 변동성이 없다" 는 잘못된 결론이다. 신고 범위의
        폭을 유지한다.
        """
        b = make_block(user, "샤워", 12, 18)
        d = block_distribution(b, observation_mean=20.0, observation_sd=0.0,
                               observation_count=1)
        assert d.sd_min > 0

    def test_inverted_range_is_survived(self, user):
        """DB 제약이 막지만 방어 코드가 예외를 던지지 않아야 한다."""
        b = make_block(user, "이상", 10, 10)
        b.default_min_minutes, b.default_max_minutes = 18, 12  # 저장하지 않고 조작
        d = block_distribution(b)
        assert d.mean_min == pytest.approx(15.0)
        assert d.sd_min == pytest.approx(1.5)


# ---------------------------------------------------------------------------
# 준비 시간
# ---------------------------------------------------------------------------


class TestEstimatePrep:
    def test_no_blocks_falls_back_to_onboarding_with_zero_sd(self, user, event):
        """블록이 없으면 값 하나뿐이다. 변동성을 만들어내지 않는다."""
        profile = user.profile
        profile.onboarding_prep_min = 30
        profile.save()

        est = estimate_prep(event, profile, fallback_minutes=30)
        assert est.block_count == 0
        assert est.source == "onboarding"
        assert est.distribution.quantile(0.99) == pytest.approx(30.0)
        assert not est.has_variance

    def test_serial_blocks_sum(self, user, event):
        """직렬 블록은 합이다. 10 + 15 + 5 = 30."""
        make_block(user, "세수", 8, 12, order=1)      # 평균 10
        make_block(user, "옷", 13, 17, order=2)       # 평균 15
        make_block(user, "가방", 4, 6, order=3)       # 평균 5

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.block_count == 3
        assert est.distribution.mean == pytest.approx(30.0, abs=0.01)

    def test_serial_variances_add(self, user, event):
        """sd 1, sd 1 → 합의 sd 는 sqrt(2) 다. 2 가 아니다."""
        make_block(user, "A", 8, 12, order=1)   # sd 1.0
        make_block(user, "B", 13, 17, order=2)  # sd 1.0
        est = estimate_prep(event, user.profile, fallback_minutes=30)
        d = est.distribution
        assert isinstance(d, dist.Normal)
        assert d.sd_min == pytest.approx(math.sqrt(2.0))

    def test_parallel_block_uses_max_not_sum(self, user, event):
        """세탁기를 돌리며 샤워하면 총 시간은 긴 쪽이다.

        직렬 20분 + 병렬 8분 → 합이면 28분, max 면 20분 근처다.
        """
        make_block(user, "샤워", 18, 22, order=1)               # 평균 20, 직렬
        make_block(user, "세탁", 7, 9, order=2, parallel=True)  # 평균 8, 병렬

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        # 합으로 계산하면 28분이다. max 면 20분 근처다.
        assert est.distribution.mean < 24.0
        # max 는 해석해가 없어 몬테카를로를 탄다. 표본 2000 이면 평균의
        # 표준오차가 sd/sqrt(n) = 1/44.7 ≈ 0.022분이므로 그만큼 허용한다.
        # 알람은 분 단위로 표시되니 이 오차는 화면에 나타나지 않는다.
        assert est.distribution.mean == pytest.approx(20.0, abs=0.1)

    def test_long_parallel_block_extends_total(self, user, event):
        """병렬이라도 더 길면 총 시간을 늘린다."""
        make_block(user, "샤워", 9, 11, order=1)                  # 평균 10, 직렬
        make_block(user, "세탁", 39, 41, order=2, parallel=True)  # 평균 40, 병렬

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.distribution.mean == pytest.approx(40.0, abs=1.0)

    def test_parallel_with_precondition_starts_after_it(self, user, event):
        """선행 블록이 직렬 사슬에 있으면 그 시점 뒤에 시작한다.

        직렬: 기상(10) → 샤워(20) = 30분
        병렬: 건조(15), 선행=샤워 → 샤워가 끝난 30분부터 시작 → 45분에 끝난다
        총: max(30, 45) = 45
        """
        wake = make_block(user, "기상", 9, 11, order=1)
        shower = make_block(user, "샤워", 19, 21, order=2)
        make_block(user, "건조", 14, 16, order=3, parallel=True, pre=shower)
        assert wake.pk  # 사용한다

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.distribution.mean == pytest.approx(45.0, abs=1.5)

    def test_excluded_by_default_is_skipped(self, user, event):
        make_block(user, "포함", 9, 11, order=1)
        make_block(user, "제외", 29, 31, order=2, default=False)

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.block_count == 1
        assert est.distribution.mean == pytest.approx(10.0, abs=0.01)

    def test_event_selection_overrides_default(self, user, event):
        """일정별 체크가 기본값을 덮는다."""
        from apps.routines.models import EventBlockSelection

        a = make_block(user, "A", 9, 11, order=1)
        b = make_block(user, "B", 29, 31, order=2, default=False)
        EventBlockSelection.objects.create(event=event, block=a, checked=False)
        EventBlockSelection.objects.create(event=event, block=b, checked=True)

        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.block_count == 1
        assert est.distribution.mean == pytest.approx(30.0, abs=0.01)

    def test_source_reports_declared_range(self, user, event):
        make_block(user, "샤워", 12, 18)
        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.source == "declared_range"
        assert est.has_variance

    def test_source_reports_declared_point(self, user, event):
        make_block(user, "양치", 10, 10)
        est = estimate_prep(event, user.profile, fallback_minutes=30)
        assert est.source == "declared_point"
        assert not est.has_variance

    def test_source_reports_observed(self, user, event):
        b = make_block(user, "샤워", 12, 18)
        est = estimate_prep(
            event, user.profile, fallback_minutes=30,
            observations={b.pk: (14.0, 2.0, 10)},
        )
        assert est.source == "observed"

    def test_breakdown_lists_each_block(self, user, event):
        make_block(user, "샤워", 12, 18, order=1)
        make_block(user, "옷", 4, 6, order=2)
        est = estimate_prep(event, user.profile, fallback_minutes=30)
        names = [row["name"] for row in est.breakdown]
        assert names == ["샤워", "옷"]
        assert est.breakdown[0]["declared_min"] == 12
        assert est.breakdown[0]["source"] == "declared"

    def test_is_deterministic(self, user, event):
        """같은 입력에 같은 알람이 나와야 한다. 재계산마다 흔들리면 신뢰를 잃는다."""
        make_block(user, "샤워", 18, 22, order=1)
        make_block(user, "세탁", 7, 9, order=2, parallel=True)
        first = estimate_prep(event, user.profile, 30).distribution.quantile(0.9)
        second = estimate_prep(event, user.profile, 30).distribution.quantile(0.9)
        assert first == second


# ---------------------------------------------------------------------------
# 이동 시간
# ---------------------------------------------------------------------------


class TestEstimateTravel:
    def test_no_correction_means_no_variance(self):
        """카카오는 점추정치만 준다. 보정 관측이 없으면 sd=0 이다."""
        est = estimate_travel(23.0, "transit:2호선", "transit",
                              datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        assert est.distribution.quantile(0.99) == pytest.approx(23.0)
        assert est.factor == 1.0
        assert est.source == "kakao"
        assert not est.has_variance

    def test_global_correction_is_applied(self):
        from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY,
            factor=1.10, sd_minutes=4.0, sample_count=40,
        )
        est = estimate_travel(20.0, "transit:2호선", "transit",
                              datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        assert est.distribution.mean == pytest.approx(22.0)
        assert est.source == "observed_global"
        assert est.has_variance

    def test_route_specific_blends_with_global(self):
        """경로 전용 표본 5건 → 전역과 50:50."""
        from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="transit", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY,
            factor=1.00, sd_minutes=2.0, sample_count=100,
        )
        RouteCorrection.objects.create(
            route_signature="transit:2호선", mode="transit", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY,
            factor=1.20, sd_minutes=6.0, sample_count=5,
        )
        est = estimate_travel(20.0, "transit:2호선", "transit",
                              datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        # factor = 0.5*1.20 + 0.5*1.00 = 1.10
        assert est.distribution.mean == pytest.approx(22.0)
        # sd: 분산으로 섞는다. sqrt(0.5*36 + 0.5*4) = sqrt(20)
        assert est.distribution.quantile(0.5) == pytest.approx(22.0)
        assert est.source == "observed_route"

    def test_hour_specific_correction_wins(self):
        """아침 8시와 낮 2시의 지연은 다르다. 시간대가 맞는 보정을 쓴다."""
        from apps.routing.models import RouteCorrection

        RouteCorrection.objects.create(
            route_signature="transit:2호선", mode="transit", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.WEEKDAY,
            factor=1.00, sd_minutes=1.0, sample_count=50,
        )
        RouteCorrection.objects.create(
            route_signature="transit:2호선", mode="transit", hour_bucket=8,
            weekday_type=RouteCorrection.WeekdayType.WEEKDAY,
            factor=1.30, sd_minutes=5.0, sample_count=50,
        )
        est = estimate_travel(20.0, "transit:2호선", "transit",
                              datetime(2026, 9, 21, 8, 30, tzinfo=KST))  # 월요일 8시
        assert est.distribution.mean == pytest.approx(26.0)

    def test_signature_is_trimmed_and_bounded(self):
        assert route_signature("  transit:2호선  ") == "transit:2호선"
        assert len(route_signature("x" * 500)) == 120
        assert route_signature("") == ""
        assert route_signature(None) == ""


# ---------------------------------------------------------------------------
# 합성과 확률
# ---------------------------------------------------------------------------


class TestComputeAlarmMath:
    def test_probability_is_none_when_nothing_varies(self):
        """둘 다 점추정이면 확률을 말할 수 없다. 오늘의 동작이다."""
        prep = PrepEstimate(dist.Normal(30.0, 0.0), source="onboarding")
        travel = TravelEstimate(dist.Normal(20.0, 0.0), raw_minutes=20.0)
        math_ = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert math_.on_time_probability is None
        assert math_.confidence_basis == "point_estimate"
        assert math_.total_minutes == 60  # 30 + 20 + 10

    def test_probability_is_none_when_only_prep_varies(self):
        """**이 테스트가 이 파일의 핵심이다.**

        준비만 변동성이 있는데 확률을 내면 이동의 불확실성을 0 으로 치므로
        확신도를 과대 보고한다. "92% 정시 도착" 이 실제로는 "이동이 예측대로
        라면 92%" 인데 그 조건이 화면에 없다.
        """
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="declared_range")
        travel = TravelEstimate(dist.Normal(20.0, 0.0), raw_minutes=20.0)
        math_ = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert math_.on_time_probability is None
        assert math_.confidence_basis == "travel_variance_unknown"

    def test_probability_is_none_when_only_travel_varies(self):
        prep = PrepEstimate(dist.Normal(30.0, 0.0), source="declared_point")
        travel = TravelEstimate(dist.Normal(20.0, 5.0), raw_minutes=20.0,
                                source="observed_global")
        math_ = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert math_.on_time_probability is None
        assert math_.confidence_basis == "prep_variance_unknown"

    def test_probability_appears_when_both_vary(self):
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0,
                                source="observed_route")
        math_ = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert math_.on_time_probability is not None
        assert math_.confidence_basis == "observed"

    def test_probability_exceeds_tau_because_of_the_buffer(self):
        """버퍼가 τ 위로 확신도를 더 사준다.

        합성 분포의 τ 분위수로 알람을 잡고 그 위에 버퍼 10분을 더하므로,
        실제 정시 도착 확률은 τ 보다 크다.
        """
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0,
                                source="observed_route")
        math_ = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert math_.on_time_probability > 90

    def test_zero_buffer_gives_probability_equal_to_tau(self):
        """버퍼가 0 이면 확률이 정확히 τ 다. 수식 정합성 확인."""
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0,
                                source="observed_route")
        math_ = compute_alarm_math(prep, travel, buffer_minutes=0, tau=0.9)
        assert math_.on_time_probability == pytest.approx(90, abs=1)

    def test_combined_quantile_is_smaller_than_the_sum_of_quantiles(self):
        """**합성 후 분위수를 써야 한다.**

        준비의 90% 분위수와 이동의 90% 분위수를 따로 구해 더하면, 둘이 동시에
        나쁜 경우를 가정해 결합 확신도가 99% 가 된다. 그만큼 알람이 이르고
        사용자는 잠을 잃는다.
        """
        prep_d = dist.Normal(30.0, 6.0)
        travel_d = dist.Normal(20.0, 8.0)
        naive = prep_d.quantile(0.9) + travel_d.quantile(0.9)

        math_ = compute_alarm_math(
            PrepEstimate(prep_d, source="observed"),
            TravelEstimate(travel_d, raw_minutes=20.0, source="observed_route"),
            buffer_minutes=0, tau=0.9,
        )
        assert math_.total_minutes < naive

    def test_higher_tau_gives_more_minutes(self):
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0,
                                source="observed_route")
        low = compute_alarm_math(prep, travel, 10, tau=0.6).total_minutes
        high = compute_alarm_math(prep, travel, 10, tau=0.99).total_minutes
        assert high > low

    def test_tau_does_nothing_without_variance(self):
        """변동성을 모르면 τ 를 올려도 알람이 안 변한다. 정직한 동작이다."""
        prep = PrepEstimate(dist.Normal(30.0, 0.0), source="onboarding")
        travel = TravelEstimate(dist.Normal(20.0, 0.0), raw_minutes=20.0)
        low = compute_alarm_math(prep, travel, 10, tau=0.6).total_minutes
        high = compute_alarm_math(prep, travel, 10, tau=0.99).total_minutes
        assert low == high == 60

    def test_breakdown_sums_to_total(self):
        """화면에 셋을 나란히 보여주므로 합이 총계와 맞아야 한다."""
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0,
                                source="observed_route")
        m = compute_alarm_math(prep, travel, buffer_minutes=10, tau=0.9)
        assert m.prep_minutes + m.travel_minutes + m.buffer_minutes == m.total_minutes

    def test_probability_is_clamped_to_percent_range(self):
        prep = PrepEstimate(dist.Normal(30.0, 1.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 1.0), raw_minutes=20.0,
                                source="observed_route")
        m = compute_alarm_math(prep, travel, buffer_minutes=120, tau=0.9)
        assert m.on_time_probability == 100
