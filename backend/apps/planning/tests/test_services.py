"""알람 계산 전체 배선 테스트.

카카오 경로 호출은 모킹한다. 테스트 설정에서 키가 비어 있어 실제로 부르면
`degraded` 로 떨어지고, 부를 수 있더라도 무료 쿼터를 테스트가 먹으면 안 된다.

**가장 중요한 확인은 회귀가 없다는 것이다.** 블록도 경로 보정도 없는 계정은
분포 엔진을 붙인 뒤에도 **전과 똑같은 알람**을 받아야 한다. 변동성을 모르는데
τ 를 반영하면 없는 근거로 사용자의 잠을 빼앗는다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from apps.events.models import Event, EventTag, Place
from apps.planning import services
from apps.planning.models import AlarmPlan
from apps.routines.models import RoutineBlock

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db


ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="plan@example.com", password="test12345", nickname="계획"
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
def stub_route(monkeypatch):
    """카카오 경로 조회를 고정값으로 바꾼다."""

    def _best(**kwargs):
        return dict(ROUTE), False

    def _resolve(key, **kwargs):
        return dict(ROUTE, key=key), False

    monkeypatch.setattr(services.clients, "best_route", _best)
    monkeypatch.setattr(services.clients, "resolve_route", _resolve)
    return ROUTE


def make_event(user, place, **kw):
    kw.setdefault("title", "수업")
    kw.setdefault("start_at", datetime(2026, 9, 21, 9, 0, tzinfo=KST))
    return Event.objects.create(user=user, place=place, **kw)


def make_block(user, name, lo, hi, *, order=0, parallel=False):
    return RoutineBlock.objects.create(
        user=user, name=name, default_min_minutes=lo, default_max_minutes=hi,
        order=order, parallelizable=parallel,
    )


class TestNoDataRegression:
    """블록도 보정도 없으면 1단계와 동일하게 동작해야 한다."""

    def test_matches_the_old_fixed_rule(self, user, place, stub_route):
        event = make_event(user, place)
        plan = services.compute_and_store(event)

        assert plan.status == AlarmPlan.Status.OK
        assert plan.prep_minutes == 30      # 온보딩 값
        assert plan.travel_minutes == 20    # 카카오 실측
        assert plan.buffer_minutes == 10    # 고정
        # 09:00 − 10 − 20 − 30 = 08:00
        assert plan.alarm_at == datetime(2026, 9, 21, 8, 0, tzinfo=KST)
        assert plan.depart_by == datetime(2026, 9, 21, 8, 30, tzinfo=KST)
        assert plan.arrive_at == datetime(2026, 9, 21, 8, 50, tzinfo=KST)

    def test_probability_stays_null(self, user, place, stub_route):
        plan = services.compute_and_store(make_event(user, place))
        assert plan.on_time_probability is None
        assert plan.confidence_basis == "point_estimate"

    def test_tau_does_not_move_the_alarm(self, user, place, stub_route):
        """변동성을 모르면 τ 를 올려도 알람이 안 변한다."""
        low = make_event(user, place, tau_override=0.55)
        high = make_event(user, place, tau_override=0.99,
                          start_at=datetime(2026, 9, 22, 9, 0, tzinfo=KST))
        p_low = services.compute_and_store(low)
        p_high = services.compute_and_store(high)
        assert p_low.prep_minutes == p_high.prep_minutes
        assert p_low.travel_minutes == p_high.travel_minutes

    def test_breakdown_is_empty_without_blocks(self, user, place, stub_route):
        plan = services.compute_and_store(make_event(user, place))
        assert plan.prep_breakdown == []
        assert plan.prep_source == "onboarding"


class TestWithBlocks:
    def test_blocks_replace_the_onboarding_value(self, user, place, stub_route):
        make_block(user, "세수", 8, 12, order=1)   # 평균 10
        make_block(user, "옷", 13, 17, order=2)    # 평균 15
        plan = services.compute_and_store(make_event(user, place))
        # 합성 τ 분위수라 평균 25 보다 크다. 온보딩 30 과는 무관하다.
        assert plan.prep_source == "declared_range"
        assert 25 <= plan.prep_minutes <= 32

    def test_tau_now_moves_the_alarm(self, user, place, stub_route):
        """블록 범위가 변동성을 주므로 τ 가 의미를 갖는다."""
        make_block(user, "샤워", 10, 30, order=1)  # 넓은 범위 → sd 5
        low = make_event(user, place, tau_override=0.55)
        high = make_event(user, place, tau_override=0.99,
                          start_at=datetime(2026, 9, 22, 9, 0, tzinfo=KST))
        p_low = services.compute_and_store(low)
        p_high = services.compute_and_store(high)
        assert p_high.prep_minutes > p_low.prep_minutes

    def test_still_no_probability_when_travel_variance_is_unknown(
        self, user, place, stub_route
    ):
        """**핵심 규칙.** 준비만 변동성이 있으면 확률을 내지 않는다.

        내면 이동의 불확실성을 0 으로 치므로 확신도를 과대 보고한다.
        """
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place))
        assert plan.on_time_probability is None
        assert plan.confidence_basis == "travel_variance_unknown"

    def test_breakdown_is_saved(self, user, place, stub_route):
        make_block(user, "샤워", 12, 18, order=1)
        make_block(user, "옷", 4, 6, order=2)
        plan = services.compute_and_store(make_event(user, place))
        names = [row["name"] for row in plan.prep_breakdown]
        assert names == ["샤워", "옷"]
        assert plan.prep_breakdown[0]["declared_min"] == 12

    def test_excluded_block_does_not_count(self, user, place, stub_route):
        from apps.routines.models import EventBlockSelection

        keep = make_block(user, "유지", 9, 11, order=1)
        drop = make_block(user, "제외", 29, 31, order=2)
        event = make_event(user, place)
        EventBlockSelection.objects.create(event=event, block=drop, checked=False)
        assert keep.pk

        plan = services.compute_and_store(event)
        assert len(plan.prep_breakdown) == 1
        assert plan.prep_breakdown[0]["name"] == "유지"


class TestWithFullVariance:
    """준비·이동 모두 변동성이 있을 때 확률이 나온다."""

    @pytest.fixture
    def correction(self):
        from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

        return RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, mode="", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY,
            factor=1.05, sd_minutes=5.0, sample_count=60,
        )

    def test_probability_appears(self, user, place, stub_route, correction):
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place))
        assert plan.on_time_probability is not None
        assert plan.confidence_basis == "observed"
        # 버퍼가 τ 위로 확신도를 더 사주므로 τ 보다 크다.
        assert plan.on_time_probability > 90

    def test_travel_source_records_the_correction(
        self, user, place, stub_route, correction
    ):
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place))
        assert "observed_global" in plan.travel_source

    def test_correction_factor_is_applied_to_travel(
        self, user, place, stub_route, correction
    ):
        """factor 1.05 → 20분이 21분 근처가 된다."""
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place))
        assert plan.travel_minutes >= 21

    def test_breakdown_sums_to_the_alarm_gap(
        self, user, place, stub_route, correction
    ):
        """화면에 셋을 나란히 보여주므로 합이 알람~시작 간격과 맞아야 한다."""
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place))
        gap = (plan.event.start_at - plan.alarm_at).total_seconds() / 60
        assert (
            plan.prep_minutes + plan.travel_minutes + plan.buffer_minutes
            == pytest.approx(gap)
        )


class TestFailureStates:
    def test_no_place(self, user, stub_route):
        event = Event.objects.create(
            user=user, title="장소 없음",
            start_at=datetime(2026, 9, 21, 9, 0, tzinfo=KST),
        )
        plan = services.compute_and_store(event)
        assert plan.status == AlarmPlan.Status.NO_PLACE
        assert plan.alarm_at is None
        assert plan.on_time_probability is None

    def test_no_origin(self, django_user_model, place, stub_route):
        u = django_user_model.objects.create_user(
            email="nohome@example.com", password="test12345", nickname="집없음"
        )
        event = make_event(u, place)
        plan = services.compute_and_store(event)
        assert plan.status == AlarmPlan.Status.NO_HOME

    def test_event_origin_works_without_a_home(
        self, django_user_model, place, stub_route
    ):
        """일정에 출발지가 있으면 집이 없어도 계산된다."""
        u = django_user_model.objects.create_user(
            email="origin@example.com", password="test12345", nickname="출발지"
        )
        event = make_event(u, place, origin_lat=37.5, origin_lng=127.0,
                          origin_label="회사")
        plan = services.compute_and_store(event)
        assert plan.status == AlarmPlan.Status.OK

    def test_route_failure(self, user, place, monkeypatch):
        monkeypatch.setattr(
            services.clients, "best_route", lambda **kw: (None, True)
        )
        plan = services.compute_and_store(make_event(user, place))
        assert plan.status == AlarmPlan.Status.ROUTE_FAILED
        assert plan.alarm_at is None

    def test_recompute_clears_stale_values(self, user, place, stub_route):
        """성공했다가 실패하면 낡은 시각이 남지 않아야 한다."""
        event = make_event(user, place)
        ok = services.compute_and_store(event)
        assert ok.alarm_at is not None

        event.place = None
        event.save()
        again = services.compute_and_store(event)
        assert again.status == AlarmPlan.Status.NO_PLACE
        assert again.alarm_at is None
        assert again.prep_minutes is None
        assert again.on_time_probability is None
        assert again.prep_breakdown == []


class TestTauFromTag:
    """태그는 `events/0002_seed_event_tags` 마이그레이션이 시드한다.

    새로 만들지 않고 시드된 값을 쓴다 — 실제로 쓰이는 τ 를 검증해야 의미가 있다.
    시드값: class 0.90 / exam 0.99 / meetup 0.85.
    """

    def test_tag_default_tau_is_used(self, user, place, stub_route):
        tag = EventTag.objects.get(key="exam")
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(make_event(user, place, tag=tag))
        assert plan.tau_used == pytest.approx(tag.default_tau)
        assert tag.default_tau == pytest.approx(0.99)

    def test_exam_wakes_you_earlier_than_a_meetup(self, user, place, stub_route):
        """시험(τ 0.99)이 약속(τ 0.85)보다 알람을 앞당긴다. τ 의 목적이다."""
        exam = EventTag.objects.get(key="exam")
        meetup = EventTag.objects.get(key="meetup")
        make_block(user, "샤워", 10, 30, order=1)

        p_exam = services.compute_and_store(make_event(user, place, tag=exam))
        p_meet = services.compute_and_store(
            make_event(user, place, tag=meetup,
                       start_at=datetime(2026, 9, 22, 9, 0, tzinfo=KST))
        )
        assert p_exam.prep_minutes > p_meet.prep_minutes

    def test_tau_override_beats_the_tag(self, user, place, stub_route):
        meetup = EventTag.objects.get(key="meetup")  # 0.85
        make_block(user, "샤워", 10, 30, order=1)
        plan = services.compute_and_store(
            make_event(user, place, tag=meetup, tau_override=0.99)
        )
        assert plan.tau_used == pytest.approx(0.99)


class TestRecomputeForUser:
    def test_recomputes_future_events_only(self, user, place, stub_route):
        from django.utils import timezone as dj_tz

        past = make_event(user, place,
                          start_at=dj_tz.now() - timedelta(days=2))
        future = make_event(user, place,
                            start_at=dj_tz.now() + timedelta(days=2))
        count = services.recompute_for_user(user)
        assert count == 1
        assert AlarmPlan.objects.filter(event=future).exists()
        assert not AlarmPlan.objects.filter(event=past).exists()
