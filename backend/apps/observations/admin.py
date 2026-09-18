"""이동 관측 관리 화면.

관측이 실제로 쌓이는지, 계획과 얼마나 벌어지는지를 눈으로 본다. 앱을
실기기에 걸어 두고 이 목록이 늘어나는지가 P1 완료 판정 기준이다.
"""

from django.contrib import admin

from .models import TripObservation


@admin.register(TripObservation)
class TripObservationAdmin(admin.ModelAdmin):
    list_display = (
        "observed_at",
        "user",
        "event",
        "kind",
        "detector",
        "delay_display",
        "distance_m",
        "accuracy_m",
    )
    list_filter = ("kind", "detector", "user")
    search_fields = ("user__email", "event__title", "client_uuid")
    date_hierarchy = "observed_at"
    autocomplete_fields = ("event",)
    readonly_fields = ("created_at",)

    def get_queryset(self, request):
        return (
            super()
            .get_queryset(request)
            .select_related("user", "event", "event__alarm_plan")
        )

    @admin.display(description="계획 대비")
    def delay_display(self, obj):
        delay = obj.delay_minutes
        if delay is None:
            return "—"
        if delay == 0:
            return "정시"
        return f"+{delay}분" if delay > 0 else f"{delay}분"
