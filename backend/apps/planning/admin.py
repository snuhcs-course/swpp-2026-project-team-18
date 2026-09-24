"""알람 계획 관리 화면.

**왜 일정 인라인만으로는 부족한가.** 계획은 `EventAdmin` 안에 인라인으로 들어
있었다. 그러면 계획을 기준으로 묻는 질문에 답할 수 없다 — "경로를 못 찾아
계산이 실패한 계획이 몇 건인가", "확률이 낮게 나온 계획은 어느 것인가" 는
계획 목록을 훑어야 답이 나온다. 일정 하나를 열어서 그 안의 계획을 보는 것과
계획 전체를 한 표로 보는 것은 다른 일이다.

여기서 계획을 **손으로 고칠 수 있게** 두지 않는다. 계획은 전부 계산 결과이고,
사람이 고쳐 놓으면 다음 재계산이 조용히 덮는다. 고쳐야 할 것은 계획이 아니라
입력(프로필 준비 시간, 일정의 τ, 경로)이다.
"""

from django.contrib import admin

from .models import AlarmPlan


@admin.register(AlarmPlan)
class AlarmPlanAdmin(admin.ModelAdmin):
    list_display = (
        "computed_at",
        "user",
        "event_title",
        "status",
        "alarm_at",
        "depart_by",
        "arrive_at",
        "minutes_breakdown",
        "probability",
        "travel_summary",
    )
    # status 는 "왜 알람이 없나" 를 좁히는 첫 질문이다. route_failed 만 골라
    # 보는 것이 이 화면을 만든 이유다.
    list_filter = ("status", "travel_mode", "travel_source", "prep_source")
    search_fields = ("user__email", "user__nickname", "event__title", "route_summary")
    date_hierarchy = "computed_at"
    autocomplete_fields = ("event",)
    ordering = ("-computed_at",)

    # 계산 결과는 읽기 전용이다. 손으로 고쳐도 다음 재계산이 덮는다.
    readonly_fields = (
        "user",
        "event",
        "computed_at",
        "alarm_at",
        "depart_by",
        "arrive_at",
        "prep_minutes",
        "travel_minutes",
        "buffer_minutes",
        "total_quantile_minutes",
        "tau_used",
        "on_time_probability",
        "confidence_basis",
        "prep_source",
        "prep_breakdown",
        "travel_mode",
        "travel_source",
        "route_summary",
        "route_key",
        "route_detail",
    )

    def get_queryset(self, request):
        # user 와 event 를 목록의 모든 행에서 읽는다. 없으면 행마다 두 번씩
        # 더 조회한다.
        return super().get_queryset(request).select_related("user", "event")

    def has_add_permission(self, request):
        # 계획은 계산으로만 생긴다. 빈 계획을 손으로 만들면 어느 입력에서
        # 나온 값인지 알 수 없는 행이 학습·리포트에 섞인다.
        return False

    @admin.display(description="일정", ordering="event__title")
    def event_title(self, obj):
        return obj.event.title if obj.event_id else "—"

    @admin.display(description="준비+이동+버퍼")
    def minutes_breakdown(self, obj):
        parts = [obj.prep_minutes, obj.travel_minutes, obj.buffer_minutes]
        if all(p is None for p in parts):
            return "—"
        return " + ".join("?" if p is None else str(p) for p in parts)

    @admin.display(description="정시 확률", ordering="on_time_probability")
    def probability(self, obj):
        if obj.on_time_probability is None:
            return "—"
        return f"{obj.on_time_probability * 100:.0f}%"

    @admin.display(description="경로")
    def travel_summary(self, obj):
        return obj.route_summary or obj.get_travel_mode_display() or "—"
