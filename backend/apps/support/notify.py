# Partly AI-generated (Claude)
"""새 문의를 팀 메일로 알린다(BE-H4b).

받는 주소는 `SUPPORT_INBOX_EMAILS`(쉼표로 여러 개)다. 비어 있으면 보내지 않는다 — 문의는
이미 저장됐고 관리 화면에서 볼 수 있다. 메일은 비밀번호 재설정 코드와 같은 설정
(`EMAIL_BACKEND` · `DEFAULT_FROM_EMAIL`, `apps/accounts/codes.py`)으로 나간다.

**실패해도 예외를 던지지 않는다.** 문의는 저장이 먼저고, 메일이 안 나갔다고 사용자에게
실패를 보이면 같은 글을 여러 번 보내게 된다. 로그에는 문의 번호와 오류 종류만 남긴다(글 ·
주소는 남기지 않는다).

답장 주소(`Reply-To`)를 사용자의 `reply_email` 로 둔다. 팀은 메일에서 [답장] 만 누르면 된다.
"""

from __future__ import annotations

import logging

from django.conf import settings
from django.core.mail import EmailMessage
from django.utils import timezone

from .models import Inquiry

logger = logging.getLogger(__name__)

SENT = "sent"
SKIPPED = "skipped"
FAILED = "failed"


def subject(inquiry: Inquiry) -> str:
    return f"[JustInTime 문의 #{inquiry.pk}] {inquiry.preview}"


def body(inquiry: Inquiry) -> str:
    user = inquiry.user
    received = timezone.localtime(inquiry.created_at).strftime("%Y-%m-%d %H:%M")
    return "\n".join(
        [
            f"문의 #{inquiry.pk} ({received} KST)",
            f"사용자: #{user.pk} {user.nickname} <{user.email}>",
            f"답 받을 이메일: {inquiry.reply_email}",
            f"앱 버전: {inquiry.app_version or '-'}",
            f"기기: {inquiry.device or '-'}",
            "",
            inquiry.message,
            "",
            "---",
            "이 메일에 답장하면 답 받을 이메일로 갑니다.",
            f"관리 화면: /admin/support/inquiry/{inquiry.pk}/change/",
        ]
    )


def notify_team(inquiry: Inquiry) -> str:
    """팀에 알린다. `SENT` · `SKIPPED`(받을 주소 없음) · `FAILED` 중 하나를 돌려준다."""
    recipients = list(getattr(settings, "SUPPORT_INBOX_EMAILS", None) or [])
    if not recipients:
        logger.info("문의 #%s 저장. SUPPORT_INBOX_EMAILS 가 비어 메일은 보내지 않는다", inquiry.pk)
        return SKIPPED
    try:
        EmailMessage(
            subject=subject(inquiry),
            body=body(inquiry),
            from_email=settings.DEFAULT_FROM_EMAIL,
            to=recipients,
            reply_to=[inquiry.reply_email],
        ).send()
    except Exception as error:  # noqa: BLE001 - 메일 설정 · 연결 오류. 응답은 그대로 201 이다
        logger.error("문의 #%s 알림 메일 실패 %s", inquiry.pk, type(error).__name__)
        return FAILED
    logger.info("문의 #%s 팀에 알림 메일을 보냈다(%d곳)", inquiry.pk, len(recipients))
    return SENT
