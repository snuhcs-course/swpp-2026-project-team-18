"""가입·실시간 비밀번호 검사 계약과 입력 속도 제한."""

import importlib

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

from apps.accounts.views import PasswordCheckView

pytestmark = pytest.mark.django_db
CHECK = "/api/auth/password/check"


@pytest.mark.parametrize(
    "password,min_length,letters_and_digits",
    [
        ("", False, False),
        ("ab1", False, True),
        ("x7Lm3tQ", False, True),
        ("x7Lm3tQ9", True, True),
        ("justletters", True, False),
        ("12345678", True, False),
        ("한글비밀번호123", True, False),
        ("letters１２３", True, False),
        ("password1", True, True),
        (" A1b2c3 ", True, True),
    ],
)
def test_check_matches_registration(password, min_length, letters_and_digits):
    client = APIClient()
    checked = client.post(CHECK, {"password": password}, format="json")
    assert checked.status_code == 200
    assert set(checked.data) == {"min_length", "letters_and_digits", "messages"}
    assert checked.data["min_length"] is min_length
    assert checked.data["letters_and_digits"] is letters_and_digits

    registered = client.post(
        "/api/auth/register",
        {
            "email": "check@example.com",
            "nickname": "검사",
            "password": password,
            "password_confirm": password,
            "terms_agreed": True,
        },
        format="json",
    )
    assert registered.status_code == (400 if checked.data["messages"] else 201)
    if password and checked.data["messages"]:
        assert checked.data["messages"] == registered.data["error"]["details"][
            "password"
        ]
    if password == "password1":
        assert checked.data["messages"]  # 조건 두 개를 통과해도 흔한 비밀번호는 거절.


@pytest.mark.parametrize(
    "payload",
    [{}, {"password": None}, {"password": True}, {"password": []}, {"password": {}}],
)
def test_invalid_input_uses_standard_field_errors(payload):
    response = APIClient().post(CHECK, payload, format="json")
    assert response.status_code == 400
    assert "password" in response.data["error"]["details"]


def test_public_response_never_echoes_password():
    password = "Private-t8X4-phrase"
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION="Bearer invalid-token")
    response = client.post(CHECK, {"password": password}, format="json")
    assert response.status_code == 200  # 토큰 검증을 요구하지 않는 공개 검사.
    assert password not in response.content.decode()
    assert response.data == {
        "min_length": True,
        "letters_and_digits": True,
        "messages": [],
    }


def test_check_uses_configured_validator_pipeline(settings):
    settings.AUTH_PASSWORD_VALIDATORS = [
        {
            "NAME": "django.contrib.auth.password_validation.MinimumLengthValidator",
            "OPTIONS": {"min_length": 10},
        },
        {"NAME": "apps.accounts.validators.LettersAndDigitsValidator"},
    ]
    response = APIClient().post(CHECK, {"password": "x7Lm3tQ9"}, format="json")
    assert response.data["min_length"] is False
    assert response.data["letters_and_digits"] is True
    assert response.data["messages"]


def test_throttle_allows_typing_but_bounds_requests_per_ip(monkeypatch):
    base = importlib.import_module("config.settings.base")
    clock = [1000.0]

    class ProductionPasswordThrottle(ScopedRateThrottle):
        # DRF 클래스는 import 때 rates를 보관하므로 테스트의 None 대신 운영값 사용.
        THROTTLE_RATES = base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
        timer = staticmethod(lambda: clock[0])

    monkeypatch.setattr(
        PasswordCheckView, "throttle_classes", [ProductionPasswordThrottle]
    )
    cache.clear()
    try:
        client = APIClient()
        for _ in range(600):
            response = client.post(CHECK, {"password": ""}, format="json")
            assert response.status_code == 200
        blocked = client.post(CHECK, {"password": ""}, format="json")
        assert blocked.status_code == 429
        assert int(blocked["Retry-After"]) == 60
        assert client.post(
            CHECK, {"password": ""}, format="json", REMOTE_ADDR="192.0.2.2"
        ).status_code == 200
        clock[0] += 61
        assert client.post(CHECK, {"password": ""}, format="json").status_code == 200
    finally:
        cache.clear()
