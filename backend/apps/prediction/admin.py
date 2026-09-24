"""admin 등록.

학습이 언제 돌았고 무엇을 배웠는지 눈으로 봐야 한다. 아티팩트는 알람 계산에
직접 들어가므로, 값이 이상해지면 알람이 조용히 틀어진다. 이력이 남으니
어느 버전부터 이상해졌는지 추적할 수 있다.
"""

from django.contrib import admin

from .models import ModelArtifact


@admin.register(ModelArtifact)
class ModelArtifactAdmin(admin.ModelAdmin):
    list_display = ("version", "scope", "sample_count", "is_active", "created_at")
    list_filter = ("is_active",)
    search_fields = ("version", "user__email")
    raw_id_fields = ("user",)
    readonly_fields = ("created_at", "payload")
    ordering = ("-created_at",)

    def get_queryset(self, request):
        # scope 가 행마다 user.email 을 읽는다. 없으면 행 수만큼 더 조회한다.
        return super().get_queryset(request).select_related("user")

    @admin.display(description="범위", ordering="user__email")
    def scope(self, obj):
        return "전역" if obj.user_id is None else obj.user.email
