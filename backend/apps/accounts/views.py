"""계정 뷰. back-spec.md 5.1 인증."""

from __future__ import annotations

from django.contrib.auth import password_validation
from django.core.exceptions import ValidationError
from rest_framework import status
from rest_framework.generics import CreateAPIView, GenericAPIView, RetrieveAPIView
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.views import TokenObtainPairView

from . import tokens
from .serializers import (
    LoginSerializer,
    LogoutSerializer,
    PasswordCheckSerializer,
    RegisterSerializer,
    UserSerializer,
)


class RegisterView(CreateAPIView):
    """POST /api/auth/register

    back-spec 5.1 은 201 `{user_id}` 만 규정하지만 `access`·`refresh`·`user` 를
    함께 내린다. 가입 직후 곧바로 로그인 화면으로 되돌리지 않고 바로 진입시키기
    위해서다. 규정된 `user_id` 는 그대로 포함하므로 명세를 어기지 않는다.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = RegisterSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "register"

    def create(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user = serializer.save()

        refresh = RefreshToken.for_user(user)
        return Response(
            {
                "user_id": user.id,
                "user": UserSerializer(user).data,
                "access": str(refresh.access_token),
                "refresh": str(refresh),
            },
            status=status.HTTP_201_CREATED,
        )


class LoginView(TokenObtainPairView):
    """POST /api/auth/token — `{email, password}` → `{access, refresh, user}`"""

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = LoginSerializer


class PasswordCheckView(GenericAPIView):
    """POST /api/auth/password/check — 가입과 같은 검증기를 사용한다."""

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = PasswordCheckSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "password_check"

    def post(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        errors = []
        try:
            password_validation.validate_password(serializer.validated_data["password"])
        except ValidationError as exc:
            errors = exc.error_list
        codes = {error.code for error in errors}
        return Response(
            {
                "min_length": "password_too_short" not in codes,
                "letters_and_digits": "password_no_letters_and_digits" not in codes,
                "messages": [message for error in errors for message in error.messages],
            }
        )


class MeView(RetrieveAPIView):
    """GET /api/auth/me

    명세에 없는 추가 엔드포인트다. 앱을 다시 열었을 때 저장된 access 토큰이
    아직 유효한지 확인하는 용도다. 없으면 클라이언트가 임의의 보호 API 를
    찔러보는 방식으로 대체해야 해서 의도가 드러나지 않는다.
    """

    serializer_class = UserSerializer

    def get_object(self):
        return self.request.user


# Partly AI-generated (Claude)
class LogoutView(GenericAPIView):
    """POST /api/auth/logout — `{refresh}` 를 취소한다. 204.

    인증 헤더를 보지 않는다. access 가 이미 만료된 앱도 로그아웃할 수 있어야 하고, refresh 자체가
    자격증명이다(가진 사람만 취소할 수 있다). 토큰 갱신(`token/refresh`)과 같은 이유다.

    이미 취소된 토큰이면 그대로 204 다(앱의 재시도, 두 번 누름). 서명·만료가 맞지 않으면
    401 `token_not_valid`. 앱은 응답과 관계없이 저장한 토큰을 지운다. 규칙은 `tokens`.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = LogoutSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "logout"

    def get_authenticate_header(self, request):
        # 인증 클래스가 없으면 DRF 가 401 을 403 으로 바꾼다. 토큰 갱신(`TokenViewBase`)처럼 401 로 둔다.
        return 'Bearer realm="api"'

    def post(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        tokens.revoke(serializer.validated_data["refresh"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class LogoutAllView(GenericAPIView):
    """POST /api/auth/me/logout-all — 모든 기기에서 로그아웃. `{revoked}` (취소한 refresh 개수)

    로그인한 사용자만 부른다. 이 기기의 refresh 도 취소되므로 앱은 부른 뒤 토큰을 지우고 로그인
    화면으로 간다. 공개 경로(`logout`)와 접두가 겹치지 않게 `me` 아래에 둔다.
    """

    def post(self, request, *args, **kwargs):
        return Response({"revoked": tokens.revoke_all(request.user)})
