"""리포트·캘리브레이션 테스트.

## 이 테스트가 지키는 것

리포트의 목적은 **앱이 틀렸는지 사용자에게 보여주는 것**이다. 그래서 여기서
가장 중요한 실패는 "숫자가 좋게 나오는 것" 이다.

    - 결과를 모르는 아침을 정시로 세지 않는다        → 정시율 부풀리기
    - 표본 1건으로 판정하지 않는다                  → 우연을 계통 오차로 보고
    - 빈 버킷을 0 으로 그리지 않는다                → 모델이 틀린 것처럼 보임
    - 측정 못 한 원인을 0 으로 두지 않는다          → 엉뚱한 곳을 고치게 함
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.planning.models import AlarmPlan
from apps.reports import services
from apps.routines.models import BlockObservation, RoutineBlock

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="rep@example.com", password="Zx8-velvet-harbor-40", nickname="리포트"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def other(django_user_model):
    return django_user_model.objects.create_user(
        email="rep2@example.com", password="Nb3-quartz-lagoon-77", nickname="남"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def place():
    return Place.objects.create(
        name="서울대", lat=37.459786, lng=126.951124, address="관악로 1"
    )


def make_past_event(
    user,
    place,
    *,
    days_ago: int = 3,
    hour: int = 9,
    tau: float | None = 0.90,
    prep: int | None = 30,
    travel: int | None = 20,
    buffer_minutes: int | None = 10,
) -> Event:
    """지난 일정 + 계획. 도착 관측은 호출부가 붙인다."""
    start = (timezone.now() - timedelta(days=days_ago)).replace(
        hour=hour, minute=0, second=0, microsecond=0
    )
    event = Event.objects.create(
        user=user, title=f"일정 -{days_ago}d", start_at=start, place=place
    )
    if tau is None:
        return event

    total = (prep or 0) + (travel or 0) + (buffer_minutes or 0)
    AlarmPlan.objects.create(
        # AlarmPlan 은 user 를 따로 들고 있다. 일정에서 유도하지 않는다 —
        # 사용자별 조회가 일정 조인 없이 가능해야 한다.
        user=user,
        event=event,
        status=AlarmPlan.Status.OK,
        alarm_at=start - timedelta(minutes=total),
        depart_by=start - timedelta(minutes=(travel or 0)),
        arrive_at=start,
        prep_minutes=prep,
        travel_minutes=travel,
        buffer_minutes=buffer_minutes,
        # total_minutes 는 프로퍼티다. 세 값에서 파생되므로 따로 저장하지 않는다.
        tau_used=tau,
    )
    return event


def observe(event, kind: str, at, *, accuracy: float = 20.0, distance: float = 30.0):
    return TripObservation.objects.create(
        user=event.user,
        event=event,
        kind=kind,
        detector=TripObservation.Detector.GPS,
        observed_at=at,
        lat=37.46,
        lng=126.95,
        accuracy_m=accuracy,
        distance_m=distance,
        client_uuid=f"{kind}-{event.pk}",
    )


def arrive_early(event, minutes: int = 5):
    """일정 시작 `minutes` 분 전 도착 = 정시."""
    observe(event, TripObservation.Kind.ARRIVE, event.start_at - timedelta(minutes=minutes))


def arrive_late(event, minutes: int = 8):
    observe(event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=minutes))


class TestOutcomes:

    def test_future_events_are_excluded(self, user, place):
        """아직 오지 않은 일정을 세면 정시율이 부풀려진다."""
        # days_ago 를 음수로 주면 미래 일정이 된다. 계획 값은 모델
        # CheckConstraint 가 status=ok 에 전부 요구하므로 헬퍼를 그대로 쓴다.
        make_past_event(user, place, days_ago=-2)
        make_past_event(user, place, days_ago=3)

        outcomes = services.collect_outcomes(user)
        assert [o.event.title for o in outcomes] == ["일정 -3d"]

    def test_slack_is_positive_when_early(self, user, place):
        event = make_past_event(user, place)
        arrive_early(event, minutes=7)
        outcome = services.collect_outcomes(user)[0]
        assert outcome.slack_minutes == pytest.approx(7.0)
        assert outcome.on_time is True

    def test_slack_is_negative_when_late(self, user, place):
        event = make_past_event(user, place)
        arrive_late(event, minutes=12)
        outcome = services.collect_outcomes(user)[0]
        assert outcome.slack_minutes == pytest.approx(-12.0)
        assert outcome.on_time is False

    def test_no_arrival_means_unknown_not_on_time(self, user, place):
        """**도착 관측이 없으면 모른다.** 정시로 세면 정시율이 거짓이 된다."""
        make_past_event(user, place)
        outcome = services.collect_outcomes(user)[0]
        assert outcome.slack_minutes is None
        assert outcome.on_time is None

    def test_earliest_arrival_wins(self, user, place):
        """같은 종류가 여러 건이면 가장 이른 것. 잠깐 돌아온 경우에 부풀지 않게."""
        event = make_past_event(user, place)
        first = event.start_at - timedelta(minutes=5)
        observe(event, TripObservation.Kind.ARRIVE, first)
        TripObservation.objects.create(
            user=user,
            event=event,
            kind=TripObservation.Kind.ARRIVE,
            detector=TripObservation.Detector.GPS,
            observed_at=event.start_at + timedelta(minutes=30),
            lat=37.46,
            lng=126.95,
            accuracy_m=20,
            distance_m=30,
            client_uuid="arrive-second",
        )
        outcome = services.collect_outcomes(user)[0]
        assert outcome.arrive_at == first


class TestBuckets:

    def test_bucket_edges(self):
        assert services.bucket_for(0.90) == (0.90, 0.95)
        assert services.bucket_for(0.94999) == (0.90, 0.95)
        assert services.bucket_for(0.95) == (0.95, 1.0)

    def test_bucket_clamps_out_of_range(self):
        # 서버가 이상값을 저장한 과거 데이터가 있을 수 있다. 버킷 계산이
        # 터지면 리포트 화면 전체가 막힌다.
        assert services.bucket_for(0.1) == (0.50, 0.55)
        assert services.bucket_for(1.5) == (0.95, 1.0)


class TestCalibration:

    def test_empty_history_gives_no_verdict(self, user):
        """표본 없이 '잘 맞음' 을 말하면 사용자가 여유를 줄인다."""
        result = services.calibration(user)
        assert result["buckets"] == []
        assert result["on_time_rate"] is None
        assert result["mean_gap"] is None
        assert result["verdict"] == "insufficient"

    def test_counts_only_scored_events(self, user, place):
        scored = make_past_event(user, place, days_ago=3)
        arrive_early(scored)
        make_past_event(user, place, days_ago=4)  # 도착 관측 없음

        result = services.calibration(user)
        assert result["scored_count"] == 1
        assert result["unscored_count"] == 1

    def test_empty_buckets_are_not_reported(self, user, place):
        """빈 버킷을 0 으로 그리면 그래프가 바닥에 붙어 완전히 틀린 것처럼 보인다."""
        event = make_past_event(user, place, tau=0.90)
        arrive_early(event)

        result = services.calibration(user)
        assert len(result["buckets"]) == 1
        assert result["buckets"][0]["center"] == pytest.approx(0.925)

    def test_single_sample_bucket_is_not_reliable(self, user, place):
        event = make_past_event(user, place, tau=0.90)
        arrive_late(event)

        result = services.calibration(user)
        bucket = result["buckets"][0]
        assert bucket["actual_rate"] == 0.0
        assert bucket["reliable"] is False
        # 신뢰 가능한 버킷이 없으므로 판정을 내리지 않는다.
        assert result["verdict"] == "insufficient"
        assert result["mean_gap"] is None

    def test_overconfident_is_detected(self, user, place):
        """0.90 이라고 말했는데 절반만 정시면 과신이다."""
        for i in range(4):
            event = make_past_event(user, place, days_ago=3 + i, tau=0.90)
            if i < 2:
                arrive_early(event)
            else:
                arrive_late(event)

        result = services.calibration(user)
        bucket = result["buckets"][0]
        assert bucket["total"] == 4
        assert bucket["actual_rate"] == 0.5
        assert bucket["reliable"] is True
        assert bucket["gap"] == pytest.approx(0.5 - 0.925)
        assert result["verdict"] == "overconfident"

    def test_calibrated_is_detected(self, user, place):
        # 0.90~0.95 버킷의 중앙값은 0.925 다. 10건 중 9건 정시면 거의 맞는다.
        for i in range(10):
            event = make_past_event(user, place, days_ago=3 + i, tau=0.92)
            if i == 0:
                arrive_late(event)
            else:
                arrive_early(event)

        result = services.calibration(user)
        assert result["verdict"] == "calibrated"

    def test_conservative_is_detected(self, user, place):
        """τ 를 낮게 잡았는데 늘 정시면 알람이 필요 이상으로 이르다."""
        for i in range(6):
            event = make_past_event(user, place, days_ago=3 + i, tau=0.55)
            arrive_early(event)

        result = services.calibration(user)
        assert result["verdict"] == "conservative"
        assert result["mean_gap"] > 0

    def test_plans_without_tau_are_skipped(self, user, place):
        event = make_past_event(user, place, tau=None)
        arrive_early(event)
        result = services.calibration(user)
        assert result["scored_count"] == 0
        assert result["unscored_count"] == 1


class TestLatenessCauses:

    def test_no_late_events_gives_empty(self, user, place):
        event = make_past_event(user, place)
        arrive_early(event)
        assert services.lateness_causes(user) == []

    def test_travel_over_is_measured_from_the_pair(self, user, place):
        event = make_past_event(user, place, travel=20)
        # 계획 출발마감에 나갔지만 이동이 32분 걸렸다 → 12분 초과
        observe(event, TripObservation.Kind.DEPART, event.start_at - timedelta(minutes=20))
        observe(event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=12))

        cause = services.lateness_causes(user)[0]
        assert cause.late_minutes == 12
        assert cause.travel_over == pytest.approx(12.0)
        assert cause.depart_late == pytest.approx(0.0)
        assert cause.primary == "travel_over"

    def test_depart_late_is_measured(self, user, place):
        event = make_past_event(user, place, travel=20)
        # 10분 늦게 나갔고 이동은 예측대로 20분
        observe(event, TripObservation.Kind.DEPART, event.start_at - timedelta(minutes=10))
        observe(event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=10))

        cause = services.lateness_causes(user)[0]
        assert cause.depart_late == pytest.approx(10.0)
        assert cause.travel_over == pytest.approx(0.0)
        assert cause.primary == "depart_late"

    def test_unmeasured_causes_are_none_not_zero(self, user, place):
        """**0 으로 채우면 '그 요인은 괜찮았다' 로 읽혀 엉뚱한 곳을 고친다.**"""
        event = make_past_event(user, place)
        arrive_late(event)  # 출발 관측 없음, 블록 관측 없음

        cause = services.lateness_causes(user)[0]
        assert cause.travel_over is None
        assert cause.depart_late is None
        assert cause.prep_over is None
        assert set(cause.unmeasured) == {"prep_over", "travel_over", "depart_late"}
        # 측정된 것이 없으면 주원인을 고를 수 없다.
        assert cause.primary is None

    def test_prep_over_uses_block_observations(self, user, place):
        event = make_past_event(user, place, prep=30)
        block = RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=12, default_max_minutes=18
        )
        BlockObservation.objects.create(
            user=user,
            block=block,
            event=event,
            observed_on=timezone.localdate(),
            duration_minutes=42.0,
            client_uuid="blk-1",
            client_recorded_at=timezone.now(),
        )
        observe(event, TripObservation.Kind.DEPART, event.start_at - timedelta(minutes=20))
        arrive_late(event, minutes=12)

        cause = services.lateness_causes(user)[0]
        assert cause.prep_over == pytest.approx(12.0)
        assert cause.primary == "prep_over"

    def test_parallel_blocks_are_excluded_from_prep(self, user, place):
        """계획이 병렬을 합으로 더하지 않으므로 실제에서도 빼야 같은 기준이다."""
        event = make_past_event(user, place, prep=30)
        serial = RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=12, default_max_minutes=18
        )
        parallel = RoutineBlock.objects.create(
            user=user,
            name="세탁기",
            default_min_minutes=5,
            default_max_minutes=5,
            parallelizable=True,
        )
        BlockObservation.objects.create(
            user=user, block=serial, event=event,
            observed_on=timezone.localdate(), duration_minutes=30.0,
            client_uuid="blk-s", client_recorded_at=timezone.now(),
        )
        BlockObservation.objects.create(
            user=user, block=parallel, event=event,
            observed_on=timezone.localdate(), duration_minutes=40.0,
            was_parallel=True,
            client_uuid="blk-p", client_recorded_at=timezone.now(),
        )
        arrive_late(event)

        cause = services.lateness_causes(user)[0]
        # 병렬 40분을 더하지 않았으므로 초과가 0 이다.
        assert cause.prep_over == pytest.approx(0.0)

    def test_nothing_measured_is_labelled_unknown(self, user, place):
        """**측정 실패를 '계획이 짧았다' 로 부르면 거짓말이다.**

        출발 관측이 없으면 아무것도 재지 못한다. 그걸 계획 탓으로 돌리면
        사용자는 앱의 계산을 의심하는데, 실제로는 추적이 동작하지 않은 것이다.
        """
        event = make_past_event(user, place)
        arrive_late(event)

        cause = services.lateness_causes(user)[0]
        assert cause.primary is None
        assert cause.label == "unknown"

    def test_all_measured_without_overshoot_is_plan_too_tight(self, user, place):
        """전부 측정했는데 초과가 없으면 계획에 여유가 없었다는 뜻이다."""
        event = make_past_event(user, place, prep=30, travel=20)
        block = RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=12, default_max_minutes=18
        )
        BlockObservation.objects.create(
            user=user, block=block, event=event,
            observed_on=timezone.localdate(), duration_minutes=28.0,
            client_uuid="blk-tight", client_recorded_at=timezone.now(),
        )
        # 계획보다 1분 일찍 나갔고 이동도 계획보다 짧았는데 늦었다.
        # (계획 자체가 도착 여유를 남기지 않은 경우다)
        observe(
            event, TripObservation.Kind.DEPART, event.start_at - timedelta(minutes=21)
        )
        observe(
            event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=2)
        )
        # 실제 이동 23분 > 계획 20분이라 travel_over 가 양수가 된다.
        # 그 경우는 위 테스트가 덮으므로 여기서는 이동을 계획보다 짧게 만든다.
        TripObservation.objects.filter(
            event=event, kind=TripObservation.Kind.ARRIVE
        ).update(observed_at=event.start_at - timedelta(minutes=2))
        # 이제 정시가 되므로 일정 시작을 당겨 지각으로 만든다.
        event.start_at = event.start_at - timedelta(minutes=5)
        event.save()

        cause = services.lateness_causes(user)[0]
        assert cause.unmeasured == []
        assert cause.primary is None
        assert cause.label == "plan_too_tight"


class TestWeekly:

    def test_defaults_to_last_week(self, user, place):
        """이번 주는 진행 중이라 값이 매 시간 달라진다. 사용자가 최종인지 모른다."""
        report = services.weekly(user)
        start, end = services.week_bounds()
        assert report["week_start"] == start.date().isoformat()
        # 지난 주 월요일이어야 한다.
        assert start.weekday() == 0
        assert start < timezone.now()

    def test_unobserved_mornings_are_reported(self, user, place):
        """숨기면 정시율이 실제보다 좋아 보인다."""
        start, _ = services.week_bounds()
        anchor = start.date()

        seen = make_past_event(user, place, days_ago=0)
        seen.start_at = start + timedelta(days=1, hours=9)
        seen.save()
        arrive_early(seen)

        unseen = make_past_event(user, place, days_ago=0)
        unseen.start_at = start + timedelta(days=2, hours=9)
        unseen.save()

        report = services.weekly(user, anchor=anchor)
        assert report["event_count"] == 2
        assert report["arrived_count"] == 1
        assert report["unobserved_count"] == 1
        assert report["on_time_rate"] == 1.0

    def test_weekday_pattern_has_seven_slots(self, user, place):
        report = services.weekly(user)
        assert [row["weekday"] for row in report["by_weekday"]] == [0, 1, 2, 3, 4, 5, 6]

    def test_tightest_slack_is_reported(self, user, place):
        """정시였어도 여유가 1분이면 운이 좋았던 것이다."""
        start, _ = services.week_bounds()
        anchor = start.date()

        for index, minutes in enumerate((20, 1, 15)):
            event = make_past_event(user, place, days_ago=0)
            event.start_at = start + timedelta(days=index, hours=9)
            event.save()
            observe(
                event,
                TripObservation.Kind.ARRIVE,
                event.start_at - timedelta(minutes=minutes),
            )

        report = services.weekly(user, anchor=anchor)
        assert report["tightest_slack_minutes"] == pytest.approx(1.0)
        assert report["median_slack_minutes"] == pytest.approx(15.0)

    def test_unmeasurable_lateness_counts_as_unknown(self, user, place):
        """측정 실패를 계획 탓으로 합치지 않는다."""
        start, _ = services.week_bounds()
        anchor = start.date()

        event = make_past_event(user, place, days_ago=0)
        event.start_at = start + timedelta(days=1, hours=9)
        event.save()
        arrive_late(event)

        report = services.weekly(user, anchor=anchor)
        assert report["late_count"] == 1
        assert report["primary_counts"] == {"unknown": 1}

    def test_measured_cause_is_counted_by_name(self, user, place):
        start, _ = services.week_bounds()
        anchor = start.date()

        event = make_past_event(user, place, days_ago=0, travel=20)
        event.start_at = start + timedelta(days=2, hours=9)
        event.save()
        # 계획 출발마감에 나갔지만 이동이 오래 걸렸다.
        observe(
            event, TripObservation.Kind.DEPART, event.start_at - timedelta(minutes=20)
        )
        observe(
            event, TripObservation.Kind.ARRIVE, event.start_at + timedelta(minutes=9)
        )

        report = services.weekly(user, anchor=anchor)
        assert report["primary_counts"] == {"travel_over": 1}


class TestApi:

    def test_weekly_requires_authentication(self):
        assert APIClient().get("/api/reports/weekly").status_code == 401

    def test_calibration_requires_authentication(self):
        assert APIClient().get("/api/reports/calibration").status_code == 401

    def test_weekly_returns_shape(self, client, user, place):
        event = make_past_event(user, place)
        arrive_early(event)
        res = client.get("/api/reports/weekly")
        assert res.status_code == 200
        for key in (
            "week_start",
            "week_end",
            "event_count",
            "arrived_count",
            "unobserved_count",
            "on_time_count",
            "late_count",
            "on_time_rate",
            "by_weekday",
            "late_causes",
            "primary_counts",
            "calibration",
        ):
            assert key in res.data, key

    def test_calibration_returns_shape(self, client):
        res = client.get("/api/reports/calibration")
        assert res.status_code == 200
        for key in (
            "buckets",
            "scored_count",
            "unscored_count",
            "reliable_bucket_count",
            "mean_gap",
            "on_time_rate",
            "verdict",
        ):
            assert key in res.data, key

    def test_bad_week_falls_back_instead_of_400(self, client):
        """읽기 전용 화면을 쿼리 하나로 막을 이유가 없다."""
        res = client.get("/api/reports/weekly?week=notadate")
        assert res.status_code == 200
        assert res.data["week_start"] == services.week_bounds()[0].date().isoformat()

    def test_explicit_week_is_honored(self, client, user, place):
        target = timezone.localdate() - timedelta(days=21)
        res = client.get(f"/api/reports/weekly?week={target.isoformat()}")
        assert res.status_code == 200
        monday = target - timedelta(days=target.weekday())
        assert res.data["week_start"] == monday.isoformat()

    def test_never_shows_another_users_data(self, client, other, place):
        """리포트는 개인의 지각 이력이다."""
        event = make_past_event(other, place)
        arrive_late(event)

        res = client.get("/api/reports/calibration")
        assert res.data["scored_count"] == 0
        assert res.data["unscored_count"] == 0

        weekly = client.get("/api/reports/weekly")
        assert weekly.data["event_count"] == 0
