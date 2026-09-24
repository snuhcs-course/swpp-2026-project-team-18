"""경로 보정 관리 화면.

이 표가 "왜 알람이 그 시각인가" 의 절반을 설명한다. 카카오가 준 23분에
`factor` 를 곱하고 `sd_minutes` 로 안전 버퍼를 잡기 때문이다. 화면에 등록되어
있지 않으면 알람이 이상할 때 확인할 방법이 `manage.py shell` 뿐이다.

**손으로 고칠 수 있게 둔다.** 다른 계산 결과와 달리 이건 관측이 쌓이기 전까지
비어 있고, 시연 전에 값을 넣어 동작을 확인할 필요가 있다. 대신 표본 수를
함께 보여 줘서 손으로 넣은 행(n=0)이 학습된 행과 섞이지 않게 한다.
"""

from django.contrib import admin

from .models import GLOBAL_SIGNATURE, RouteCorrection


class ScopeFilter(admin.SimpleListFilter):
    """전역 보정과 경로 전용 보정을 나눠 본다.

    빈 문자열을 필터 값으로 쓰는 모델이라 기본 필터로는 갈라지지 않는다.
    전역 행은 한 줄뿐인데 경로 전용이 수백 줄로 늘면 목록에서 찾을 수 없다.
    """

    title = "적용 범위"
    parameter_name = "scope"

    def lookups(self, request, model_admin):
        return (("global", "전역"), ("route", "경로 전용"))

    def queryset(self, request, queryset):
        if self.value() == "global":
            return queryset.filter(route_signature=GLOBAL_SIGNATURE)
        if self.value() == "route":
            return queryset.exclude(route_signature=GLOBAL_SIGNATURE)
        return queryset


@admin.register(RouteCorrection)
class RouteCorrectionAdmin(admin.ModelAdmin):
    list_display = (
        "scope_display",
        "mode",
        "hour_display",
        "weekday_type",
        "factor",
        "sd_minutes",
        "sample_count",
        "updated_at",
    )
    list_filter = (ScopeFilter, "mode", "weekday_type")
    search_fields = ("route_signature",)
    ordering = ("route_signature", "mode", "hour_bucket")
    list_editable = ("factor", "sd_minutes")

    @admin.display(description="적용 범위", ordering="route_signature")
    def scope_display(self, obj):
        return obj.route_signature or "(전역)"

    @admin.display(description="시간대", ordering="hour_bucket")
    def hour_display(self, obj):
        return "전체" if obj.hour_bucket < 0 else f"{obj.hour_bucket}시"
