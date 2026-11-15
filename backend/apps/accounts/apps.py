from django.apps import AppConfig


class AccountsConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.accounts"
    # Partly AI-generated (Claude) — admin 섹션 번호(db-schema.md §6). 데이터가 생기는 순서다.
    verbose_name = "1. 계정"

    def ready(self):
        # 사용자 생성 시 프로필을 함께 만드는 시그널을 등록한다.
        # import 자체가 등록이므로 noqa 로 미사용 경고만 끈다.
        from . import signals  # noqa: F401

        # Partly AI-generated (Claude)
        # `ATOMIC_REQUESTS` 가 켜지면 로그인 잠금이 세지 않는다 — 서버가 뜨지 않게 막는다(A3-4).
        from . import checks  # noqa: F401

        # 관리 화면 로그인에도 같은 잠금(A3-5). `admin.site` 는 하나뿐이라 여기서 폼만 바꾼다.
        from django.contrib import admin

        from .admin_login import LockedAdminAuthenticationForm

        admin.site.login_form = LockedAdminAuthenticationForm
