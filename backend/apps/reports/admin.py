# Partly AI-generated (Claude)
"""주간 요약 관리 화면. **읽기 전용이다.**

요약은 LLM 이 집계값으로 쓴 결과다. 손으로 고치면 `facts_hash` 는 그대로라
다음 요청이 고친 문장을 LLM 문장으로 내보낸다. 고칠 것은 문장이 아니라 입력이다.
"""

from django.contrib import admin

from .models import WeeklySummary


@admin.register(WeeklySummary)
class WeeklySummaryAdmin(admin.ModelAdmin):
    list_display = ("week_start", "user", "model", "updated_at")
    search_fields = ("user__email",)
    date_hierarchy = "week_start"
    ordering = ("-week_start",)
    readonly_fields = (
        "user",
        "week_start",
        "facts_hash",
        "facts",
        "text",
        "model",
        "created_at",
        "updated_at",
    )

    def get_queryset(self, request):
        # 목록의 모든 행이 user 를 읽는다. 없으면 행마다 한 번씩 더 조회한다.
        return super().get_queryset(request).select_related("user")

    def has_add_permission(self, request):
        # 요약은 요청 때 LLM 이 쓴 것만 저장한다. 손으로 만든 행은 어느 집계값에서
        # 나온 문장인지 알 수 없다.
        return False
