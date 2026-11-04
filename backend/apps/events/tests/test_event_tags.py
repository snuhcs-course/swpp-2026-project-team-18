# Partly AI-generated (Claude)
"""사용자가 만드는 일정 종류(BE-21, 화면 12-a "+ 추가"). 계약은 docs/iter3/event-tags-api.md."""

from __future__ import annotations

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, EventTag
from apps.planning import services
from apps.planning.models import AlarmPlan

pytestmark = pytest.mark.django_db
KST = ZoneInfo("Asia/Seoul")
HOME = (37.4842, 126.9297)
CAMPUS = {"name": "관악캠퍼스", "lat": 37.4601, "lng": 126.9520}
ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
}
GLOBAL_KEYS = ["class", "exam", "presentation", "train", "parttime", "meetup"]


def make_user(django_user_model, email):
    user = django_user_model.objects.create_user(email=email, password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = (*HOME, "신림역")
    profile.onboarding_prep_min = 30
    profile.timezone = "Asia/Seoul"
    profile.default_tau = 0.9
    profile.save()
    return user


@pytest.fixture
def user(django_user_model):
    return make_user(django_user_model, "tags@example.com")


@pytest.fixture
def other(django_user_model):
    return make_user(django_user_model, "tags-other@example.com")


def api(user) -> APIClient:
    client = APIClient()
    client.force_authenticate(user)
    return client


@pytest.fixture
def client(user):
    return api(user)


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


def add_tag(client, label, **body):
    return client.post("/api/events/tags", {"label": label, **body}, format="json")


def add_event(client, **changes) -> int:
    body = {"title": "동아리", "start_at": at(2, 9).isoformat(), "place": CAMPUS}
    response = client.post("/api/events", body | changes, format="json")
    assert response.status_code == 201, response.data
    return response.data["id"]


def tau_of(pk) -> float:
    return AlarmPlan.objects.get(event_id=pk).tau_used


@pytest.mark.integration
class TestCreate:
    def test_creates_a_custom_tag_that_inherits_the_profile_tau(self, client, user):
        res = add_tag(client, "  동아리   모임 ")
        assert res.status_code == 201, res.data
        body = res.data
        assert body["label"] == "동아리 모임"
        assert body["custom"] is True
        assert body["default_tau"] is None
        assert body["penalty_shape"] == "linear"
        assert body["key"].startswith("u") and body["key"] not in GLOBAL_KEYS
        assert "user" not in body
        assert EventTag.objects.get(key=body["key"]).user == user

    def test_keeps_a_given_tau(self, client):
        res = add_tag(client, "면접", default_tau=0.97)
        assert res.status_code == 201
        assert res.data["default_tau"] == 0.97

    def test_a_global_label_returns_the_global_tag(self, client):
        res = add_tag(client, "시험")
        assert res.status_code == 200
        assert res.data["key"] == "exam"
        assert res.data["custom"] is False
        assert not EventTag.objects.filter(user__isnull=False).exists()

    def test_the_same_label_again_returns_the_first_one(self, client, user):
        first = add_tag(client, "Gym")
        again = add_tag(client, "gym", default_tau=0.99)
        assert again.status_code == 200
        assert again.data["key"] == first.data["key"]
        assert again.data["default_tau"] is None
        assert EventTag.objects.filter(user=user).count() == 1

    def test_another_user_can_use_the_same_label(self, client, other):
        mine = add_tag(client, "동아리")
        theirs = add_tag(api(other), "동아리")
        assert theirs.status_code == 201
        assert theirs.data["key"] != mine.data["key"]

    @pytest.mark.parametrize(
        "body",
        [
            {"label": "   "},
            {"label": "가" * 21},
            {"label": "동아리", "default_tau": 0.3},
            {"label": "동아리", "default_tau": 1.0},
            {},
        ],
    )
    def test_rejects_bad_input(self, client, body):
        res = client.post("/api/events/tags", body, format="json")
        assert res.status_code == 400
        assert not EventTag.objects.filter(user__isnull=False).exists()

    def test_limit_per_user(self, client):
        for i in range(EventTag.MAX_PER_USER):
            assert add_tag(client, f"종류{i}").status_code == 201
        res = add_tag(client, "하나 더")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "tag_limit"
        # 이미 있는 이름은 한도와 상관없이 돌려준다.
        assert add_tag(client, "종류0").status_code == 200

    def test_ignores_a_user_in_the_body(self, client, other, user):
        res = client.post(
            "/api/events/tags", {"label": "동아리", "user": other.pk}, format="json"
        )
        assert EventTag.objects.get(key=res.data["key"]).user == user


@pytest.mark.integration
class TestList:
    def test_globals_first_then_mine_and_not_others(self, client, other):
        add_tag(client, "동아리")
        add_tag(client, "면접", default_tau=0.97)
        add_tag(api(other), "남의 종류")

        res = client.get("/api/events/tags")
        assert res.status_code == 200
        labels = [tag["label"] for tag in res.data]
        assert [tag["key"] for tag in res.data[:6]] == GLOBAL_KEYS
        assert labels[6:] == ["동아리", "면접"]
        assert [tag["custom"] for tag in res.data] == [False] * 6 + [True, True]


@pytest.mark.integration
class TestUseOnEvents:
    def test_an_event_with_my_tag_uses_the_profile_tau_until_the_tag_has_one(
        self, client, route
    ):
        key = add_tag(client, "동아리").data["key"]
        pk = add_event(client, tag_key=key)

        res = client.get(f"/api/events/{pk}")
        assert res.data["tag"]["key"] == key
        assert res.data["tag"]["custom"] is True
        assert tau_of(pk) == 0.9

    def test_my_tag_tau_is_used(self, client, route):
        key = add_tag(client, "면접", default_tau=0.97).data["key"]
        pk = add_event(client, tag_key=key)
        assert tau_of(pk) == 0.97

    def test_another_users_tag_is_rejected(self, client, other, route):
        key = add_tag(api(other), "남의 종류").data["key"]
        res = client.post(
            "/api/events",
            {"title": "x", "start_at": at(2, 9).isoformat(), "place": CAMPUS, "tag_key": key},
            format="json",
        )
        assert res.status_code == 400
        pk = add_event(client)
        res = client.patch(f"/api/events/{pk}", {"tag_key": key}, format="json")
        assert res.status_code == 400
        assert Event.objects.get(pk=pk).tag is None

    def test_calendar_import_rejects_another_users_tag(self, client, other, route):
        key = add_tag(api(other), "남의 종류").data["key"]
        res = client.post(
            "/api/events/import",
            {"events": [{
                "external_id": "cal-1", "title": "x",
                "start_at": at(2, 9).isoformat(), "place": CAMPUS, "tag_key": key,
            }]},
            format="json",
        )
        assert res.status_code == 400
        assert not Event.objects.filter(external_id="cal-1").exists()

    def test_risk_options_do_not_claim_an_auto_adjustment_for_an_inherited_tau(
        self, client, route
    ):
        key = add_tag(client, "동아리").data["key"]
        pk = add_event(client, tag_key=key)
        res = client.get(f"/api/events/{pk}/risk-options")
        assert res.status_code == 200
        assert res.data["auto_adjusted"] is False
        assert res.data["auto_reason"] is None


@pytest.mark.integration
class TestEditAndDelete:
    def test_rename_and_tau_change_remix_future_alarms_without_a_route_lookup(
        self, client, route
    ):
        key = add_tag(client, "동아리").data["key"]
        pk = add_event(client, tag_key=key)
        calls = len(route)

        res = client.patch(
            f"/api/events/tags/{key}", {"label": "동아리 MT", "default_tau": 0.99}, format="json"
        )
        assert res.status_code == 200, res.data
        assert res.data["label"] == "동아리 MT"
        assert res.data["default_tau"] == 0.99
        assert tau_of(pk) == 0.99
        assert len(route) == calls

        # τ 를 비우면 다시 프로필 기본값.
        client.patch(f"/api/events/tags/{key}", {"default_tau": None}, format="json")
        assert tau_of(pk) == 0.9

    def test_rename_to_a_label_that_is_taken_is_a_conflict(self, client):
        key = add_tag(client, "동아리").data["key"]
        add_tag(client, "면접")
        for label in ("시험", "면접"):
            res = client.patch(f"/api/events/tags/{key}", {"label": label}, format="json")
            assert res.status_code == 409
            assert res.json()["error"]["code"] == "tag_duplicate"
        assert EventTag.objects.get(key=key).label == "동아리"

    def test_global_and_other_users_tags_are_not_found(self, client, other):
        theirs = add_tag(api(other), "남의 종류").data["key"]
        for key in ("exam", theirs, "nope"):
            assert client.patch(
                f"/api/events/tags/{key}", {"label": "탈취"}, format="json"
            ).status_code == 404
            assert client.delete(f"/api/events/tags/{key}").status_code == 404
        assert EventTag.objects.get(key="exam").label == "시험"
        assert EventTag.objects.get(key=theirs).label == "남의 종류"

    def test_delete_leaves_events_without_a_tag_and_remixes_them(self, client, route):
        key = add_tag(client, "면접", default_tau=0.97).data["key"]
        pk = add_event(client, tag_key=key)
        assert tau_of(pk) == 0.97

        assert client.delete(f"/api/events/tags/{key}").status_code == 204
        assert not EventTag.objects.filter(key=key).exists()
        assert Event.objects.get(pk=pk).tag is None
        assert tau_of(pk) == 0.9
        assert [t["key"] for t in client.get("/api/events/tags").data] == GLOBAL_KEYS


class TestModelRules:
    def test_a_global_tag_must_have_a_tau(self):
        with pytest.raises(IntegrityError), transaction.atomic():
            EventTag.objects.create(key="noτ", label="전역", penalty_shape="linear")

    def test_one_label_per_user(self, user):
        EventTag.objects.create(key="u1", label="동아리", user=user, penalty_shape="linear")
        with pytest.raises(IntegrityError), transaction.atomic():
            EventTag.objects.create(key="u2", label="동아리", user=user, penalty_shape="linear")

    def test_visible_to(self, user, other):
        mine = EventTag.objects.create(key="u1", label="내 것", user=user, penalty_shape="linear")
        EventTag.objects.create(key="u2", label="남의 것", user=other, penalty_shape="linear")
        keys = set(EventTag.objects.visible_to(user).values_list("key", flat=True))
        assert keys == set(GLOBAL_KEYS) | {mine.key}

    def test_effective_tau_falls_back_to_the_profile(self, user):
        tag = EventTag.objects.create(key="u1", label="내 것", user=user, penalty_shape="linear")
        event = Event.objects.create(user=user, title="x", start_at=at(2, 9), tag=tag)
        assert event.effective_tau == 0.9
