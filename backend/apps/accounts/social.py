# Partly AI-generated (Claude)
"""카카오·구글 소셜 로그인. ID 토큰을 검증하고 우리 사용자를 찾거나 만든다.

## 무엇을 받는가

앱이 카카오·구글 SDK 로 로그인하면 **ID 토큰**(OpenID Connect, RS256 JWT)을 받는다. 앱은
그 토큰만 서버로 보낸다. 서버는 제공자의 공개키(JWKS)로 서명을 확인하고 아래를 본다.

| 항목 | 카카오 | 구글 |
| --- | --- | --- |
| 공개키 | `kauth.kakao.com/.well-known/jwks.json` | `www.googleapis.com/oauth2/v3/certs` |
| `iss` | `https://kauth.kakao.com` | `accounts.google.com` 또는 `https://accounts.google.com` |
| `aud` | 네이티브 앱 키(`KAKAO_NATIVE_APP_KEY`) | 웹 클라이언트 ID(`GOOGLE_WEB_CLIENT_ID`) |
| 사용자 | `sub`(회원번호) | `sub` |
| 이메일 | `email`(동의했을 때만) | `email`, `email_verified` 가 참일 때만 |

`aud` 를 꼭 본다. 보지 않으면 **다른 앱이 받은 토큰**으로 우리 계정에 들어올 수 있다.
키가 설정되지 않은 제공자는 503 이다(키 없이 서명만 맞는 토큰을 받지 않는다).

## 계정 연결 규칙

- 제공자 `sub` 로 연결된 계정이 있으면 그 사용자로 로그인한다.
- 없으면 새 계정을 만든다. **같은 이메일의 기존 계정에 자동으로 붙지 않는다.** 카카오 ID
  토큰의 이메일에는 인증 여부가 없고, 구글도 gmail 이 아닌 주소는 지금 주인을 보장하지
  않는다. 이메일만 보고 붙이면 남의 이메일을 단 소셜 계정으로 그 사람 계정에 들어간다.
  그래서 409 를 돌려주고, 사용자가 원래 방법으로 로그인한 뒤 연결한다(`link`).
- 새 계정은 약관 동의(`terms_agreed`)가 있어야 만든다. 이메일이 없으면 자리 표시 주소
  (`…@social.invalid`)를 넣는다. 비밀번호는 쓸 수 없는 값이라 이메일 로그인은 안 된다.

## 공개키 캐시

프로세스마다 메모리에 둔다. 응답의 `Cache-Control: max-age` 만큼(5분~하루) 쓰고, 모르는
`kid` 가 오면(키 교체) 다시 받는다. 다시 받기는 1분에 한 번까지다. 엉터리 `kid` 로 우리
서버가 제공자를 두드리게 만들 수 없게 하려는 것이다.
"""

from __future__ import annotations

import hashlib
import logging
import re
import threading
import time
from dataclasses import dataclass
from typing import Callable

import jwt
import requests
from django.conf import settings
from django.core.exceptions import ValidationError as DjangoValidationError
from django.core.validators import validate_email
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework import status
from rest_framework.exceptions import APIException

from .models import SocialAccount, User

logger = logging.getLogger(__name__)

# 서버와 제공자의 시계 차이. 방금 발급된 토큰이 "아직 유효하지 않음" 으로 떨어지지 않게 한다.
LEEWAY_SECONDS = 60
JWKS_TIMEOUT_SECONDS = 5
JWKS_MIN_TTL = 5 * 60
JWKS_MAX_TTL = 24 * 60 * 60
JWKS_DEFAULT_TTL = 60 * 60
JWKS_MIN_REFETCH = 60

# 이메일을 주지 않은 소셜 계정의 자리 표시 주소. `.invalid` 는 메일이 갈 수 없는 예약
# 도메인이다(RFC 2606). 앱은 이 주소를 화면에 보여 주지 않는다.
PLACEHOLDER_DOMAIN = "social.invalid"
NICKNAME_MAX = 20


# --- 오류 -------------------------------------------------------------------
# 공통 오류 포맷의 `code` 는 예외 클래스의 `default_code` 다(`common.errors`).


class SocialTokenInvalid(APIException):
    # `AuthenticationFailed` 를 쓰지 않는다. 인증 클래스가 없는 뷰에서 DRF 가 403 으로 바꾼다.
    status_code = status.HTTP_401_UNAUTHORIZED
    default_code = "social_token_invalid"
    default_detail = "로그인 정보를 확인할 수 없다. 다시 로그인할 것."


class SocialNotConfigured(APIException):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    default_code = "social_not_configured"
    default_detail = "이 로그인 방법은 아직 설정되지 않았다."


class SocialUnavailable(APIException):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    default_code = "social_unavailable"
    default_detail = "로그인 서버에 연결하지 못했다. 잠시 뒤 다시 시도할 것."


class SocialEmailInUse(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "social_email_in_use"


class SocialTermsRequired(APIException):
    status_code = status.HTTP_400_BAD_REQUEST
    default_code = "social_terms_required"


class SocialAlreadyLinked(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "social_already_linked"


class SocialProviderLinked(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "social_provider_linked"


class SocialLastLoginMethod(APIException):
    status_code = status.HTTP_400_BAD_REQUEST
    default_code = "social_last_login_method"


# --- 제공자 -----------------------------------------------------------------


def _setting_list(name: str) -> tuple[str, ...]:
    """쉼표로 여러 값을 받는다. 개발용·배포용 앱을 따로 등록했을 때 둘 다 넣는다."""
    raw = getattr(settings, name, "") or ""
    return tuple(value.strip() for value in raw.split(",") if value.strip())


def _kakao_email(claims: dict) -> str:
    # 카카오는 유효한 이메일이 있을 때만 `email` 을 싣는다. 인증 여부는 없다(그래서 연결에 안 씀).
    return claims.get("email") or ""


def _google_email(claims: dict) -> str:
    # 문자열 "true" 가 아니라 불리언이어야 한다.
    if claims.get("email_verified") is not True:
        return ""
    return claims.get("email") or ""


@dataclass(frozen=True)
class Provider:
    key: str
    label: str
    jwks_url: str
    issuers: tuple[str, ...]
    audience_setting: str
    email: Callable[[dict], str]
    nickname_claims: tuple[str, ...]

    @property
    def audiences(self) -> tuple[str, ...]:
        return _setting_list(self.audience_setting)


PROVIDERS = {
    "kakao": Provider(
        key=SocialAccount.Provider.KAKAO,
        label="카카오",
        jwks_url="https://kauth.kakao.com/.well-known/jwks.json",
        issuers=("https://kauth.kakao.com",),
        audience_setting="KAKAO_NATIVE_APP_KEY",
        email=_kakao_email,
        nickname_claims=("nickname",),
    ),
    "google": Provider(
        key=SocialAccount.Provider.GOOGLE,
        label="구글",
        jwks_url="https://www.googleapis.com/oauth2/v3/certs",
        issuers=("accounts.google.com", "https://accounts.google.com"),
        audience_setting="GOOGLE_WEB_CLIENT_ID",
        email=_google_email,
        nickname_claims=("name", "given_name"),
    ),
}


@dataclass(frozen=True)
class Identity:
    """검증한 ID 토큰에서 꺼낸 것."""

    provider: Provider
    subject: str
    email: str
    nickname: str


# --- 공개키 -----------------------------------------------------------------

_jwks_lock = threading.Lock()
# jwks_url → (만료 시각, 마지막으로 받은 시각, {kid: PyJWK})
_jwks_cache: dict[str, tuple[float, float, dict]] = {}


def _max_age(response) -> int:
    match = re.search(r"max-age=(\d+)", response.headers.get("Cache-Control", ""))
    seconds = int(match.group(1)) if match else JWKS_DEFAULT_TTL
    return min(JWKS_MAX_TTL, max(JWKS_MIN_TTL, seconds))


def fetch_jwks(url: str) -> tuple[dict, int]:
    """공개키 목록을 받는다. `({kid: PyJWK}, 캐시 초)`. 테스트가 이 함수를 바꿔 끼운다."""
    try:
        response = requests.get(url, timeout=JWKS_TIMEOUT_SECONDS)
        response.raise_for_status()
        keys = jwt.PyJWKSet.from_dict(response.json()).keys
    except (requests.RequestException, ValueError, jwt.PyJWKError, jwt.PyJWKSetError) as exc:
        logger.warning("공개키를 받지 못했다: %s (%s)", url, type(exc).__name__)
        raise SocialUnavailable() from exc
    return {key.key_id: key for key in keys if key.key_id}, _max_age(response)


def _signing_key(provider: Provider, kid: str | None):
    now = time.monotonic()
    with _jwks_lock:
        expires, fetched, keys = _jwks_cache.get(provider.jwks_url, (0.0, -JWKS_MIN_REFETCH, {}))
        stale = now >= expires
        unknown = kid not in keys and now - fetched >= JWKS_MIN_REFETCH
        if stale or unknown:
            keys, ttl = fetch_jwks(provider.jwks_url)
            _jwks_cache[provider.jwks_url] = (now + ttl, now, keys)
    key = keys.get(kid)
    if key is None:
        raise SocialTokenInvalid()
    return key


def clear_jwks_cache() -> None:
    with _jwks_lock:
        _jwks_cache.clear()


# --- 검증 -------------------------------------------------------------------


def verify(provider_name: str, token: str) -> Identity:
    """ID 토큰을 검증한다. 맞지 않으면 `SocialTokenInvalid`."""
    provider = PROVIDERS[provider_name]
    audiences = provider.audiences
    if not audiences:
        raise SocialNotConfigured()
    try:
        header = jwt.get_unverified_header(token)
    except jwt.InvalidTokenError as exc:
        raise SocialTokenInvalid() from exc
    # 알고리즘은 토큰이 아니라 우리가 정한다. `none`·HS256 으로 바꾼 토큰을 받지 않는다.
    if header.get("alg") != "RS256":
        raise SocialTokenInvalid()
    key = _signing_key(provider, header.get("kid"))
    try:
        claims = jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=audiences,
            issuer=provider.issuers,
            leeway=LEEWAY_SECONDS,
            options={"require": ["exp", "iat", "iss", "aud", "sub"]},
        )
    except jwt.InvalidTokenError as exc:
        logger.info("%s ID 토큰 거절: %s", provider.label, type(exc).__name__)
        raise SocialTokenInvalid() from exc

    subject = str(claims.get("sub") or "").strip()
    if not subject or len(subject) > 255:
        raise SocialTokenInvalid()
    return Identity(
        provider=provider,
        subject=subject,
        email=_clean_email(provider.email(claims)),
        nickname=_clean_nickname(*(claims.get(name) for name in provider.nickname_claims)),
    )


def _clean_email(value) -> str:
    email = str(value or "").strip().lower()
    if not email:
        return ""
    try:
        validate_email(email)
    except DjangoValidationError:
        return ""
    return email


def _clean_nickname(*values) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text[:NICKNAME_MAX]
    return ""


# --- 로그인·연결 ------------------------------------------------------------


def login_methods(user) -> list[str]:
    """이 사용자가 로그인할 수 있는 방법. `password`·`kakao`·`google`."""
    methods = ["password"] if user.has_usable_password() else []
    methods += sorted(user.social_accounts.values_list("provider", flat=True))
    return methods


def placeholder_email(identity: Identity) -> str:
    local = re.sub(r"[^0-9A-Za-z]", "", identity.subject)[:64]
    if local != identity.subject:
        local = hashlib.sha256(identity.subject.encode()).hexdigest()[:32]
    return f"{identity.provider.key}-{local}@{PLACEHOLDER_DOMAIN}"


def sign_in(identity: Identity, *, terms_agreed: bool, nickname: str = "") -> tuple[User, bool]:
    """연결된 사용자를 돌려주거나 새로 만든다. `(사용자, 새로 만들었나)`."""
    account = _linked(identity)
    if account is not None:
        return _logged_in(account), False

    if identity.email:
        existing = User.objects.filter(email=identity.email).first()
        if existing is not None:
            raise SocialEmailInUse({
                "email": [
                    f"이 이메일로 가입한 계정이 있다. 그 방법으로 로그인한 뒤 설정에서 "
                    f"{identity.provider.label} 계정을 연결할 것."
                ],
                "login_methods": login_methods(existing),
            })
    if not terms_agreed:
        raise SocialTermsRequired({"terms_agreed": ["약관에 동의해야 가입할 수 있다."]})

    try:
        with transaction.atomic():
            user = User.objects.create_user(
                email=identity.email or placeholder_email(identity),
                password=None,
                nickname=(
                    _clean_nickname(nickname, identity.nickname)
                    or f"{identity.provider.label} 사용자"
                ),
                terms_agreed_at=timezone.now(),
            )
            SocialAccount.objects.create(
                user=user,
                provider=identity.provider.key,
                subject=identity.subject,
                email=identity.email,
                last_login_at=timezone.now(),
            )
    except IntegrityError:
        # 같은 토큰으로 두 요청이 동시에 왔다. 먼저 만든 쪽으로 로그인한다.
        account = _linked(identity)
        if account is None:
            raise
        return _logged_in(account), False
    return user, True


def link(user, identity: Identity) -> SocialAccount:
    """로그인한 사용자에게 소셜 계정을 연결한다. 같은 연결을 다시 보내면 그대로 돌려준다."""
    account = _linked(identity)
    if account is not None:
        if account.user_id == user.pk:
            return account
        raise SocialAlreadyLinked(
            f"이 {identity.provider.label} 계정은 다른 사용자에게 연결돼 있다."
        )
    if user.social_accounts.filter(provider=identity.provider.key).exists():
        raise SocialProviderLinked(
            f"이미 다른 {identity.provider.label} 계정이 연결돼 있다. 먼저 연결을 끊을 것."
        )
    try:
        with transaction.atomic():
            return SocialAccount.objects.create(
                user=user,
                provider=identity.provider.key,
                subject=identity.subject,
                email=identity.email,
            )
    except IntegrityError as exc:
        raise SocialAlreadyLinked(
            f"이 {identity.provider.label} 계정은 다른 사용자에게 연결돼 있다."
        ) from exc


def unlink(user, provider_name: str) -> bool:
    """연결을 끊는다. 연결이 없으면 False. 마지막 로그인 방법이면 끊지 않는다."""
    provider = PROVIDERS[provider_name]
    with transaction.atomic():
        account = (
            user.social_accounts.select_for_update().filter(provider=provider.key).first()
        )
        if account is None:
            return False
        if login_methods(user) == [provider.key]:
            raise SocialLastLoginMethod(
                f"{provider.label} 연결이 유일한 로그인 방법이라 끊을 수 없다."
            )
        account.delete()
    return True


def _linked(identity: Identity) -> SocialAccount | None:
    return (
        SocialAccount.objects.select_related("user")
        .filter(provider=identity.provider.key, subject=identity.subject)
        .first()
    )


def _logged_in(account: SocialAccount) -> User:
    if not account.user.is_active:
        raise SocialTokenInvalid("사용할 수 없는 계정이다.")
    SocialAccount.objects.filter(pk=account.pk).update(last_login_at=timezone.now())
    return account.user
