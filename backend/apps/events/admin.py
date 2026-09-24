"""일정 관리 화면. 어느 사용자의 일정인지, 알람이 계산됐는지 한눈에 본다."""

from django import forms
from django.contrib import admin
from django.db.models import Count

from apps.planning.models import AlarmPlan

from .models import Event, EventTag, Place


class EventForm(forms.ModelForm):
    """출발지 좌표를 짝으로만 받는다.

    `events_event_origin_pair` 제약과 같은 규칙이다. DB 에서 걸리면 admin 이
    IntegrityError 로 500 을 띄워 어느 칸이 문제인지 알 수 없다.
    """

    class Meta:
        model = Event
        fields = "__all__"

    def clean(self):
        cleaned = super().clean()
        lat, lng = cleaned.get("origin_lat"), cleaned.get("origin_lng")
        if (lat is None) != (lng is None):
            missing = "origin_lng" if lng is None else "origin_lat"
            self.add_error(
                missing,
                "출발지 좌표는 위도·경도를 함께 넣거나 함께 비워야 한다. "
                "비우면 프로필의 집을 쓴다.",
            )
        return cleaned


class AlarmPlanInline(admin.StackedInline):
    model = AlarmPlan
    can_delete = False
    extra = 0
    verbose_name_plural = "알람 계획"
    # 계획은 전부 계산 결과다. 손으로 고치면 다음 재계산이 조용히 덮는다.
    readonly_fields = ("computed_at",)


@admin.register(Event)
class EventAdmin(admin.ModelAdmin):
    form = EventForm
    list_display = ("start_at", "title", "user", "place", "tag", "plan_status", "alarm_at")
    # user 를 필터에서 뺐다. 사용자 수만큼 선택지가 늘어나 목록 옆이 못 쓰게
    # 된다. 사용자로 좁히는 것은 검색(이메일·닉네임)이 담당한다.
    #
    # 대신 계획 상태를 넣었다. "알람이 안 뜬다" 는 문의에서 가장 먼저 확인할
    # 것이 route_failed / no_place 이기 때문이다.
    list_filter = ("source", "tag", "alarm_plan__status")
    search_fields = ("title", "user__email", "user__nickname", "place__name")
    date_hierarchy = "start_at"
    autocomplete_fields = ("place", "user", "tag")
    inlines = [AlarmPlanInline]

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user", "place", "tag", "alarm_plan")

    @admin.display(description="계획 상태", ordering="alarm_plan__status")
    def plan_status(self, obj):
        plan = getattr(obj, "alarm_plan", None)
        return plan.get_status_display() if plan else "미계산"

    @admin.display(description="알람", ordering="alarm_plan__alarm_at")
    def alarm_at(self, obj):
        plan = getattr(obj, "alarm_plan", None)
        return plan.alarm_at if plan and plan.alarm_at else "—"


@admin.register(Place)
class PlaceAdmin(admin.ModelAdmin):
    list_display = ("name", "address", "lat", "lng", "kakao_place_id", "event_count")
    search_fields = ("name", "address", "kakao_place_id")

    def get_queryset(self, request):
        # 예전에는 `obj.events.count()` 를 행마다 불러서 목록 한 페이지에
        # 100번 더 조회했다. 집계로 한 번에 받는다.
        return super().get_queryset(request).annotate(_event_count=Count("events"))

    @admin.display(description="사용 일정 수", ordering="_event_count")
    def event_count(self, obj):
        return obj._event_count


@admin.register(EventTag)
class EventTagAdmin(admin.ModelAdmin):
    list_display = ("key", "label", "default_tau", "penalty_shape", "order")
    list_display_links = ("key",)
    search_fields = ("key", "label")
    ordering = ("order",)
    # τ 는 "시험을 고르면 얼마나 일찍 깨울지" 를 정하는 값이다. 시연 전에
    # 목록에서 바로 조정할 수 있어야 한다.
    list_editable = ("default_tau", "order")
