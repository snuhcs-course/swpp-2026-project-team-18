# Partly AI-generated (Claude)
"""경로 선택 화면의 기본 선택(`route_preference`).

같은 목적지의 최근 선택이 먼저고, 없으면 최근 선택 10개에서 가장 많이 고른 수단이다.
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


def prefer(user, dest, candidates=CANDIDATES):
    return pref.preferred_route(user, dest[0], dest[1], candidates)


class TestPreferredRoute:
    def test_no_history_gives_no_preference(self, user):
        assert prefer(user, CAMPUS) == {
            "preferred_key": None, "preferred_mode": None, "preferred_basis": None,
        }

    def test_same_destination_reuses_the_exact_route(self, user, campus):
        chose(user, campus, "transit:2호선", 1)

        assert prefer(user, CAMPUS) == {
            "preferred_key": "transit:2호선",
            "preferred_mode": "transit",
            "preferred_basis": pref.SAME_DESTINATION,
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
        chose(user, campus, "transit:2호선", 1)
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
