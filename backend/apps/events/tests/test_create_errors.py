# Partly AI-generated (Claude)
"""일정 추가가 실패할 때 서버가 하는 일(11-04 피드백). 계약은 docs/iter3/event-create-errors.md.

- 같은 추가 요청을 다시 보내도(`client_request_id`) 한 번만 만든다.
- 알람 계산이 터져도 한 번 일정은 남긴다(test_alarm_switch.py 에서도 본다).
- 처리하지 못한 오류도 `{"error": {"code": "server_error", ...}}` 로 온다.
"""

from __future__ import annotations

from datetime import datetime, time, timedelta
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event
from apps.planning import services
from apps.planning.models import AlarmPlan

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
KST = ZoneInfo("Asia/Seoul")
CAMPUS = {"name": "관악캠퍼스", "lat": 37.4601, "lng": 126.9520}
ROUTE = {
    "minutes": 20, "mode": "transit", "source": "kakao_transit",
    "summary": "2호선 · 20분", "key": "transit:2호선", "detail": "2호선",
}
REQUEST_ID = "3b0f6c1e-4a1d-4f7e-9a51-2f7d5a0c9e11"


def make_user(django_user_model, email):
    user = django_user_model.objects.create_user(email=email, password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = 37.4842, 126.9297, "신림역"
    profile.onboarding_prep_min = 30
    profile.timezone = "Asia/Seoul"
    profile.save()
    return user


def api(user) -> APIClient:
    client = APIClient()
    client.force_authenticate(user)
    return client


@pytest.fixture
def user(django_user_model):
    return make_user(django_user_model, "create-errors@example.com")


@pytest.fixture
def client(user):
    return api(user)


@pytest.fixture
def route(monkeypatch):
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(ROUTE), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )
    return calls


def body(**changes):
    day = timezone.now().astimezone(KST).date() + timedelta(days=2)
    start = datetime.combine(day, time(9), tzinfo=KST)
    return {"title": "수업", "start_at": start.isoformat(), "place": CAMPUS} | changes


class TestRetryDoesNotDuplicate:
    def test_the_same_request_id_returns_the_first_event(self, client, route):
        first = client.post("/api/events", body(client_request_id=REQUEST_ID), format="json")
        again = client.post("/api/events", body(client_request_id=REQUEST_ID), format="json")

        assert first.status_code == 201
        assert again.status_code == 200
        assert again.data["id"] == first.data["id"]
        assert Event.objects.count() == 1
        assert len(route) == 1  # 다시 보낸 요청은 경로를 조회하지 않는다

    def test_a_retry_returns_the_first_event_even_if_the_body_changed(self, client, route):
        """응답을 못 받은 사이 사용자가 제목을 고쳐 다시 눌러도 일정은 하나다. 고치기는 PATCH 로."""
        first = client.post("/api/events", body(client_request_id=REQUEST_ID), format="json")
        again = client.post(
            "/api/events", body(client_request_id=REQUEST_ID, title="고친 제목"), format="json"
        )
        assert again.status_code == 200
        assert again.data["title"] == first.data["title"] == "수업"

    def test_without_a_request_id_each_post_creates(self, client, route):
        assert client.post("/api/events", body(), format="json").status_code == 201
        assert client.post("/api/events", body(), format="json").status_code == 201
        assert Event.objects.count() == 2

    def test_request_ids_are_per_user(self, client, route, django_user_model):
        other = api(make_user(django_user_model, "create-errors-2@example.com"))
        assert client.post("/api/events", body(client_request_id=REQUEST_ID), format="json").status_code == 201
        assert other.post("/api/events", body(client_request_id=REQUEST_ID), format="json").status_code == 201
        assert Event.objects.count() == 2

    @pytest.mark.parametrize("bad", ["short", "has space in it", "x" * 65, "한글요청아이디입니다"])
    def test_a_malformed_request_id_is_rejected(self, client, route, bad):
        res = client.post("/api/events", body(client_request_id=bad), format="json")
        assert res.status_code == 400
        assert "client_request_id" in res.json()["error"]["details"]
        assert not Event.objects.exists()

    def test_patch_does_not_change_the_request_id(self, client, route):
        pk = client.post("/api/events", body(client_request_id=REQUEST_ID), format="json").data["id"]
        res = client.patch(f"/api/events/{pk}", {"client_request_id": "another-id-1234"}, format="json")
        assert res.status_code == 200
        assert Event.objects.get(pk=pk).client_request_id == REQUEST_ID

    def test_a_failed_validation_does_not_burn_the_request_id(self, client, route):
        """제목이 비어 400 이었으면 아무것도 저장하지 않았다. 고쳐서 같은 id 로 보내면 만든다."""
        assert client.post(
            "/api/events", body(client_request_id=REQUEST_ID, title=" "), format="json"
        ).status_code == 400
        assert client.post(
            "/api/events", body(client_request_id=REQUEST_ID), format="json"
        ).status_code == 201


class TestPlanningCrash:
    def test_the_event_is_kept_with_a_failed_plan(self, client, monkeypatch):
        monkeypatch.setattr(services, "compute_and_store", Mock(side_effect=KeyError("boom")))
        res = client.post("/api/events", body(client_request_id=REQUEST_ID), format="json")

        assert res.status_code == 201
        assert res.data["title"] == "수업"
        assert res.data["alarm_plan"]["status"] == "route_failed"
        plan = AlarmPlan.objects.get(event_id=res.data["id"])
        assert plan.status == AlarmPlan.Status.ROUTE_FAILED
        assert plan.alarm_at is None

    def test_recompute_later_fixes_it(self, client, monkeypatch, route):
        real = services.compute_and_store
        monkeypatch.setattr(services, "compute_and_store", Mock(side_effect=KeyError("boom")))
        pk = client.post("/api/events", body(), format="json").data["id"]
        monkeypatch.setattr(services, "compute_and_store", real)

        res = client.post(f"/api/events/{pk}/recompute")
        assert res.status_code == 200
        assert AlarmPlan.objects.get(event_id=pk).status == AlarmPlan.Status.OK


class TestUnhandledErrorsUseTheCommonFormat:
    def test_an_unexpected_error_is_a_json_500(self, client, monkeypatch):
        monkeypatch.setattr(
            "apps.events.tags.visible_tags", Mock(side_effect=RuntimeError("bug"))
        )
        res = client.get("/api/events/tags")
        assert res.status_code == 500
        error = res.json()["error"]
        assert error["code"] == "server_error"
        assert error["message"]
        assert "bug" not in error["message"]

    def test_debug_still_raises_for_the_developer(self, client, monkeypatch, settings):
        settings.DEBUG = True
        monkeypatch.setattr(
            "apps.events.tags.visible_tags", Mock(side_effect=RuntimeError("bug"))
        )
        with pytest.raises(RuntimeError, match="bug"):
            client.get("/api/events/tags")
