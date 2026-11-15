# Partly AI-generated (Claude)
"""문의 본문 검사. 규칙은 앱의 `domain/model/Inquiry.kt` 와 같다(앱이 먼저 막는다).

`app_version` · `device` 는 앱이 스스로 채우는 값이라 사용자가 고칠 수 없다. 길면 거절하지 않고
자른다(거절하면 사용자는 이유 모를 실패만 보고 시간당 5건을 헛되이 쓴다).
"""

from __future__ import annotations

import re

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
NOT_TEXT = "글자로 보내 주세요"

# `Reply-To` 에 그대로 들어가므로 로컬 부분은 dot-atom 만 받는다(따옴표 로컬 부분은 제어 문자 ·
# 역슬래시를 품을 수 있어 헤더가 깨진다). Django `EmailValidator.user_regex` 의 dot-atom 과 같다.
DOT_ATOM = re.compile(r"[-!#$%&'*+/=?^_`{}|~0-9A-Za-z]+(\.[-!#$%&'*+/=?^_`{}|~0-9A-Za-z]+)*\Z")


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
    # 선택 칸. 빠지거나 null 이면 빈 문자열로 저장한다. 길면 자른다(모듈 설명).
    app_version = serializers.CharField(
        required=False,
        allow_blank=True,
        allow_null=True,
        error_messages={"invalid": NOT_TEXT},
    )
    device = serializers.CharField(
        required=False,
        allow_blank=True,
        allow_null=True,
        error_messages={"invalid": NOT_TEXT},
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

    def validate_reply_email(self, value: str) -> str:
        # 형식 검사(EmailValidator)는 이미 지났다. 헤더에 넣기 전에 더 좁힌다.
        local, _, _ = value.rpartition("@")
        if any(not ch.isprintable() for ch in value) or not DOT_ATOM.match(local):
            raise serializers.ValidationError(EMAIL_INVALID)
        return value

    def validate_app_version(self, value):
        return (value or "")[:APP_VERSION_MAX].strip()

    def validate_device(self, value):
        return (value or "")[:DEVICE_MAX].strip()
