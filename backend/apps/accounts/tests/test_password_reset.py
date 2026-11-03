# Partly AI-generated (Claude)
"""비밀번호 찾기·변경(R3-9)과 이메일 인증(R3-10). 계약은 `docs/iter3/password-api.md`."""

from __future__ import annotations

import re
from datetime import timedelta

import pytest
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts import codes
from apps.accounts.models import EmailCode

pytestmark = [pytest.mark.django_db, pytest.mark.integration]

PASSWORD = "Gk9-hollow-meadow-51"
NEW_PASSWORD = "Tx4-quiet-harbor-77"


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="reset@example.com", password=PASSWORD, nickname="재설정"
    )


@pytest.fixture
def social_user(django_user_model):
    user = django_user_model.objects.create_user(
        email="kakao-123@social.invalid", password=None, nickname="소셜"
    )
    user.set_unusable_password()
    user.save()
    return user


def post(path, body, client=None):
    return (client or APIClient()).post(path, body, format="json")


def login(email, password):
    return post("/api/auth/token", {"email": email, "password": password})


def refresh(raw):
    return post("/api/auth/token/refresh", {"refresh": raw})


def code_from(mail) -> str:
    return re.search(r"\b(\d{6})\b", mail.body).group(1)


def request_code(mailoutbox, email="reset@example.com") -> str:
    response = post("/api/auth/password/reset", {"email": email})
    assert response.status_code == 202
    return code_from(mailoutbox[-1])


def confirm(code, email="reset@example.com", password=NEW_PASSWORD, confirm_password=None):
    return post("/api/auth/password/reset/confirm", {
        "email": email,
        "reset_code": code,
        "new_password": password,
        "new_password_confirm": confirm_password if confirm_password is not None else password,
    })


class TestResetRequest:
    def test_sends_a_code_and_answers_202(self, user, mailoutbox):
        response = post("/api/auth/password/reset", {"email": "  Reset@Example.COM "})
        assert response.status_code == 202
        assert response.data == {"sent": True, "expires_in": 600}
        assert len(mailoutbox) == 1
        assert mailoutbox[0].to == ["reset@example.com"]
        assert "10분" in mailoutbox[0].body
        # 코드는 해시로만 저장한다.
        row = EmailCode.objects.get(user=user)
        assert code_from(mailoutbox[0]) not in row.code_hash

    @pytest.mark.parametrize("email", ["nobody@example.com", "kakao-123@social.invalid"])
    def test_same_answer_without_sending_for_unknown_or_social_only(
        self, social_user, mailoutbox, email
    ):
        response = post("/api/auth/password/reset", {"email": email})
        assert response.status_code == 202
        assert response.data == {"sent": True, "expires_in": 600}
        assert mailoutbox == []

    def test_new_request_kills_the_old_code(self, user, mailoutbox):
        first = request_code(mailoutbox)
        second = request_code(mailoutbox)
        if first != second:
            assert confirm(first).data["error"]["code"] == "invalid_reset_code"
        assert confirm(second).status_code == 200

    def test_bad_email_is_invalid(self):
        response = post("/api/auth/password/reset", {"email": "not-an-email"})
        assert response.status_code == 400
        assert response.data["error"]["code"] == "invalid"
        assert "email" in response.data["error"]["details"]

    def test_mail_failure_still_answers_202(self, user, monkeypatch):
        def boom(*args, **kwargs):
            raise OSError("smtp down")

        monkeypatch.setattr(codes, "send_mail", boom)
        assert post("/api/auth/password/reset", {"email": user.email}).status_code == 202


class TestResetConfirm:
    def test_changes_password_revokes_old_refresh_and_logs_in(self, user, mailoutbox):
        old = login(user.email, PASSWORD).data
        code = request_code(mailoutbox)

        response = confirm(code)
        assert response.status_code == 200
        assert set(response.data) == {"access", "refresh", "user"}
        assert response.data["user"]["email"] == user.email

        assert refresh(old["refresh"]).status_code == 401
        assert refresh(response.data["refresh"]).status_code == 200
        assert login(user.email, PASSWORD).status_code == 401
        assert login(user.email, NEW_PASSWORD).status_code == 200

    def test_code_is_single_use(self, user, mailoutbox):
        code = request_code(mailoutbox)
        assert confirm(code).status_code == 200
        response = confirm(code, password="Zr8-other-river-33")
        assert response.data["error"]["code"] == "invalid_reset_code"

    def test_unknown_email_and_wrong_code_look_the_same(self, user, mailoutbox):
        code = request_code(mailoutbox)
        wrong = "000000" if code != "000000" else "111111"
        a = confirm(wrong)
        b = confirm(code, email="nobody@example.com")
        assert a.status_code == b.status_code == 400
        assert a.data["error"]["code"] == b.data["error"]["code"] == "invalid_reset_code"

    def test_expired(self, user, mailoutbox):
        code = request_code(mailoutbox)
        EmailCode.objects.update(expires_at=timezone.now() - timedelta(seconds=1))
        assert confirm(code).data["error"]["code"] == "reset_code_expired"

    def test_five_wrong_tries_kill_the_code(self, user, mailoutbox):
        code = request_code(mailoutbox)
        wrong = "000000" if code != "000000" else "111111"
        for _ in range(codes.MAX_ATTEMPTS):
            assert confirm(wrong).data["error"]["code"] == "invalid_reset_code"
        assert confirm(code).data["error"]["code"] == "too_many_attempts"
        assert login(user.email, PASSWORD).status_code == 200

    def test_password_rules_do_not_burn_the_code(self, user, mailoutbox):
        code = request_code(mailoutbox)
        short = confirm(code, password="abc1")
        assert short.status_code == 400
        assert short.data["error"]["code"] == "invalid"
        assert "new_password" in short.data["error"]["details"]

        mismatch = confirm(code, confirm_password=NEW_PASSWORD + "x")
        assert mismatch.data["error"]["details"]["new_password_confirm"] == [
            "비밀번호가 일치하지 않는다."
        ]
        assert confirm(code).status_code == 200


class TestPasswordChange:
    def client_for(self, user):
        client = APIClient()
        client.credentials(
            HTTP_AUTHORIZATION=f"Bearer {login(user.email, PASSWORD).data['access']}"
        )
        return client

    def change(self, client, current=PASSWORD, new=NEW_PASSWORD, again=None):
        return post("/api/auth/me/password", {
            "current_password": current,
            "new_password": new,
            "new_password_confirm": again if again is not None else new,
        }, client)

    def test_changes_and_revokes_every_device(self, user):
        other = login(user.email, PASSWORD).data
        client = self.client_for(user)

        response = self.change(client)
        assert response.status_code == 200
        assert set(response.data) == {"access", "refresh", "revoked"}
        assert response.data["revoked"] == 2  # 다른 기기 + 이 기기
        assert refresh(other["refresh"]).status_code == 401
        assert refresh(response.data["refresh"]).status_code == 200
        assert login(user.email, NEW_PASSWORD).status_code == 200

    def test_wrong_current_password_is_400_not_401(self, user):
        response = self.change(self.client_for(user), current="wrong-password-1")
        assert response.status_code == 400
        assert response.data["error"]["code"] == "invalid_current_password"

    def test_same_password(self, user):
        response = self.change(self.client_for(user), new=PASSWORD)
        assert response.data["error"]["code"] == "password_unchanged"

    def test_validation(self, user):
        response = self.change(self.client_for(user), new="abc1")
        assert response.data["error"]["code"] == "invalid"
        assert "new_password" in response.data["error"]["details"]

    def test_social_only_account_is_409(self, social_user):
        client = APIClient()
        client.force_authenticate(social_user)
        response = self.change(client, current="x")
        assert response.status_code == 409
        assert response.data["error"]["code"] == "password_login_unavailable"

    def test_requires_login(self):
        assert post("/api/auth/me/password", {}).status_code == 401


class TestEmailVerify:
    def test_verify_flow(self, user, mailoutbox):
        client = APIClient()
        client.force_authenticate(user)
        assert client.get("/api/auth/me").data["email_verified"] is False

        response = post("/api/auth/me/email/verify", {}, client)
        assert response.status_code == 202
        code = code_from(mailoutbox[-1])

        wrong = "000000" if code != "000000" else "111111"
        bad = post("/api/auth/me/email/verify/confirm", {"code": wrong}, client)
        assert bad.data["error"]["code"] == "invalid_verify_code"

        ok = post("/api/auth/me/email/verify/confirm", {"code": code}, client)
        assert ok.status_code == 200
        assert ok.data["user"]["email_verified"] is True

        again = post("/api/auth/me/email/verify", {}, client)
        assert again.status_code == 409
        assert again.data["error"]["code"] == "email_already_verified"

    def test_placeholder_email_is_not_deliverable(self, social_user, mailoutbox):
        client = APIClient()
        client.force_authenticate(social_user)
        response = post("/api/auth/me/email/verify", {}, client)
        assert response.status_code == 409
        assert response.data["error"]["code"] == "email_not_deliverable"
        assert mailoutbox == []

    def test_reset_code_does_not_verify_email(self, user, mailoutbox):
        code = request_code(mailoutbox)
        client = APIClient()
        client.force_authenticate(user)
        response = post("/api/auth/me/email/verify/confirm", {"code": code}, client)
        assert response.data["error"]["code"] == "invalid_verify_code"


class TestResetThrottle:
    def test_per_email_limit(self, user, settings, mailoutbox):
        from rest_framework.throttling import SimpleRateThrottle

        from apps.accounts import views_password

        rates = {**SimpleRateThrottle.THROTTLE_RATES, "password_reset_email": "3/hour"}
        cache.clear()
        try:
            original = views_password.PasswordResetEmailThrottle.THROTTLE_RATES
            views_password.PasswordResetEmailThrottle.THROTTLE_RATES = rates
            statuses = [
                post("/api/auth/password/reset", {"email": user.email}).status_code
                for _ in range(4)
            ]
        finally:
            views_password.PasswordResetEmailThrottle.THROTTLE_RATES = original
            cache.clear()
        assert statuses == [202, 202, 202, 429]
        assert len(mailoutbox) == 3
