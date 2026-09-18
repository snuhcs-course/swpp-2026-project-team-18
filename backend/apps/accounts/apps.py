from django.apps import AppConfig


class AccountsConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.accounts"

    def ready(self):
        # 사용자 생성 시 프로필을 함께 만드는 시그널을 등록한다.
        # import 자체가 등록이므로 noqa 로 미사용 경고만 끈다.
        from . import signals  # noqa: F401
