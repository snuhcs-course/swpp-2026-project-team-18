# Partly AI-generated (Claude)
"""문의 관리 화면. 팀이 읽고 상태 · 메모만 바꾼다.

추가는 막는다 — 문의는 앱에서만 온다. 글 · 이메일 · 기기는 사용자가 보낸 그대로 둔다(읽기
전용). 상태를 접수 밖으로 처음 바꾸면 `handled_at` 을 채우고, 다시 접수로 돌리면 비운다.
삭제는 열어 둔다 — 사용자를 지울 때 연결된 문의 admin 이 삭제를 거부하면 사용자 삭제까지 막힌다.
"""

from __future__ import annotations

from django.contrib import admin
from django.utils import timezone

from .models import Inquiry


def _mark(queryset, status: str) -> int:
    """고른 문의의 상태를 바꾼다. 바꾼 행 수를 돌려준다. 처리 시각은 처음 처리할 때만 채운다."""
    if status == Inquiry.Status.OPEN:
        return queryset.update(status=status, handled_at=None)
    queryset.filter(handled_at__isnull=True).update(handled_at=timezone.now())
    return queryset.update(status=status)


@admin.register(Inquiry)
class InquiryAdmin(admin.ModelAdmin):
    list_display = (
        "id", "created_at", "status", "user_email", "reply_email", "preview", "app_version",
    )
    list_display_links = ("id", "preview")
    list_filter = ("status", "created_at", "app_version")
    search_fields = ("message", "reply_email", "user__email", "user__nickname")
    date_hierarchy = "created_at"
    ordering = ("-created_at", "-id")
    readonly_fields = (
        "user", "reply_email", "message", "app_version", "device", "created_at", "handled_at",
    )
    fields = (
        "status", "note", "user", "reply_email", "message", "app_version", "device",
        "created_at", "handled_at",
    )
    actions = ("mark_answered", "mark_closed", "mark_open")

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user")

    def has_add_permission(self, request):
        return False

    def save_model(self, request, obj, form, change):
        if obj.status == Inquiry.Status.OPEN:
            obj.handled_at = None
        elif obj.handled_at is None:
            obj.handled_at = timezone.now()
        super().save_model(request, obj, form, change)

    @admin.display(description="사용자", ordering="user__email")
    def user_email(self, obj):
        return obj.user.email

    @admin.display(description="내용")
    def preview(self, obj):
        return obj.preview

    @admin.action(description="답함으로 바꾸기")
    def mark_answered(self, request, queryset):
        self.message_user(request, f"{_mark(queryset, Inquiry.Status.ANSWERED)}건을 답함으로 바꿨다.")

    @admin.action(description="닫음으로 바꾸기")
    def mark_closed(self, request, queryset):
        self.message_user(request, f"{_mark(queryset, Inquiry.Status.CLOSED)}건을 닫음으로 바꿨다.")

    @admin.action(description="접수로 되돌리기")
    def mark_open(self, request, queryset):
        self.message_user(request, f"{_mark(queryset, Inquiry.Status.OPEN)}건을 접수로 되돌렸다.")
