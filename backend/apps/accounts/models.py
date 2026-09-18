"""계정 모델.

back-spec.md 4.1 은 `User`를 "Django 기본"으로 적었으나 **커스텀 User 로 바꿨다.**

이유 — 로그인 식별자가 이메일이다. `django.contrib.auth.models.User` 의 `email`
필드는 unique 가 아니고, 내장 모델의 필드 제약을 나중에 바꾸는 깔끔한 방법이 없다.
또 `username` 이 필수라 이메일을 그대로 복사해 넣는 죽은 컬럼이 생긴다.
지금은 P0 이고 `auth_user` 행이 0개였으므로 전환 비용이 DB 재생성 한 번이다.
나중에 바꾸면 데이터 이관이 필요하다.
"""

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.core.validators import MaxValueValidator, MinValueValidator
from django.db import models
from django.db.models import Q
from django.utils import timezone


class UserManager(BaseUserManager):
    """이메일을 식별자로 쓰는 매니저."""

    use_in_migrations = True

    def _create_user(self, email, password, **extra):
        if not email:
            raise ValueError("이메일은 필수다.")
        email = self.normalize_email(email).lower()
        user = self.model(email=email, **extra)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_user(self, email, password=None, **extra):
        extra.setdefault("is_staff", False)
        extra.setdefault("is_superuser", False)
        return self._create_user(email, password, **extra)

    def create_superuser(self, email, password=None, **extra):
        extra.setdefault("is_staff", True)
        extra.setdefault("is_superuser", True)
        if extra.get("is_staff") is not True:
            raise ValueError("슈퍼유저는 is_staff=True 여야 한다.")
        if extra.get("is_superuser") is not True:
            raise ValueError("슈퍼유저는 is_superuser=True 여야 한다.")
        extra.setdefault("nickname", "admin")
        return self._create_user(email, password, **extra)


class User(AbstractBaseUser, PermissionsMixin):
    """이메일 로그인 사용자.

    `AbstractUser` 가 아니라 `AbstractBaseUser` 를 상속한다. `username`,
    `first_name`, `last_name` 을 쓰지 않으므로 빈 컬럼을 만들지 않는다.
    """

    email = models.EmailField("이메일", unique=True)
    nickname = models.CharField("닉네임", max_length=20)

    is_active = models.BooleanField("활성", default=True)
    is_staff = models.BooleanField("스태프", default=False)
    date_joined = models.DateTimeField("가입 시각", default=timezone.now)

    objects = UserManager()

    USERNAME_FIELD = "email"
    EMAIL_FIELD = "email"
    # createsuperuser 가 물어볼 추가 항목
    REQUIRED_FIELDS = ["nickname"]

    class Meta:
        db_table = "accounts_user"
        verbose_name = "사용자"
        verbose_name_plural = "사용자"

    def __str__(self):
        return f"{self.nickname} <{self.email}>"

    def save(self, *args, **kwargs):
        # 대소문자만 다른 중복 가입을 막는다. unique 제약은 대소문자를 구분한다.
        if self.email:
            self.email = self.email.strip().lower()
        super().save(*args, **kwargs)


class Profile(models.Model):
    """back-spec.md 4.1 Profile.

    회원가입 시점에 기본값으로 함께 만든다. 나중에 붙이면 "프로필 없는 사용자"
    상태를 P1 알람 계산이 매번 방어해야 한다.
    """

    user = models.OneToOneField(
        User, on_delete=models.CASCADE, related_name="profile", verbose_name="사용자"
    )

    home_lat = models.FloatField("집 위도", null=True, blank=True)
    home_lng = models.FloatField("집 경도", null=True, blank=True)
    home_label = models.CharField("집 표시명", max_length=80, blank=True)

    # 도착 확률 목표. 0.90 이면 "정시 도착 확률 90%".
    default_tau = models.FloatField(
        "기본 τ",
        default=0.90,
        validators=[MinValueValidator(0.5), MaxValueValidator(0.999)],
    )

    # 온보딩에서 사용자가 직접 답한 준비시간. 관측이 쌓이기 전의 사전값이다.
    onboarding_prep_min = models.PositiveIntegerField(
        "온보딩 준비시간(분)", null=True, blank=True
    )
    shadow_started_at = models.DateTimeField("섀도 시작 시각", null=True, blank=True)
    timezone = models.CharField("시간대", max_length=40, default="Asia/Seoul")

    class Meta:
        db_table = "accounts_profile"
        verbose_name = "프로필"
        verbose_name_plural = "프로필"
        constraints = [
            models.CheckConstraint(
                condition=Q(default_tau__gte=0.5) & Q(default_tau__lte=0.999),
                name="accounts_profile_default_tau_range",
            )
        ]

    def __str__(self):
        return f"{self.user.email} 프로필"
