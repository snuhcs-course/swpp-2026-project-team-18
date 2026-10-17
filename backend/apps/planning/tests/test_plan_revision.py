# Partly AI-generated (Claude)
"""알람 계획 이력(db-schema.md A2).

`AlarmPlan` 은 재계산이 덮어쓴다. 이력은 결정값이 바뀔 때만 한 줄씩 쌓여, 이동이 끝난
뒤 일정을 고쳐도 "그때 따른 계획" 이 남는다. 카카오 경로는 모킹한다(키 없음·쿼터).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from django.db import DatabaseError
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import history, services
from apps.planning.models import AlarmPlan, PlanRevision

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}
START = datetime(2026, 9, 21, 9, 0, tzinfo=KST)


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="rev@example.com", password="test12345", nickname="이력"
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
def route(monkeypatch):
    """경로 조회를 고정값으로. `route["minutes"]` 를 바꾸면 다음 계산에 반영된다."""
    current = dict(ROUTE)
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(current), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(current, key=key), False)
    )
    return current


def make_event(user, place, **kw):
    kw.setdefault("title", "수업")
    kw.setdefault("start_at", START)
    return Event.objects.create(user=user, place=place, **kw)


class TestRecording:
    def test_first_compute_copies_the_plan(self, user, place, route):
        event = make_event(user, place)
        plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)

        rev = PlanRevision.objects.get(event=event)
        for name in history.COPIED_FIELDS:
            assert getattr(rev, name) == getattr(plan, name), name
        assert rev.user_id == user.pk
        assert rev.computed_at == plan.computed_at
        assert rev.event_start_at == START
        assert rev.trigger == "create"
        assert rev.requested_route_key == ""
        assert rev.route_minutes_raw == 20  # 카카오 원값(G2)

    def test_route_coordinates_are_not_copied(self):
        names = {f.name for f in PlanRevision._meta.get_fields()}
        assert "route_path" not in names
        assert "alt_route_path" not in names

    def test_same_result_adds_no_row(self, user, place, route):
        event = make_event(user, place)
        services.compute_and_store(event, trigger="create")
        services.compute_and_store(event, trigger="recompute")
        assert PlanRevision.objects.filter(event=event).count() == 1

    def test_changed_result_adds_a_row_and_keeps_the_old_one(self, user, place, route):
        event = make_event(user, place)
        services.compute_and_store(event, trigger="create")
        first_alarm = AlarmPlan.objects.get(event=event).alarm_at

        route["minutes"] = 35
        services.compute_and_store(event, trigger="recompute")

        old, new = PlanRevision.objects.filter(event=event).order_by("computed_at", "id")
        assert old.alarm_at == first_alarm
        assert new.alarm_at < first_alarm
        assert new.alarm_at == AlarmPlan.objects.get(event=event).alarm_at
        assert (old.route_minutes_raw, new.route_minutes_raw) == (20, 35)
        assert new.trigger == "recompute"

    def test_moving_the_event_is_a_new_decision(self, user, place, route):
        event = make_event(user, place)
        services.compute_and_store(event, trigger="create")
        event.start_at = START + timedelta(hours=1)
        event.save()
        services.compute_and_store(event, trigger="update")

        starts = list(
            PlanRevision.objects.filter(event=event)
            .order_by("computed_at", "id")
            .values_list("event_start_at", flat=True)
        )
        assert starts == [START, START + timedelta(hours=1)]

    def test_failure_states_are_recorded(self, user, place, monkeypatch, route):
        event = make_event(user, place)
        monkeypatch.setattr(services.clients, "best_route", lambda **kw: (None, True))
        services.compute_and_store(event, trigger="create")

        rev = PlanRevision.objects.get(event=event)
        assert rev.status == AlarmPlan.Status.ROUTE_FAILED
        assert rev.route_minutes_raw is None

    def test_no_place_is_recorded(self, user):
        event = Event.objects.create(user=user, title="장소 없음", start_at=START)
        services.compute_and_store(event)
        rev = PlanRevision.objects.get(event=event)
        assert rev.status == AlarmPlan.Status.NO_PLACE
        assert rev.trigger == ""

    def test_history_failure_keeps_the_plan(self, user, place, route, monkeypatch):
        """이력 쓰기가 실패해도(마이그레이션 전·DB 오류) 알람 계획은 저장된다."""

        def broken(*args, **kwargs):
            raise DatabaseError("no such table")

        monkeypatch.setattr(PlanRevision.objects, "create", broken)
        event = make_event(user, place)
        plan = services.compute_and_store(event, trigger="create")

        assert plan.status == AlarmPlan.Status.OK
        assert AlarmPlan.objects.filter(event=event).exists()
        assert not PlanRevision.objects.exists()


class TestTriggers:
    @pytest.fixture
    def client(self, user):
        c = APIClient()
        c.force_authenticate(user=user)
        return c

    def test_api_paths_record_why_they_recomputed(self, client, user, route):
        # 프로필 변경은 앞으로의 일정만 다시 계산한다. 오늘보다 한참 뒤로 둔다.
        start = (datetime.now(KST) + timedelta(days=30)).replace(hour=9, minute=0, second=0, microsecond=0)
        res = client.post(
            "/api/events",
            {"title": "수업", "start_at": start.isoformat(),
             "place": {"name": "관악캠퍼스", "lat": 37.4601, "lng": 126.9520}},
            format="json",
        )
        assert res.status_code == 201
        event_id = res.data["id"]

        later = (start + timedelta(hours=1)).isoformat()
        assert client.patch(f"/api/events/{event_id}", {"start_at": later}, format="json").status_code == 200
        route["minutes"] = 30
        assert client.post(f"/api/events/{event_id}/recompute").status_code == 200
        res = client.patch("/api/profile", {"onboarding_prep_min": 45}, format="json")
        assert res.status_code == 200
        assert res.data["recomputed_plans"] == 1

        triggers = list(
            PlanRevision.objects.filter(event_id=event_id)
            .order_by("computed_at", "id")
            .values_list("trigger", flat=True)
        )
        assert triggers == ["create", "update", "recompute", "profile"]

    def test_alarm_switch_alone_adds_no_revision(self, client, user, place, route):
        event = make_event(user, place)
        services.compute_and_store(event, trigger="create")
        res = client.patch(f"/api/events/{event.pk}", {"alarm_enabled": False}, format="json")
        assert res.status_code == 200
        assert PlanRevision.objects.filter(event=event).count() == 1


class TestDecisionRevision:
    """결정 시점: 첫 울림 전 마지막 이력 → 없으면 첫 출발 전 → 없으면 마지막."""

    def rev(self, user, event, at, minutes):
        return PlanRevision.objects.create(
            user=user, event=event, computed_at=at, status="ok",
            event_start_at=event.start_at, travel_minutes=minutes,
        )

    def test_picks_the_last_revision_before_the_first_alarm(self, user, place):
        event = make_event(user, place)
        before = self.rev(user, event, START - timedelta(hours=12), 20)
        self.rev(user, event, START - timedelta(hours=1), 35)  # 울린 뒤 재계산
        AlarmLog.objects.create(
            user=user, event=event, kind="fired", occurred_at=START - timedelta(hours=2),
            client_uuid="f1",
        )
        assert history.decision_revision(event.pk) == before

    def test_falls_back_to_the_departure(self, user, place):
        event = make_event(user, place)
        before = self.rev(user, event, START - timedelta(hours=12), 20)
        self.rev(user, event, START - timedelta(minutes=10), 35)
        TripObservation.objects.create(
            user=user, event=event, kind="depart", detector="gps",
            observed_at=START - timedelta(minutes=40), lat=37.48, lng=126.93,
            accuracy_m=10.0, distance_m=160.0, client_uuid="d1",
        )
        assert history.decision_revision(event.pk) == before

    def test_falls_back_to_the_last_revision(self, user, place):
        event = make_event(user, place)
        self.rev(user, event, START - timedelta(hours=12), 20)
        last = self.rev(user, event, START - timedelta(hours=1), 35)
        assert history.decision_revision(event.pk) == last

    def test_none_without_history(self, user, place):
        assert history.decision_revision(make_event(user, place).pk) is None
