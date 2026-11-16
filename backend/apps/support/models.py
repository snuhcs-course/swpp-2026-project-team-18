# Partly AI-generated (Claude)
"""앱 안 문의하기(iter4 H2-4 · BE-H4b). 계약은 `docs/iter4/server-inquiry.md`.

도움말 › [문의하기] 에서 보낸 글 한 건이 한 행이다. 답은 `reply_email` 로 팀이 직접 보낸다.
팀은 관리 화면에서 읽고 상태를 바꾼다(접수 → 답함 → 닫음). 새 문의는 팀 메일로도 알린다
(`apps.support.notify`).

계정을 지우면 문의도 함께 지운다. 글과 답 받을 이메일은 그 사람의 개인 정보다.
"""

from __future__ import annotations

from django.conf import settings
from django.db import models
from django.db.models.functions import Now


class Inquiry(models.Model):
    class Status(models.TextChoices):
        OPEN = "open", "접수"
        ANSWERED = "answered", "답함"
        CLOSED = "closed", "닫음"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="inquiries",
        verbose_name="사용자",
    )
    # 앞뒤 공백을 뺀 10~1000자(`serializers.InquirySerializer`).
    message = models.TextField("문의 내용")
    # 로그인 이메일과 다를 수 있다. 이메일을 주지 않은 소셜 계정이 있다.
    reply_email = models.EmailField("답 받을 이메일", max_length=254)
    app_version = models.CharField(
        "앱 버전", max_length=32, blank=True, default="", db_default=""
    )
    # "samsung SM-S911N · Android 14". 기기 id 같은 개인 식별 값은 앱이 보내지 않는다.
    device = models.CharField("기기", max_length=120, blank=True, default="", db_default="")

    status = models.CharField(
        "상태",
        max_length=10,
        choices=Status.choices,
        default=Status.OPEN,
        db_default=Status.OPEN,
        db_index=True,
    )
    # 팀이 남기는 메모(무엇을 답했나 등). 사용자에게는 보이지 않는다.
    note = models.TextField("메모", blank=True, default="", db_default="")
    created_at = models.DateTimeField("받은 시각", auto_now_add=True, db_default=Now())
    # 상태를 접수 밖으로 처음 바꾼 시각. 관리 화면이 채운다.
    handled_at = models.DateTimeField("처리 시각", null=True, blank=True)

    class Meta:
        db_table = "support_inquiry"
        verbose_name = "문의"
        verbose_name_plural = "문의"
        ordering = ["-created_at", "-id"]
        indexes = [
            models.Index(fields=["user", "created_at"], name="inquiry_user_time_idx"),
        ]

    def __str__(self):
        return f"#{self.pk} {self.get_status_display()} — {self.reply_email}"

    @property
    def preview(self) -> str:
        """한 줄로 줄인 앞부분. 목록과 메일 제목에 쓴다(줄바꿈이 제목에 들어가면 안 된다)."""
        text = " ".join(self.message.split())
        return text if len(text) <= 40 else text[:39] + "…"
