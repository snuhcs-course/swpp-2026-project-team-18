# Partly AI-generated (Claude)
"""문의 본문 검사. 규칙은 앱의 `domain/model/Inquiry.kt` 와 같다(앱이 먼저 막는다)."""

from __future__ import annotations

from rest_framework import serializers

from .models import Inquiry

MESSAGE_MIN = 10
MESSAGE_MAX = 1000
APP_VERSION_MAX = 32
DEVICE_MAX = 120

# 칸 아래에 그대로 붙일 수 있는 문구. 앱 `Inquiry.TOO_SHORT` 등과 같다.
MESSAGE_REQUIRED = "문의 내용을 적어 주세요"
MESSAGE_TOO_SHORT = f"{MESSAGE_MIN}자 이상 적어 주세요"
MESSAGE_TOO_LONG = f"{MESSAGE_MAX}자까지 쓸 수 있어요"
EMAIL_REQUIRED = "답 받을 이메일을 적어 주세요"
EMAIL_INVALID = "이메일 형식을 확인해 주세요"
APP_VERSION_TOO_LONG = f"앱 버전은 {APP_VERSION_MAX}자까지예요"
DEVICE_TOO_LONG = f"기기 정보는 {DEVICE_MAX}자까지예요"
NOT_TEXT = "글자로 보내 주세요"


class InquirySerializer(serializers.ModelSerializer):
    # 글자 수는 앞뒤 공백을 뺀 코드 포인트 수다. 파이썬 `len` 이 코드 포인트를 센다 —
    # 앱(`codePointCount`)과 같다. 공백을 뺀 뒤에 세려고 길이 검사는 직접 한다.
    message = serializers.CharField(
        trim_whitespace=True,
        error_messages={
            "required": MESSAGE_REQUIRED,
            "blank": MESSAGE_REQUIRED,
            "null": MESSAGE_REQUIRED,
            "invalid": NOT_TEXT,
        },
    )
    reply_email = serializers.EmailField(
        max_length=254,
        error_messages={
            "required": EMAIL_REQUIRED,
            "blank": EMAIL_REQUIRED,
            "null": EMAIL_REQUIRED,
            "invalid": EMAIL_INVALID,
            "max_length": EMAIL_INVALID,
        },
    )
    # 선택 칸. 빠지거나 null 이면 빈 문자열로 저장한다.
    app_version = serializers.CharField(
        required=False,
        allow_blank=True,
        allow_null=True,
        max_length=APP_VERSION_MAX,
        error_messages={"max_length": APP_VERSION_TOO_LONG, "invalid": NOT_TEXT},
    )
    device = serializers.CharField(
        required=False,
        allow_blank=True,
        allow_null=True,
        max_length=DEVICE_MAX,
        error_messages={"max_length": DEVICE_TOO_LONG, "invalid": NOT_TEXT},
    )

    class Meta:
        model = Inquiry
        fields = ("message", "reply_email", "app_version", "device")

    def validate_message(self, value: str) -> str:
        if len(value) < MESSAGE_MIN:
            raise serializers.ValidationError(MESSAGE_TOO_SHORT)
        if len(value) > MESSAGE_MAX:
            raise serializers.ValidationError(MESSAGE_TOO_LONG)
        return value

    def validate_app_version(self, value):
        return value or ""

    def validate_device(self, value):
        return value or ""
