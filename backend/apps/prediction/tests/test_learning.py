"""학습 파이프라인 테스트.

가장 중요한 것은 **모수 복원**이다. 알려진 계수로 데이터를 만들고 학습이
그것을 되찾는지 본다. 이게 없으면 학습 결과가 틀렸을 때 코드 문제인지
데이터 부족인지 구분할 수 없다(back-spec 6.3 의 의도).

그리고 **이상치를 버리는지**도 고정한다. 학습 데이터에 쓰레기가 섞이면
알람이 조용히 틀어지므로, 버려야 할 것을 버리는지가 정확성의 핵심이다.
"""

from __future__ import annotations

import random
from datetime import date, datetime, timedelta, timezone

import pytest

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.planning.models import AlarmPlan
from apps.prediction import learning
from apps.prediction.models import ModelArtifact
from apps.routines.models import BlockObservation, RoutineBlock
from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="learn@example.com", password="Nb3-copper-lantern-77", nickname="학습"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


def make_block(user, name, lo=10, hi=20, **kw):
    return RoutineBlock.objects.create(
        user=user, name=name, default_min_minutes=lo, default_max_minutes=hi, **kw
    )


def add_block_obs(user, block, day, minutes, *, slack=None, parallel=False, uid=None):
    return BlockObservation.objects.create(
        user=user,
        block=block,
        observed_on=day,
        duration_minutes=minutes,
        slack_minutes=slack,
        was_parallel=parallel,
        client_uuid=uid or f"o-{block.pk}-{day}-{minutes}",
        client_recorded_at=datetime(2026, 9, 20, 7, 0, tzinfo=KST),
    )


def make_trip(
    user,
    place,
    *,
    predicted,
    actual_minutes,
    depart_at,
    route_key="transit:2호선",
    mode="transit",
    accuracy=15.0,
    skip_arrive=False,
):
    """출발·도착 쌍과 그 근거가 되는 알람 계획을 만든다."""
    event = Event.objects.create(
        user=user,
        place=place,
        title="이동",
        start_at=depart_at + timedelta(minutes=predicted + 30),
    )
    AlarmPlan.objects.create(
        user=user,
        event=event,
        status=AlarmPlan.Status.OK,
        alarm_at=depart_at - timedelta(minutes=30),
        depart_by=depart_at,
        arrive_at=depart_at + timedelta(minutes=predicted),
        prep_minutes=30,
        travel_minutes=predicted,
        buffer_minutes=10,
        travel_mode=mode,
        route_key=route_key,
    )
    TripObservation.objects.create(
        user=user, event=event, kind=TripObservation.Kind.DEPART,
        detector=TripObservation.Detector.GPS, observed_at=depart_at,
        lat=37.4842, lng=126.9297, accuracy_m=accuracy, distance_m=60.0,
        client_uuid=f"d-{event.pk}",
    )
    if not skip_arrive:
        TripObservation.objects.create(
            user=user, event=event, kind=TripObservation.Kind.ARRIVE,
            detector=TripObservation.Detector.GPS,
            observed_at=depart_at + timedelta(minutes=actual_minutes),
            lat=37.4601, lng=126.9520, accuracy_m=accuracy, distance_m=20.0,
            client_uuid=f"a-{event.pk}",
        )
    return event


# ---------------------------------------------------------------------------
# 이동 표본 수집
# ---------------------------------------------------------------------------


class TestCollectTravelSamples:
    def test_pairs_become_samples(self, user, place):
        make_trip(user, place, predicted=20, actual_minutes=24,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        samples, dropped = learning.collect_travel_samples()
        assert len(samples) == 1
        s = samples[0]
        assert s.predicted_minutes == 20
        assert s.actual_minutes == pytest.approx(24)
        assert s.ratio == pytest.approx(1.2)
        assert s.mode == "transit"
        assert s.hour == 8
        assert s.weekday_type == "weekday"
        assert dropped == {}

    def test_weekend_is_labelled(self, user, place):
        # 2026-09-26 은 토요일
        make_trip(user, place, predicted=20, actual_minutes=22,
                  depart_at=datetime(2026, 9, 26, 10, 0, tzinfo=KST))
        samples, _ = learning.collect_travel_samples()
        assert samples[0].weekday_type == "weekend"

    def test_drops_incomplete_pair(self, user, place):
        make_trip(user, place, predicted=20, actual_minutes=24,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST),
                  skip_arrive=True)
        samples, dropped = learning.collect_travel_samples()
        assert samples == []
        assert dropped["쌍 미완성"] == 1

    def test_drops_low_accuracy_fix(self, user, place):
        """오차 150m 인 fix 로 "반경 진입" 을 말할 수 없다."""
        make_trip(user, place, predicted=20, actual_minutes=24,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST),
                  accuracy=400.0)
        samples, dropped = learning.collect_travel_samples()
        assert samples == []
        assert dropped["정확도 초과"] == 2

    def test_drops_zero_duration(self, user, place):
        """출발·도착이 같은 fix 로 판정된 경우."""
        make_trip(user, place, predicted=20, actual_minutes=0,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        samples, dropped = learning.collect_travel_samples()
        assert samples == []
        assert dropped["소요 0분 또는 역순"] == 1

    def test_drops_absurd_ratio(self, user, place):
        """예측 20분에 실제 200분이면 중간에 딴 데 들렀다."""
        make_trip(user, place, predicted=20, actual_minutes=200,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        samples, dropped = learning.collect_travel_samples()
        assert samples == []
        assert dropped["비율 범위 밖"] == 1

    def test_drops_when_no_prediction(self, user, place):
        """계획이 없으면 비교 대상이 없다."""
        event = Event.objects.create(
            user=user, place=place, title="계획 없음",
            start_at=datetime(2026, 9, 21, 9, 0, tzinfo=KST),
        )
        base = datetime(2026, 9, 21, 8, 0, tzinfo=KST)
        for kind, off in ((TripObservation.Kind.DEPART, 0), (TripObservation.Kind.ARRIVE, 20)):
            TripObservation.objects.create(
                user=user, event=event, kind=kind,
                observed_at=base + timedelta(minutes=off),
                lat=37.48, lng=126.93, accuracy_m=10.0, distance_m=10.0,
                client_uuid=f"n-{kind}",
            )
        samples, dropped = learning.collect_travel_samples()
        assert samples == []
        assert dropped["예측값 없음"] == 1

    def test_user_filter(self, user, place, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="Rt5-quiet-meadow-19", nickname="타인"
        )
        make_trip(user, place, predicted=20, actual_minutes=24,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        make_trip(other, place, predicted=20, actual_minutes=30,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))
        mine, _ = learning.collect_travel_samples(user=user)
        allsamples, _ = learning.collect_travel_samples()
        assert len(mine) == 1
        assert len(allsamples) == 2


# ---------------------------------------------------------------------------
# 경로 보정
# ---------------------------------------------------------------------------


class TestUpdateRouteCorrections:
    def _many(self, user, place, ratios, hour=8):
        for i, r in enumerate(ratios):
            make_trip(
                user, place, predicted=20, actual_minutes=20 * r,
                depart_at=datetime(2026, 9, 21, hour, 0, tzinfo=KST) + timedelta(days=i * 7),
            )

    def test_factor_is_the_mean_ratio(self, user, place):
        """비율 1.1, 1.2, 1.3 → factor 1.2."""
        self._many(user, place, [1.1, 1.2, 1.3])
        result = learning.update_route_corrections()
        assert result["samples"] == 3

        row = RouteCorrection.objects.get(
            route_signature="transit:2호선", mode="transit", hour_bucket=8,
            weekday_type="weekday",
        )
        assert row.factor == pytest.approx(1.2, abs=1e-3)
        assert row.sample_count == 3

    def test_residual_sd_is_measured_after_correction(self, user, place):
        """**보정 후** 잔차의 표준편차여야 한다.

        보정 전 오차를 쓰면 체계적 편향(factor)과 변동성(sd)을 구분하지 못한다.
        비율이 전부 1.2 로 같으면 factor 로 완전히 설명되므로 sd 는 0 이다.
        """
        self._many(user, place, [1.2, 1.2, 1.2])
        learning.update_route_corrections()
        row = RouteCorrection.objects.get(
            route_signature="transit:2호선", hour_bucket=8, weekday_type="weekday",
            mode="transit",
        )
        assert row.factor == pytest.approx(1.2, abs=1e-3)
        assert row.sd_minutes == pytest.approx(0.0, abs=0.01)

    def test_global_row_is_always_written(self, user, place):
        """경로 전용 표본이 적어도 전역은 만든다."""
        self._many(user, place, [1.3])
        learning.update_route_corrections()
        assert RouteCorrection.objects.filter(
            route_signature=GLOBAL_SIGNATURE
        ).exists()

    def test_route_specific_needs_minimum_samples(self, user, place):
        """표본 2건으로 경로 전용 계수를 만들면 우연이 계수가 된다."""
        self._many(user, place, [1.5, 1.5])
        learning.update_route_corrections()
        assert not RouteCorrection.objects.filter(
            route_signature="transit:2호선"
        ).exists()
        assert RouteCorrection.objects.filter(
            route_signature=GLOBAL_SIGNATURE
        ).exists()

    def test_factor_is_clamped_to_db_range(self, user, place):
        """DB CheckConstraint 범위를 넘으면 저장이 터진다."""
        self._many(user, place, [3.9, 3.9, 3.9])
        learning.update_route_corrections()
        for row in RouteCorrection.objects.all():
            assert 0.25 <= row.factor <= 4.0

    def test_hourly_buckets_are_separate(self, user, place):
        """아침 8시와 낮 2시의 지연은 다르다."""
        self._many(user, place, [1.4, 1.4, 1.4], hour=8)
        self._many(user, place, [1.0, 1.0, 1.0], hour=14)
        learning.update_route_corrections()
        morning = RouteCorrection.objects.get(
            route_signature="transit:2호선", hour_bucket=8, weekday_type="weekday",
            mode="transit",
        )
        noon = RouteCorrection.objects.get(
            route_signature="transit:2호선", hour_bucket=14, weekday_type="weekday",
            mode="transit",
        )
        assert morning.factor > noon.factor

    def test_rerunning_updates_instead_of_duplicating(self, user, place):
        self._many(user, place, [1.1, 1.2, 1.3])
        learning.update_route_corrections()
        first = RouteCorrection.objects.count()
        learning.update_route_corrections()
        assert RouteCorrection.objects.count() == first

    def test_no_samples_writes_nothing(self, user):
        result = learning.update_route_corrections()
        assert result["written"] == 0
        assert not RouteCorrection.objects.exists()


# ---------------------------------------------------------------------------
# 블록 통계
# ---------------------------------------------------------------------------


class TestBlockStats:
    def test_groups_by_name_not_id(self, user, place, django_user_model):
        """전역 아티팩트는 여러 사용자의 "샤워" 를 합쳐야 한다."""
        other = django_user_model.objects.create_user(
            email="o2@example.com", password="Lp2-amber-forest-66", nickname="타인"
        )
        mine = make_block(user, "샤워")
        theirs = make_block(other, "샤워")
        add_block_obs(user, mine, date(2026, 9, 21), 14.0, uid="m1")
        add_block_obs(user, mine, date(2026, 9, 22), 16.0, uid="m2")
        add_block_obs(other, theirs, date(2026, 9, 21), 20.0, uid="t1")
        add_block_obs(other, theirs, date(2026, 9, 22), 24.0, uid="t2")

        glob = learning.block_stats()
        assert glob["샤워"]["n"] == 4
        assert glob["샤워"]["mean"] == pytest.approx(18.5)

        mine_only = learning.block_stats(user=user)
        assert mine_only["샤워"]["n"] == 2
        assert mine_only["샤워"]["mean"] == pytest.approx(15.0)

    def test_single_sample_reports_no_sd(self, user):
        """표본 1건으로 sd 를 만들면 0 이 나온다. 그건 "변동성 없음" 이 아니다."""
        b = make_block(user, "샤워")
        add_block_obs(user, b, date(2026, 9, 21), 14.0, uid="s1")
        stats = learning.block_stats(user=user)
        assert stats["샤워"]["n"] == 1
        assert stats["샤워"]["sd"] is None

    def test_sd_is_sample_stdev(self, user):
        b = make_block(user, "샤워")
        for i, m in enumerate([10.0, 20.0]):
            add_block_obs(user, b, date(2026, 9, 21 + i), m, uid=f"x{i}")
        stats = learning.block_stats(user=user)
        # 표본 표준편차(n-1): sqrt(((10-15)^2+(20-15)^2)/1) = sqrt(50) ≈ 7.07
        assert stats["샤워"]["sd"] == pytest.approx(7.07, abs=0.01)


class TestPrepTotalStats:
    def test_sums_per_day(self, user):
        a = make_block(user, "A")
        b = make_block(user, "B")
        add_block_obs(user, a, date(2026, 9, 21), 10.0, uid="a1")
        add_block_obs(user, b, date(2026, 9, 21), 15.0, uid="b1")
        add_block_obs(user, a, date(2026, 9, 22), 12.0, uid="a2")
        add_block_obs(user, b, date(2026, 9, 22), 18.0, uid="b2")

        stats = learning.prep_total_stats(user=user)
        assert stats["n"] == 2
        assert stats["prep_mean"] == pytest.approx(27.5)  # (25 + 30) / 2

    def test_excludes_parallel_blocks(self, user):
        """병렬로 진행한 시간을 합치면 실제보다 길어진다."""
        a = make_block(user, "직렬")
        b = make_block(user, "병렬", parallelizable=True)
        add_block_obs(user, a, date(2026, 9, 21), 20.0, uid="p1")
        add_block_obs(user, b, date(2026, 9, 21), 30.0, parallel=True, uid="p2")
        stats = learning.prep_total_stats(user=user)
        assert stats["prep_mean"] == pytest.approx(20.0)

    def test_empty(self, user):
        assert learning.prep_total_stats(user=user)["n"] == 0


class TestSlackCoef:
    def test_recovers_a_planted_slope(self, user):
        """여유 1분당 0.2분 늘어나게 심고 되찾는지 본다."""
        b = make_block(user, "샤워")
        rng = random.Random(7)
        for i in range(40):
            slack = float(i % 20)
            duration = 14.0 + 0.2 * slack + rng.gauss(0, 0.5)
            add_block_obs(
                user, b, date(2026, 9, 1) + timedelta(days=i), duration,
                slack=slack, uid=f"sl{i}",
            )
        coef = learning.estimate_slack_coef(user=user)
        assert coef == pytest.approx(0.2, abs=0.05)

    def test_returns_none_with_few_samples(self, user):
        """표본이 적으면 기울기를 억지로 내지 않는다. 노이즈가 계수가 된다."""
        b = make_block(user, "샤워")
        for i in range(5):
            add_block_obs(user, b, date(2026, 9, 1) + timedelta(days=i),
                          14.0, slack=float(i), uid=f"f{i}")
        assert learning.estimate_slack_coef(user=user) is None

    def test_returns_none_when_slack_never_varies(self, user):
        b = make_block(user, "샤워")
        for i in range(12):
            add_block_obs(user, b, date(2026, 9, 1) + timedelta(days=i),
                          14.0 + i, slack=5.0, uid=f"c{i}")
        assert learning.estimate_slack_coef(user=user) is None


# ---------------------------------------------------------------------------
# 아티팩트
# ---------------------------------------------------------------------------


class TestArtifact:
    def test_schema_matches_the_spec(self, user):
        b = make_block(user, "샤워")
        add_block_obs(user, b, date(2026, 9, 21), 14.0, uid="a1")
        add_block_obs(user, b, date(2026, 9, 22), 16.0, uid="a2")
        payload = learning.build_artifact(user=user)

        # front-spec 11 의 키
        for key in (
            "version", "global", "blocks", "tag_adjust", "slack_coef",
            "route_correction", "weather_adjust",
        ):
            assert key in payload, f"{key} 가 없다"
        assert payload["scope"] == "user"
        assert payload["blocks"]["샤워"]["n"] == 2

    def test_unlearned_sections_are_empty_not_zero(self, user):
        """**빈 값이 0.0 보다 정직하다.**

        클라이언트가 "보정 없음" 과 "보정이 0 으로 학습됨" 을 구분할 수 있어야 한다.
        """
        payload = learning.build_artifact(user=user)
        assert payload["tag_adjust"] == {}
        assert payload["weather_adjust"] == {}
        assert payload["slack_coef"] is None

    def test_save_deactivates_the_previous_one(self, user):
        first = learning.save_artifact({"version": "v1", "blocks": {}}, user=user)
        second = learning.save_artifact({"version": "v2", "blocks": {}}, user=user)
        first.refresh_from_db()
        assert first.is_active is False
        assert second.is_active is True
        # 이력은 남는다. "언제 왜 알람이 바뀌었나" 를 추적해야 한다.
        assert ModelArtifact.objects.filter(user=user).count() == 2

    def test_global_and_user_artifacts_coexist(self, user):
        learning.save_artifact({"version": "g1", "blocks": {}}, user=None)
        learning.save_artifact({"version": "u1", "blocks": {}}, user=user)
        assert ModelArtifact.objects.filter(is_active=True).count() == 2

    def test_sample_count_sums_block_observations(self, user):
        payload = {"version": "v", "blocks": {"a": {"n": 3}, "b": {"n": 4}}}
        artifact = learning.save_artifact(payload, user=user)
        assert artifact.sample_count == 7

    def test_route_correction_is_included(self, user, place):
        RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY,
            factor=1.15, sd_minutes=3.5, sample_count=40,
        )
        payload = learning.build_artifact()
        assert payload["route_correction"]["*"]["factor"] == pytest.approx(1.15)


# ---------------------------------------------------------------------------
# 학습이 알람에 실제로 반영되는가
# ---------------------------------------------------------------------------


class TestLearningReachesTheAlarm:
    def test_corrections_make_probability_appear(self, user, place, monkeypatch):
        """**이 테스트가 학습의 목적이다.**

        경로 보정이 생기면 이동 변동성이 생기고, 블록 범위가 준비 변동성을
        주므로 두 성분이 모두 갖춰져 확률이 계산된다.
        """
        from apps.planning import services

        route = {
            "minutes": 20, "mode": "transit", "source": "kakao",
            "summary": "2호선 · 20분", "key": "transit:2호선", "detail": "2호선",
        }
        monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
        monkeypatch.setattr(
            services.clients, "resolve_route", lambda key, **kw: (dict(route, key=key), False)
        )

        make_block(user, "샤워", 10, 30)

        event = Event.objects.create(
            user=user, place=place, title="확률 확인",
            start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
        )

        # 보정 전: 이동 변동성을 모르므로 확률 없음
        plan = services.compute_and_store(event)
        assert plan.on_time_probability is None
        assert plan.confidence_basis == "travel_variance_unknown"

        # 관측을 쌓고 학습
        for i in range(6):
            make_trip(
                user, place, predicted=20, actual_minutes=20 + (i % 4) * 3,
                depart_at=datetime(2026, 9, 7, 8, 0, tzinfo=KST) + timedelta(days=i * 7),
            )
        learning.update_route_corrections()

        # 보정 후: 확률이 나온다
        plan = services.compute_and_store(event)
        assert plan.on_time_probability is not None
        assert plan.confidence_basis == "observed"
        assert "observed" in plan.travel_source
