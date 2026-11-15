# Partly AI-generated (Claude)
"""이메일 로그인 잠금(iter4 BE-A3). 계약은 `docs/iter4/server-login-lock.md`.

| 규칙 | 값 |
| --- | --- |
| 세는 단위 | 정규화한 이메일(`strip().lower()`, 로그인과 같다). 없는 이메일도 같게 센다 |
| 잠그는 때 | 연속 [settings.LOGIN_MAX_FAILURES] (5) 번째로 틀린 그 시도부터 |
| 잠그는 시간 | [settings.LOGIN_LOCK_SECONDS] (15분). 잠긴 동안의 시도는 세지 않고 늘리지도 않는다 |
| 잊는 때 | 마지막 시도에서 잠금 시간만큼 지나면 실패 횟수도 0 부터 |
| 푸는 때 | 로그인 성공, 비밀번호 재설정 완료, admin 에서 행 삭제 |

**시도를 먼저 실패로 센 뒤 비밀번호를 본다**(`begin`). 비밀번호를 보고 나서 세면, 동시에 보낸
틀린 시도들이 모두 "아직 4번" 을 보고 통과해 다섯 번보다 많이 맞혀 볼 수 있다. 먼저 세면 행
잠금(`select_for_update`) 아래에서 한 번에 하나씩 번호를 받으므로 다섯 번째 뒤의 시도는
비밀번호를 보기 전에 잠김으로 끝난다. 맞으면 `succeed` 가 행을 지운다.

예외를 쓰지 않는다 — 센 값을 저장한 뒤 오류를 내야 한다(`codes` 와 같은 이유). 요청 전체를 묶는
트랜잭션(`ATOMIC_REQUESTS`)은 켜져 있지 않다. 켜면 401 과 함께 센 값이 되돌려진다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from .models import LoginAttempt


def max_failures() -> int:
    return int(settings.LOGIN_MAX_FAILURES)


def lock_duration() -> timedelta:
    return timedelta(seconds=int(settings.LOGIN_LOCK_SECONDS))


def normalize(email) -> str:
    """로그인(`LoginSerializer`)·재설정과 같은 정규화."""
    return email.strip().lower() if isinstance(email, str) else ""


@dataclass(frozen=True)
class Attempt:
    """`begin` 의 결과. `failures` 는 이번 시도를 실패로 센 뒤의 값이다."""

    failures: int
    locked_until: datetime | None
    # 이번 시도 전부터 잠겨 있었다(비밀번호를 보지 않고 거절한다).
    was_locked: bool = False

    @property
    def remaining(self) -> int:
        """이번 시도가 틀렸을 때 잠기기 전까지 남은 횟수."""
        return max(0, max_failures() - self.failures)

    def retry_after_seconds(self, now: datetime | None = None) -> int:
        if self.locked_until is None:
            return 0
        seconds = (self.locked_until - (now or timezone.now())).total_seconds()
        return max(1, math.ceil(seconds))


def begin(email: str, *, now: datetime | None = None) -> Attempt:
    """시도 하나를 실패로 먼저 센다. 이미 잠겨 있으면 세지 않고 그 잠금을 돌려준다."""
    now = now or timezone.now()
    with transaction.atomic():
        row, _ = LoginAttempt.objects.select_for_update().get_or_create(email=email)
        if row.locked_until is not None and row.locked_until > now:
            return Attempt(row.failures, row.locked_until, was_locked=True)

        stale = row.last_attempt_at is None or now - row.last_attempt_at >= lock_duration()
        if row.locked_until is not None or stale:
            # 잠금이 끝났거나 오래 쉬었다. 새로 센다.
            row.failures = 0
            row.locked_until = None
        row.failures += 1
        if row.failures >= max_failures():
            row.locked_until = now + lock_duration()
        row.last_attempt_at = now
        row.save(update_fields=["failures", "locked_until", "last_attempt_at"])
        return Attempt(row.failures, row.locked_until)


def succeed(email: str) -> None:
    """로그인 성공. 실패 기록을 지운다."""
    LoginAttempt.objects.filter(email=email).delete()


def clear(email: str) -> None:
    """비밀번호 재설정 완료. 잠금과 실패 기록을 지운다."""
    LoginAttempt.objects.filter(email=normalize(email)).delete()
