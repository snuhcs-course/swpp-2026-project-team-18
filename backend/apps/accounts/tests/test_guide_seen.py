# Partly AI-generated (Claude)
"""첫 실행 안내 · 홈 코치마크 "본 적 있음"(iter4 BE-H4). 계약은 `docs/iter4/server-first-run-guide.md`."""

from __future__ import annotations

from datetime import datetime, timezone as dt_timezone

import pytest
from django.db.models import QuerySet
from rest_framework.test import APIClient

from apps.accounts.models import Profile
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


# --- 리뷰 수정(H4-1·H4-2·H4-3). (Partly AI-generated (Claude)) ---

HOME = (37.4842, 126.9297)
TOKYO = (35.6812, 139.7671)  # 서비스 지역 밖(예전 규칙으로 저장됐을 수 있는 집)


class TestOnlySentFieldsAreSaved:
    """H4-1. 다른 요청이 먼저 바꾼 칸을 되돌리지 않는다. 직렬화기 단위 테스트는 `test_api.py`
    `TestProfileSerializerSavesOnlySentFields`."""

    def test_view_rereads_the_row_instead_of_the_cached_profile(self, client, user, recompute_calls):
        """`request.user.profile` 가 옛 값이어도(다른 워커가 집을 바꿈) 안내 PATCH 가 집을 되돌리지 않는다."""
        assert user.profile.home_lat is None  # 캐시를 만든다
        Profile.objects.filter(user=user).update(
            home_lat=HOME[0], home_lng=HOME[1], coachmark_seen_at=SEEN_AT
        )
        res = client.patch("/api/profile", {"intro_seen_at": SEEN}, format="json")
        assert res.status_code == 200
        data = res.json()
        assert (data["home_lat"], data["home_lng"]) == HOME
        assert parse(data["coachmark_seen_at"]) == SEEN_AT
        assert data["recomputed_plans"] == 0
        fresh = Profile.objects.get(user=user)
        assert (fresh.home_lat, fresh.home_lng) == HOME
        assert fresh.coachmark_seen_at == SEEN_AT

    def test_view_locks_the_profile_row(self, client, monkeypatch):
        """SQLite 는 `FOR UPDATE` 를 내지 않으므로 잠금을 거는지만 본다(Postgres 는 손으로 확인)."""
        locked = []
        original = QuerySet.select_for_update

        def spy(self, *args, **kwargs):
            locked.append(self.model)
            return original(self, *args, **kwargs)

        monkeypatch.setattr(QuerySet, "select_for_update", spy)
        assert client.patch("/api/profile", {"intro_seen_at": SEEN}, format="json").status_code == 200
        assert Profile in locked


class TestHomeValidationOnlyWhenSent:
    """H4-2. 국외 집이 저장된 계정도 집을 보내지 않는 PATCH 는 된다."""

    @pytest.fixture
    def abroad(self, user):
        # 시리얼라이저 검증을 타지 않는 길(예전 규칙·admin)로 저장된 국외 집.
        Profile.objects.filter(user=user).update(home_lat=TOKYO[0], home_lng=TOKYO[1])
        user.profile.refresh_from_db()

    @pytest.mark.parametrize("body", [
        {"intro_seen_at": SEEN},
        {"coachmark_seen_at": None},
        {"home_label": "도쿄"},
        {"default_tau": 0.95},
    ])
    def test_patch_without_home_passes(self, client, abroad, recompute_calls, body):
        res = client.patch("/api/profile", body, format="json")
        assert res.status_code == 200, res.json()

    def test_sending_one_coordinate_still_checks_the_pair(self, client, abroad, recompute_calls):
        res = client.patch("/api/profile", {"home_lat": 35.0}, format="json")
        assert res.status_code == 400
        assert res.json()["error"]["details"]["home_lat"] == ["국내 좌표만 집으로 설정할 수 있어요."]

    def test_pair_message_is_haeyo(self, client):
        res = client.patch("/api/profile", {"home_lat": HOME[0]}, format="json")
        assert res.status_code == 400
        assert res.json()["error"]["details"]["home_lat"] == ["집 위도와 경도는 함께 설정해야 해요."]


def test_nanosecond_timestamps_are_truncated(client, user):
    """H4-3. 앱이 나노초 9자리를 보낼 수 있다(`Instant.toString()`). 마이크로초로 자른다."""
    res = client.patch(
        "/api/profile", {"intro_seen_at": "2026-11-11T04:12:30.512345678Z"}, format="json"
    )
    assert res.status_code == 200
    user.profile.refresh_from_db()
    assert user.profile.intro_seen_at == datetime(
        2026, 11, 11, 4, 12, 30, 512345, tzinfo=dt_timezone.utc
    )
