"""이동 관측 관리 화면.

관측이 실제로 쌓이는지, 계획과 얼마나 벌어지는지를 눈으로 본다. 앱을
실기기에 걸어 두고 이 목록이 늘어나는지가 P1 완료 판정 기준이다.

**손으로 고칠 수 없게 둔다.** 관측은 이 프로젝트의 유일한 학습 재료다. 값을
하나 고치면 그게 분포에 들어가고 알람 시각으로 나온다. 잘못 올라온 관측은
고치는 것이 아니라 지우는 것이 맞다.
"""

from django.contrib import admin

from .models import DWELL_CONFIRM_SECONDS, TripObservation


class DwellFilter(admin.SimpleListFilter):
    """체류 기준을 채운 도착만 골라 본다.

    `dwell_confirmed` 는 파생 프로퍼티라 기본 필터로 쓸 수 없다. 그런데 이
    구분이 학습에서 가장 중요하다 — 2분을 머문 도착과 추적 마감에 밀려
    확정된 도착을 섞으면 "지나가던 차" 가 도착으로 들어간다.
    """

    title = "체류 확인"
    parameter_name = "dwell"

    def lookups(self, request, model_admin):
        return (
            ("confirmed", f"기준 충족 ({DWELL_CONFIRM_SECONDS}초 이상)"),
            ("short", "기준 미달"),
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
        mark = "✓" if obj.dwell_confirmed else "미달"
        return f"{obj.dwell_seconds}초 {mark}"
