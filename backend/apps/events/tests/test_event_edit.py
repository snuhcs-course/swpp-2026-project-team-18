# Partly AI-generated (Claude)
"""알람 결정 뒤 일정 수정(`PATCH /api/events/{id}`, `events.edits`).

수정 종류마다 알람 계획을 어떻게 다시 만드는지 고정한다. 카카오 경로는 모킹하고 호출 수를
센다. 계약 문서는 docs/iter3/event-edit-api.md.

| 수정 | 계획 | 카카오 |
| --- | --- | --- |
| 출발지·목적지·경로 | 다시 계산 | 1회 |
| 시각·τ | 지금 원값으로 다시 섞기 | 0회 |
| 제목·켬/끔 | 그대로 | 0회 |
"""

from __future__ import annotations

from datetime import datetime, time, timedelta
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events import edits
from apps.events.models import Event
from apps.observations.models import AlarmLog
from apps.planning import history, services
from apps.planning.models import AlarmPlan, PlanRevision

pytestmark = pytest.mark.django_db
KST = ZoneInfo("Asia/Seoul")
HOME = (37.4842, 126.9297)
CAMPUS = {"name": "관악캠퍼스", "lat": 37.4601, "lng": 126.9520}
LIBRARY = {"name": "중앙도서관", "lat": 37.4664, "lng": 126.9520}

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}


@pytest.fixture
def user(django_user_model):
    user = django_user_model.objects.create_user(email="edit@example.com", password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = (*HOME, "신림역")
    profile.onboarding_prep_min = 30
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
    """경로 조회를 고정값으로. `calls` 로 호출을 센다."""
    current = dict(ROUTE)
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(current), False

    def resolve_route(key, **kw):
        calls.append("resolve")
        return dict(current, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return {"current": current, "calls": calls}


def at(days: int, hour: int, minute: int = 0) -> datetime:
    """오늘부터 `days` 일 뒤 한국 시각 `hour:minute`."""
    day = timezone.now().astimezone(KST).date() + timedelta(days=days)
    return datetime.combine(day, time(hour, minute), tzinfo=KST)


def create(client, **changes) -> int:
    body = {"title": "수업", "start_at": at(2, 9).isoformat(), "place": CAMPUS}
    response = client.post("/api/events", body | changes, format="json")
    assert response.status_code == 201, response.data
    return response.data["id"]


def patch(client, pk, **body):
    response = client.patch(f"/api/events/{pk}", body, format="json")
    assert response.status_code == 200, response.data
    return response.data


def plan_of(pk) -> AlarmPlan:
    return AlarmPlan.objects.get(event_id=pk)


def revisions(pk) -> list[PlanRevision]:
    return list(PlanRevision.objects.filter(event_id=pk).order_by("computed_at", "id"))


@pytest.mark.integration
class TestTimeOnly:
    def test_moves_the_alarm_without_a_route_lookup(self, client, route):
        pk = create(client)
        before = plan_of(pk)
        calls = len(route["calls"])
        # 경로를 다시 조회했다면 이 값이 들어간다.
        route["current"]["minutes"] = 99

        data = patch(client, pk, start_at=at(2, 11).isoformat())

        after = plan_of(pk)
        assert len(route["calls"]) == calls
        assert after.alarm_at == before.alarm_at + timedelta(hours=2)
        assert after.depart_by == before.depart_by + timedelta(hours=2)
        assert after.arrive_at == before.arrive_at + timedelta(hours=2)
        assert (after.prep_minutes, after.travel_minutes, after.buffer_minutes) == (
            before.prep_minutes, before.travel_minutes, before.buffer_minutes,
        )
        # 경로 필드는 그대로다.
        assert after.route_key == before.route_key
        assert after.route_path == before.route_path
        assert after.route_summary == before.route_summary
        # 응답이 새 알람을 싣는다. 앱은 이 값으로 기기 알람을 다시 건다.
        assert datetime.fromisoformat(data["alarm_plan"]["alarm_at"]) == after.alarm_at

    def test_records_an_update_revision_with_the_same_raw_minutes(self, client, route):
        pk = create(client)
        patch(client, pk, start_at=at(2, 11).isoformat())

        first, second = revisions(pk)
        assert second.trigger == PlanRevision.Trigger.UPDATE
        assert second.route_minutes_raw == first.route_minutes_raw == 20
        assert second.event_start_at == at(2, 11)
        assert second.alarm_at == plan_of(pk).alarm_at

    def test_repeated_time_edits_keep_reusing_the_route(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        for hour in (10, 8, 13):
            patch(client, pk, start_at=at(3, hour).isoformat())
        assert len(route["calls"]) == calls
        assert plan_of(pk).arrive_at == at(3, 13) - timedelta(minutes=10)

    def test_risk_preview_still_has_a_route_after_the_edit(self, client, route):
        pk = create(client)
        patch(client, pk, start_at=at(2, 11).isoformat())
        event = Event.objects.get(pk=pk)
        assert history.reusable_revision(event, plan_of(pk)) is not None

    def test_without_a_matching_revision_falls_back_to_a_route_lookup(self, client, route):
        pk = create(client)
        PlanRevision.objects.filter(event_id=pk).delete()
        calls = len(route["calls"])
        route["current"]["minutes"] = 30

        patch(client, pk, start_at=at(2, 11).isoformat())

        assert len(route["calls"]) == calls + 1
        assert revisions(pk)[-1].route_minutes_raw == 30

    def test_a_failed_plan_looks_the_route_up_again(self, client, route):
        pk = create(client)
        AlarmPlan.objects.filter(event_id=pk).update(
            status=AlarmPlan.Status.ROUTE_FAILED, alarm_at=None
        )
        calls = len(route["calls"])

        patch(client, pk, start_at=at(2, 11).isoformat())

        assert len(route["calls"]) == calls + 1
        assert plan_of(pk).status == AlarmPlan.Status.OK

    def test_an_identical_start_is_not_an_edit(self, client, route):
        pk = create(client)
        before = plan_of(pk)
        patch(client, pk, start_at=at(2, 9).isoformat(), title="수업")
        assert plan_of(pk).computed_at == before.computed_at
        assert len(revisions(pk)) == 1


@pytest.mark.integration
class TestRouteInputs:
    def test_place_change_looks_the_route_up(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        route["current"]["minutes"] = 25

        patch(client, pk, place=LIBRARY)

        assert len(route["calls"]) == calls + 1
        assert revisions(pk)[-1].route_minutes_raw == 25
        assert revisions(pk)[-1].trigger == PlanRevision.Trigger.UPDATE

    def test_resending_the_same_place_is_not_a_route_change(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        patch(client, pk, place=CAMPUS, title="전공 수업")
        assert len(route["calls"]) == calls
        assert len(revisions(pk)) == 1

    def test_route_choice_looks_up_only_that_route(self, client, route):
        pk = create(client)
        calls = len(route["calls"])

        patch(client, pk, route_key="walk")

        assert route["calls"][calls:] == ["resolve"]
        assert plan_of(pk).route_key == "walk"

    def test_origin_change_looks_the_route_up(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        patch(client, pk, origin_lat=37.4770, origin_lng=126.9630, origin_label="낙성대")
        assert len(route["calls"]) == calls + 1
        # 출발지를 따로 고르면 준비 시간이 빠진다(`estimate_components`).
        assert plan_of(pk).prep_minutes == 0

    def test_origin_at_the_home_coordinates_drops_prep_without_a_route_lookup(
        self, client, route
    ):
        """좌표가 집과 같아도 출발지를 따로 고르면 준비 시간이 빠진다. 경로는 그대로다."""
        pk = create(client)
        assert plan_of(pk).prep_minutes > 0
        calls = len(route["calls"])
        patch(client, pk, origin_lat=HOME[0], origin_lng=HOME[1], origin_label="집 앞 카페")
        assert len(route["calls"]) == calls
        assert plan_of(pk).prep_minutes == 0

    def test_home_destination_looks_the_route_up(self, client, route):
        pk = create(client, origin_lat=37.4770, origin_lng=126.9630)
        calls = len(route["calls"])
        patch(client, pk, destination_is_home=True)
        assert len(route["calls"]) == calls + 1

    def test_time_and_place_together_look_the_route_up_once(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        patch(client, pk, start_at=at(2, 11).isoformat(), place=LIBRARY)
        assert len(route["calls"]) == calls + 1
        assert revisions(pk)[-1].event_start_at == at(2, 11)


@pytest.mark.integration
class TestNoRecompute:
    def test_title_only_keeps_the_plan(self, client, route):
        pk = create(client)
        before = plan_of(pk)
        calls = len(route["calls"])

        data = patch(client, pk, title="전공 수업")

        assert data["title"] == "전공 수업"
        assert len(route["calls"]) == calls
        assert plan_of(pk).computed_at == before.computed_at
        assert len(revisions(pk)) == 1

    def test_origin_label_only_keeps_the_plan(self, client, route):
        pk = create(client, origin_lat=37.4770, origin_lng=126.9630, origin_label="낙성대")
        calls = len(route["calls"])
        patch(client, pk, origin_label="낙성대역 4번 출구")
        assert len(route["calls"]) == calls
        assert len(revisions(pk)) == 1

    def test_alarm_switch_only_keeps_the_plan(self, client, route):
        pk = create(client)
        calls = len(route["calls"])
        patch(client, pk, alarm_enabled=False)
        assert len(route["calls"]) == calls
        assert len(revisions(pk)) == 1


@pytest.mark.integration
class TestTau:
    def test_tau_change_moves_the_alarm_without_a_route_lookup(self, client, route):
        pk = create(client)
        calls = len(route["calls"])

        patch(client, pk, tau_override=0.95)

        plan = plan_of(pk)
        assert len(route["calls"]) == calls
        assert plan.tau_used == 0.95
        assert revisions(pk)[-1].trigger == PlanRevision.Trigger.UPDATE
        assert revisions(pk)[-1].tau_used == 0.95

    def test_tag_with_another_default_tau_moves_the_alarm(self, client, route):
        pk = create(client, tag_key="class")
        calls = len(route["calls"])
        patch(client, pk, tag_key="exam")
        assert len(route["calls"]) == calls
        assert plan_of(pk).tau_used == 0.99

    def test_tag_with_the_same_tau_keeps_the_plan(self, client, route):
        """수업(0.90)과 프로필 기본값(0.90)은 같은 τ 다. 태그는 τ 로만 계획에 들어간다."""
        pk = create(client)
        before = plan_of(pk)
        patch(client, pk, tag_key="class")
        assert plan_of(pk).computed_at == before.computed_at
        assert len(revisions(pk)) == 1

    def test_matches_a_full_recompute(self, client, route):
        """경로를 다시 조회한 결과와 같다. 카카오 원값이 같으면 갈 길이 같다."""
        pk = create(client)
        patch(client, pk, tau_override=0.97, start_at=at(2, 10).isoformat())
        remixed = plan_of(pk)

        services.compute_and_store(Event.objects.get(pk=pk))
        full = plan_of(pk)
        for name in PlanRevision.DECISION_FIELDS:
            if name != "event_start_at":
                assert getattr(remixed, name) == getattr(full, name), name


@pytest.mark.integration
class TestDateMove:
    def test_moving_to_another_day_resets_that_days_choices(self, client, user, route):
        moved = create(client, alarm_enabled=False)
        first = create(client, start_at=at(3, 8).isoformat(), alarm_enabled=True)
        second = create(client, start_at=at(3, 13).isoformat(), alarm_enabled=False)
        # 생성이 같은 날 선택을 돌리므로 두 일정의 선택을 다시 넣는다.
        Event.objects.filter(pk=first).update(alarm_enabled=True)
        Event.objects.filter(pk=moved).update(alarm_enabled=False)

        patch(client, moved, start_at=at(3, 10).isoformat())

        assert Event.objects.get(pk=first).alarm_enabled is None
        assert Event.objects.get(pk=second).alarm_enabled is None
        # 옮긴 일정의 선택은 그대로 둔다. 생성 때 새 일정의 선택을 두는 것과 같다.
        assert Event.objects.get(pk=moved).alarm_enabled is False
        logs = AlarmLog.objects.filter(kind=AlarmLog.Kind.AUTO, source=AlarmLog.Source.SERVER)
        assert set(logs.values_list("event_id", flat=True)) == {first, second}

    def test_the_day_left_behind_keeps_its_choices(self, client, route):
        stays = create(client, start_at=at(2, 8).isoformat())
        Event.objects.filter(pk=stays).update(alarm_enabled=True)
        moved = create(client, start_at=at(4, 9).isoformat())
        patch(client, moved, start_at=at(2, 10).isoformat())
        Event.objects.filter(pk=stays).update(alarm_enabled=True)

        patch(client, moved, start_at=at(5, 9).isoformat())

        assert Event.objects.get(pk=stays).alarm_enabled is True

    def test_same_day_time_change_keeps_choices(self, client, route):
        other = create(client, start_at=at(2, 13).isoformat())
        Event.objects.filter(pk=other).update(alarm_enabled=True)
        pk = create(client, start_at=at(2, 15).isoformat())
        Event.objects.filter(pk=other).update(alarm_enabled=True)

        patch(client, pk, start_at=at(2, 7).isoformat())

        assert Event.objects.get(pk=other).alarm_enabled is True

    def test_crossing_utc_midnight_on_the_same_local_day_keeps_choices(self, client, route):
        """한국 10:00 → 08:00 은 UTC 로는 날짜가 바뀌지만 사용자에게는 같은 날이다."""
        other = create(client, start_at=at(2, 13).isoformat())
        pk = create(client, start_at=at(2, 10).isoformat())
        Event.objects.filter(pk=other).update(alarm_enabled=True)

        patch(client, pk, start_at=at(2, 8).isoformat())

        assert Event.objects.get(pk=other).alarm_enabled is True

    def test_day_is_the_users_local_day(self, client, user, route):
        """UTC 로는 같은 날이어도 사용자 시간대로 날짜가 바뀌면 옮긴 것이다."""
        other = create(client, start_at=at(3, 8).isoformat())
        # 한국 23:30 → 다음 날 00:30. UTC 로는 둘 다 같은 날 14:30·15:30 이다.
        pk = create(client, start_at=at(2, 23, 30).isoformat())
        Event.objects.filter(pk=other).update(alarm_enabled=False)

        patch(client, pk, start_at=at(3, 0, 30).isoformat())

        assert Event.objects.get(pk=other).alarm_enabled is None


@pytest.mark.integration
class TestRecurringOccurrence:
    @pytest.fixture
    def clock(self, monkeypatch):
        value = [datetime(2026, 10, 19, 8, tzinfo=KST)]  # 월요일
        monkeypatch.setattr(timezone, "now", lambda: value[0])
        return value

    @pytest.fixture
    def weekly(self, client, clock, route):
        """월·수 반복. 창(7일) 안에 회차 둘."""
        response = client.post("/api/events", {
            "title": "반복 수업", "start_at": "2026-10-19T09:00:00+09:00",
            "repeat_weekdays": [0, 2], "place": CAMPUS,
        }, format="json")
        assert response.status_code == 201
        found = client.get("/api/events").data["results"]
        assert len(found) == 2
        return [row["id"] for row in found]

    def test_time_edit_changes_only_that_occurrence_without_a_route_lookup(
        self, client, route, weekly
    ):
        monday, wednesday = weekly
        rule = Event.objects.get(pk=monday).recurrence
        before = {pk: plan_of(pk).alarm_at for pk in weekly}
        calls = len(route["calls"])

        patch(client, monday, start_at="2026-10-19T10:00:00+09:00")

        assert len(route["calls"]) == calls
        assert plan_of(monday).alarm_at == before[monday] + timedelta(hours=1)
        assert plan_of(wednesday).alarm_at == before[wednesday]
        rule.refresh_from_db()
        assert rule.start_at.astimezone(KST).hour == 9
        assert len(client.get("/api/events").data["results"]) == 2

    def test_moving_out_of_the_window_and_back_recomputes(self, client, route, weekly):
        monday, _ = weekly
        patch(client, monday, start_at="2026-11-02T09:00:00+09:00")
        assert not AlarmPlan.objects.filter(event_id=monday).exists()
        calls = len(route["calls"])

        data = patch(client, monday, start_at="2026-10-20T09:00:00+09:00")

        # 계획이 없으니 원값도 없다. 경로부터 다시 구한다.
        assert len(route["calls"]) == calls + 1
        assert data["alarm_plan"]["status"] == "ok"

    def test_title_edit_outside_the_window_keeps_the_plan(self, client, route, weekly, clock):
        monday, _ = weekly
        clock[0] = datetime(2026, 10, 27, 8, tzinfo=KST)
        before = plan_of(monday)
        calls = len(route["calls"])

        patch(client, monday, title="지난 수업")

        assert len(route["calls"]) == calls
        assert plan_of(monday).alarm_at == before.alarm_at


class TestClassify:
    """`edits.classify` 단위. 값 비교 규칙만 본다."""

    BASE = edits.PlanInputs(
        route=((37.48, 126.93), (37.46, 126.95), ""),
        model=(datetime(2026, 11, 2, 9, tzinfo=KST), 0.9, True),
    )

    def test_nothing_changed(self):
        assert edits.classify(self.BASE, self.BASE) == edits.KEEP

    def test_route_wins_over_model(self):
        after = edits.PlanInputs(
            route=(self.BASE.route[0], (37.47, 126.95), ""),
            model=(datetime(2026, 11, 2, 10, tzinfo=KST), 0.9, True),
        )
        assert edits.classify(self.BASE, after) == edits.ROUTE

    @pytest.mark.parametrize("index,value", [
        (0, datetime(2026, 11, 2, 10, tzinfo=KST)), (1, 0.95), (2, False),
    ])
    def test_model_inputs(self, index, value):
        model = list(self.BASE.model)
        model[index] = value
        after = edits.PlanInputs(route=self.BASE.route, model=tuple(model))
        assert edits.classify(self.BASE, after) == edits.REMIX


@pytest.mark.integration
def test_remix_failure_falls_back_to_a_route_lookup(client, route, monkeypatch):
    """원값 재사용이 실패하면 경로부터 다시 구한다(`refresh_edited` 가 None)."""
    pk = create(client)
    monkeypatch.setattr(edits.refresh, "refresh_edited", Mock(return_value=None))
    calls = len(route["calls"])
    patch(client, pk, tau_override=0.95)
    assert len(route["calls"]) == calls + 1
