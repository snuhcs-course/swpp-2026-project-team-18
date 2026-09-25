"""준비 시간이 **해당되는 일정**과 그렇지 않은 일정.

준비 시간은 집에서 씻고 옷을 입고 나서는 시간이다. 이미 밖에 있는 사람이 다음
일정으로 갈 때는 그 항목이 없다. 그런데 계산기는 출발지와 무관하게 늘 준비
시간을 넣고 있었다.

신호는 `Event.origin_lat` 이다. 그 필드가 있다는 것 자체가 "이 일정만의 출발지를
따로 골랐다" 는 뜻이다(`Event.resolve_origin`). 좌표를 집과 비교하지 않는다 —
집 근처 카페를 골랐어도 그건 집이 아니고 준비 단계도 아니다.

## 0 으로 만들어 넘기면 안 되는 이유

`PrepEstimate(distribution=Normal(0, 0))` 을 넘기면 `has_variance` 가 False 가
되어 **정시 도착 확률이 영영 안 나온다.** 이동에 변동성이 있어도 그렇다. 그래서
`compute_alarm_math(prep=None, ...)` 경로를 따로 둔다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from apps.events.models import Event, Place
from apps.planning import estimators, services
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

# 이동에 변동성을 주려면 관측이 필요하다. 여기서는 점추정치만 쓰고, 확률은
# 별도 테스트에서 분포를 직접 만들어 확인한다.
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
        email="prep@example.com", password="test12345", nickname="준비"
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


def make_event(user, place, **kw):
    kw.setdefault("title", "수업")
    kw.setdefault("start_at", datetime(2026, 9, 21, 9, 0, tzinfo=KST))
    return Event.objects.create(user=user, place=place, **kw)


class TestFromHome:
    """출발지를 따로 고르지 않은 일정. 지금까지의 동작이 그대로여야 한다."""

    def test_prep_is_included(self, user, place):
        plan = services.compute_and_store(make_event(user, place))
        assert plan.status == AlarmPlan.Status.OK
        assert plan.prep_minutes == 30
        assert plan.prep_source == "onboarding"

    def test_alarm_is_prep_before_departure(self, user, place):
        plan = services.compute_and_store(make_event(user, place))
        assert (plan.depart_by - plan.alarm_at) == timedelta(minutes=30)


class TestNotFromHome:
    """일정마다 출발지를 고른 경우. 준비 단계가 없다."""

    def test_prep_is_dropped(self, user, place):
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276, origin_label="강남역")
        )
        assert plan.status == AlarmPlan.Status.OK
        assert plan.prep_minutes == 0

    def test_source_says_why(self, user, place):
        """0분과 "해당되지 않는다" 를 화면이 구분할 수 있어야 한다."""
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.prep_source == estimators.PREP_SOURCE_NOT_FROM_HOME

    def test_no_prep_breakdown_is_left_behind(self, user, place):
        """준비 시간이 0인데 "샤워 14분" 이 남아 있으면 화면이 거짓을 그린다."""
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.prep_breakdown == []

    def test_alarm_equals_departure(self, user, place):
        """준비 시간이 없으면 알람은 곧 출발 시각이다."""
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.alarm_at == plan.depart_by

    def test_total_is_travel_plus_buffer(self, user, place):
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.travel_minutes == 20
        assert plan.buffer_minutes == services.DEFAULT_BUFFER_MINUTES
        assert plan.total_minutes == 30

    def test_arrival_and_appointment_are_unchanged(self, user, place):
        """버퍼 규칙은 그대로다. 준비만 빠진다."""
        event = make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        plan = services.compute_and_store(event)
        assert plan.arrive_at == event.start_at - timedelta(
            minutes=services.DEFAULT_BUFFER_MINUTES
        )

    def test_blocks_are_ignored(self, user, place):
        """루틴 블록이 있어도 집에서 출발하지 않으면 준비 단계가 없다."""
        from apps.routines.models import RoutineBlock

        RoutineBlock.objects.create(
            user=user, name="샤워", default_min_minutes=12, default_max_minutes=18, order=0
        )
        plan = services.compute_and_store(
            make_event(user, place, origin_lat=37.4979, origin_lng=127.0276)
        )
        assert plan.prep_minutes == 0
        assert plan.prep_breakdown == []


class TestMathWithoutPrep:
    """`compute_alarm_math(prep=None)` 의 계약."""

    def _travel(self, sd: float) -> estimators.TravelEstimate:
        from apps.planning import distributions as dist

        return estimators.TravelEstimate(
            distribution=dist.Normal(20.0, sd), raw_minutes=20.0, source="kakao"
        )

    def test_probability_comes_from_travel_alone(self):
        """이동에 변동성이 있으면 확률을 낸다.

        0 으로 만들어 넘기면 `has_variance` 가 False 가 되어 확률이 사라진다.
        준비 단계가 없는 것은 "모른다" 가 아니므로 확률을 못 낼 이유가 없다.
        """
        math_ = estimators.compute_alarm_math(None, self._travel(4.0), 10, 0.9)
        assert math_.on_time_probability is not None
        assert math_.confidence_basis == "observed"

    def test_basis_is_travel_only_when_travel_is_a_point(self):
        """`travel_variance_unknown` 을 재사용하지 않는다.

        그 값의 화면 문구는 "준비 시간은 분포가 있지만" 으로 시작한다. 준비가
        아예 없는 일정에서는 틀린 설명이다.
        """
        math_ = estimators.compute_alarm_math(None, self._travel(0.0), 10, 0.9)
        assert math_.on_time_probability is None
        assert math_.confidence_basis == estimators.BASIS_TRAVEL_ONLY

    def test_minutes_add_up(self):
        math_ = estimators.compute_alarm_math(None, self._travel(0.0), 10, 0.9)
        assert math_.prep_minutes == 0
        assert math_.prep_minutes + math_.travel_minutes + math_.buffer_minutes == (
            math_.total_minutes
        )

    def test_source_and_breakdown_are_emptied(self):
        math_ = estimators.compute_alarm_math(None, self._travel(2.0), 10, 0.9)
        assert math_.prep_source == estimators.PREP_SOURCE_NOT_FROM_HOME
        assert math_.prep_breakdown == []

    def test_prep_with_variance_still_works(self):
        """기존 경로가 그대로여야 한다."""
        from apps.planning import distributions as dist

        prep = estimators.PrepEstimate(
            distribution=dist.Normal(30.0, 5.0), block_count=1, source="declared_range"
        )
        math_ = estimators.compute_alarm_math(prep, self._travel(4.0), 10, 0.9)
        assert math_.prep_minutes > 0
        assert math_.confidence_basis == "observed"
        assert math_.prep_source == "declared_range"
