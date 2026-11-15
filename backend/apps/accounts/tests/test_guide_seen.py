# Partly AI-generated (Claude)
"""첫 실행 안내 · 홈 코치마크 "본 적 있음"(iter4 BE-H4). 계약은 `docs/iter4/server-first-run-guide.md`."""

from __future__ import annotations

from datetime import datetime, timezone as dt_timezone

import pytest
from rest_framework.test import APIClient

from apps.planning import services as planning_services

pytestmark = [pytest.mark.django_db, pytest.mark.integration]

PASSWORD = "Gk9-hollow-meadow-51"
SEEN = "2026-11-11T04:12:30.512Z"
SEEN_AT = datetime(2026, 11, 11, 4, 12, 30, 512000, tzinfo=dt_timezone.utc)


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="guide@example.com", password=PASSWORD, nickname="안내"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def recompute_calls(monkeypatch):
    calls = []

    def fake(user, **kwargs):
        calls.append(kwargs)
        return 3

    monkeypatch.setattr(planning_services, "recompute_for_user", fake)
    return calls


def parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def test_both_fields_start_null(client):
    data = client.get("/api/profile").json()
    assert data["intro_seen_at"] is None
    assert data["coachmark_seen_at"] is None


def test_patch_sets_intro_seen_without_recompute(client, user, recompute_calls):
    res = client.patch("/api/profile", {"intro_seen_at": SEEN}, format="json")
    assert res.status_code == 200
    data = res.json()
    assert parse(data["intro_seen_at"]) == SEEN_AT
    assert data["coachmark_seen_at"] is None
    assert data["recomputed_plans"] == 0
    assert recompute_calls == []
    user.profile.refresh_from_db()
    assert user.profile.intro_seen_at == SEEN_AT
    assert parse(client.get("/api/profile").json()["intro_seen_at"]) == SEEN_AT


def test_patch_both_and_clear_with_null(client, user, recompute_calls):
    client.patch(
        "/api/profile", {"intro_seen_at": SEEN, "coachmark_seen_at": SEEN}, format="json"
    )
    res = client.patch("/api/profile", {"coachmark_seen_at": None}, format="json")
    assert res.status_code == 200
    data = res.json()
    assert data["coachmark_seen_at"] is None
    assert parse(data["intro_seen_at"]) == SEEN_AT  # 보내지 않은 칸은 그대로
    assert data["recomputed_plans"] == 0
    assert recompute_calls == []
    user.profile.refresh_from_db()
    assert user.profile.coachmark_seen_at is None


def test_seen_fields_with_a_home_change_still_recompute(client, recompute_calls):
    res = client.patch(
        "/api/profile",
        {"coachmark_seen_at": SEEN, "home_lat": 37.4842, "home_lng": 126.9297},
        format="json",
    )
    assert res.status_code == 200
    assert res.json()["recomputed_plans"] == 3
    assert len(recompute_calls) == 1


def test_bad_datetime_is_400(client):
    res = client.patch("/api/profile", {"intro_seen_at": "어제"}, format="json")
    assert res.status_code == 400
    assert "intro_seen_at" in res.json()["error"]["details"]


def test_offset_timestamps_are_accepted(client, user):
    res = client.patch(
        "/api/profile", {"intro_seen_at": "2026-11-11T13:12:30.512+09:00"}, format="json"
    )
    assert res.status_code == 200
    user.profile.refresh_from_db()
    assert user.profile.intro_seen_at == SEEN_AT


def test_requires_login():
    assert APIClient().patch("/api/profile", {"intro_seen_at": SEEN}, format="json").status_code == 401
