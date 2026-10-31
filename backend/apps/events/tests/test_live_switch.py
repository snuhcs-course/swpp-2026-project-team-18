# Partly AI-generated (Claude)
"""이동 중 "지금 경로로는 늦으니 바꾸자" (P2, 10-31).

- `/api/routes/live` 의 `arrival`·`switch_suggestion`(`planning.live_switch`)
- `POST /api/events/{id}/reroute`(`observations.TripReroute`)

**늦을 때만 바꾸자고 한다.** 안 늦는데 빠른 대안은 알리지 않는다(사용자 10-31). 도착 예상은
알람 계산과 같은 학습 보정을 적용한 평균이다. 바꾼 이동은 이동 학습에서 빠진다.
"""

from __future__ import annotations

from datetime import datetime, timedelta

import pytest
from django.urls import reverse
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events import views
from apps.events.models import Event, Place
from apps.observations.models import TripReroute, current_route_key
from apps.planning import live_switch, services
from apps.planning.models import AlarmPlan
from apps.routing import realtime
from apps.routing.models import RouteCorrection, UserTravelProfile

pytestmark = pytest.mark.django_db

HOME = (37.484267, 126.929745)
HERE = (37.497942, 127.027621)
DEST = (37.459786, 126.951124)
CHOSEN_KEY = "transit:5516"
ALT_KEY = "transit:2호선"


def transit(minutes: int, *, vehicle="5516", walk=240, wait=300) -> list[dict]:
    """걷기 → 대기 → 승차 → 걷기. 합이 `minutes` 분이 되게 승차 시간을 맞춘다."""
    ride = minutes * 60 - walk - wait - 60
    return [
        {"kind": "walk", "seconds": walk, "label": "도보"},
        {"kind": "wait", "seconds": wait, "label": "대기"},
        {"kind": "bus", "seconds": ride, "label": vehicle, "vehicle": vehicle, "stops": ["신림역"],
         "region": "metro_seoul"},
        {"kind": "walk", "seconds": 60, "label": "도보"},
    ]


def live_route(minutes=40, alt_minutes=28, *, key=CHOSEN_KEY):
    route = {
        "minutes": minutes,
        "mode": "버스",
        "source": "kakao_transit",
        "key": key,
        "detail": "5516",
        "path": [[37.49, 127.02], [37.46, 126.95]],
        "segments": transit(minutes),
    }
    if alt_minutes is not None and alt_minutes < minutes:
        route["alternative"] = {
            "key": ALT_KEY,
            "label": "2호선",
            "faster_minutes": minutes - alt_minutes,
            "path": [[37.49, 127.02], [37.47, 126.99]],
            "minutes": alt_minutes,
            "mode": "지하철",
            "segments": transit(alt_minutes, vehicle="2호선"),
        }
    return route


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="switch@example.com", password="Wd8-amber-river-53", nickname="바꾸기"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = HOME[0], HOME[1], "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def client_auth(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=DEST[0], lng=DEST[1])


@pytest.fixture
def realtime_calls(monkeypatch):
    """실시간 도착 호출을 센다. 기본은 아무것도 붙이지 않는다."""
    calls = []
    monkeypatch.setattr(realtime, "enrich_candidates", lambda cands: calls.append(cands))
    return calls


def make_event(user, place, monkeypatch, *, starts_in: float) -> Event:
    """`starts_in` 분 뒤에 시작하는 일정. 계획까지 만든다(경로 조회는 고정값)."""
    monkeypatch.setattr(services.clients, "resolve_route", lambda key, **kw: (live_route(key=key), False))
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (live_route(), False))
    event = Event.objects.create(
        user=user, place=place, title="수업",
        start_at=(timezone.now() + timedelta(minutes=starts_in)).replace(microsecond=0),
        route_key=CHOSEN_KEY,
    )
    services.compute_and_store(event)
    event.refresh_from_db()
    return event


def post_live(client, event, route, monkeypatch, calls=None):
    def fake(key, **kw):
        if calls is not None:
            calls.append(key)
        return dict(route, key=key), False

    monkeypatch.setattr(views.clients, "resolve_route", fake)
    res = client.post(
        reverse("events:route_live"), {"event_id": event.pk, "lat": HERE[0], "lng": HERE[1]},
        format="json",
    )
    assert res.status_code == 200
    return res.json()


def reroute(client, event, route_key=ALT_KEY, **extra):
    return client.post(
        reverse("events:reroute", args=[event.pk]), {"route_key": route_key, **extra}, format="json"
    )


# ---------------------------------------------------------------------------
# 판정 규칙
# ---------------------------------------------------------------------------


def arrival(minutes_late: float, start=datetime(2026, 11, 2, 9, 0, tzinfo=timezone.get_current_timezone())):
    at = start + timedelta(minutes=minutes_late)
    return live_switch.Arrival("k", "", at, live_switch.late_minutes(at, start), False)


class TestRules:
    def test_late_minutes_round_to_the_nearest_minute(self):
        start = datetime(2026, 11, 2, 9, 0, tzinfo=timezone.get_current_timezone())
        assert live_switch.late_minutes(start + timedelta(seconds=29), start) == 0
        assert live_switch.late_minutes(start + timedelta(seconds=31), start) == 1
        assert live_switch.late_minutes(start - timedelta(minutes=5), start) == 0

    def test_only_when_late_now(self):
        assert not live_switch.should_switch(arrival(-1), arrival(-10))
        assert not live_switch.should_switch(arrival(0.4), arrival(-10))

    def test_alternative_that_is_on_time_wins_by_any_margin(self):
        assert live_switch.should_switch(arrival(1), arrival(-0.5))

    def test_alternative_that_is_still_late_must_save_two_minutes(self):
        assert not live_switch.should_switch(arrival(6), arrival(4.5))
        assert live_switch.should_switch(arrival(6), arrival(4))

    def test_no_alternative_or_a_slower_one(self):
        assert not live_switch.should_switch(arrival(5), None)
        assert not live_switch.should_switch(arrival(5), arrival(5))
        assert not live_switch.should_switch(arrival(5), arrival(7))


class TestRealtimeWait:
    def test_replaces_kakao_wait_with_the_next_catchable_bus(self):
        """정류장까지 4분 걷는다. 2분 뒤 버스는 못 탄다. 10분 뒤 버스 → 대기 6분(카카오 5분)."""
        route = live_route(alt_minutes=None, minutes=30)
        route["segments"][2]["arrivals"] = [{"seconds": 120}, {"seconds": 600}]
        assert live_switch.realtime_minutes(route) == (pytest.approx(31.0), True)

    def test_alternative_bus_on_the_same_stretch_counts(self):
        route = live_route(alt_minutes=None, minutes=30)
        route["segments"][2]["arrivals"] = [{"seconds": 600}]
        route["segments"][2]["alt_vehicles"] = [{"name": "6514", "arrivals": [{"seconds": 300}]}]
        # 대기 1분(300 − 240초). 카카오 5분보다 4분 빠르다.
        assert live_switch.realtime_minutes(route) == (pytest.approx(26.0), True)

    def test_without_catchable_arrivals_kakao_stays(self):
        route = live_route(alt_minutes=None, minutes=30)
        assert live_switch.realtime_minutes(route) == (30.0, False)
        route["segments"][2]["arrivals"] = [{"seconds": 100}]
        assert live_switch.realtime_minutes(route) == (30.0, False)


# ---------------------------------------------------------------------------
# /api/routes/live
# ---------------------------------------------------------------------------


@pytest.mark.integration
class TestLiveSuggestion:
    def test_late_now_suggests_the_faster_route(self, client_auth, user, place, monkeypatch, realtime_calls):
        """40분 걸리는데 30분 뒤 시작 → 10분 지각. 2호선(28분)이면 안 늦는다."""
        event = make_event(user, place, monkeypatch, starts_in=30)
        data = post_live(client_auth, event, live_route(40, 28), monkeypatch)

        assert data["arrival"]["route_key"] == CHOSEN_KEY
        assert data["arrival"]["late_minutes"] == 10
        suggestion = data["switch_suggestion"]
        assert suggestion["reason"] == "late_now"
        assert suggestion["route_key"] == ALT_KEY
        assert suggestion["label"] == "2호선"
        assert suggestion["late_minutes"] == 0
        assert suggestion["saves_minutes"] == 12
        # 여유가 없으니 실시간을 한 번(두 경로 함께) 불러 봤다.
        assert len(realtime_calls) == 1
        assert [c["key"] for c in realtime_calls[0]] == [CHOSEN_KEY, ALT_KEY]
        # 지도용 경로는 예전 그대로다.
        assert data["route"]["route_key"] == ALT_KEY

    def test_on_time_says_nothing_and_skips_realtime(self, client_auth, user, place, monkeypatch, realtime_calls):
        event = make_event(user, place, monkeypatch, starts_in=60)
        data = post_live(client_auth, event, live_route(40, 28), monkeypatch)

        assert data["arrival"]["late_minutes"] == 0
        assert data["switch_suggestion"] is None
        assert realtime_calls == [], "여유가 넉넉하면 실시간 쿼터를 쓰지 않는다"

    def test_no_alternative_means_no_suggestion(self, client_auth, user, place, monkeypatch, realtime_calls):
        event = make_event(user, place, monkeypatch, starts_in=30)
        data = post_live(client_auth, event, live_route(40, None), monkeypatch)

        assert data["arrival"]["late_minutes"] == 10
        assert data["switch_suggestion"] is None
        assert realtime_calls == []

    def test_realtime_can_turn_a_close_trip_late(self, client_auth, user, place, monkeypatch):
        """카카오로는 2분 여유(대기 5분). 정류장에 닿고 12분 뒤에야 버스가 와서 대기가 7분 늘어 지각이다."""
        event = make_event(user, place, monkeypatch, starts_in=42)

        def enrich(cands):
            # 정류장까지 4분 + 12분
            cands[0]["segments"][2]["arrivals"] = [{"seconds": 960}]

        monkeypatch.setattr(realtime, "enrich_candidates", enrich)
        data = post_live(client_auth, event, live_route(40, 30), monkeypatch)

        assert data["arrival"]["realtime"] is True
        assert data["arrival"]["late_minutes"] == 5
        assert data["switch_suggestion"]["route_key"] == ALT_KEY
        assert data["switch_suggestion"]["realtime"] is False

    def test_learned_corrections_decide_lateness(self, client_auth, user, place, monkeypatch, realtime_calls):
        """원값 30분이면 35분 뒤 시작에 안 늦는다. 이 경로는 기록상 1.5배 → 45분 → 지각."""
        event = make_event(user, place, monkeypatch, starts_in=35)
        before = post_live(client_auth, event, live_route(30, 27), monkeypatch)
        assert before["switch_suggestion"] is None

        RouteCorrection.objects.create(
            route_signature=CHOSEN_KEY, mode="버스", hour_bucket=-1,
            weekday_type=RouteCorrection.WeekdayType.ANY, factor=1.5, sd_minutes=2.0, sample_count=20,
        )
        after = post_live(client_auth, event, live_route(30, 27), monkeypatch)
        assert after["arrival"]["late_minutes"] == 10
        assert after["switch_suggestion"]["route_key"] == ALT_KEY

    def test_walking_speed_is_part_of_the_estimate(self, client_auth, user, place, monkeypatch, realtime_calls):
        event = make_event(user, place, monkeypatch, starts_in=60)
        plain = post_live(client_auth, event, live_route(40, None), monkeypatch)
        UserTravelProfile.objects.create(user=user, walk_ratio=1.4, walk_samples=5)  # → 1.2
        slow = post_live(client_auth, event, live_route(40, None), monkeypatch)

        gap = datetime.fromisoformat(slow["arrival"]["arrive_at"]) - datetime.fromisoformat(plain["arrival"]["arrive_at"])
        # 걷는 5분(240 + 60초) × 0.2 = 1분
        assert abs(gap.total_seconds() - 60) <= 2

    def test_keys_are_always_present(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)
        monkeypatch.setattr(views.clients, "resolve_route", lambda key, **kw: (None, True))
        res = client_auth.post(
            reverse("events:route_live"), {"event_id": event.pk, "lat": HERE[0], "lng": HERE[1]},
            format="json",
        )
        assert res.json() == {"route": None, "degraded": True, "arrival": None, "switch_suggestion": None}

    def test_a_failed_assessment_still_returns_the_route(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)

        def broken(*a, **kw):
            raise RuntimeError("boom")

        monkeypatch.setattr(live_switch, "assess", broken)
        data = post_live(client_auth, event, live_route(40, 28), monkeypatch)
        assert data["route"]["route_key"] == ALT_KEY
        assert data["arrival"] is None and data["switch_suggestion"] is None

    def test_after_switching_the_new_route_is_the_baseline(self, client_auth, user, place, monkeypatch, realtime_calls):
        event = make_event(user, place, monkeypatch, starts_in=30)
        assert reroute(client_auth, event, reason="late_now").status_code == 201

        calls = []
        data = post_live(client_auth, event, live_route(28, None), monkeypatch, calls=calls)
        assert calls == [ALT_KEY]
        assert data["arrival"]["route_key"] == ALT_KEY
        assert data["switch_suggestion"] is None


# ---------------------------------------------------------------------------
# POST /api/events/{id}/reroute
# ---------------------------------------------------------------------------


@pytest.mark.integration
class TestReroute:
    def test_records_this_trip_only(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)
        plan_before = AlarmPlan.objects.get(event=event)

        res = reroute(client_auth, event, reason="late_now")

        assert res.status_code == 201
        body = res.json()
        assert body["event_id"] == event.pk
        assert body["from_route_key"] == CHOSEN_KEY
        assert body["to_route_key"] == ALT_KEY
        assert body["reason"] == "late_now"
        assert datetime.fromisoformat(body["rerouted_at"])
        # 일정의 고른 경로와 알람 계획은 그대로다.
        event.refresh_from_db()
        assert event.route_key == CHOSEN_KEY
        plan_after = AlarmPlan.objects.get(event=event)
        assert (plan_after.route_key, plan_after.alarm_at, plan_after.computed_at) == (
            plan_before.route_key, plan_before.alarm_at, plan_before.computed_at,
        )
        assert current_route_key(event) == ALT_KEY

    def test_switching_again_starts_from_the_last_switch(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)
        reroute(client_auth, event)
        res = reroute(client_auth, event, route_key="walk")
        assert res.status_code == 201
        assert res.json()["from_route_key"] == ALT_KEY
        assert res.json()["reason"] == ""
        assert TripReroute.objects.filter(event=event).count() == 2
        assert current_route_key(event) == "walk"

    def test_same_route_is_refused(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)
        res = reroute(client_auth, event, route_key=CHOSEN_KEY)
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "same_route"

    @pytest.mark.parametrize("key", ["", "transit:", "bus", "x" * 121, None, 5])
    def test_bad_route_keys(self, client_auth, user, place, monkeypatch, key):
        event = make_event(user, place, monkeypatch, starts_in=30)
        res = reroute(client_auth, event, route_key=key)
        assert res.status_code == 400
        assert res.json()["error"]["code"] == "invalid_route_key"
        assert not TripReroute.objects.exists()

    def test_bad_reason(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=30)
        res = reroute(client_auth, event, reason="faster")
        assert res.status_code == 400
        assert res.json()["error"]["code"] == "invalid_reason"

    @pytest.mark.parametrize("starts_in", [6 * 60 + 5, -(6 * 60 + 5)])
    def test_only_around_the_trip(self, client_auth, user, place, monkeypatch, starts_in):
        event = make_event(user, place, monkeypatch, starts_in=starts_in)
        res = reroute(client_auth, event)
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "outside_trip"

    def test_late_but_still_within_six_hours(self, client_auth, user, place, monkeypatch):
        event = make_event(user, place, monkeypatch, starts_in=-30)
        assert reroute(client_auth, event).status_code == 201

    def test_others_events_and_anonymous(self, client_auth, user, place, monkeypatch, django_user_model):
        other = django_user_model.objects.create_user(
            email="other-switch@example.com", password="Wd8-amber-river-54", nickname="남"
        )
        theirs = make_event(other, place, monkeypatch, starts_in=30)
        assert reroute(client_auth, theirs).status_code == 404
        assert APIClient().post(
            reverse("events:reroute", args=[theirs.pk]), {"route_key": ALT_KEY}, format="json"
        ).status_code in (401, 403)
        assert not TripReroute.objects.exists()
