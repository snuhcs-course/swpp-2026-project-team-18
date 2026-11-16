# Partly AI-generated (Claude)
"""새 문의를 팀 메일로 알린다(BE-H4b).

받는 주소는 `SUPPORT_INBOX_EMAILS`(쉼표로 여러 개)다. 비어 있으면 보내지 않는다 — 문의는
이미 저장됐고 관리 화면에서 볼 수 있다. 메일은 비밀번호 재설정 코드와 같은 설정
(`EMAIL_BACKEND` · `DEFAULT_FROM_EMAIL`, `apps/accounts/codes.py`)으로 나간다.

**요청을 기다리게 하지 않는다.** 뷰는 `schedule(inquiry)` 만 부른다. 메일은 요청 스레드에서
다 만들어 두고(DB 를 읽는 일은 여기서 끝난다), 저장이 커밋된 뒤(`transaction.on_commit`)
데몬 스레드에서 보낸다. SMTP 가 느리거나 막혀도(`EMAIL_TIMEOUT` 10초) 201 은 바로 나간다.
스레드는 DB 를 건드리지 않는다. 테스트는 `SUPPORT_NOTIFY_IN_BACKGROUND = False` 로 커밋
직후 같은 스레드에서 보낸다(결과가 정해진다).

**실패해도 예외를 던지지 않는다.** 문의는 저장이 먼저고, 메일이 안 나갔다고 사용자에게
실패를 보이면 같은 글을 여러 번 보내게 된다. 로그에는 문의 번호와 오류 종류만 남긴다(글 ·
주소 · 닉네임은 남기지 않는다).

**콘솔 · dummy 백엔드에서는 보내지 않는다.** 콘솔 백엔드는 메일 전체(글 · 이메일 · 닉네임)를
stdout 으로, 곧 운영 로그로 찍고 아무 데도 배달하지 않는다. 이때는 문의 번호만 한 줄 남긴다.

답장 주소(`Reply-To`)를 사용자의 `reply_email` 로 둔다. 팀은 메일에서 [답장] 만 누르면 된다.
"""

from __future__ import annotations

import logging
import threading

from django.conf import settings
from django.core.mail import EmailMessage
from django.db import transaction
from django.utils import timezone

from .models import Inquiry

logger = logging.getLogger(__name__)

SENT = "sent"
SKIPPED = "skipped"
FAILED = "failed"

# 아무 데도 배달하지 않는 백엔드. 콘솔은 메일 전체를 로그로 찍는다.
UNDELIVERED_BACKENDS = frozenset(
    {
        "django.core.mail.backends.console.EmailBackend",
        "django.core.mail.backends.dummy.EmailBackend",
    }
)


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


def _prepare(inquiry: Inquiry) -> EmailMessage | None:
    """보낼 메일을 만든다. 보내지 않을 때는 이유를 로그에 남기고 None."""
    recipients = list(getattr(settings, "SUPPORT_INBOX_EMAILS", None) or [])
    if not recipients:
        logger.info("문의 #%s 저장. SUPPORT_INBOX_EMAILS 가 비어 메일은 보내지 않는다", inquiry.pk)
        return None
    if settings.EMAIL_BACKEND in UNDELIVERED_BACKENDS:
        logger.warning(
            "문의 #%s 저장. EMAIL_BACKEND 가 배달하지 않는 백엔드라 메일은 보내지 않는다", inquiry.pk
        )
        return None
    return EmailMessage(
        subject=subject(inquiry),
        body=body(inquiry),
        from_email=settings.DEFAULT_FROM_EMAIL,
        to=recipients,
        reply_to=[inquiry.reply_email],
    )


def _send(message: EmailMessage, inquiry_id: int) -> str:
    """만들어 둔 메일을 보낸다. DB 를 쓰지 않는다(백그라운드 스레드에서 돈다)."""
    try:
        message.send()
    except Exception as error:  # noqa: BLE001 - 메일 설정 · 연결 오류. 응답은 그대로 201 이다
        logger.error("문의 #%s 알림 메일 실패 %s", inquiry_id, type(error).__name__)
        return FAILED
    logger.info("문의 #%s 팀에 알림 메일을 보냈다(%d곳)", inquiry_id, len(message.to))
    return SENT


def notify_team(inquiry: Inquiry) -> str:
    """지금 이 스레드에서 팀에 알린다. `SENT` · `SKIPPED` · `FAILED` 중 하나를 돌려준다."""
    message = _prepare(inquiry)
    if message is None:
        return SKIPPED
    return _send(message, inquiry.pk)


def schedule(inquiry: Inquiry) -> None:
    """저장이 커밋된 뒤 팀에 알린다. 요청은 메일을 기다리지 않는다(뷰가 부른다)."""
    message = _prepare(inquiry)
    if message is None:
        return
    inquiry_id = inquiry.pk

    def dispatch():
        if getattr(settings, "SUPPORT_NOTIFY_IN_BACKGROUND", True):
            threading.Thread(
                target=_send,
                args=(message, inquiry_id),
                name=f"support-notify-{inquiry_id}",
                daemon=True,
            ).start()
        else:
            _send(message, inquiry_id)

    transaction.on_commit(dispatch)
