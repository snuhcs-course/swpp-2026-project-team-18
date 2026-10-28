# Partly AI-generated (Claude)
"""GPS 출발 기준 준비 시간 학습(`prep_observations`).

준비 시간 = 그 일정의 첫 GPS 출발 − 그 전 마지막 알람 끄기. 사람이 누른 블록
기록보다 이 값을 더 믿고, 출발 뒤에 찍힌 블록 기록은 학습에서 뺀다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import estimators, prep_observations, services
from apps.planning.distributions import Normal
from apps.prediction import learning
from apps.routines.models import BlockObservation, RoutineBlock

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao",
    "summary": "2호선 · 20분",
    "key": "transit:2호선",
    "detail": "2호선",
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="gps-prep@example.com", password="test12345", nickname="지피에스"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture(autouse=True)
def stub_route(monkeypatch):
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


def morning(day: int) -> datetime:
    """9월 `day` 일 아침 7시(KST)."""
    return datetime(2026, 9, day, 7, 0, tzinfo=KST)


def make_event(user, place, day: int, **kw) -> Event:
    return Event.objects.create(
        user=user, place=place, title="수업",
        start_at=morning(day) + timedelta(hours=2), **kw,
    )


def dismiss(user, event, at: datetime, uid: str) -> AlarmLog:
    return AlarmLog.objects.create(
        user=user, event=event, kind=AlarmLog.Kind.DISMISSED, occurred_at=at, client_uuid=uid,
    )


def depart(user, event, at: datetime, uid: str, *, accuracy=10.0, detector="gps"):
    return TripObservation.objects.create(
        user=user, event=event, kind=TripObservation.Kind.DEPART, detector=detector,
        observed_at=at, lat=37.48, lng=126.93, accuracy_m=accuracy, distance_m=160.0,
        client_uuid=uid,
    )


def gps_morning(user, place, day: int, prep_minutes: float) -> Event:
    """알람을 끄고 `prep_minutes` 뒤에 GPS 로 집을 나선 아침 하나."""
    event = make_event(user, place, day)
    dismiss(user, event, morning(day), f"dis-{day}")
    depart(user, event, morning(day) + timedelta(minutes=prep_minutes), f"dep-{day}")
    return event


class TestGpsPrepSamples:
    def test_prep_is_depart_minus_dismiss(self, user, place):
        gps_morning(user, place, 1, 42)
        assert prep_observations.gps_prep_samples(user.pk) == [42.0]

    def test_uses_last_dismiss_before_depart(self, user, place):
        """미루기 뒤 다시 울려 끈 경우 마지막 끄기부터 준비를 시작한 것이다."""
        event = make_event(user, place, 1)
        dismiss(user, event, morning(1), "dis-a")
        dismiss(user, event, morning(1) + timedelta(minutes=10), "dis-b")
        depart(user, event, morning(1) + timedelta(minutes=45), "dep")
        # 출발 뒤의 끄기는 무시한다.
        dismiss(user, event, morning(1) + timedelta(minutes=50), "dis-c")
        assert prep_observations.gps_prep_samples(user.pk) == [35.0]

    def test_uses_first_depart_of_the_event(self, user, place):
        event = gps_morning(user, place, 1, 30)
        depart(user, event, morning(1) + timedelta(minutes=55), "dep-again")
        assert prep_observations.gps_prep_samples(user.pk) == [30.0]

    def test_drops_untrustworthy_mornings(self, user, place):
        # 오차 반경이 크다
        e1 = make_event(user, place, 1)
        dismiss(user, e1, morning(1), "d1")
        depart(user, e1, morning(1) + timedelta(minutes=30), "p1", accuracy=300.0)
        # 직접 누른 출발(GPS 아님)
        e2 = make_event(user, place, 2)
        dismiss(user, e2, morning(2), "d2")
        depart(user, e2, morning(2) + timedelta(minutes=30), "p2", detector="manual")
        # 끈 기록이 없다
        e3 = make_event(user, place, 3)
        depart(user, e3, morning(3) + timedelta(minutes=30), "p3")
        # 짝이 잘못됐다(끄자마자 출발 / 6시간 넘게 뒤)
        gps_morning(user, place, 4, 0.5)
        gps_morning(user, place, 5, 400)
        # 출발지를 따로 고른 일정은 집에서 준비한 아침이 아니다
        e6 = make_event(user, place, 6, origin_lat=37.4979, origin_lng=127.0276)
        dismiss(user, e6, morning(6), "d6")
        depart(user, e6, morning(6) + timedelta(minutes=30), "p6")

        assert prep_observations.gps_prep_samples(user.pk) == []

    def test_skips_mornings_without_home_prep(self, user, place):
        """준비를 뺀 집 출발, 준비를 넣은 친구 집 출발은 둘 다 평소 준비가 아니다."""
        gps_morning(user, place, 1, 40)
        skipped = make_event(user, place, 2, include_prep=False)
        dismiss(user, skipped, morning(2), "d2")
        depart(user, skipped, morning(2) + timedelta(minutes=5), "p2")
        away = make_event(
            user, place, 3, origin_lat=37.4979, origin_lng=127.0276, include_prep=True
        )
        dismiss(user, away, morning(3), "d3")
        depart(user, away, morning(3) + timedelta(minutes=20), "p3")
        # 집에서 준비를 넣었다고 고른 아침은 그대로 쓴다.
        chosen = make_event(user, place, 4, include_prep=True)
        dismiss(user, chosen, morning(4), "d4")
        depart(user, chosen, morning(4) + timedelta(minutes=35), "p4")

        assert prep_observations.gps_prep_samples(user.pk) == [35.0, 40.0]

    def test_newest_first(self, user, place):
        gps_morning(user, place, 1, 30)
        gps_morning(user, place, 2, 40)
        gps_morning(user, place, 3, 50)
        assert prep_observations.gps_prep_samples(user.pk) == [50.0, 40.0, 30.0]

    def test_other_users_are_ignored(self, user, place, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="test12345", nickname="남"
        )
        gps_morning(other, place, 1, 40)
        assert prep_observations.gps_prep_samples(user.pk) == []


class TestWithinExpected:
    """고정 분이 아니라 사용자의 예상 준비 시간 대비 비율로 거른다."""

    def test_ratio_window_follows_the_user(self):
        # 평소 20분인 사람: 5~80분만 믿는다
        assert prep_observations.within_expected([4.0, 5.0, 80.0, 81.0], 20.0) == [5.0, 80.0]
        # 평소 90분인 사람: 같은 200분도 믿는다
        assert prep_observations.within_expected([200.0], 90.0) == [200.0]
        assert prep_observations.within_expected([200.0], 20.0) == []

    def test_keeps_newest_and_caps(self, monkeypatch):
        monkeypatch.setattr(prep_observations, "GPS_PREP_MAX_SAMPLES", 2)
        assert prep_observations.within_expected([50.0, 40.0, 30.0], 40.0) == [50.0, 40.0]

    def test_unknown_expectation_keeps_samples(self):
        assert prep_observations.within_expected([40.0], 0.0) == [40.0]


class TestEstimatePrepWithGps:
    def test_no_samples_keeps_prior(self, user, place):
        event = make_event(user, place, 10)
        prep = estimators.estimate_prep(event, user.profile, 30, gps_samples=[])
        assert prep.distribution == Normal(30.0, 0.0)
        assert prep.source == "onboarding"

    def test_samples_pull_onboarding_value_and_add_variance(self, user, place):
        """온보딩 값 하나뿐이던 사용자도 GPS 아침이 쌓이면 변동성이 생긴다."""
        event = make_event(user, place, 10)
        prep = estimators.estimate_prep(event, user.profile, 30, gps_samples=[40.0, 50.0, 45.0])
        # w = 3 / (3 + 3) = 0.5 → 평균은 30 과 45 의 가운데
        assert prep.distribution.mean == pytest.approx(37.5)
        assert prep.has_variance
        assert prep.source == "observed"

    def test_gps_outweighs_block_records(self):
        """같은 표본 수면 GPS 쪽 k(3)가 블록 k(5)보다 작아 더 세게 끌어당긴다."""
        assert estimators.GPS_PREP_SHRINKAGE_K < estimators.BLOCK_SHRINKAGE_K

    def test_far_off_mornings_do_not_move_the_estimate(self, user, place):
        """온보딩 30분인 사람의 5분·150분 아침은 판정 오류로 보고 버린다."""
        event = make_event(user, place, 10)
        prep = estimators.estimate_prep(event, user.profile, 30, gps_samples=[5.0, 150.0])
        assert prep.distribution == Normal(30.0, 0.0)
        assert prep.source == "onboarding"

    def test_block_breakdown_is_rescaled_to_the_total(self, user, place):
        RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=10, default_max_minutes=10, order=0
        )
        RoutineBlock.objects.create(
            user=user, name="옷 입기", default_min_minutes=10, default_max_minutes=10, order=1
        )
        event = make_event(user, place, 10)
        prep = estimators.estimate_prep(event, user.profile, 30, gps_samples=[40.0] * 3)
        # 사전 20분, GPS 40분, w=0.5 → 30분. 블록 비율(1:1)은 유지한다.
        assert prep.distribution.mean == pytest.approx(30.0)
        assert [row["minutes"] for row in prep.breakdown] == [15.0, 15.0]
        assert prep.block_count == 2


class TestAlarmUsesGps:
    def test_alarm_moves_earlier_when_gps_says_prep_is_longer(self, user, place):
        before = services.compute_and_store(make_event(user, place, 20))
        assert before.prep_minutes == 30

        for day in (1, 2, 3, 4, 5, 6):
            gps_morning(user, place, day, 50)
        after = services.compute_and_store(make_event(user, place, 21))

        assert after.prep_minutes > before.prep_minutes
        assert after.prep_source == "observed"

    def test_not_from_home_event_still_has_no_prep(self, user, place):
        for day in (1, 2, 3):
            gps_morning(user, place, day, 50)
        plan = services.compute_and_store(
            make_event(user, place, 20, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.prep_minutes == 0

    def test_away_morning_with_prep_uses_what_home_mornings_taught(self, user, place):
        for day in (1, 2, 3, 4, 5, 6):
            gps_morning(user, place, day, 50)
        home = services.compute_and_store(make_event(user, place, 20))
        away = services.compute_and_store(make_event(
            user, place, 21, origin_lat=37.4979, origin_lng=127.0276, include_prep=True,
        ))
        assert away.prep_source == "observed"
        assert away.prep_minutes == home.prep_minutes


class TestBlockRecordsAfterDeparture:
    @pytest.fixture
    def block(self, user):
        return RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=10, default_max_minutes=20, order=0
        )

    def record(self, user, block, event, at: datetime, minutes: float, uid: str):
        return BlockObservation.objects.create(
            user=user, block=block, event=event, observed_on=at.date(),
            duration_minutes=minutes, client_uuid=uid, client_recorded_at=at,
        )

    def test_records_after_gps_depart_are_excluded(self, user, place, block):
        event = gps_morning(user, place, 1, 40)
        kept = self.record(user, block, event, morning(1) + timedelta(minutes=15), 15, "b1")
        self.record(user, block, event, morning(1) + timedelta(minutes=90), 75, "b2")

        rows = prep_observations.exclude_after_departure(BlockObservation.objects.all())
        assert list(rows) == [kept]
        assert services._block_observation_stats(user.pk)[block.pk][2] == 1
        assert learning.block_stats(user=user)["샤워"]["n"] == 1
        assert learning.prep_total_stats(user=user)["prep_mean"] == 15.0

    def test_records_without_departure_are_kept(self, user, place, block):
        """출발 관측이 아직 안 올라왔으면 거를 근거가 없다. 나중에 올라오면 그때 빠진다."""
        event = make_event(user, place, 1)
        self.record(user, block, event, morning(1) + timedelta(minutes=90), 75, "b1")
        assert BlockObservation.objects.count() == 1
        assert prep_observations.exclude_after_departure(BlockObservation.objects.all()).count() == 1

        depart(user, event, morning(1) + timedelta(minutes=40), "dep")
        assert prep_observations.exclude_after_departure(BlockObservation.objects.all()).count() == 0

    def test_manual_depart_does_not_exclude(self, user, place, block):
        event = make_event(user, place, 1)
        depart(user, event, morning(1) + timedelta(minutes=40), "dep", detector="manual")
        self.record(user, block, event, morning(1) + timedelta(minutes=90), 75, "b1")
        assert prep_observations.exclude_after_departure(BlockObservation.objects.all()).count() == 1

    def test_records_without_event_are_kept(self, user, block):
        self.record(user, block, None, morning(1), 15, "b1")
        assert prep_observations.exclude_after_departure(BlockObservation.objects.all()).count() == 1
