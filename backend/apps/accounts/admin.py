"""관리자 화면. 데모 중 계정 상태를 눈으로 확인할 수 있게 최소한만 등록한다."""

from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as BaseUserAdmin

from .models import Profile, User


class ProfileInline(admin.StackedInline):
    """사용자 화면에서 프로필을 함께 본다. 두 화면을 오가지 않게 한다."""

    model = Profile
    can_delete = False
    verbose_name_plural = "프로필"
    extra = 0


@admin.register(User)
class UserAdmin(BaseUserAdmin):
    # 기본 UserAdmin 은 username 기준이므로 email 기준으로 다시 정의한다.
    ordering = ("-date_joined",)
    list_display = (
        "email",
        "nickname",
        "has_profile",
        "default_tau",
        "is_active",
        "is_staff",
        "date_joined",
    )
    search_fields = ("email", "nickname")
    list_filter = ("is_active", "is_staff")
    inlines = [ProfileInline]

    @admin.display(boolean=True, description="프로필")
    def has_profile(self, obj):
        return hasattr(obj, "profile")

    @admin.display(description="기본 τ")
    def default_tau(self, obj):
        return getattr(getattr(obj, "profile", None), "default_tau", None)

    fieldsets = (
        (None, {"fields": ("email", "password")}),
        ("개인 정보", {"fields": ("nickname",)}),
        ("권한", {"fields": ("is_active", "is_staff", "is_superuser", "groups", "user_permissions")}),
        ("기록", {"fields": ("last_login", "date_joined")}),
    )
    add_fieldsets = (
        (
            None,
            {
                "classes": ("wide",),
                "fields": ("email", "nickname", "password1", "password2"),
            },
        ),
    )


@admin.register(Profile)
class ProfileAdmin(admin.ModelAdmin):
    list_display = ("user", "default_tau", "home_label", "timezone")
    search_fields = ("user__email", "user__nickname")
