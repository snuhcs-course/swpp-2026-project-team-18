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
LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 맞지 않아요."


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


# --- 리뷰 수정(iter4 A3-1 ~ A3-6) ----------------------------------------------
# Partly AI-generated (Claude)

EMAIL_COLUMN = LoginAttempt._meta.get_field("email").max_length
LONG_EMAIL = "a" * 300 + "@example.com"


class TestOverlongEmail:
    """A3-1. 254자를 넘는 이메일이 Postgres 에서 `DataError` → 500 이었다.

    SQLite 는 varchar 길이를 보지 않으므로 저장된 키의 **길이**를 직접 확인한다.
    """

    def test_key_always_fits_the_column(self):
        login_lock.begin(LONG_EMAIL)
        row = LoginAttempt.objects.get()
        assert len(row.email) <= EMAIL_COLUMN
        assert row.email.startswith("sha256:")

    def test_emails_up_to_the_column_are_kept_as_is(self):
        email = "b" * (EMAIL_COLUMN - len("@x.com")) + "@x.com"
        assert len(email) == EMAIL_COLUMN
        assert login_lock.key(email) == email
        assert login_lock.key(email + "m") != email + "m"

    def test_same_long_email_shares_one_counter(self):
        assert [login_lock.begin(LONG_EMAIL).failures for _ in range(3)] == [1, 2, 3]
        assert login_lock.begin("c" + LONG_EMAIL).failures == 1

    @pytest.mark.integration
    def test_http_answers_like_any_unknown_email(self, user):
        responses = fail(6, email=LONG_EMAIL)
        unknown = fail(6, email="nobody@example.com")
        assert [r.status_code for r in responses] == [r.status_code for r in unknown]
        assert [r.status_code for r in responses] == [401, 401, 401, 401, 423, 423]
        assert error(responses[0]) == error(unknown[0])
        assert error(responses[0])["details"] == {"remaining_attempts": 4}

    @pytest.mark.integration
    def test_clear_uses_the_same_key(self, user):
        fail(2, email=LONG_EMAIL)
        login_lock.clear(LONG_EMAIL.upper())
        assert not LoginAttempt.objects.exists()


class TestLoginIpThrottle:
    """A3-2. 이메일별 잠금과 따로 IP 별 `login` 통(429). 테스트 설정은 끄므로 여기서 켠다."""

    @pytest.fixture
    def throttled(self, monkeypatch):
        from django.core.cache import cache
        from rest_framework.throttling import ScopedRateThrottle

        monkeypatch.setattr(ScopedRateThrottle, "THROTTLE_RATES", {"login": "3/hour"})
        cache.clear()
        yield
        cache.clear()

    def test_rate_is_defined_in_base_and_off_in_tests(self, settings):
        import importlib

        base = importlib.import_module("config.settings.base")
        assert base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]["login"] == "60/hour"
        assert settings.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]["login"] is None

    @pytest.mark.integration
    def test_ip_is_throttled_with_429_distinct_from_the_lock(self, user, throttled):
        responses = [login(f"spray{i}@example.com", "wrong-password-1") for i in range(4)]
        assert [r.status_code for r in responses] == [401, 401, 401, 429]
        body = error(responses[3])
        assert body["code"] == "throttled"
        assert responses[3]["Retry-After"]
        # 스로틀은 뷰 앞에서 걸린다. 429 는 실패로 세지 않고 행도 만들지 않는다.
        assert not LoginAttempt.objects.filter(email="spray3@example.com").exists()

    @pytest.mark.integration
    def test_other_ip_is_not_affected(self, user, throttled):
        for _ in range(3):
            login("x@example.com", "wrong-password-1")
        res = APIClient().post(
            "/api/auth/token",
            {"email": EMAIL, "password": PASSWORD},
            format="json",
            REMOTE_ADDR="192.0.2.9",
        )
        assert res.status_code == 200


class TestStaleRowsArePurged:
    """A3-2. 효력이 없는 행(없는 이메일 포함)은 하루 한 번 보존 정리가 지운다."""

    def test_purge_drops_only_rows_that_have_no_effect(self):
        from apps.common import retention

        now = timezone.now()
        old = now - timedelta(minutes=16)
        LoginAttempt.objects.create(email="stale@x.com", failures=3, last_attempt_at=old)
        LoginAttempt.objects.create(
            email="expired-lock@x.com", failures=5, last_attempt_at=old,
            locked_until=old + timedelta(minutes=15),
        )
        LoginAttempt.objects.create(email="never@x.com")  # last_attempt_at 없음
        LoginAttempt.objects.create(
            email="recent@x.com", failures=2, last_attempt_at=now - timedelta(minutes=14)
        )
        # 잠긴 행은 마지막 시도가 오래됐어도 남긴다.
        LoginAttempt.objects.create(
            email="locked@x.com", failures=5, last_attempt_at=old,
            locked_until=now + timedelta(minutes=1),
        )

        assert retention.purge()["login_attempt"] == 3
        assert set(LoginAttempt.objects.values_list("email", flat=True)) == {
            "recent@x.com", "locked@x.com",
        }

    def test_purged_row_behaves_like_the_stale_row(self):
        """지운 행과 남은 행은 다음 시도에서 같은 값이다 — 둘 다 1 부터 센다."""
        from apps.common import retention

        now = timezone.now()
        LoginAttempt.objects.create(
            email="a@x.com", failures=4, last_attempt_at=now - timedelta(minutes=20)
        )
        kept = login_lock.begin("a@x.com", now=now)
        LoginAttempt.objects.update(failures=4, last_attempt_at=now - timedelta(minutes=20))
        retention.purge(now=now)
        assert not LoginAttempt.objects.exists()
        purged = login_lock.begin("a@x.com", now=now)
        assert kept.failures == purged.failures == 1

    def test_window_follows_the_lock_setting(self, settings):
        from apps.common import retention

        settings.LOGIN_LOCK_SECONDS = 60
        LoginAttempt.objects.create(
            email="a@x.com", failures=1, last_attempt_at=timezone.now() - timedelta(minutes=2)
        )
        assert retention.purge_login_attempts() == 1


class TestAtomicRequestsGuard:
    """A3-4. `ATOMIC_REQUESTS` 를 켜면 오류 응답과 함께 센 값이 되돌려진다 — 서버가 뜨지 않게 한다."""

    def test_current_settings_pass(self):
        from apps.accounts.checks import check_atomic_requests_off

        assert check_atomic_requests_off() == []

    def test_enabled_atomic_requests_is_an_error(self, monkeypatch):
        from types import SimpleNamespace

        from apps.accounts import checks

        monkeypatch.setattr(
            checks,
            "settings",
            SimpleNamespace(DATABASES={"default": {"ATOMIC_REQUESTS": True}, "other": {}}),
        )
        errors = checks.check_atomic_requests_off()
        assert [e.id for e in errors] == ["accounts.E001"]
        assert "default" in errors[0].msg

    def test_check_is_registered(self):
        from django.core import checks as django_checks

        from apps.accounts.checks import check_atomic_requests_off

        assert check_atomic_requests_off in django_checks.registry.registry.get_checks()

    def test_base_settings_keep_it_off(self):
        import importlib

        base = importlib.import_module("config.settings.base")
        for config in base.DATABASES.values():
            assert not config.get("ATOMIC_REQUESTS")

    @pytest.mark.integration
    def test_counting_survives_an_outer_atomic_block(self, user):
        """테스트는 바깥 트랜잭션 안에서 돈다. `begin` 의 저장점이 401 뒤에도 남는다."""
        from django.db import connection

        assert connection.in_atomic_block
        fail(2)
        assert LoginAttempt.objects.get(email=EMAIL).failures == 2


@pytest.mark.integration
class TestAdminLoginLock:
    """A3-5. `/admin/login/` 도 같은 잠금을 쓴다(같은 행)."""

    @pytest.fixture
    def staff(self, django_user_model):
        return django_user_model.objects.create_user(
            email="staff@example.com", password=PASSWORD, nickname="관리", is_staff=True,
            is_superuser=True,
        )

    def admin_login(self, client, email="staff@example.com", password=PASSWORD):
        return client.post(
            "/admin/login/?next=/admin/", {"username": email, "password": password}
        )

    def test_uses_the_locking_form(self, client):
        from django.contrib import admin

        from apps.accounts.admin_login import LockedAdminAuthenticationForm

        assert admin.site.login_form is LockedAdminAuthenticationForm
        assert client.get("/admin/login/").status_code == 200

    def test_five_wrong_passwords_lock_even_the_right_one(self, client, staff):
        for _ in range(4):
            res = self.admin_login(client, password="wrong-password-1")
            assert res.status_code == 200  # 폼을 다시 보인다
            assert "로그인할 수 없어요" not in res.content.decode()
        fifth = self.admin_login(client, password="wrong-password-1")
        assert "로그인할 수 없어요" in fifth.content.decode()
        res = self.admin_login(client)
        assert res.status_code == 200
        assert "로그인할 수 없어요" in res.content.decode()
        assert "_auth_user_id" not in client.session
        assert LoginAttempt.objects.get(email="staff@example.com").failures == 5

    def test_success_logs_in_and_clears_the_count(self, client, staff):
        self.admin_login(client, password="wrong-password-1")
        assert LoginAttempt.objects.get().failures == 1
        res = self.admin_login(client, email="  Staff@Example.com ")
        assert res.status_code == 302
        assert client.session["_auth_user_id"] == str(staff.pk)
        assert not LoginAttempt.objects.exists()

    def test_shares_the_lock_with_the_app_login(self, client, staff):
        fail(5, email="staff@example.com")
        res = self.admin_login(client)
        assert "로그인할 수 없어요" in res.content.decode()
        assert "_auth_user_id" not in client.session

    def test_right_password_of_a_non_staff_user_resets_like_the_app(self, client, user):
        fail(3)
        res = self.admin_login(client, email=EMAIL)
        assert res.status_code == 200  # 스태프가 아니라 들어가지 못한다
        assert "_auth_user_id" not in client.session
        assert not LoginAttempt.objects.exists()

    def test_empty_password_is_not_counted(self, client, staff):
        self.admin_login(client, password="")
        assert not LoginAttempt.objects.exists()


@pytest.mark.integration
class TestRemainingAttemptsAfterOwnerLogin:
    """A3-3 — 알려진 한계를 고정한다. 성공은 횟수를 지운다(요구 사항 "성공하면 0 으로").

    그래서 남의 이메일을 두 번 틀려 본 사람은 그 사이 주인이 로그인했는지 알 수 있다.
    이 동작을 바꾸면 `docs/iter4/server-login-lock.md` "알려진 한계" 도 고친다.
    """

    def test_owner_success_in_between_resets_the_probe(self, user):
        assert error(fail(1)[0])["details"]["remaining_attempts"] == 4
        assert login().status_code == 200
        assert error(fail(1)[0])["details"]["remaining_attempts"] == 4
