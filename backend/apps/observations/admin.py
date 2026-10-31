"""이동 관측 관리 화면.

관측이 실제로 쌓이는지, 계획과 얼마나 벌어지는지를 눈으로 본다. 앱을
실기기에 걸어 두고 이 목록이 늘어나는지가 P1 완료 판정 기준이다.

**손으로 고칠 수 없게 둔다.** 관측은 이 프로젝트의 유일한 학습 재료다. 값을
하나 고치면 그게 분포에 들어가고 알람 시각으로 나온다. 잘못 올라온 관측은
고치는 것이 아니라 지우는 것이 맞다.
"""

from django.contrib import admin

from .models import DWELL_CONFIRM_SECONDS, AlarmLog, TripObservation, TripReroute


class DwellFilter(admin.SimpleListFilter):
    """과거 체류 기록 조회용. 즉시 도착 기록도 정상 관측이다."""

    title = "체류 기록"
    parameter_name = "dwell"

    def lookups(self, request, model_admin):
        return (
            ("confirmed", f"{DWELL_CONFIRM_SECONDS}초 이상"),
            ("short", f"{DWELL_CONFIRM_SECONDS}초 미만 (즉시 도착 포함)"),
            ("none", "값 없음"),
        )

    def queryset(self, request, queryset):
        value = self.value()
        if value == "confirmed":
            return queryset.filter(dwell_seconds__gte=DWELL_CONFIRM_SECONDS)
        if value == "short":
            return queryset.filter(dwell_seconds__lt=DWELL_CONFIRM_SECONDS)
        if value == "none":
            return queryset.filter(dwell_seconds__isnull=True)
        return queryset


@admin.register(TripObservation)
class TripObservationAdmin(admin.ModelAdmin):
    list_display = (
        "observed_at",
        "user",
        "event",
        "kind",
        "detector",
        "delay_display",
        "dwell_display",
        "distance_m",
        "accuracy_m",
    )
    # user 를 필터에서 뺐다 — 사용자 수만큼 선택지가 늘어난다. 검색이 맡는다.
    list_filter = ("kind", "detector", DwellFilter)
    search_fields = ("user__email", "user__nickname", "event__title", "client_uuid")
    date_hierarchy = "observed_at"
    autocomplete_fields = ("event", "user")
    readonly_fields = ("created_at",)

    def get_queryset(self, request):
        return (
            super()
            .get_queryset(request)
            .select_related("user", "event", "event__alarm_plan")
        )

    def has_add_permission(self, request):
        # 관측은 앱이 GPS 로 판별해 올린다. 손으로 만든 행은 어느 아침의
        # 무엇인지 알 수 없으면서 학습에는 그대로 들어간다.
        return False

    @admin.display(description="계획 대비", ordering="observed_at")
    def delay_display(self, obj):
        delay = obj.delay_minutes
        if delay is None:
            return "—"
        if delay == 0:
            return "정시"
        return f"+{delay}분" if delay > 0 else f"{delay}분"

    @admin.display(description="체류", ordering="dwell_seconds")
    def dwell_display(self, obj):
        if obj.dwell_seconds is None:
            return "—"
        return f"{obj.dwell_seconds}초"


# Partly AI-generated (Claude)
@admin.register(AlarmLog)
class AlarmLogAdmin(admin.ModelAdmin):
    """알람 기록. **읽기 전용이다.**

    울림·끄기·미루기는 기기가 겪은 사실이다. 손으로 만들거나 고친 행은 어느 아침의
    무엇인지 알 수 없으면서 학습 재료로 남는다. 잘못 올라온 기록은 지운다.
    """

    list_display = (
        "occurred_at",
        "user_email",
        "event_title",
        "kind",
        "alarm_at",
        "snooze_minutes",
        "source",
    )
    list_filter = ("kind", "source")
    search_fields = ("user__email", "user__nickname", "event__title", "client_uuid")
    date_hierarchy = "occurred_at"
    ordering = ("-occurred_at", "-id")

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user", "event")

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False

    @admin.display(description="사용자", ordering="user__email")
    def user_email(self, obj):
        return obj.user.email

    @admin.display(description="일정", ordering="event__title")
    def event_title(self, obj):
        return obj.event.title if obj.event_id else "—"


@admin.register(TripReroute)
class TripRerouteAdmin(admin.ModelAdmin):
    """이동 중 경로 변경. **읽기 전용이다.** 앱의 [이 경로로 바꾸기]만 남긴다.

    이 행이 있는 일정은 이동 학습에서 빠진다(`prediction.learning`). 손으로 더하면 멀쩡한
    이동이 학습에서 사라진다. (Partly AI-generated (Claude))
    """

    list_display = ("rerouted_at", "user_email", "event_title", "from_route_key", "to_route_key", "reason")
    list_filter = ("reason",)
    search_fields = ("user__email", "user__nickname", "event__title", "to_route_key")
    date_hierarchy = "rerouted_at"
    ordering = ("-rerouted_at", "-id")

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user", "event")

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False

    @admin.display(description="사용자", ordering="user__email")
    def user_email(self, obj):
        return obj.user.email

    @admin.display(description="일정", ordering="event__title")
    def event_title(self, obj):
        return obj.event.title
