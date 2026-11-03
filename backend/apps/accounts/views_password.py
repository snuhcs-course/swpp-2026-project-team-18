# Partly AI-generated (Claude)
"""비밀번호 찾기·변경(R3-9)과 이메일 인증(R3-10). 계약은 `docs/iter3/password-api.md`.

| 경로 | 인증 | 성공 |
| --- | --- | --- |
| `POST /api/auth/password/reset` | 없음 | 202 `{sent, expires_in}` — 계정이 있든 없든 같다 |
| `POST /api/auth/password/reset/confirm` | 없음 | 200 `{access, refresh, user}` |
| `POST /api/auth/me/password` | 필요 | 200 `{access, refresh, revoked}` |
| `POST /api/auth/me/email/verify` | 필요 | 202 `{sent, expires_in}` |
| `POST /api/auth/me/email/verify/confirm` | 필요 | 200 `{user}` |

비밀번호가 바뀌면 `tokens.revoke_all` 로 모든 기기의 refresh 를 취소한 **뒤에** 새 쌍을 낸다.
순서가 바뀌면 방금 낸 refresh 까지 취소된다.

틀린 현재 비밀번호는 401 이 아니라 400 `invalid_current_password` 다. 401 이면 앱이 토큰 갱신·
세션 만료 경로를 탄다.
"""

from __future__ import annotations

from django.contrib.auth import password_validation
from django.core.exceptions import ValidationError as DjangoValidationError
from django.utils import timezone
from rest_framework import serializers, status
from rest_framework.exceptions import APIException
from rest_framework.generics import GenericAPIView
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle, SimpleRateThrottle
from rest_framework_simplejwt.tokens import RefreshToken

from . import codes, tokens
from .models import EmailCode, User
from .serializers import UserSerializer
from .social import PLACEHOLDER_DOMAIN

PASSWORD_MISMATCH = "비밀번호가 일치하지 않는다."


# --- 오류 -------------------------------------------------------------------
# 공통 오류 포맷의 `code` 는 `default_code` 다(`common.errors`).


def _error(name: str, code: str, message: str, http_status: int = 400) -> type[APIException]:
    return type(name, (APIException,), {
        "status_code": http_status, "default_code": code, "default_detail": message,
    })


InvalidResetCode = _error("InvalidResetCode", "invalid_reset_code", "코드가 맞지 않는다.")
ResetCodeExpired = _error("ResetCodeExpired", "reset_code_expired", "코드가 만료됐다. 다시 요청할 것.")
TooManyAttempts = _error(
    "TooManyAttempts", "too_many_attempts", "코드를 너무 여러 번 틀렸다. 다시 요청할 것."
)
InvalidCurrentPassword = _error(
    "InvalidCurrentPassword", "invalid_current_password", "현재 비밀번호가 맞지 않는다."
)
PasswordUnchanged = _error(
    "PasswordUnchanged", "password_unchanged", "지금 비밀번호와 같다."
)
PasswordLoginUnavailable = _error(
    "PasswordLoginUnavailable", "password_login_unavailable",
    "비밀번호로 로그인하지 않는 계정이다.", status.HTTP_409_CONFLICT,
)
InvalidVerifyCode = _error("InvalidVerifyCode", "invalid_verify_code", "코드가 맞지 않는다.")
VerifyCodeExpired = _error(
    "VerifyCodeExpired", "verify_code_expired", "코드가 만료됐다. 다시 요청할 것."
)
EmailAlreadyVerified = _error(
    "EmailAlreadyVerified", "email_already_verified", "이미 인증한 이메일이다.",
    status.HTTP_409_CONFLICT,
)
EmailNotDeliverable = _error(
    "EmailNotDeliverable", "email_not_deliverable", "메일을 받을 수 없는 주소다.",
    status.HTTP_409_CONFLICT,
)

RESET_FAILURES = {
    codes.MISSING: InvalidResetCode,
    codes.WRONG: InvalidResetCode,
    codes.EXPIRED: ResetCodeExpired,
    codes.TOO_MANY: TooManyAttempts,
}
VERIFY_FAILURES = {
    codes.MISSING: InvalidVerifyCode,
    codes.WRONG: InvalidVerifyCode,
    codes.EXPIRED: VerifyCodeExpired,
    codes.TOO_MANY: TooManyAttempts,
}


# --- 스로틀 ------------------------------------------------------------------


class PasswordResetEmailThrottle(SimpleRateThrottle):
    """재설정 요청을 이메일마다 센다. IP 를 바꿔 가며 한 주소로 메일을 퍼붓지 못하게."""

    scope = "password_reset_email"

    def get_cache_key(self, request, view):
        email = _normalized_email(request.data.get("email") if hasattr(request, "data") else None)
        if not email:
            return None
        return self.cache_format % {"scope": self.scope, "ident": email}


def _normalized_email(value) -> str:
    return value.strip().lower() if isinstance(value, str) else ""


# --- 시리얼라이저 --------------------------------------------------------------


class ResetRequestSerializer(serializers.Serializer):
    email = serializers.EmailField(max_length=254)

    def validate_email(self, value: str) -> str:
        return _normalized_email(value)


class NewPasswordSerializer(serializers.Serializer):
    new_password = serializers.CharField(write_only=True, trim_whitespace=False)
    new_password_confirm = serializers.CharField(write_only=True, trim_whitespace=False)

    def check_new_password(self, user) -> None:
        """두 칸이 같은지, Django 검증기를 통과하는지. `invalid` 오류로 던진다."""
        data = self.validated_data
        if data["new_password"] != data["new_password_confirm"]:
            raise serializers.ValidationError({"new_password_confirm": [PASSWORD_MISMATCH]})
        try:
            password_validation.validate_password(data["new_password"], user)
        except DjangoValidationError as exc:
            raise serializers.ValidationError({"new_password": list(exc.messages)}) from exc


class ResetConfirmSerializer(NewPasswordSerializer):
    email = serializers.EmailField(max_length=254)
    reset_code = serializers.CharField(max_length=16)

    def validate_email(self, value: str) -> str:
        return _normalized_email(value)


class PasswordChangeSerializer(NewPasswordSerializer):
    current_password = serializers.CharField(write_only=True, trim_whitespace=False)


class VerifyConfirmSerializer(serializers.Serializer):
    code = serializers.CharField(max_length=16)


# --- 뷰 -----------------------------------------------------------------------


def _token_pair(user) -> dict:
    refresh = RefreshToken.for_user(user)
    return {"access": str(refresh.access_token), "refresh": str(refresh)}


def _sent_response() -> Response:
    return Response(
        {"sent": True, "expires_in": codes.CODE_TTL_SECONDS}, status=status.HTTP_202_ACCEPTED
    )


class PublicView(GenericAPIView):
    permission_classes = [AllowAny]
    authentication_classes = []

    def get_authenticate_header(self, request):
        # 인증 클래스가 없으면 DRF 가 401 을 403 으로 바꾼다(`LogoutView` 와 같다).
        return 'Bearer realm="api"'


class PasswordResetView(PublicView):
    """POST /api/auth/password/reset — 코드를 메일로 보낸다. 계정 여부와 관계없이 같은 202."""

    serializer_class = ResetRequestSerializer
    throttle_classes = [ScopedRateThrottle, PasswordResetEmailThrottle]
    throttle_scope = "password_reset"

    def post(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user = User.objects.filter(
            email=serializer.validated_data["email"], is_active=True
        ).first()
        # 쓸 수 있는 비밀번호가 있는 계정에만 보낸다. 소셜로만 가입한 계정은 보내지 않는다.
        if user is not None and user.has_usable_password():
            code = codes.issue(user, EmailCode.Purpose.PASSWORD_RESET)
            codes.send(user, EmailCode.Purpose.PASSWORD_RESET, code)
        return _sent_response()


class PasswordResetConfirmView(PublicView):
    """POST /api/auth/password/reset/confirm — 코드를 확인하고 비밀번호를 바꾼다. 로그인 응답 모양."""

    serializer_class = ResetConfirmSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "password_reset_confirm"

    def post(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        user = User.objects.filter(email=data["email"], is_active=True).first()
        if user is not None and not user.has_usable_password():
            user = None  # 메일을 보내지 않은 계정. 코드가 있을 수 없다

        purpose = EmailCode.Purpose.PASSWORD_RESET
        # 먼저 코드만 본다(사용 처리하지 않음). 비밀번호 검사에서 떨어져도 코드를 다시 쓸 수 있다.
        result = codes.check(user, purpose, data["reset_code"], consume=False)
        if result != codes.OK:
            raise RESET_FAILURES[result]()
        serializer.check_new_password(user)
        result = codes.check(user, purpose, data["reset_code"], consume=True)
        if result != codes.OK:  # 두 요청이 같은 코드로 동시에 온 경우
            raise RESET_FAILURES[result]()

        user.set_password(data["new_password"])
        user.save(update_fields=["password"])
        tokens.revoke_all(user)
        return Response({**_token_pair(user), "user": UserSerializer(user).data})


class PasswordChangeView(GenericAPIView):
    """POST /api/auth/me/password — 로그인한 채 비밀번호를 바꾼다. 모든 기기 로그아웃 뒤 새 쌍."""

    serializer_class = PasswordChangeSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "password_change"

    def post(self, request, *args, **kwargs):
        user = request.user
        if not user.has_usable_password():
            raise PasswordLoginUnavailable()
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        if not user.check_password(data["current_password"]):
            raise InvalidCurrentPassword()
        if data["new_password"] == data["current_password"]:
            raise PasswordUnchanged()
        serializer.check_new_password(user)

        user.set_password(data["new_password"])
        user.save(update_fields=["password"])
        revoked = tokens.revoke_all(user)
        return Response({**_token_pair(user), "revoked": revoked})


def _deliverable(user) -> bool:
    return not user.email.endswith("@" + PLACEHOLDER_DOMAIN)


class EmailVerifyView(GenericAPIView):
    """POST /api/auth/me/email/verify — 인증 코드를 메일로 보낸다(선택 기능, 로그인을 막지 않는다)."""

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "email_verify"

    def post(self, request, *args, **kwargs):
        user = request.user
        if user.email_verified_at is not None:
            raise EmailAlreadyVerified()
        if not _deliverable(user):
            raise EmailNotDeliverable()
        code = codes.issue(user, EmailCode.Purpose.EMAIL_VERIFY)
        codes.send(user, EmailCode.Purpose.EMAIL_VERIFY, code)
        return _sent_response()


class EmailVerifyConfirmView(GenericAPIView):
    """POST /api/auth/me/email/verify/confirm `{code}` → `{user}`"""

    serializer_class = VerifyConfirmSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "email_verify_confirm"

    def post(self, request, *args, **kwargs):
        user = request.user
        if user.email_verified_at is not None:
            raise EmailAlreadyVerified()
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        result = codes.check(
            user, EmailCode.Purpose.EMAIL_VERIFY, serializer.validated_data["code"], consume=True
        )
        if result != codes.OK:
            raise VERIFY_FAILURES[result]()
        user.email_verified_at = timezone.now()
        user.save(update_fields=["email_verified_at"])
        return Response({"user": UserSerializer(user).data})
