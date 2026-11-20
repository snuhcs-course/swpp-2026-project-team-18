# Partly AI-generated (Claude)
"""정시 도착 확률 하나의 식(BE-P100, BE-H3c). `apps/planning/on_time.py`."""

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.planning import distributions as dist
from apps.planning import on_time, services
from apps.planning.estimators import PrepEstimate, TravelEstimate, compute_alarm_math
from apps.planning.models import AlarmPlan
from apps.routing.models import RouteCorrection
from apps.routines.models import RoutineBlock

KST = timezone(timedelta(hours=9))


# --- 순수 함수 -----------------------------------------------------------------


class TestNumbers:
    def test_precise_never_says_certain(self):
        total = dist.Normal(30.0, 1.0)
        assert on_time.precise_of(total, 200) == 99.9
        assert on_time.precise_of(total, 1) == 0.1  # 시간이 남아 있으면 0.0 이 아니다
        assert on_time.precise_of(total, 0) == 0.0
        assert on_time.precise_of(total, -5) == 0.0

    def test_precise_has_one_decimal(self):
        value = on_time.precise_of(dist.Normal(30.0, 5.0), 33)
        assert value == round(value, 1)
        assert 0.1 < value < 99.9

    @pytest.mark.parametrize("precise,whole", [
        (None, None), (0.0, 0), (0.1, 1), (0.4, 1), (0.5, 1), (1.5, 2), (2.5, 3),
        (98.4, 98), (98.5, 99), (99.4, 99), (99.9, 99),
    ])
    def test_whole_rounds_half_up_and_caps_at_99(self, precise, whole):
        assert on_time.whole(precise) == whole

    @pytest.mark.parametrize("precise,whole_value,expected", [
        (97.3, 97, 97.3), (None, 97, 97.0), (None, 100, 99.9), (None, 0, 0.0), (None, None, None),
    ])
    def test_stored_falls_back_to_the_old_integer(self, precise, whole_value, expected):
        assert on_time.stored(precise, whole_value) == expected

    def test_table_starts_at_zero_and_ends_at_the_cap(self):
        table = on_time.table(dist.Normal(30.0, 4.0))
        precise = table["precise"]
        assert table["unit"] == "minutes_before_start"
        assert precise[0] == 0.0
        assert precise[-1] == 99.9 and table["max_minutes"] == len(precise) - 1
        assert all(a <= b for a, b in zip(precise, precise[1:]))
        assert table["whole"] == [on_time.whole(p) for p in precise]
        assert max(table["whole"]) == 99

    def test_point_estimate_has_no_probability(self):
        prep = PrepEstimate(dist.Normal(30.0, 0.0))
        travel = TravelEstimate(dist.Normal(20.0, 0.0))
        assert on_time.can_tell(prep, travel) is False
        assert on_time.function(prep, travel, datetime(2026, 11, 21, 9, tzinfo=KST))(
            datetime(2026, 11, 21, 8, tzinfo=KST)
        ) is None

    def test_one_side_is_enough(self):
        prep = PrepEstimate(dist.Normal(30.0, 5.0))
        travel = TravelEstimate(dist.Normal(20.0, 0.0))
        assert on_time.can_tell(prep, travel)
        assert on_time.can_tell(None, TravelEstimate(dist.Normal(20.0, 3.0)))
        assert not on_time.can_tell(None, TravelEstimate(dist.Normal(20.0, 0.0)))

    def test_plan_probability_is_the_value_at_its_own_alarm_time(self):
        """계획 확률은 실제 알람 시각(반올림한 분위수 + 버퍼)에 일어날 때의 값이다."""
        prep = PrepEstimate(dist.Normal(30.0, 6.0), source="observed")
        travel = TravelEstimate(dist.Normal(20.0, 8.0), raw_minutes=20.0, source="observed_route")
        start = datetime(2026, 11, 21, 9, tzinfo=KST)
        math_ = compute_alarm_math(prep, travel, buffer_minutes=5, tau=0.9)
        at = start - timedelta(minutes=math_.total_minutes)
        assert on_time.function(prep, travel, start)(at) == math_.on_time_probability_precise
        assert on_time.whole(math_.on_time_probability_precise) == math_.on_time_probability


# --- API ----------------------------------------------------------------------



@pytest.fixture
def ctx(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(email="ontime@test.com", password="test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="학교", lat=37.46, lng=126.95)
    start = (datetime.now(KST) + timedelta(days=2)).replace(hour=9, minute=0, second=0, microsecond=0)
    event = Event.objects.create(user=user, place=place, title="수업", start_at=start)
    route = {"minutes": 20, "mode": "transit", "key": "transit:test", "source": "kakao"}
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(route), False))
    client = APIClient()
    client.force_authenticate(user)
    return client, event


def _vary(event, *, prep=(10, 30), travel_sd=6):
    RoutineBlock.objects.create(
        user=event.user, name="샤워", default_min_minutes=prep[0], default_max_minutes=prep[1],
    )
    if travel_sd is not None:
        RouteCorrection.objects.create(
            route_signature="transit:test", mode="transit", factor=1.2,
            sd_minutes=travel_sd, sample_count=20,
        )
    services.compute_and_store(event, trigger="create")


def _risk(client, event):
    response = client.get(f"/api/events/{event.pk}/risk-options")
    assert response.status_code == 200
    return response.json()


def _lead(event, at_iso) -> int:
    return int((event.start_at - datetime.fromisoformat(at_iso)).total_seconds() // 60)


def _plan(client, event):
    response = client.get(f"/api/events/{event.pk}")
    assert response.status_code == 200
    return response.json()["alarm_plan"]


@pytest.mark.django_db
@pytest.mark.integration
def test_narrow_spread_never_shows_100(ctx):
    """BE-P100: 흔들림이 작고 버퍼가 크면 세 칸이 예전엔 100 / 100 / 100 이었다."""
    client, event = ctx
    _vary(event, prep=(14, 16), travel_sd=1)
    data = _risk(client, event)
    for option in data["options"]:
        assert option["probability"] <= 99
        assert option["probability_precise"] <= 99.9
        assert option["probability"] == on_time.whole(option["probability_precise"])
    plan = _plan(client, event)
    assert plan["on_time_probability"] <= 99
    assert plan["effective_on_time_probability"] <= 99
    assert plan["on_time_probability_precise"] <= 99.9


@pytest.mark.django_db
@pytest.mark.integration
def test_table_agrees_with_options_plan_and_saved_user_time(ctx):
    """BE-H3c: 휠 표 · 세 칸 · 계획 · 저장한 직접 시각이 같은 숫자다."""
    client, event = ctx
    _vary(event)
    data = _risk(client, event)
    table = data["probability_table"]
    precise, whole = table["precise"], table["whole"]

    def look(lead):
        index = min(lead, table["max_minutes"])
        return precise[index], whole[index]

    for option in data["options"]:
        assert look(_lead(event, option["alarm_at"])) == (
            option["probability_precise"], option["probability"],
        )

    plan = _plan(client, event)
    assert look(_lead(event, plan["effective_alarm_at"])) == (
        plan["effective_on_time_probability_precise"], plan["effective_on_time_probability"],
    )

    # 세 칸이 아닌 시각(추천 + 7분)을 휠로 골라 저장한다.
    picked = datetime.fromisoformat(plan["alarm_at"]) + timedelta(minutes=7)
    response = client.put(
        f"/api/events/{event.pk}/alarm/time", {"alarm_at": picked.isoformat()}, format="json",
    )
    assert response.status_code == 200, response.content
    saved = response.json()["alarm_plan"]
    lead = _lead(event, picked.isoformat())
    assert (saved["user_alarm_probability_precise"], saved["user_alarm_probability"]) == look(lead)
    assert saved["effective_on_time_probability_precise"] == saved["user_alarm_probability_precise"]


@pytest.mark.django_db
@pytest.mark.integration
def test_one_side_variance_shows_everywhere(ctx):
    """11-20 결정: 준비만 흔들려도 계획 · 세 칸 · 휠 표 · 직접 시각에 확률이 있다."""
    client, event = ctx
    _vary(event, travel_sd=None)
    plan = _plan(client, event)
    assert plan["confidence_basis"] == "travel_variance_unknown"
    assert plan["on_time_probability"] is not None
    data = _risk(client, event)
    assert data["probability_table"] is not None
    assert all(o["probability"] is not None for o in data["options"])
    picked = datetime.fromisoformat(plan["alarm_at"]) + timedelta(minutes=6)
    saved = client.put(
        f"/api/events/{event.pk}/alarm/time", {"alarm_at": picked.isoformat()}, format="json",
    ).json()["alarm_plan"]
    assert saved["user_alarm_probability"] is not None


@pytest.mark.django_db
@pytest.mark.integration
def test_point_estimate_has_no_table(ctx):
    client, event = ctx
    services.compute_and_store(event, trigger="create")  # 블록 · 보정 없음
    data = _risk(client, event)
    assert data["options"] == [] and data["probability_table"] is None
    assert data["unavailable_reason"]
    assert _plan(client, event)["on_time_probability"] is None


@pytest.mark.django_db
@pytest.mark.integration
def test_rows_stored_before_the_decimal_columns(ctx):
    """0.21.0 이 저장한 100 은 99 / 99.9 로 내보낸다. 소수 칸이 비어도 응답이 깨지지 않는다."""
    client, event = ctx
    _vary(event)
    AlarmPlan.objects.filter(event=event).update(
        on_time_probability=100, on_time_probability_precise=None,
    )
    plan = _plan(client, event)
    assert plan["on_time_probability"] == 99
    assert plan["on_time_probability_precise"] == 99.9
    assert plan["effective_on_time_probability"] == 99
