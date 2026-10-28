# Partly AI-generated (Claude)
"""refresh 토큰 취소(`accounts.tokens`): 로그아웃, 모든 기기 로그아웃, 갱신 회전.

전에는 서버가 refresh 를 무효로 만들 방법이 없었다. 로그아웃해도 그 refresh 는 14일 동안 새
access 를 받았다. 이 파일은 취소한 refresh 가 갱신에서 거절되는 것과, **기존 로그인·갱신은
그대로인 것**(회전은 기본 꺼짐, 지금 앱이 새 refresh 를 저장하지 않는다)을 고정한다.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.urls import reverse
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken
from rest_framework_simplejwt.tokens import AccessToken, RefreshToken

from apps.accounts import tokens

pytestmark = pytest.mark.django_db

PASSWORD = "Gk9-hollow-meadow-51"


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="logout@example.com", password=PASSWORD, nickname="로그아웃"
    )


def login(user) -> dict:
    response = APIClient().post(
        "/api/auth/token", {"email": user.email, "password": PASSWORD}, format="json"
    )
    assert response.status_code == 200
    return response.data


def refresh(raw: str):
    return APIClient().post("/api/auth/token/refresh", {"refresh": raw}, format="json")


def logout(raw, **extra):
    return APIClient().post("/api/auth/logout", {"refresh": raw}, format="json", **extra)


def as_user(access: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access}")
    return client


@pytest.mark.integration
class TestExistingBehaviour:
    """지금 앱이 쓰는 흐름. 바뀌면 안 된다."""

    def test_refresh_returns_only_access_and_can_be_reused(self, user):
        """회전은 기본 꺼짐이다. 앱(`AuthInterceptor.kt`)은 응답의 `access` 만 저장하고 같은
        refresh 를 14일 동안 다시 쓴다. 켜져 있으면 두 번째 갱신이 401 이 되어 모두 로그아웃된다."""
        tokens_ = login(user)
        first = refresh(tokens_["refresh"])
        second = refresh(tokens_["refresh"])
        assert first.status_code == second.status_code == 200
        assert set(first.data) == {"access"}
        assert as_user(second.data["access"]).get("/api/auth/me").status_code == 200

    def test_login_register_and_social_record_issued_tokens(self, user):
        """발급 기록이 있어야 모든 기기 로그아웃이 그 토큰을 찾는다."""
        login(user)
        response = APIClient().post("/api/auth/register", {
            "email": "new@example.com", "nickname": "새", "password": PASSWORD,
            "password_confirm": PASSWORD, "terms_agreed": True,
        }, format="json")
        assert response.status_code == 201
        assert OutstandingToken.objects.filter(user=user).count() == 1
        assert OutstandingToken.objects.filter(user__email="new@example.com").count() == 1

    def test_tokens_issued_before_this_feature_still_refresh(self, user):
        """배포 전에 받은 refresh 는 발급 기록이 없다. 그래도 갱신된다."""
        raw = str(RefreshToken.for_user(user))
        OutstandingToken.objects.all().delete()
        assert refresh(raw).status_code == 200


@pytest.mark.integration
class TestLogout:
    def test_revoked_refresh_no_longer_refreshes(self, user):
        tokens_ = login(user)
        response = logout(tokens_["refresh"])
        assert response.status_code == 204
        failed = refresh(tokens_["refresh"])
        assert failed.status_code == 401
        assert failed.data["error"]["code"] == "token_not_valid"

    def test_other_devices_keep_working(self, user):
        phone, tablet = login(user), login(user)
        logout(phone["refresh"])
        assert refresh(tablet["refresh"]).status_code == 200

    def test_twice_is_still_204(self, user):
        """앱의 재시도·두 번 누름. 두 번째가 401 이면 앱이 오류를 띄운다."""
        raw = login(user)["refresh"]
        assert logout(raw).status_code == 204
        assert logout(raw).status_code == 204
        assert BlacklistedToken.objects.count() == 1

    def test_works_without_an_access_token(self, user):
        """access 가 만료된 뒤에도 로그아웃할 수 있어야 한다. 낡은 Bearer 헤더도 무시한다."""
        raw = login(user)["refresh"]
        response = logout(raw, HTTP_AUTHORIZATION="Bearer expired-or-garbage")
        assert response.status_code == 204

    def test_token_issued_before_this_feature_can_be_revoked(self, user):
        raw = str(RefreshToken.for_user(user))
        OutstandingToken.objects.all().delete()
        assert logout(raw).status_code == 204
        assert refresh(raw).status_code == 401
        assert OutstandingToken.objects.get().user is None  # 기록을 만들며 토큰만 남긴다

    def test_access_token_stays_valid_until_it_expires(self, user):
        """access 는 취소 목록을 보지 않는다(매 요청 조회를 피하는 설계). 최대 30분 남는다."""
        tokens_ = login(user)
        logout(tokens_["refresh"])
        assert as_user(tokens_["access"]).get("/api/auth/me").status_code == 200

    @pytest.mark.parametrize("raw", ["garbage", "a.b.c", ""])
    def test_rejects_garbage(self, user, raw):
        response = logout(raw)
        assert response.status_code in (400, 401)
        assert not BlacklistedToken.objects.exists()

    def test_rejects_an_access_token(self, user):
        response = logout(login(user)["access"])
        assert response.status_code == 401
        assert not BlacklistedToken.objects.exists()

    def test_rejects_an_expired_refresh(self, user):
        token = RefreshToken.for_user(user)
        token.set_exp(lifetime=timedelta(seconds=-1))
        assert logout(str(token)).status_code == 401

    def test_rejects_a_forged_signature(self, user):
        raw = login(user)["refresh"]
        head, body, signature = raw.split(".")
        forged = ".".join([head, body, ("A" if signature[0] != "A" else "B") + signature[1:]])
        assert logout(forged).status_code == 401
        assert not BlacklistedToken.objects.exists()

    def test_missing_body_is_400(self):
        response = APIClient().post("/api/auth/logout", {}, format="json")
        assert response.status_code == 400
        assert "refresh" in response.data["error"]["details"]


@pytest.mark.integration
class TestLogoutAll:
    def test_revokes_every_device(self, user):
        phone, tablet = login(user), login(user)
        response = as_user(phone["access"]).post("/api/auth/me/logout-all")
        assert response.status_code == 200
        assert response.data == {"revoked": 2}
        assert refresh(phone["refresh"]).status_code == 401
        assert refresh(tablet["refresh"]).status_code == 401

    def test_other_users_are_untouched(self, user, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password=PASSWORD, nickname="남"
        )
        mine, theirs = login(user), login(other)
        as_user(mine["access"]).post("/api/auth/me/logout-all")
        assert refresh(theirs["refresh"]).status_code == 200

    def test_needs_login(self):
        assert APIClient().post("/api/auth/me/logout-all").status_code == 401

    def test_skips_already_revoked_and_expired(self, user):
        phone = login(user)
        logout(phone["refresh"])
        stale = RefreshToken.for_user(user)
        OutstandingToken.objects.filter(jti=stale["jti"]).update(
            expires_at=timezone.now() - timedelta(minutes=1)
        )
        tablet = login(user)
        response = as_user(tablet["access"]).post("/api/auth/me/logout-all")
        assert response.data == {"revoked": 1}


class TestRevokeAll:
    def test_returns_zero_when_nothing_is_live(self, user):
        assert tokens.revoke_all(user) == 0

    def test_counts_only_this_user(self, user, django_user_model):
        other = django_user_model.objects.create_user(
            email="x@example.com", password=PASSWORD, nickname="x"
        )
        RefreshToken.for_user(user)
        RefreshToken.for_user(other)
        assert tokens.revoke_all(user) == 1
        assert BlacklistedToken.objects.get().token.user == user


@pytest.mark.integration
class TestRotation:
    """앱이 새 refresh 를 저장하도록 고친 뒤 `JWT_ROTATE_REFRESH_TOKENS=1` 로 켠다."""

    @pytest.fixture(autouse=True)
    def rotate(self, monkeypatch):
        # `override_settings` 로는 안 된다. SimpleJWT 는 설정이 바뀌면 `settings.api_settings` 를
        # 새 객체로 바꾸는데, 갱신 serializer 는 import 때 받은 옛 객체를 계속 본다.
        from rest_framework_simplejwt import serializers

        monkeypatch.setattr(serializers.api_settings, "ROTATE_REFRESH_TOKENS", True)

    def test_old_refresh_is_revoked_and_new_one_works(self, user):
        old = login(user)["refresh"]
        rotated = refresh(old)
        assert rotated.status_code == 200
        assert rotated.data["refresh"] != old
        assert refresh(old).status_code == 401
        assert refresh(rotated.data["refresh"]).status_code == 200

    def test_rotated_refresh_can_log_out(self, user):
        new = refresh(login(user)["refresh"]).data["refresh"]
        assert logout(new).status_code == 204
        assert refresh(new).status_code == 401


class TestSetting:
    def test_rotation_is_off_unless_the_environment_turns_it_on(self, monkeypatch):
        import importlib

        from config.settings import base

        monkeypatch.delenv("JWT_ROTATE_REFRESH_TOKENS", raising=False)
        assert importlib.reload(base).SIMPLE_JWT["ROTATE_REFRESH_TOKENS"] is False
        monkeypatch.setenv("JWT_ROTATE_REFRESH_TOKENS", "1")
        assert importlib.reload(base).SIMPLE_JWT["ROTATE_REFRESH_TOKENS"] is True
        monkeypatch.setenv("JWT_ROTATE_REFRESH_TOKENS", "true")
        assert importlib.reload(base).SIMPLE_JWT["ROTATE_REFRESH_TOKENS"] is False
        monkeypatch.delenv("JWT_ROTATE_REFRESH_TOKENS")
        importlib.reload(base)
        assert base.SIMPLE_JWT["BLACKLIST_AFTER_ROTATION"] is True


@pytest.mark.integration
def test_admin_pages_open(admin_client, user):
    login(user)
    for model in ("outstandingtoken", "blacklistedtoken"):
        url = reverse(f"admin:token_blacklist_{model}_changelist")
        assert admin_client.get(url).status_code == 200


def test_access_token_type_is_not_refresh(user):
    """`revoke` 가 종류를 보는 근거. access 도 같은 키로 서명된다."""
    access = str(AccessToken.for_user(user))
    with pytest.raises(Exception):
        tokens.revoke(access)
    assert not BlacklistedToken.objects.exists()
