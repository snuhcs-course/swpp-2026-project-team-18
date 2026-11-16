# Partly AI-generated (Claude)
"""관리 화면(`/admin/login/`) 로그인에도 앱 로그인과 같은 잠금을 건다(iter4 A3-5).

전에는 admin 로그인 폼이 `login_lock` 을 거치지 않아 스태프 계정의 비밀번호를 제한 없이 맞혀 볼 수
있었다. 같은 `LoginAttempt` 행을 쓰므로 앱과 admin 의 실패가 합쳐 세지고, 한쪽에서 잠기면 다른 쪽도
잠긴다. 규칙은 `login_lock` 과 같다 — 먼저 실패로 세고, 비밀번호가 맞으면 지운다.

비밀번호는 맞았지만 스태프가 아니어서 막힌 경우(`confirm_login_allowed`)는 성공으로 본다. 비밀번호를
맞힌 것은 같으므로 앱 로그인의 횟수를 남길 이유가 없다(앱 API 와 같은 기준: 비밀번호가 맞는지).
칸이 빈 제출은 세지 않는다(API 의 400 과 같다).
"""

from __future__ import annotations

from django.contrib.admin.forms import AdminAuthenticationForm
from django.core.exceptions import ValidationError

from . import login_lock


class LockedAdminAuthenticationForm(AdminAuthenticationForm):
    def clean(self):
        username = self.cleaned_data.get("username")
        password = self.cleaned_data.get("password")
        if username is None or not password:
            return super().clean()

        email = login_lock.normalize(username)
        self.cleaned_data["username"] = email
        attempt = login_lock.begin(email)
        if attempt.was_locked:
            raise self._locked(attempt)
        try:
            cleaned = super().clean()
        except ValidationError:
            if self.user_cache is not None:
                login_lock.succeed(email)
            elif attempt.locked_until is not None:
                raise self._locked(attempt) from None
            raise
        login_lock.succeed(email)
        return cleaned

    @staticmethod
    def _locked(attempt: login_lock.Attempt) -> ValidationError:
        # 문구는 앱 로그인의 423 과 같다(`AccountLocked`).
        from .serializers import AccountLocked

        return ValidationError(str(AccountLocked(attempt).detail), code="account_locked")
