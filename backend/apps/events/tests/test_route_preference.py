# Partly AI-generated (Claude)
"""경로 선택 화면의 기본 선택(`route_preference`).

같은 출발지 → 같은 목적지로 90일 안에 다녀온 가장 최근 이동에서 실제로 탄 경로가 먼저고(BE-PREF),
없으면 최근 선택 10개에서 가장 많이 고른 수단이다.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events import route_preference as pref
from apps.events.models import Event, EventRecurrence, Place
from apps.routing import clients

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

HOME = (37.4842, 126.9297)
CAMPUS = (37.4601, 126.9520)
CAFE = (37.4850, 126.9310)
T0 = datetime(2026, 9, 1, 9, 0, tzinfo=KST)
# 기준 "지금". 아래 일정(T0 + 며칠)은 모두 다녀온 이동이고 90일 안이다.
NOW = T0 + timedelta(days=30)

CANDIDATES = [
    {"key": "car", "kind": "car", "minutes": 12, "transfers": 0},
    {"key": "transit:2호선", "kind": "transit", "minutes": 25, "transfers": 0},
    {"key": "transit:5513", "kind": "transit", "minutes": 22, "transfers": 0},
    {"key": "transit:2호선>5513", "kind": "transit", "minutes": 22, "transfers": 1},
    {"key": "walk", "kind": "walk", "minutes": 45, "transfers": 0},
]


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="pref@example.com", password="Yt6-amber-river-40", nickname="선택"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = *HOME, "신림역"
    p.save()
    return u


@pytest.fixture
def campus():
    return Place.objects.create(name="관악캠퍼스", lat=CAMPUS[0], lng=CAMPUS[1])


@pytest.fixture
def cafe():
    return Place.objects.create(name="카페", lat=CAFE[0], lng=CAFE[1])


def chose(user, place, key, days, **kw) -> Event:
    """`days` 일째에 `key` 를 고른 일정. 생성 시각이 선택 시각이다."""
    event = Event.objects.create(
        user=user, place=place, title="일정", start_at=T0 + timedelta(days=days),
        route_key=key, **kw,
    )
    Event.objects.filter(pk=event.pk).update(created_at=T0 + timedelta(days=days))
    return event


def prefer(user, dest, candidates=CANDIDATES, origin=None, now=NOW):
    lat, lng = origin if origin is not None else (None, None)
    return pref.preferred_route(
        user, dest[0], dest[1], candidates, origin_lat=lat, origin_lng=lng, now=now,
    )


class TestPreferredRoute:
    def test_no_history_gives_no_preference(self, user):
        assert prefer(user, CAMPUS) == {
            "preferred_key": None, "preferred_mode": None, "preferred_basis": None,
            "preferred_match": None,
        }

    def test_same_destination_reuses_the_exact_route(self, user, campus):
        chose(user, campus, "transit:2호선", 1)

        assert prefer(user, CAMPUS) == {
            "preferred_key": "transit:2호선",
            "preferred_mode": "transit",
            "preferred_basis": pref.SAME_DESTINATION,
            "preferred_match": pref.SAME_ROUTE,
        }

    def test_same_destination_latest_choice_wins(self, user, campus):
        chose(user, campus, "transit:2호선", 1)
        chose(user, campus, "walk", 2)

        assert prefer(user, CAMPUS)["preferred_key"] == "walk"

    def test_route_gone_falls_back_to_the_fastest_of_that_mode(self, user, campus):
        """노선 조합이 바뀌었거나 출발지가 달라 같은 key 가 없다."""
        chose(user, campus, "transit:6512", 1)

        result = prefer(user, CAMPUS)

        assert result["preferred_mode"] == "transit"
        # 22분 둘 중 환승이 적은 쪽(전체 최단과 같은 순위).
        assert result["preferred_key"] == "transit:5513"
        assert result["preferred_basis"] == pref.SAME_DESTINATION
        assert result["preferred_match"] == pref.SAME_MODE_FALLBACK

    # Partly AI-generated (Claude) — BE-PREF(11-20): 같은 출발지 · 90일 안 · 다녀온 이동 · 실제로 탄 경로.
    def test_other_origin_does_not_count(self, user, campus):
        """카페에서 캠퍼스로 간 기록은 집에서 캠퍼스로 가는 기본 선택이 아니다."""
        chose(user, campus, "walk", 1, origin_lat=CAFE[0], origin_lng=CAFE[1])

        assert prefer(user, CAMPUS)["preferred_basis"] == pref.RECENT_MODE
        assert prefer(user, CAMPUS, origin=CAFE)["preferred_key"] == "walk"
        # 출발지를 좌표로 보내도 집 격자와 같으면 집에서 간 기록을 쓴다.
        chose(user, campus, "transit:2호선", 2)
        assert prefer(user, CAMPUS, origin=HOME)["preferred_key"] == "transit:2호선"

    def test_nearby_origins_across_a_grid_edge_still_match(self, user, campus):
        """격자로 비교하면 11m 차이도 갈린다(37.49949 → 37.499, 37.49959 → 37.5). 거리로 본다(리뷰)."""
        chose(user, campus, "walk", 1, origin_lat=37.49949, origin_lng=126.9295)

        assert prefer(user, CAMPUS, origin=(37.49959, 126.9295))["preferred_key"] == "walk"
        assert prefer(user, CAMPUS, origin=(37.5010, 126.9295))["preferred_basis"] == pref.RECENT_MODE

    def test_destination_just_outside_100m_is_not_the_same(self, user):
        near = Place.objects.create(name="정문 밖", lat=CAMPUS[0] + 0.0011, lng=CAMPUS[1])  # 약 122m
        chose(user, near, "walk", 1)

        assert prefer(user, CAMPUS)["preferred_basis"] == pref.RECENT_MODE

    def test_saved_origin_is_compared_by_its_coordinates(self, user, campus):
        from apps.accounts.models import SavedPlace

        saved = SavedPlace.objects.create(user=user, label="본가", lat=37.55, lng=126.99)
        chose(user, campus, "car", 1, origin_place=saved, origin_lat=37.55, origin_lng=126.99)

        assert prefer(user, CAMPUS, origin=(37.5501, 126.9902))["preferred_key"] == "car"

    def test_only_the_last_90_days(self, user, campus):
        chose(user, campus, "walk", 1)

        assert prefer(user, CAMPUS, now=T0 + timedelta(days=90))["preferred_key"] == "walk"
        assert prefer(user, CAMPUS, now=T0 + timedelta(days=92))["preferred_basis"] == pref.RECENT_MODE

    def test_trips_not_taken_yet_or_skipped_do_not_count(self, user, campus):
        chose(user, campus, "walk", 40)  # NOW 뒤의 일정 — 아직 다녀오지 않았다
        skipped = chose(user, campus, "car", 2)
        Event.objects.filter(pk=skipped.pk).update(skipped_at=T0 + timedelta(days=2))

        assert prefer(user, CAMPUS)["preferred_basis"] == pref.RECENT_MODE

    def test_rerouted_trip_uses_the_route_actually_taken(self, user, campus):
        from apps.observations.models import TripReroute

        event = chose(user, campus, "transit:2호선", 1)
        TripReroute.objects.create(
            user=user, event=event, from_route_key="transit:2호선", to_route_key="walk",
        )

        assert prefer(user, CAMPUS)["preferred_key"] == "walk"
        assert prefer(user, CAMPUS)["preferred_match"] == pref.SAME_ROUTE

    def test_bus_merged_into_another_card_picks_that_card(self, user, campus):
        """버스 번호만 다른 경로는 한 카드로 온다. 전에 고른 5516 이 대표가 아니어도 그 카드다."""
        chose(user, campus, "transit:5516", 1)
        merged = [
            *CANDIDATES,
            {"key": "transit:6514", "kind": "transit", "minutes": 30, "transfers": 0,
             "member_keys": ["transit:6514", "transit:5516"]},
        ]

        assert prefer(user, CAMPUS, merged)["preferred_key"] == "transit:6514"

    def test_nearby_place_counts_as_the_same_destination(self, user):
        near = Place.objects.create(name="정문", lat=CAMPUS[0] + 0.0005, lng=CAMPUS[1])
        chose(user, near, "walk", 1)

        assert prefer(user, CAMPUS)["preferred_basis"] == pref.SAME_DESTINATION

    def test_home_destination_uses_the_current_home(self, user):
        chose(user, None, "transit:2호선", 1, destination_is_home=True)

        assert prefer(user, HOME)["preferred_key"] == "transit:2호선"

    def test_new_destination_uses_the_most_chosen_mode(self, user, campus, cafe):
        for day in range(3):
            chose(user, campus, "transit:2호선", day)
        chose(user, cafe, "walk", 5)

        result = prefer(user, (37.50, 127.00))

        assert result == {
            "preferred_key": "transit:5513",
            "preferred_mode": "transit",
            "preferred_basis": pref.RECENT_MODE,
            "preferred_match": None,
        }

    def test_tie_goes_to_the_more_recent_mode(self, user, campus, cafe):
        chose(user, campus, "transit:2호선", 1)
        chose(user, cafe, "car", 2)

        assert prefer(user, (37.50, 127.00))["preferred_mode"] == "car"

    def test_only_the_last_ten_choices_are_counted(self, user, campus):
        for day in range(8):
            chose(user, campus, "car", day)
        for day in range(10, 16):
            chose(user, campus, "transit:2호선", day)
        for day in range(20, 24):
            chose(user, campus, "walk", day)

        # 최근 10개는 transit 6, walk 4. 전부 세면 car 8 이 이긴다.
        assert prefer(user, (37.50, 127.00))["preferred_mode"] == "transit"

    def test_mode_without_candidates_gives_no_preference(self, user, campus):
        chose(user, campus, "bicycle", 1)

        assert prefer(user, (37.50, 127.00))["preferred_key"] is None
        # 같은 목적지여도 그 수단 후보가 없으면 횟수 규칙으로 넘어가고, 거기도 없다.
        assert prefer(user, CAMPUS)["preferred_key"] is None

    def test_events_without_a_choice_are_ignored(self, user, campus):
        chose(user, campus, "", 1)

        assert prefer(user, CAMPUS)["preferred_key"] is None

    def test_other_users_choices_are_ignored(self, user, campus, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="Yt6-amber-river-41", nickname="남"
        )
        chose(other, campus, "walk", 1)

        assert prefer(user, CAMPUS)["preferred_key"] is None

    def test_recurring_rule_counts_once_at_its_first_occurrence(self, user, campus, cafe):
        rule = EventRecurrence.objects.create(
            user=user, start_at=T0, timezone="Asia/Seoul", weekdays=[0, 1, 2, 3, 4],
            template={"route_key": "car", "destination_is_home": False}, place=cafe,
        )
        for day in range(8):
            occurrence = Event.objects.create(
                user=user, place=cafe, title="반복", start_at=T0 + timedelta(days=day),
                route_key="car", recurrence=rule, recurrence_date=date(2026, 9, 1 + day),
            )
            # 회차는 매일 새로 만들어진다. 마지막 회차가 가장 최근이다.
            Event.objects.filter(pk=occurrence.pk).update(created_at=T0 + timedelta(days=day * 3))
        chose(user, campus, "transit:2호선", 2)
        chose(user, campus, "transit:2호선", 3)

        choices = pref.recent_choices(user)

        assert [c.mode for c in choices] == ["transit", "transit", "car"]
        assert choices[-1].at == T0
        assert prefer(user, (37.50, 127.00))["preferred_mode"] == "transit"


@pytest.mark.integration
class TestCandidatesResponse:
    def test_response_carries_the_preference(self, user, campus, monkeypatch):
        from django.utils import timezone as dj_tz

        # 실제 "지금" 기준 어제 다녀온 이동.
        chose(user, campus, "transit:2호선", (dj_tz.now() - T0).days - 1)
        monkeypatch.setattr("apps.routing.realtime.enrich_candidates", lambda items: None)
        monkeypatch.setattr(
            clients, "route_candidates",
            lambda **kw: ([dict(c, mode=c["kind"]) for c in CANDIDATES], False),
        )
        client = APIClient()
        client.force_authenticate(user)

        res = client.get("/api/routes/candidates", {"dest_lat": CAMPUS[0], "dest_lng": CAMPUS[1]})

        assert res.status_code == 200, res.data
        assert res.data["fastest_key"] == "car"
        assert res.data["preferred_key"] == "transit:2호선"
        assert res.data["preferred_mode"] == "transit"
        assert res.data["preferred_basis"] == "same_destination"
        assert res.data["preferred_match"] == "same_route"
