# Partly AI-generated (Claude)
"""일정마다 준비 시간을 넣거나 빼기(`Event.include_prep`).

출발지를 따로 고른 일정은 "이미 밖에 있다" 로 보고 준비 시간을 뺀다. 친구 집·숙소에서
자고 나서는 아침은 그게 틀려서 알람이 준비 시간만큼 늦다. 사용자가 일정마다 고른다.

| `include_prep` | 집에서 출발 | 출발지를 따로 고름 |
| --- | --- | --- |
| null(기본) | 넣는다 | 뺀다 |
| true | 넣는다 | 넣는다 |
| false | 뺀다 | 뺀다 |

계약 문서는 docs/iter3/include-prep-api.md.
"""

from __future__ import annotations

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events import edits
from apps.events.models import Event, EventRecurrence
from apps.planning import services
from apps.planning.models import AlarmPlan, PlanRevision

pytestmark = pytest.mark.django_db
KST = ZoneInfo("Asia/Seoul")
HOME = (37.4842, 126.9297)
CAMPUS = {"name": "관악캠퍼스", "lat": 37.4601, "lng": 126.9520}
AWAY = {"origin_lat": 37.4979, "origin_lng": 127.0276, "origin_label": "친구 집"}
PREP = 30

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 · 20분",
    "key": "transit:2호선",
    "detail": "2호선",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}


@pytest.fixture
def user(django_user_model):
    user = django_user_model.objects.create_user(email="prep-flag@example.com", password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = (*HOME, "신림역")
    profile.onboarding_prep_min = PREP
    profile.timezone = "Asia/Seoul"
    profile.save()
    return user


@pytest.fixture
def client(user):
    api = APIClient()
    api.force_authenticate(user)
    return api


@pytest.fixture
def route(monkeypatch):
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(ROUTE), False

    def resolve_route(key, **kw):
        calls.append("resolve")
        return dict(ROUTE, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return calls


def at(days: int, hour: int) -> datetime:
    day = timezone.now().astimezone(KST).date() + timedelta(days=days)
    return datetime.combine(day, time(hour), tzinfo=KST)


def create(client, **changes) -> dict:
    body = {"title": "수업", "start_at": at(2, 9).isoformat(), "place": CAMPUS}
    response = client.post("/api/events", body | changes, format="json")
    assert response.status_code == 201, response.data
    return response.data


def patch(client, pk, **body) -> dict:
    response = client.patch(f"/api/events/{pk}", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def plan_of(pk) -> AlarmPlan:
    return AlarmPlan.objects.get(event_id=pk)


@pytest.mark.integration
class TestCreate:
    def test_default_is_null_and_follows_the_origin(self, client, route):
        home = create(client)
        away = create(client, **AWAY)
        assert (home["include_prep"], home["prep_applies"]) == (None, True)
        assert (away["include_prep"], away["prep_applies"]) == (None, False)
        assert home["alarm_plan"]["prep_minutes"] == PREP
        assert away["alarm_plan"]["prep_minutes"] == 0

    def test_away_origin_with_prep_rings_earlier(self, client, route):
        without = create(client, **AWAY)
        with_prep = create(client, include_prep=True, **AWAY)

        assert with_prep["include_prep"] is True
        assert with_prep["prep_applies"] is True
        plan = with_prep["alarm_plan"]
        assert plan["prep_minutes"] == PREP
        assert plan["prep_source"] == "onboarding"
        assert datetime.fromisoformat(plan["alarm_at"]) == datetime.fromisoformat(
            without["alarm_plan"]["alarm_at"]
        ) - timedelta(minutes=PREP)

    def test_home_can_drop_prep(self, client, route):
        data = create(client, include_prep=False)
        assert data["prep_applies"] is False
        assert data["alarm_plan"]["prep_minutes"] == 0
        assert data["alarm_plan"]["prep_source"] == "not_from_home"

    @pytest.mark.parametrize("value", ["maybe", 2, [True]])
    def test_rejects_non_booleans(self, client, route, value):
        response = client.post("/api/events", {
            "title": "수업", "start_at": at(2, 9).isoformat(), "place": CAMPUS,
            "include_prep": value,
        }, format="json")
        assert response.status_code == 400
        assert "include_prep" in response.data["error"]["details"]
        assert not Event.objects.exists()

    def test_list_shows_both_fields(self, client, route):
        create(client, include_prep=True, **AWAY)
        row = client.get("/api/events").data["results"][0]
        assert (row["include_prep"], row["prep_applies"]) == (True, True)

    def test_prep_applies_is_read_only(self, client, route):
        data = create(client, prep_applies=True, **AWAY)
        assert data["prep_applies"] is False
        assert Event.objects.get(pk=data["id"]).include_prep is None


@pytest.mark.integration
class TestEdit:
    def test_turning_prep_on_moves_the_alarm_without_a_route_lookup(self, client, route):
        pk = create(client, **AWAY)["id"]
        before = plan_of(pk)
        calls = len(route)

        data = patch(client, pk, include_prep=True)

        after = plan_of(pk)
        assert len(route) == calls
        assert after.prep_minutes == PREP
        assert after.alarm_at == before.alarm_at - timedelta(minutes=PREP)
        assert after.depart_by == before.depart_by
        assert after.route_path == before.route_path
        assert datetime.fromisoformat(data["alarm_plan"]["alarm_at"]) == after.alarm_at
        assert PlanRevision.objects.filter(
            event_id=pk, trigger=PlanRevision.Trigger.UPDATE,
        ).exists()

    def test_back_to_null_follows_the_origin_again(self, client, route):
        pk = create(client, include_prep=True, **AWAY)["id"]
        data = patch(client, pk, include_prep=None)
        assert (data["include_prep"], data["prep_applies"]) == (None, False)
        assert plan_of(pk).prep_minutes == 0

    def test_same_result_keeps_the_plan(self, client, route):
        """집 출발에서 true 를 골라도 이미 넣고 있었다. 계획을 다시 만들지 않는다."""
        pk = create(client)["id"]
        before = plan_of(pk)
        revisions = PlanRevision.objects.filter(event_id=pk).count()

        data = patch(client, pk, include_prep=True)

        assert data["include_prep"] is True
        assert plan_of(pk).computed_at == before.computed_at
        assert PlanRevision.objects.filter(event_id=pk).count() == revisions

    def test_choice_survives_an_origin_change(self, client, route):
        """출발지를 바꿔도 고른 값은 그대로다. 앱이 같은 화면에서 보여 주고 고친다."""
        pk = create(client, include_prep=True, **AWAY)["id"]
        data = patch(client, pk, origin_lat=37.5665, origin_lng=126.9780, origin_label="숙소")
        assert data["include_prep"] is True
        assert plan_of(pk).prep_minutes == PREP

    def test_dropping_prep_at_home_moves_the_alarm_later(self, client, route):
        pk = create(client)["id"]
        before = plan_of(pk)
        patch(client, pk, include_prep=False)
        assert plan_of(pk).alarm_at == before.alarm_at + timedelta(minutes=PREP)

    def test_other_fields_leave_the_choice_alone(self, client, route):
        pk = create(client, include_prep=False)["id"]
        data = patch(client, pk, title="세미나")
        assert data["include_prep"] is False


@pytest.mark.integration
class TestRecurring:
    @pytest.fixture
    def clock(self, monkeypatch):
        monkeypatch.setattr(timezone, "now", lambda: datetime(2026, 10, 19, 8, tzinfo=KST))

    def weekly(self, client, **changes):
        response = client.post("/api/events", {
            "title": "반복 수업", "start_at": "2026-10-19T09:00:00+09:00",
            "repeat_weekdays": [0, 2], "place": CAMPUS, **changes,
        }, format="json")
        assert response.status_code == 201, response.data
        return client.get("/api/events").data["results"]

    def test_occurrences_keep_the_choice(self, client, route, clock):
        rows = self.weekly(client, include_prep=True, **AWAY)
        assert [row["include_prep"] for row in rows] == [True, True]
        assert [row["alarm_plan"]["prep_minutes"] for row in rows] == [PREP, PREP]

    def test_rule_stores_only_a_chosen_value(self, client, route, clock):
        """비워 둔 값은 규칙에 넣지 않는다. 이 필드를 모르는 이전 버전도 회차를 만든다."""
        self.weekly(client, **AWAY)
        assert "include_prep" not in EventRecurrence.objects.get().template


class TestPlanInputs:
    """`edits.plan_inputs` 는 고른 값이 아니라 결과(`prep_applies`)를 비교한다."""

    def test_remix_only_when_the_result_changes(self, user):
        event = Event(user=user, title="수업", start_at=at(2, 9))
        before = edits.plan_inputs(event)
        event.include_prep = True
        assert edits.classify(before, edits.plan_inputs(event)) == edits.KEEP
        event.include_prep = False
        assert edits.classify(before, edits.plan_inputs(event)) == edits.REMIX
