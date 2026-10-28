# Partly AI-generated (Claude)
"""카카오·구글 소셜 로그인(`apps.accounts.social`).

진짜 제공자 대신 테스트가 RSA 키를 만들어 ID 토큰에 서명하고, 공개키 조회
(`social.fetch_jwks`)를 그 키로 바꿔 끼운다. 그래서 서명·iss·aud·만료 검증은 실제 코드
그대로 돈다.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from unittest.mock import Mock

import jwt
import pytest
import requests
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from rest_framework.test import APIClient

from apps.accounts import social
from apps.accounts.models import SocialAccount, User

pytestmark = pytest.mark.django_db

KAKAO_KEY = "kakao-native-key"
GOOGLE_ID = "web-client.apps.googleusercontent.com"
PASSWORD = "Gk9-hollow-meadow-51"
ISSUERS = {"kakao": "https://kauth.kakao.com", "google": "https://accounts.google.com"}
AUDIENCES = {"kakao": KAKAO_KEY, "google": GOOGLE_ID}


def _private_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


SIGNER = _private_key()
OTHER = _private_key()
# 픽스처가 바꿔 끼우기 전의 진짜 조회.
REAL_FETCH = social.fetch_jwks


def _b64(data: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()


def _jwk(private_key, kid: str) -> dict:
    data = jwt.algorithms.RSAAlgorithm.to_jwk(private_key.public_key(), as_dict=True)
    return {**data, "kid": kid, "alg": "RS256", "use": "sig"}


@pytest.fixture(autouse=True)
def configured(settings):
    settings.KAKAO_NATIVE_APP_KEY = KAKAO_KEY
    settings.GOOGLE_WEB_CLIENT_ID = GOOGLE_ID
    social.clear_jwks_cache()
    yield
    social.clear_jwks_cache()


@pytest.fixture(autouse=True)
def jwks(monkeypatch):
    """공개키 조회를 가짜로. `calls` 로 횟수를 세고 `keys` 로 키를 바꾼다."""
    state = {"calls": [], "keys": [_jwk(SIGNER, "k1")]}

    def fetch(url):
        state["calls"].append(url)
        keys = jwt.PyJWKSet.from_dict({"keys": state["keys"]}).keys
        return {key.key_id: key for key in keys}, 3600

    monkeypatch.setattr(social, "fetch_jwks", fetch)
    return state


def id_token(provider="kakao", *, key=SIGNER, kid="k1", alg="RS256", drop=(), **claims) -> str:
    now = int(time.time())
    payload = {
        "iss": ISSUERS[provider],
        "aud": AUDIENCES[provider],
        "sub": "1234567890",
        "iat": now,
        "exp": now + 3600,
        **claims,
    }
    for name in drop:
        payload.pop(name)
    return jwt.encode(payload, key, algorithm=alg, headers={"kid": kid})


def login(client, provider="kakao", token=None, **body):
    body.setdefault("terms_agreed", True)
    return client.post(
        f"/api/auth/social/{provider}",
        {"id_token": token or id_token(provider), **body},
        format="json",
    )


def code(response) -> str:
    return response.data["error"]["code"]


@pytest.fixture
def client():
    return APIClient()


@pytest.fixture
def member(django_user_model):
    """이메일·비밀번호로 가입한 사용자."""
    return django_user_model.objects.create_user(
        email="member@example.com", password=PASSWORD, nickname="회원"
    )


@pytest.fixture
def member_client(member):
    api = APIClient()
    api.force_authenticate(member)
    return api


@pytest.mark.integration
class TestSignUpAndLogin:
    def test_first_login_creates_a_linked_account(self, client):
        response = login(client, token=id_token(email="New@Kakao.com", nickname="새사람"))

        assert response.status_code == 201
        assert response.data["created"] is True
        user = User.objects.get(pk=response.data["user_id"])
        assert user.email == "new@kakao.com"
        assert user.nickname == "새사람"
        assert user.terms_agreed_at is not None
        assert not user.has_usable_password()
        assert user.profile is not None
        assert response.data["user"]["login_methods"] == ["kakao"]
        account = SocialAccount.objects.get()
        assert (account.user_id, account.provider, account.subject) == (user.pk, "kakao", "1234567890")

    def test_the_access_token_works(self, client):
        access = login(client).data["access"]
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {access}")
        assert client.get("/api/auth/me").status_code == 200

    def test_second_login_returns_the_same_user(self, client):
        first = login(client).data["user_id"]
        SocialAccount.objects.update(last_login_at=None)

        response = login(client, terms_agreed=False)

        assert response.status_code == 200
        assert response.data["created"] is False
        assert response.data["user_id"] == first
        assert User.objects.count() == 1
        assert SocialAccount.objects.get().last_login_at is not None

    def test_without_email_uses_a_placeholder_and_a_default_nickname(self, client):
        response = login(client)
        user = User.objects.get(pk=response.data["user_id"])
        assert user.email == "kakao-1234567890@social.invalid"
        assert user.nickname == "카카오 사용자"

    def test_nickname_in_the_request_wins(self, client):
        response = login(client, token=id_token(nickname="카톡이름"), nickname="  내 이름  ")
        assert response.data["user"]["nickname"] == "내 이름"

    def test_long_nickname_is_cut(self, client):
        response = login(client, token=id_token(nickname="가" * 40))
        assert response.data["user"]["nickname"] == "가" * 20

    def test_social_only_account_cannot_use_password_login(self, client):
        email = User.objects.get(pk=login(client).data["user_id"]).email
        response = client.post(
            "/api/auth/token", {"email": email, "password": ""}, format="json"
        )
        assert response.status_code in (400, 401)
        response = client.post(
            "/api/auth/token", {"email": email, "password": PASSWORD}, format="json"
        )
        assert response.status_code == 401

    def test_inactive_user_is_refused(self, client):
        user = User.objects.get(pk=login(client).data["user_id"])
        user.is_active = False
        user.save()
        response = login(client)
        assert response.status_code == 401
        assert code(response) == "social_token_invalid"

    def test_concurrent_first_logins_end_on_one_user(self, client, monkeypatch):
        """같은 토큰 두 요청이 겹쳐 둘 다 "연결 없음" 을 봤을 때. 늦은 쪽은 먼저 만든 쪽으로."""
        existing = User.objects.get(pk=login(client).data["user_id"])
        real = social._linked
        calls = []

        def linked(identity):
            calls.append(identity)
            return None if len(calls) == 1 else real(identity)

        monkeypatch.setattr(social, "_linked", linked)

        response = login(client)

        assert response.status_code == 200
        assert response.data["user_id"] == existing.pk
        assert User.objects.count() == SocialAccount.objects.count() == 1


@pytest.mark.integration
class TestGoogle:
    def test_verified_email_is_used(self, client):
        token = id_token(
            "google", sub="1098", email="me@gmail.com", email_verified=True, name="구글 이름"
        )
        response = login(client, "google", token)
        assert response.status_code == 201
        user = User.objects.get(pk=response.data["user_id"])
        assert (user.email, user.nickname) == ("me@gmail.com", "구글 이름")
        assert response.data["user"]["login_methods"] == ["google"]

    @pytest.mark.parametrize("verified", [False, "true", None])
    def test_unverified_email_is_not_used(self, client, verified):
        token = id_token("google", sub="1098", email="me@corp.example", email_verified=verified)
        response = login(client, "google", token)
        user = User.objects.get(pk=response.data["user_id"])
        assert user.email == "google-1098@social.invalid"
        assert user.nickname == "구글 사용자"

    def test_both_issuer_forms_are_accepted(self, client):
        assert login(client, "google", id_token("google", iss="accounts.google.com")).status_code == 201

    def test_a_kakao_token_is_not_a_google_token(self, client):
        response = login(client, "google", id_token("kakao"))
        assert response.status_code == 401


@pytest.mark.integration
class TestTokenChecks:
    def assert_refused(self, response):
        assert response.status_code == 401
        assert code(response) == "social_token_invalid"
        assert User.objects.count() == SocialAccount.objects.count() == 0

    def test_another_apps_token(self, client):
        self.assert_refused(login(client, token=id_token(aud="someone-elses-app")))

    def test_wrong_issuer(self, client):
        self.assert_refused(login(client, token=id_token(iss="https://evil.example")))

    def test_expired(self, client):
        past = int(time.time()) - 3600
        self.assert_refused(login(client, token=id_token(iat=past - 60, exp=past)))

    def test_clock_skew_within_a_minute_is_allowed(self, client):
        now = int(time.time())
        assert login(client, token=id_token(iat=now - 3600, exp=now - 30)).status_code == 201

    def test_wrong_signature(self, client):
        self.assert_refused(login(client, token=id_token(key=OTHER)))

    def test_hs256_signed_with_the_public_key(self, client):
        """공개키를 HMAC 비밀로 쓰는 고전 공격. 알고리즘을 토큰이 아니라 서버가 정한다."""
        # PyJWT 는 이런 토큰을 만들어 주지 않는다. 손으로 서명한다.
        public = SIGNER.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        header = _b64({"alg": "HS256", "typ": "JWT", "kid": "k1"})
        now = int(time.time())
        payload = _b64({
            "iss": ISSUERS["kakao"], "aud": KAKAO_KEY, "sub": "1", "iat": now, "exp": now + 60,
        })
        signature = hmac.new(public, f"{header}.{payload}".encode(), hashlib.sha256).digest()
        token = f"{header}.{payload}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"
        self.assert_refused(login(client, token=token))

    def test_unsigned(self, client):
        token = jwt.encode(
            {"iss": ISSUERS["kakao"], "aud": KAKAO_KEY, "sub": "1"}, None, algorithm="none"
        )
        self.assert_refused(login(client, token=token))

    @pytest.mark.parametrize("claim", ["sub", "exp", "iat", "aud"])
    def test_missing_claim(self, client, claim):
        self.assert_refused(login(client, token=id_token(drop=(claim,))))

    def test_blank_subject(self, client):
        self.assert_refused(login(client, token=id_token(sub="  ")))

    def test_garbage(self, client):
        self.assert_refused(login(client, token="not-a-jwt"))

    def test_too_long_is_a_bad_request(self, client):
        response = login(client, token="a" * 9000)
        assert response.status_code == 400

    def test_unknown_provider_is_404(self, client):
        assert login(client, "naver", id_token()).status_code == 404


@pytest.mark.integration
class TestConfiguration:
    def test_missing_key_is_503_without_fetching(self, client, settings, jwks):
        settings.KAKAO_NATIVE_APP_KEY = ""
        response = login(client)
        assert response.status_code == 503
        assert code(response) == "social_not_configured"
        assert jwks["calls"] == []

    def test_one_provider_missing_does_not_block_the_other(self, client, settings):
        settings.KAKAO_NATIVE_APP_KEY = ""
        assert login(client, "google", id_token("google")).status_code == 201

    def test_several_audiences_separated_by_commas(self, client, settings):
        settings.KAKAO_NATIVE_APP_KEY = "dev-key, " + KAKAO_KEY
        assert login(client, token=id_token(aud="dev-key")).status_code == 201

    def test_provider_down_is_503(self, client, monkeypatch):
        monkeypatch.setattr(social, "fetch_jwks", REAL_FETCH)
        monkeypatch.setattr(
            social.requests, "get", Mock(side_effect=requests.ConnectionError("down"))
        )
        response = login(client)
        assert response.status_code == 503
        assert code(response) == "social_unavailable"


class TestKeyCache:
    def test_keys_are_fetched_once(self, jwks):
        social.verify("kakao", id_token())
        social.verify("kakao", id_token(sub="2"))
        assert len(jwks["calls"]) == 1

    def test_rotated_key_is_fetched_again(self, jwks, monkeypatch):
        social.verify("kakao", id_token())
        jwks["keys"] = [_jwk(SIGNER, "k1"), _jwk(OTHER, "k2")]
        later = time.monotonic() + 61
        monkeypatch.setattr(social.time, "monotonic", lambda: later)
        identity = social.verify("kakao", id_token(key=OTHER, kid="k2"))
        assert identity.subject == "1234567890"
        assert len(jwks["calls"]) == 2

    def test_unknown_kid_does_not_hammer_the_provider(self, jwks, monkeypatch):
        social.verify("kakao", id_token())
        clock = [time.monotonic() + 61]
        monkeypatch.setattr(social.time, "monotonic", lambda: clock[0])
        for _ in range(3):
            with pytest.raises(social.SocialTokenInvalid):
                social.verify("kakao", id_token(kid="nope"))
        # 첫 모르는 kid 에서 한 번만 다시 받는다. 1분 안의 나머지는 캐시로 거절한다.
        assert len(jwks["calls"]) == 2

    def test_fetch_reads_max_age(self, monkeypatch):
        response = Mock(headers={"Cache-Control": "public, max-age=22685"})
        response.json.return_value = {"keys": [_jwk(SIGNER, "k1")]}
        monkeypatch.setattr(social.requests, "get", Mock(return_value=response))
        keys, ttl = REAL_FETCH("https://example.test/jwks")
        assert list(keys) == ["k1"]
        assert ttl == 22685

    @pytest.mark.parametrize("header,ttl", [
        ("max-age=10", social.JWKS_MIN_TTL),
        ("max-age=999999", social.JWKS_MAX_TTL),
        ("", social.JWKS_DEFAULT_TTL),
    ])
    def test_ttl_is_bounded(self, monkeypatch, header, ttl):
        response = Mock(headers={"Cache-Control": header})
        response.json.return_value = {"keys": [_jwk(SIGNER, "k1")]}
        monkeypatch.setattr(social.requests, "get", Mock(return_value=response))
        assert REAL_FETCH("https://example.test/jwks")[1] == ttl


@pytest.mark.integration
class TestExistingEmail:
    def test_same_email_as_a_password_account_is_409(self, client, member):
        response = login(client, token=id_token(email="Member@example.com"))

        assert response.status_code == 409
        assert code(response) == "social_email_in_use"
        assert response.data["error"]["details"]["login_methods"] == ["password"]
        assert "연결" in response.data["error"]["message"]
        # 자동으로 붙지 않는다.
        assert not SocialAccount.objects.exists()
        assert User.objects.count() == 1

    def test_same_email_as_another_social_account_names_that_method(self, client):
        login(client, "google", id_token("google", email="me@gmail.com", email_verified=True))
        response = login(client, token=id_token(email="me@gmail.com"))
        assert response.status_code == 409
        assert response.data["error"]["details"]["login_methods"] == ["google"]

    def test_email_check_comes_before_terms(self, client, member):
        """약관에 동의하게 한 뒤에 "이미 계정이 있다" 고 하면 순서가 틀렸다."""
        response = login(client, token=id_token(email=member.email), terms_agreed=False)
        assert code(response) == "social_email_in_use"


@pytest.mark.integration
class TestTerms:
    @pytest.mark.parametrize("value", [False, "true", None])
    def test_new_account_needs_terms(self, client, value):
        body = {"id_token": id_token()}
        if value is not None:
            body["terms_agreed"] = value
        response = client.post("/api/auth/social/kakao", body, format="json")
        assert response.status_code == 400
        assert code(response) == "social_terms_required"
        assert User.objects.count() == 0


@pytest.mark.integration
class TestLink:
    def test_link_then_log_in_with_kakao(self, client, member, member_client):
        response = member_client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
        assert response.status_code == 200
        assert response.data["user"]["login_methods"] == ["password", "kakao"]

        response = login(client, terms_agreed=False)
        assert response.status_code == 200
        assert response.data["user_id"] == member.pk

    def test_linking_with_a_different_email_is_allowed(self, member_client, member):
        """로그인한 사람이 그 카카오 토큰도 가졌다. 두 계정을 모두 가진 것이 증명됐다."""
        response = member_client.post(
            "/api/auth/me/social/kakao", {"id_token": id_token(email="other@kakao.com")}, format="json"
        )
        assert response.status_code == 200
        assert SocialAccount.objects.get().email == "other@kakao.com"
        member.refresh_from_db()
        assert member.email == "member@example.com"

    def test_linking_twice_is_idempotent(self, member_client):
        for _ in range(2):
            response = member_client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
            assert response.status_code == 200
        assert SocialAccount.objects.count() == 1

    def test_kakao_account_of_another_user_is_409(self, client, member_client):
        login(client)
        response = member_client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
        assert response.status_code == 409
        assert code(response) == "social_already_linked"

    def test_second_kakao_account_is_409(self, member_client):
        member_client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
        response = member_client.post(
            "/api/auth/me/social/kakao", {"id_token": id_token(sub="999")}, format="json"
        )
        assert response.status_code == 409
        assert code(response) == "social_provider_linked"

    def test_bad_token_is_401_and_links_nothing(self, member_client):
        response = member_client.post(
            "/api/auth/me/social/kakao", {"id_token": id_token(aud="other")}, format="json"
        )
        assert response.status_code == 401
        assert not SocialAccount.objects.exists()

    def test_needs_login(self, client):
        response = client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
        assert response.status_code == 401


@pytest.mark.integration
class TestUnlink:
    def test_unlink(self, member_client):
        member_client.post("/api/auth/me/social/kakao", {"id_token": id_token()}, format="json")
        response = member_client.delete("/api/auth/me/social/kakao")
        assert response.status_code == 200
        assert response.data["user"]["login_methods"] == ["password"]
        assert member_client.delete("/api/auth/me/social/kakao").status_code == 404

    def test_last_login_method_is_kept(self, client):
        access = login(client).data["access"]
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {access}")
        response = client.delete("/api/auth/me/social/kakao")
        assert response.status_code == 400
        assert code(response) == "social_last_login_method"
        assert SocialAccount.objects.count() == 1

    def test_social_only_user_with_two_providers_can_drop_one(self, client):
        access = login(client).data["access"]
        client.credentials(HTTP_AUTHORIZATION=f"Bearer {access}")
        client.post("/api/auth/me/social/google", {"id_token": id_token("google")}, format="json")
        assert client.delete("/api/auth/me/social/kakao").status_code == 200
        assert client.get("/api/auth/me").data["login_methods"] == ["google"]
