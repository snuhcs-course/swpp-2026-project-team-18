# Partly AI-generated (Claude)
"""API 요청 기록 관리 화면. **읽기 전용이다.**

미들웨어가 남긴 기록이다. 손으로 만들거나 고친 행은 실제로 오간 요청이 아니다. 오래된
행은 `purge_logs` 가 지운다.
"""

from django.contrib import admin

from .models import ApiRequestLog


@admin.register(ApiRequestLog)
class ApiRequestLogAdmin(admin.ModelAdmin):
    list_display = (
        "created_at",
        "user_email",
        "method",
        "route",
        "object_id",
        "status_code",
        "duration_ms",
        "app_version",
        "error_code",
    )
    list_filter = ("method", "status_code")
    search_fields = ("user__email", "route", "error_code", "app_version")
    date_hierarchy = "created_at"
    ordering = ("-created_at", "-id")

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user")

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False

    @admin.display(description="사용자", ordering="user__email")
    def user_email(self, obj):
        return obj.user.email if obj.user_id else "—"
