# Partly AI-generated (Claude)
from django.apps import AppConfig


class CommonConfig(AppConfig):
    """공통 오류 형식·헬스 체크·API 요청 기록(db-schema.md A4).

    모델이 생기면서 `INSTALLED_APPS` 에 들어갔다. 그 전에는 모듈만 있는 패키지였다.
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.common"
    verbose_name = "8. 운영"
