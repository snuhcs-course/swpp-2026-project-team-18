"""일정 관리 화면. 어느 사용자의 일정인지, 알람이 계산됐는지 한눈에 본다."""

from django.contrib import admin

from apps.planning.models import AlarmPlan

from .models import Event, EventTag, Place


class AlarmPlanInline(admin.StackedInline):
    model = AlarmPlan
    can_delete = False
    extra = 0
    verbose_name_plural = "알람 계획"
    readonly_fields = ("computed_at",)


@admin.register(Event)
class EventAdmin(admin.ModelAdmin):
    list_display = ("start_at", "title", "user", "place", "tag", "plan_status", "alarm_at")
    list_filter = ("source", "tag", "user")
    search_fields = ("title", "user__email", "place__name")
    date_hierarchy = "start_at"
    autocomplete_fields = ("place",)
    inlines = [AlarmPlanInline]

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user", "place", "tag", "alarm_plan")

    @admin.display(description="계획 상태")
    def plan_status(self, obj):
        plan = getattr(obj, "alarm_plan", None)
        return plan.get_status_display() if plan else "미계산"

    @admin.display(description="알람")
    def alarm_at(self, obj):
        plan = getattr(obj, "alarm_plan", None)
        return plan.alarm_at if plan and plan.alarm_at else "—"


@admin.register(Place)
class PlaceAdmin(admin.ModelAdmin):
    list_display = ("name", "address", "lat", "lng", "kakao_place_id", "event_count")
    search_fields = ("name", "address", "kakao_place_id")

    @admin.display(description="사용 일정 수")
    def event_count(self, obj):
        return obj.events.count()


@admin.register(EventTag)
class EventTagAdmin(admin.ModelAdmin):
    list_display = ("order", "key", "label", "default_tau", "penalty_shape")
    ordering = ("order",)
