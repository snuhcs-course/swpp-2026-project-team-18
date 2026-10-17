from django.apps import AppConfig


class PlanningConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.planning"
    # Partly AI-generated (Claude) — admin 섹션 번호(db-schema.md §6). 데이터가 생기는 순서다.
    verbose_name = "4. 알람 계획"
