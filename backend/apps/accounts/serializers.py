"""계정 시리얼라이저.

back-spec.md 5.1 인증. 요청·응답은 snake_case JSON 이다.
"""

from __future__ import annotations

from django.contrib.auth import password_validation
from django.core.exceptions import ValidationError as DjangoValidationError
from django.db import transaction
from rest_framework import serializers
from rest_framework_simplejwt.serializers import TokenObtainPairSerializer

from .models import Profile, User


class UserSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ("id", "email", "nickname", "date_joined")
        read_only_fields = fields


class RegisterSerializer(serializers.Serializer):
    """회원가입.

    Figma ⑩ 회원가입 화면의 입력과 1:1로 맞춘다.
    닉네임 / 이메일 / 비밀번호 / 비밀번호 확인.
    """

    email = serializers.EmailField()
    nickname = serializers.CharField(max_length=20)
    # trim_whitespace=False — 비밀번호 앞뒤 공백도 사용자가 의도한 문자다.
    password = serializers.CharField(write_only=True, trim_whitespace=False)
    password_confirm = serializers.CharField(write_only=True, trim_whitespace=False)

    def validate_email(self, value: str) -> str:
        email = value.strip().lower()
        if User.objects.filter(email=email).exists():
            raise serializers.ValidationError("이미 가입된 이메일이다.")
        return email

    def validate_nickname(self, value: str) -> str:
        nickname = value.strip()
        if not nickname:
            raise serializers.ValidationError("닉네임을 입력해야 한다.")
        return nickname

    def validate(self, attrs):
        if attrs["password"] != attrs["password_confirm"]:
            # 어느 필드의 문제인지 클라이언트가 알 수 있게 필드에 붙인다.
            raise serializers.ValidationError(
                {"password_confirm": "비밀번호가 일치하지 않는다."}
            )

        # Django 검증기를 태운다. UserAttributeSimilarityValidator 가 이메일·닉네임과
        # 비슷한 비밀번호를 걸러내므로 저장하지 않은 인스턴스를 넘겨준다.
        probe = User(email=attrs["email"], nickname=attrs["nickname"])
        try:
            password_validation.validate_password(attrs["password"], user=probe)
        except DjangoValidationError as exc:
            raise serializers.ValidationError({"password": list(exc.messages)}) from exc
        return attrs

    @transaction.atomic
    def create(self, validated_data):
        validated_data.pop("password_confirm", None)
        user = User.objects.create_user(
            email=validated_data["email"],
            password=validated_data["password"],
            nickname=validated_data["nickname"],
        )
        # 프로필은 `signals.create_profile_for_new_user` 가 같은 트랜잭션 안에서
        # 만든다. 여기서 또 만들지 않는다. 시그널로 옮긴 이유는
        # createsuperuser·admin·shell 등 이 경로를 타지 않는 생성에서도 프로필이
        # 보장돼야 하기 때문이다. signals.py 주석에 근거를 적어 두었다.
        return user


class LoginSerializer(TokenObtainPairSerializer):
    """이메일·비밀번호 로그인.

    `USERNAME_FIELD` 가 email 이므로 입력 필드명이 자동으로 `email` 이 된다.
    back-spec 5.1 의 `{email, password}` 와 일치한다.

    기본 응답 `{access, refresh}` 에 `user` 를 더한다. 로그인 직후 클라이언트가
    닉네임을 표시하려고 `/api/auth/me` 를 한 번 더 부르는 왕복을 없앤다.
    """

    default_error_messages = {
        "no_active_account": "이메일 또는 비밀번호가 올바르지 않다.",
    }

    def validate(self, attrs):
        data = super().validate(attrs)
        data["user"] = UserSerializer(self.user).data
        return data


class ProfileSerializer(serializers.ModelSerializer):
    """프로필. 집 위치가 알람 계산의 출발지다.

    `home_lat`/`home_lng` 는 짝이다. 하나만 설정하면 좌표가 되지 않으므로
    함께 오거나 함께 비어야 한다.
    """

    has_home = serializers.SerializerMethodField()

    class Meta:
        model = Profile
        fields = (
            "home_lat",
            "home_lng",
            "home_label",
            "has_home",
            "default_tau",
            "onboarding_prep_min",
            "shadow_started_at",
            "timezone",
        )
        read_only_fields = ("shadow_started_at",)

    def get_has_home(self, obj) -> bool:
        return obj.home_lat is not None and obj.home_lng is not None

    def validate(self, attrs):
        # 부분 수정이므로 인스턴스의 현재 값과 합쳐서 판단한다.
        lat = attrs.get("home_lat", getattr(self.instance, "home_lat", None))
        lng = attrs.get("home_lng", getattr(self.instance, "home_lng", None))
        if (lat is None) != (lng is None):
            raise serializers.ValidationError(
                {"home_lat": "집 위도와 경도는 함께 설정해야 한다."}
            )
        return attrs
