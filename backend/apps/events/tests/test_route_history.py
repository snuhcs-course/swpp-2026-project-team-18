# Partly AI-generated (Claude)
"""최근 이동 경로 · 가장 자주 이용한 경로(`route_history`, `GET /api/routes/history`)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.accounts.models import SavedPlace
from apps.events import route_history as rh
from apps.events.models import Event, Place
from apps.observations.models import TripReroute
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

HOME = (37.4842, 126.9297)
CAMPUS = (37.4601, 126.9520)
CAFE = (37.4850, 126.9310)
NOW = datetime(2026, 11, 15, 12, 0, tzinfo=KST)


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="history@example.com", password="Yt6-amber-river-40", nickname="기록"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = *HOME, "신림 집"
    p.save()
    return u


@pytest.fixture
def campus():
    return Place.objects.create(
        name="관악캠퍼스", lat=CAMPUS[0], lng=CAMPUS[1], address="관악로 1", kakao_place_id="111"
    )


@pytest.fixture
def cafe():
    return Place.objects.create(name="카페", lat=CAFE[0], lng=CAFE[1])


def trip(user, place, *, days_ago, route_key="transit:2호선", plan_key=None, summary="2호선",
         minutes=25, mode="transit", **kw) -> Event:
    """`days_ago` 일 전 09:00 에 시작한 일정과 그 계획."""
    event = Event.objects.create(
        user=user, place=place, title="일정",
        start_at=NOW.replace(hour=9) - timedelta(days=days_ago), route_key=route_key, **kw,
    )
    ok_plan(
        user, event, route_key=plan_key if plan_key is not None else route_key,
        travel_mode=mode, route_summary=summary, travel_minutes=minutes,
    )
    return event


def ok_plan(user, event, **kw) -> AlarmPlan:
    """계산을 마친 계획. `ok` 는 시각·분 값이 모두 있어야 한다(DB 제약)."""
    start = event.start_at
    values = dict(
        alarm_at=start - timedelta(minutes=70), depart_by=start - timedelta(minutes=35),
        arrive_at=start - timedelta(minutes=5), prep_minutes=35, travel_minutes=30, buffer_minutes=5,
    )
    values.update(kw)
    return AlarmPlan.objects.create(user=user, event=event, status=AlarmPlan.Status.OK, **values)


def history(user, **kw):
    return rh.history(user, now=NOW, **kw)


class TestHistory:
    def test_no_trips_is_empty(self, user):
        assert history(user) == []

    def test_recent_orders_by_last_trip_and_groups_same_route(self, user, campus, cafe):
        trip(user, campus, days_ago=5)
        trip(user, campus, days_ago=3)
        trip(user, cafe, days_ago=1, route_key="walk", summary="도보", mode="walk", minutes=8)

        rows = history(user)

        assert [r["destination"]["name"] for r in rows] == ["카페", "관악캠퍼스"]
        assert rows[1]["trip_count"] == 2
        assert rows[1]["last_trip_at"].startswith("2026-11-12T09:00")
        assert rows[1]["route_key"] == "transit:2호선"
        assert rows[1]["route_summary"] == "2호선"
        assert rows[1]["travel_minutes"] == 25
        assert rows[0]["travel_mode"] == "walk"

    def test_frequent_orders_by_count_then_recency(self, user, campus, cafe):
        for d in (9, 8, 7):
            trip(user, campus, days_ago=d)
        trip(user, cafe, days_ago=1, route_key="walk")

        rows = history(user, sort=rh.FREQUENT)

        assert [(r["destination"]["name"], r["trip_count"]) for r in rows] == [("관악캠퍼스", 3), ("카페", 1)]

    def test_frequent_tie_goes_to_the_more_recent(self, user, campus, cafe):
        trip(user, campus, days_ago=4)
        trip(user, cafe, days_ago=2, route_key="walk")

        rows = history(user, sort=rh.FREQUENT)

        assert [r["destination"]["name"] for r in rows] == ["카페", "관악캠퍼스"]

    def test_different_routes_to_the_same_place_are_separate(self, user, campus):
        trip(user, campus, days_ago=3, route_key="transit:2호선")
        trip(user, campus, days_ago=2, route_key="transit:5513", summary="5513")

        rows = history(user)

        assert [r["route_key"] for r in rows] == ["transit:5513", "transit:2호선"]

    def test_future_skipped_old_and_routeless_events_are_ignored(self, user, campus):
        trip(user, campus, days_ago=-1)  # 아직 시작 전
        trip(user, campus, days_ago=2, skipped_at=NOW)  # 안 감
        trip(user, campus, days_ago=rh.HISTORY_DAYS + 1)  # 기간 밖
        trip(user, campus, days_ago=3, route_key="", plan_key="")  # 경로를 모름

        assert history(user) == []

    def test_plan_route_is_used_when_the_server_picked_it(self, user, campus):
        # 경로를 고르지 않으면 서버가 고른 경로가 계획에 남는다. 그 경로가 다시 쓸 key 다.
        trip(user, campus, days_ago=2, route_key="", plan_key="transit:5513")

        assert history(user)[0]["route_key"] == "transit:5513"

    def test_reroute_counts_the_route_actually_taken(self, user, campus):
        event = trip(user, campus, days_ago=2, route_key="transit:2호선")
        TripReroute.objects.create(
            user=user, event=event, from_route_key="transit:2호선", to_route_key="transit:5513"
        )

        row = history(user)[0]

        assert row["route_key"] == "transit:5513"
        assert row["rerouted"] is True

    def test_origin_kinds(self, user, campus):
        saved = SavedPlace.objects.create(
            user=user, client_uuid="u1", label="본가", lat=37.50, lng=127.03
        )
        trip(user, campus, days_ago=3)  # 집
        trip(user, campus, days_ago=2, origin_place=saved, origin_lat=37.50, origin_lng=127.03,
             origin_label="본가")
        trip(user, campus, days_ago=1, origin_lat=37.47, origin_lng=126.95, origin_label="도서관")

        rows = history(user)

        assert [r["origin"]["kind"] for r in rows] == ["point", "saved_place", "home"]
        assert rows[0]["origin"]["label"] == "도서관"
        assert rows[1]["origin"]["saved_place_id"] == saved.pk
        assert rows[1]["origin"]["label"] == "본가"
        assert rows[2]["origin"] == {
            "kind": "home", "saved_place_id": None, "label": "신림 집", "lat": HOME[0], "lng": HOME[1],
        }

    def test_nearby_points_are_grouped_and_far_ones_are_not(self, user):
        a = Place.objects.create(name="카페", lat=37.48501, lng=126.93101)
        b = Place.objects.create(name="카페", lat=37.48504, lng=126.93104)  # 몇 m 옆
        c = Place.objects.create(name="카페", lat=37.49000, lng=126.93100)  # 500m 넘게
        trip(user, a, days_ago=3)
        trip(user, b, days_ago=2)
        trip(user, c, days_ago=1)

        rows = history(user, sort=rh.FREQUENT)

        assert [r["trip_count"] for r in rows] == [2, 1]

    def test_home_destination(self, user):
        event = Event.objects.create(
            user=user, destination_is_home=True, title="귀가",
            start_at=NOW - timedelta(hours=5), route_key="walk",
        )
        ok_plan(user, event, route_key="walk")

        dest = history(user)[0]["destination"]

        assert dest["kind"] == "home"
        assert (dest["lat"], dest["lng"], dest["name"]) == (*HOME, "신림 집")

    def test_limit_and_other_users(self, user, campus, cafe, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="Yt6-amber-river-40", nickname="남"
        )
        trip(other, campus, days_ago=1)
        trip(user, campus, days_ago=3)
        trip(user, cafe, days_ago=2, route_key="walk")

        rows = history(user, limit=1)

        assert [r["destination"]["name"] for r in rows] == ["카페"]

    def test_query_count_does_not_grow_per_trip(self, user, campus, cafe, django_assert_max_num_queries):
        for d in range(1, 30):
            trip(user, campus if d % 2 else cafe, days_ago=d)
        with django_assert_max_num_queries(4):
            history(user)


@pytest.mark.integration
class TestEndpoint:
    def client(self, user):
        c = APIClient()
        c.force_authenticate(user)
        return c

    def test_recent_by_default(self, user, campus, monkeypatch):
        monkeypatch.setattr(rh.timezone, "now", lambda: NOW)
        trip(user, campus, days_ago=1)

        res = self.client(user).get("/api/routes/history")

        assert res.status_code == 200, res.data
        assert res.data["sort"] == "recent"
        assert res.data["days"] == rh.HISTORY_DAYS
        assert res.data["results"][0]["destination"]["kakao_place_id"] == "111"

    def test_frequent_and_limit(self, user, campus, monkeypatch):
        monkeypatch.setattr(rh.timezone, "now", lambda: NOW)
        trip(user, campus, days_ago=1)

        res = self.client(user).get("/api/routes/history", {"sort": "frequent", "limit": "20"})

        assert res.status_code == 200
        assert res.data["sort"] == "frequent"
        assert len(res.data["results"]) == 1

    @pytest.mark.parametrize("params,field", [
        ({"sort": "all"}, "sort"),
        ({"limit": "0"}, "limit"),
        ({"limit": "21"}, "limit"),
        ({"limit": "x"}, "limit"),
    ])
    def test_bad_params_are_400(self, user, params, field):
        res = self.client(user).get("/api/routes/history", params)

        assert res.status_code == 400
        assert field in res.data["error"]["details"]

    def test_requires_login(self):
        assert APIClient().get("/api/routes/history").status_code == 401
