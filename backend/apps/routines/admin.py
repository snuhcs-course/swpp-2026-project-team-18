"""admin 등록. 관측이 실제로 쌓이는지 눈으로 확인하는 용도다.

학습 파이프라인은 관측 없이는 아무것도 못 한다. 그런데 앱이 업로드에
실패해도 화면에는 아무 표시가 없다 — 오프라인 큐가 조용히 쌓아 두기
때문이다. admin 에서 행이 늘어나는지 보는 것이 가장 빠른 확인이다.
"""

from django.contrib import admin

from .models import BlockObservation, EventBlockSelection, RoutineBlock


@admin.register(RoutineBlock)
class RoutineBlockAdmin(admin.ModelAdmin):
    list_display = (
        "name",
        "user",
        "default_min_minutes",
        "default_max_minutes",
        "parallelizable",
        "included_by_default",
        "precondition",
        "order",
    )
    list_filter = ("parallelizable", "included_by_default", "drop_cost")
    search_fields = ("name", "user__email")
    # 사용자와 선행 블록은 행이 많아질 수 있으므로 드롭다운을 쓰지 않는다.
    raw_id_fields = ("user", "precondition")
    ordering = ("user", "order")


@admin.register(EventBlockSelection)
class EventBlockSelectionAdmin(admin.ModelAdmin):
    list_display = ("event", "block", "checked", "updated_at")
    list_filter = ("checked",)
    raw_id_fields = ("event", "block")
    search_fields = ("event__title", "block__name")


@admin.register(BlockObservation)
class BlockObservationAdmin(admin.ModelAdmin):
    list_display = (
        "observed_on",
        "block",
        "user",
        "duration_minutes",
        "slack_minutes",
        "was_parallel",
        "server_received_at",
    )
    list_filter = ("was_parallel", "observed_on")
    search_fields = ("block__name", "user__email", "client_uuid")
    raw_id_fields = ("user", "block", "event")
    date_hierarchy = "observed_on"
    ordering = ("-observed_on",)
