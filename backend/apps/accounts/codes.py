# Partly AI-generated (Claude)
"""메일로 보내는 6자리 코드. 비밀번호 재설정(R3-9)과 이메일 인증(R3-10)이 쓴다.

| 규칙 | 값 |
| --- | --- |
| 자릿수 | 6 (`000000`~`999999`) |
| 유효 시간 | [CODE_TTL_SECONDS] (10분) |
| 코드 하나당 틀릴 수 있는 횟수 | [MAX_ATTEMPTS] (5). 다섯 번째로 틀린 뒤로는 맞아도 `too_many_attempts` |
| 저장 | `salted_hmac` 해시만. 코드 자체는 메일에만 있다 |
| 다시 요청 | 같은 사용자·용도의 이전 코드를 지운다 |

부르는 쪽(뷰)이 결과 이름을 공통 오류 코드로 바꾼다. 여기서는 예외를 쓰지 않는다 — 틀린
횟수를 저장한 뒤 오류를 내야 해서, 트랜잭션을 되돌리는 예외로 끝내면 횟수가 사라진다.
"""

from __future__ import annotations

import hmac
import logging
import secrets
from datetime import timedelta

from django.conf import settings
from django.core.mail import send_mail
from django.db import transaction
from django.db.models import F
from django.utils import timezone
from django.utils.crypto import salted_hmac

from .models import EmailCode

logger = logging.getLogger(__name__)

CODE_TTL_SECONDS = 600
MAX_ATTEMPTS = 5

OK = "ok"
MISSING = "missing"
EXPIRED = "expired"
TOO_MANY = "too_many_attempts"
WRONG = "wrong"

SUBJECTS = {
    EmailCode.Purpose.PASSWORD_RESET: "JustInTime 비밀번호 재설정 코드",
    EmailCode.Purpose.EMAIL_VERIFY: "JustInTime 이메일 인증 코드",
}


def _hash(user_id: int, purpose: str, code: str) -> str:
    return salted_hmac("accounts.EmailCode", f"{purpose}:{user_id}:{code}").hexdigest()


def issue(user, purpose: str) -> str:
    """새 코드를 만들고 이전 코드를 지운다. 코드(평문)를 돌려준다 — 메일로만 보낸다."""
    code = f"{secrets.randbelow(1_000_000):06d}"
    with transaction.atomic():
        EmailCode.objects.filter(user=user, purpose=purpose).delete()
        EmailCode.objects.create(
            user=user,
            purpose=purpose,
            code_hash=_hash(user.pk, purpose, code),
            expires_at=timezone.now() + timedelta(seconds=CODE_TTL_SECONDS),
        )
    return code


def send(user, purpose: str, code: str) -> bool:
    """코드를 메일로 보낸다. 실패해도 예외를 던지지 않는다(응답으로 계정 존재를 알리지 않는다)."""
    minutes = CODE_TTL_SECONDS // 60
    body = f"{SUBJECTS[purpose]}: {code} ({minutes}분 동안 유효)"
    try:
        send_mail(SUBJECTS[purpose], body, settings.DEFAULT_FROM_EMAIL, [user.email])
    except Exception as error:  # SMTP 설정·연결 오류. 주소·코드는 남기지 않는다
        logger.error("코드 메일 발송 실패 purpose=%s %s", purpose, type(error).__name__)
        return False
    return True


def check(user, purpose: str, code: str, *, consume: bool) -> str:
    """코드를 확인한다. 틀리면 횟수를 올려 저장한다. `consume` 이면 맞을 때 사용 처리한다.

    `user` 가 None 이면 `MISSING` (없는 이메일과 코드 없음이 같은 답이다).
    """
    if user is None:
        return MISSING
    with transaction.atomic():
        row = (
            EmailCode.objects.select_for_update()
            .filter(user=user, purpose=purpose, used_at__isnull=True)
            .order_by("-created_at")
            .first()
        )
        if row is None:
            return MISSING
        if row.attempts >= MAX_ATTEMPTS:
            return TOO_MANY
        if row.expires_at <= timezone.now():
            return EXPIRED
        if not hmac.compare_digest(row.code_hash, _hash(user.pk, purpose, str(code))):
            EmailCode.objects.filter(pk=row.pk).update(attempts=F("attempts") + 1)
            return WRONG
        if consume:
            row.used_at = timezone.now()
            row.save(update_fields=["used_at"])
    return OK
