from django.apps import AppConfig


class ObservationsConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.observations"
    # Partly AI-generated (Claude) — admin 섹션 번호(db-schema.md §6). 데이터가 생기는 순서다.
    verbose_name = "5. 실측 기록"
