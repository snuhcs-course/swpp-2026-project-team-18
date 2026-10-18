"""회원가입은 성공·실패를 합쳐 IP당 시간 10회만 허용한다."""

import importlib
import re

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

pytestmark = pytest.mark.integration


@pytest.mark.django_db
def test_register_bounds_attempts_per_ip_without_blocking_password_checks(
    monkeypatch, django_user_model,
):
    # 기본 회귀에서는 꺼 둔 스로틀을 운영 설정과 가상 시계로 확인한다.
    base = importlib.import_module("config.settings.base")
    clock = [1000.0]
    monkeypatch.setattr(
        ScopedRateThrottle,
        "THROTTLE_RATES",
        base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"],
    )
    monkeypatch.setattr(ScopedRateThrottle, "timer", staticmethod(lambda: clock[0]))
    client = APIClient()
    payload = {
        "email": "first@example.com",
        "nickname": "가입",
        "password": "Vt7-silver-canyon-42",
        "password_confirm": "Vt7-silver-canyon-42",
        "terms_agreed": True,
    }
    cache.clear()
    try:
        assert client.post("/api/auth/register", payload, format="json").status_code == 201
        for _ in range(9):
            assert client.post("/api/auth/register", {}, format="json").status_code == 400

        payload["email"] = "blocked@example.com"
        blocked = client.post("/api/auth/register", payload, format="json")
        assert blocked.status_code == 429
        assert blocked.data["error"]["code"] == "throttled"
        assert re.search("[가-힣]", blocked.data["error"]["message"])
        assert blocked.data["error"]["details"] == {}
        assert int(blocked["Retry-After"]) == 3600
        assert not django_user_model.objects.filter(email=payload["email"]).exists()

        payload["email"] = "other-ip@example.com"
        assert client.post(
            "/api/auth/register", payload, format="json", REMOTE_ADDR="192.0.2.2"
        ).status_code == 201
        assert client.post(
            "/api/auth/password/check", {"password": ""}, format="json"
        ).status_code == 200

        clock[0] += 3600
        payload["email"] = "after-wait@example.com"
        assert client.post("/api/auth/register", payload, format="json").status_code == 201
    finally:
        cache.clear()
