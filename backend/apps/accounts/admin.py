"""계정·프로필 관리 화면.

프로필이 이 앱에서 **사용자별로 조정할 수 있는 거의 모든 것**을 들고 있다 —
집 좌표, 기본 τ, 평소 준비 시간, 시간대. 알람 시각이 이상할 때 가장 먼저
확인할 표이고, 사용자를 대신해 값을 고쳐 줄 수 있는 유일한 자리다.

집 좌표는 폼에서 검증한다. DB 제약(`accounts_profile_home_pair`)이 위도만
저장하는 것을 막지만, 그 제약에 걸리면 admin 은 IntegrityError 로 500 을
띄운다. 무엇이 틀렸는지 화면에 남지 않으므로 폼에서 먼저 잡는다.
"""

from django import forms
from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as BaseUserAdmin
from django.db.models import Count

from .models import Profile, User


class ProfileForm(forms.ModelForm):
    """집 좌표를 짝으로만 받는다.

    위도만 넣고 저장하면 DB 제약이 막되 화면에는 500 만 남는다. 어느 칸이
    문제인지 알 수 없어서 고치려던 사람이 포기한다.
    """

    class Meta:
        model = Profile
        fields = "__all__"

    def clean(self):
        cleaned = super().clean()
        lat, lng = cleaned.get("home_lat"), cleaned.get("home_lng")
        if (lat is None) != (lng is None):
            missing = "home_lng" if lng is None else "home_lat"
            self.add_error(
                missing,
                "집 좌표는 위도·경도를 함께 넣거나 함께 비워야 한다. "
                "한쪽만 있으면 출발지를 정할 수 없다.",
            )
        return cleaned


class ProfileInline(admin.StackedInline):
    """사용자 화면에서 프로필을 함께 본다. 두 화면을 오가지 않게 한다."""

    model = Profile
    form = ProfileForm
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
        "home_label",
        "prep_minutes",
        "default_tau",
        "event_count",
        "is_active",
        "is_staff",
        "date_joined",
    )
    search_fields = ("email", "nickname")
    list_filter = ("is_active", "is_staff")
    inlines = [ProfileInline]

    def get_queryset(self, request):
        # 목록의 모든 행이 프로필과 일정 수를 읽는다. 없으면 사용자 수만큼
        # 쿼리가 더 나간다.
        return (
            super()
            .get_queryset(request)
            .select_related("profile")
            .annotate(_event_count=Count("events", distinct=True))
        )

    @admin.display(description="집", ordering="profile__home_label")
    def home_label(self, obj):
        profile = getattr(obj, "profile", None)
        if profile is None:
            return "프로필 없음"
        if profile.home_lat is None:
            return "미설정"
        return profile.home_label or f"{profile.home_lat:.4f}, {profile.home_lng:.4f}"

    @admin.display(description="준비 시간", ordering="profile__onboarding_prep_min")
    def prep_minutes(self, obj):
        value = getattr(getattr(obj, "profile", None), "onboarding_prep_min", None)
        # null 은 "아직 묻지 않았다" 는 뜻이다. 0 분과 구분해야 온보딩이
        # 왜 떴는지/안 떴는지 설명할 수 있다.
        return "미응답" if value is None else f"{value}분"

    @admin.display(description="기본 τ", ordering="profile__default_tau")
    def default_tau(self, obj):
        return getattr(getattr(obj, "profile", None), "default_tau", None)

    @admin.display(description="일정 수", ordering="_event_count")
    def event_count(self, obj):
        return obj._event_count

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
    form = ProfileForm
    list_display = (
        "user",
        "home_display",
        "prep_display",
        "default_tau",
        "timezone",
        "shadow_started_at",
    )
    list_filter = ("timezone",)
    search_fields = ("user__email", "user__nickname", "home_label")
    autocomplete_fields = ("user",)
    fieldsets = (
        (None, {"fields": ("user",)}),
        (
            "집",
            {
                "fields": ("home_label", "home_lat", "home_lng"),
                "description": "알람 계산의 출발지다. 위도·경도는 함께 넣거나 함께 비운다.",
            },
        ),
        (
            "기준값",
            {
                "fields": ("default_tau", "onboarding_prep_min", "timezone"),
                "description": "준비 시간을 비우면 앱이 다음 실행에서 사용자에게 다시 묻는다.",
            },
        ),
        ("실험", {"fields": ("shadow_started_at",)}),
    )

    def get_queryset(self, request):
        return super().get_queryset(request).select_related("user")

    @admin.display(description="집", ordering="home_label")
    def home_display(self, obj):
        if obj.home_lat is None:
            return "미설정"
        label = obj.home_label or "이름 없음"
        return f"{label} ({obj.home_lat:.4f}, {obj.home_lng:.4f})"

    @admin.display(description="준비 시간", ordering="onboarding_prep_min")
    def prep_display(self, obj):
        value = obj.onboarding_prep_min
        return "미응답" if value is None else f"{value}분"
