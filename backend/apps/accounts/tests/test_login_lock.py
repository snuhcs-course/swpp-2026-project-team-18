# Partly AI-generated (Claude)
"""로그인 잠금(iter4 BE-A3). 계약은 `docs/iter4/server-login-lock.md`."""

from __future__ import annotations

import re
from datetime import datetime, timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts import login_lock
from apps.accounts.models import LoginAttempt

pytestmark = pytest.mark.django_db

PASSWORD = "Gk9-hollow-meadow-51"
NEW_PASSWORD = "Tx4-quiet-harbor-77"
EMAIL = "lock@example.com"
LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않다."


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(email=EMAIL, password=PASSWORD, nickname="잠금")


def login(email=EMAIL, password=PASSWORD):
    return APIClient().post("/api/auth/token", {"email": email, "password": password}, format="json")


def fail(times, email=EMAIL):
    return [login(email, "wrong-password-1") for _ in range(times)]


def error(response) -> dict:
    return response.json()["error"]


# --- 규칙(login_lock) ---------------------------------------------------------


class TestBegin:
    NOW = timezone.make_aware(datetime(2026, 11, 15, 9, 0))

    def test_counts_up_and_locks_on_the_fifth(self):
        results = [login_lock.begin("a@x.com", now=self.NOW) for _ in range(5)]
        assert [r.failures for r in results] == [1, 2, 3, 4, 5]
        assert [r.remaining for r in results] == [4, 3, 2, 1, 0]
        assert [r.locked_until for r in results[:4]] == [None] * 4
        assert results[4].locked_until == self.NOW + timedelta(minutes=15)
        assert not results[4].was_locked

    def test_attempts_while_locked_are_not_counted_and_do_not_extend(self):
        for _ in range(5):
            login_lock.begin("a@x.com", now=self.NOW)
        later = login_lock.begin("a@x.com", now=self.NOW + timedelta(minutes=10))
        assert later.was_locked
        assert later.failures == 5
        assert later.locked_until == self.NOW + timedelta(minutes=15)
        assert later.retry_after_seconds(self.NOW + timedelta(minutes=10)) == 300

    def test_expired_lock_starts_over(self):
        for _ in range(5):
            login_lock.begin("a@x.com", now=self.NOW)
        after = login_lock.begin("a@x.com", now=self.NOW + timedelta(minutes=15))
        assert not after.was_locked
        assert after.failures == 1
        assert after.locked_until is None

    def test_failures_are_forgotten_after_a_quiet_window(self):
        for _ in range(4):
            login_lock.begin("a@x.com", now=self.NOW)
        assert login_lock.begin("a@x.com", now=self.NOW + timedelta(minutes=14)).failures == 5
        LoginAttempt.objects.all().delete()
        for _ in range(4):
            login_lock.begin("a@x.com", now=self.NOW)
        assert login_lock.begin("a@x.com", now=self.NOW + timedelta(minutes=15)).failures == 1

    def test_emails_are_counted_separately(self):
        for _ in range(5):
            login_lock.begin("a@x.com", now=self.NOW)
        assert login_lock.begin("b@x.com", now=self.NOW).failures == 1

    def test_clear_normalizes(self):
        login_lock.begin("a@x.com", now=self.NOW)
        login_lock.clear("  A@X.com ")
        assert not LoginAttempt.objects.exists()

    def test_limits_come_from_settings(self, settings):
        settings.LOGIN_MAX_FAILURES = 2
        settings.LOGIN_LOCK_SECONDS = 60
        login_lock.begin("a@x.com", now=self.NOW)
        second = login_lock.begin("a@x.com", now=self.NOW)
        assert second.locked_until == self.NOW + timedelta(seconds=60)

    def test_retry_after_is_rounded_up_and_at_least_one(self):
        attempt = login_lock.Attempt(5, self.NOW + timedelta(seconds=10, milliseconds=1))
        assert attempt.retry_after_seconds(self.NOW) == 11
        assert attempt.retry_after_seconds(self.NOW + timedelta(seconds=11)) == 1


# --- HTTP -------------------------------------------------------------------


@pytest.mark.integration
class TestLoginLockApi:
    def test_wrong_password_keeps_the_old_body_and_adds_remaining_attempts(self, user):
        res = login(password="nope-nope-1")
        assert res.status_code == 401
        body = error(res)
        # 전과 같은 code·message. 앱은 message 를 그대로 보인다.
        assert body["code"] == "authentication_failed"
        assert body["message"] == LOGIN_FAILED_MESSAGE
        assert body["details"] == {"remaining_attempts": 4}
        assert res["WWW-Authenticate"]

    def test_counts_down_then_locks_on_the_fifth_and_stays_locked(self, user):
        responses = fail(6)
        assert [r.status_code for r in responses] == [401, 401, 401, 401, 423, 423]
        assert [error(r)["details"]["remaining_attempts"] for r in responses[:4]] == [4, 3, 2, 1]

        locked = responses[4]
        body = error(locked)
        assert body["code"] == "account_locked"
        assert "15분 뒤에 다시 해 주세요" in body["message"]
        details = body["details"]
        assert set(details) == {"locked_until", "retry_after_seconds"}
        assert 899 <= details["retry_after_seconds"] <= 900
        assert locked["Retry-After"] == str(details["retry_after_seconds"])
        until = datetime.fromisoformat(details["locked_until"])
        assert until.tzinfo is not None
        assert abs((until - timezone.now()).total_seconds() - 900) < 5
        # 초로 올림한다. 앱이 그 시각에 다시 눌렀을 때 아직 잠겨 있으면 안 된다.
        actual = LoginAttempt.objects.get(email=EMAIL).locked_until
        assert until >= actual
        assert (until - actual).total_seconds() < 1

    def test_correct_password_is_rejected_while_locked(self, user):
        fail(5)
        res = login()
        assert res.status_code == 423
        assert error(res)["code"] == "account_locked"
        assert "access" not in res.json()

    def test_fifth_attempt_with_the_right_password_still_logs_in(self, user):
        fail(4)
        res = login()
        assert res.status_code == 200
        assert "access" in res.json()
        assert not LoginAttempt.objects.exists()

    def test_success_resets_the_count(self, user):
        fail(3)
        assert login().status_code == 200
        assert error(fail(1)[0])["details"]["remaining_attempts"] == 4

    def test_lock_ends_after_fifteen_minutes(self, user):
        fail(5)
        LoginAttempt.objects.filter(email=EMAIL).update(
            locked_until=timezone.now() - timedelta(seconds=1)
        )
        assert login().status_code == 200

    def test_email_is_normalized_like_login(self, user):
        fail(3, email="  Lock@Example.COM ")
        fail(2, email="LOCK@example.com")
        assert login().status_code == 423
        assert LoginAttempt.objects.get().email == EMAIL

    def test_unknown_email_is_counted_and_answered_the_same(self, user):
        known = fail(6)
        unknown = fail(6, email="nobody@example.com")
        assert [r.status_code for r in unknown] == [r.status_code for r in known]
        for a, b in zip(known, unknown):
            ea, eb = error(a), error(b)
            assert ea["code"] == eb["code"]
            assert set(ea["details"]) == set(eb["details"])
            if a.status_code == 401:
                assert ea == eb
        assert LoginAttempt.objects.filter(email="nobody@example.com").exists()

    def test_other_accounts_are_not_affected(self, user, django_user_model):
        django_user_model.objects.create_user(
            email="other@example.com", password=PASSWORD, nickname="다른"
        )
        fail(5)
        assert login(email="other@example.com").status_code == 200

    def test_missing_fields_are_400_and_not_counted(self, user):
        res = APIClient().post("/api/auth/token", {"email": EMAIL}, format="json")
        assert res.status_code == 400
        assert not LoginAttempt.objects.exists()

    def test_inactive_account_counts_like_a_wrong_password(self, user):
        user.is_active = False
        user.save()
        res = login()
        assert res.status_code == 401
        assert error(res)["details"] == {"remaining_attempts": 4}

    def test_admin_deleting_the_row_unlocks(self, user):
        fail(5)
        LoginAttempt.objects.filter(email=EMAIL).delete()
        assert login().status_code == 200

    def test_password_reset_confirm_unlocks(self, user, mailoutbox):
        fail(5)
        assert login().status_code == 423
        client = APIClient()
        assert client.post(
            "/api/auth/password/reset", {"email": EMAIL}, format="json"
        ).status_code == 202
        code = re.search(r"\b(\d{6})\b", mailoutbox[-1].body).group(1)
        res = client.post(
            "/api/auth/password/reset/confirm",
            {
                "email": "Lock@Example.com",
                "reset_code": code,
                "new_password": NEW_PASSWORD,
                "new_password_confirm": NEW_PASSWORD,
            },
            format="json",
        )
        assert res.status_code == 200
        assert not LoginAttempt.objects.exists()
        assert login(password=NEW_PASSWORD).status_code == 200

    def test_failed_reset_confirm_does_not_unlock(self, user):
        fail(5)
        res = APIClient().post(
            "/api/auth/password/reset/confirm",
            {
                "email": EMAIL,
                "reset_code": "000000",
                "new_password": NEW_PASSWORD,
                "new_password_confirm": NEW_PASSWORD,
            },
            format="json",
        )
        assert res.status_code == 400
        assert login().status_code == 423

    def test_wrong_current_password_on_change_does_not_count(self, user):
        client = APIClient()
        client.force_authenticate(user=user)
        for _ in range(6):
            res = client.post(
                "/api/auth/me/password",
                {
                    "current_password": "wrong-password-1",
                    "new_password": NEW_PASSWORD,
                    "new_password_confirm": NEW_PASSWORD,
                },
                format="json",
            )
            assert res.status_code == 400
        assert not LoginAttempt.objects.exists()
        assert login().status_code == 200
