"""배포 설정.

팀 공용 서버용이다. 한 사람이 `runserver` 를 띄워 둔 동안만 앱이 동작하는
구조를 벗어나기 위한 것이므로, **혼자 쓰는 개발 설정과 다르게 취급한다.**

- 비어 있으면 안 되는 값은 시작할 때 터뜨린다. 조용히 기본값으로 떨어지면
  SQLite 로 뜬 서버에 팀원들이 각자 계정을 만들고, 그 데이터는 다음 배포에서
  사라진다. 늦게 발견될수록 손해가 크다.
- 로그는 표준출력으로 낸다. PaaS·도커·systemd 가 모두 stdout 을 수집한다.

필수 환경변수:
    DJANGO_SECRET_KEY       개발 키를 재사용하지 않는다
    DATABASE_URL            Postgres. 비우면 시작하지 않는다
    DJANGO_ALLOWED_HOSTS    배포 도메인 (쉼표 구분)
"""

import os

from django.core.exceptions import ImproperlyConfigured

from .base import *  # noqa: F401,F403
from .base import BASE_DIR

DEBUG = False


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ImproperlyConfigured(
            f"환경변수 {name} 가 비어 있다. 배포 설정에서는 기본값으로 떨어지지 않는다."
        )
    return value


# ---------------------------------------------------------------------------
# 시크릿
# ---------------------------------------------------------------------------
# base.py 는 개발 편의를 위해 "dev-only-insecure-key" 로 폴백한다. 배포에서
# 그 값이 쓰이면 세션·토큰 서명을 누구나 위조할 수 있다.
SECRET_KEY = _required("DJANGO_SECRET_KEY")

# ---------------------------------------------------------------------------
# 데이터베이스
# ---------------------------------------------------------------------------
# base.py 는 DATABASE_URL 이 비면 SQLite 로 떨어진다. 그 동작이 배포에서는
# 위험하다 - 컨테이너가 재시작되면 파일이 사라지고, 여러 워커가 같은 파일을
# 잠그며, 팀원들의 데이터가 서로 보이지 않는다.
if not os.getenv("DATABASE_URL", "").strip():
    raise ImproperlyConfigured(
        "배포 설정에는 DATABASE_URL 이 필요하다. "
        "SQLite 로 떨어지면 재배포마다 데이터가 사라진다."
    )

# ---------------------------------------------------------------------------
# 호스트
# ---------------------------------------------------------------------------
ALLOWED_HOSTS = [h.strip() for h in os.getenv("DJANGO_ALLOWED_HOSTS", "").split(",") if h.strip()]

# Render 는 배포 도메인을 이 환경변수로 준다. 손으로 적지 않아도 되게 받는다.
_render_host = os.getenv("RENDER_EXTERNAL_HOSTNAME", "").strip()
if _render_host and _render_host not in ALLOWED_HOSTS:
    ALLOWED_HOSTS.append(_render_host)

if not ALLOWED_HOSTS:
    raise ImproperlyConfigured(
        "DJANGO_ALLOWED_HOSTS 가 비어 있다. 배포 도메인을 넣어야 한다. "
        "비우면 Django 가 모든 요청에 400 DisallowedHost 를 돌려준다."
    )

# Django admin 로그인에 필요하다. HTTPS 로 들어오는 POST 는 Origin 헤더가
# 신뢰 목록에 없으면 CSRF 검증에서 막힌다.
CSRF_TRUSTED_ORIGINS = [f"https://{h}" for h in ALLOWED_HOSTS if not h.startswith(".")]

# ---------------------------------------------------------------------------
# HTTPS
# ---------------------------------------------------------------------------
# **프록시 뒤에 있다는 것을 먼저 알려야 한다.** PaaS 가 TLS 를 종료하고 앱에는
# http 로 넘긴다. 이 헤더 설정 없이 SECURE_SSL_REDIRECT 를 켜면 Django 가
# "http 로 들어왔으니 https 로 보내야 한다" 고 판단해 무한 리다이렉트가 된다.
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")

# 자체 서버(Tailscale 내부 등)에 TLS 없이 띄우는 경우를 위한 탈출구.
# 기본은 켠다 - 앱이 토큰을 평문으로 보내지 않게 하는 것이 기본값이어야 한다.
SECURE_SSL_REDIRECT = os.getenv("DJANGO_SECURE_SSL_REDIRECT", "1") != "0"
SESSION_COOKIE_SECURE = SECURE_SSL_REDIRECT
CSRF_COOKIE_SECURE = SECURE_SSL_REDIRECT

# 상태 점검은 리다이렉트에서 빼 둔다. 플랫폼이 내부에서 http 로 부르는 경우가
# 있고, 301 을 받으면 배포가 실패로 처리된다.
SECURE_REDIRECT_EXEMPT = [r"^api/health/?$"]

SECURE_CONTENT_TYPE_NOSNIFF = True
X_FRAME_OPTIONS = "DENY"

# HSTS 는 도메인이 확정된 뒤에 켠다. 먼저 켜면 브라우저가 https 를 기억해
# 임시 도메인에서 되돌리기 어렵다.
SECURE_HSTS_SECONDS = int(os.getenv("DJANGO_HSTS_SECONDS", "0"))

# ---------------------------------------------------------------------------
# 정적 파일 (admin)
# ---------------------------------------------------------------------------
# DEBUG=False 면 Django 가 정적 파일을 서빙하지 않는다. admin 이 스타일 없이
# 뜨는 것을 막으려고 whitenoise 를 넣는다. 앞단에 nginx 가 있어도 무해하다.
STATIC_ROOT = BASE_DIR / "staticfiles"
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {
        "BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage",
    },
}

# SecurityMiddleware 바로 뒤에 와야 한다. 순서를 바꾸면 정적 파일 응답에
# 보안 헤더가 붙지 않는다.
_security_index = MIDDLEWARE.index("django.middleware.security.SecurityMiddleware")  # noqa: F405
MIDDLEWARE.insert(_security_index + 1, "whitenoise.middleware.WhiteNoiseMiddleware")  # noqa: F405

# ---------------------------------------------------------------------------
# CORS
# ---------------------------------------------------------------------------
# 안드로이드 클라이언트는 CORS 영향을 받지 않는다(브라우저 전용 메커니즘).
# admin 과 향후 웹 대시보드용으로만 좁게 허용한다. 전체 허용을 두지 않는다.
CORS_ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv("DJANGO_CORS_ORIGINS", "").split(",")
    if origin.strip()
]

# ---------------------------------------------------------------------------
# 로그
# ---------------------------------------------------------------------------
# 파일에 쓰지 않는다. 컨테이너는 재시작하면 파일이 사라지고, 플랫폼 로그
# 수집기는 stdout 만 본다.
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {
        "plain": {
            "format": "{levelname} {asctime} {name} {message}",
            "style": "{",
        },
    },
    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "formatter": "plain",
        },
    },
    "root": {
        "handlers": ["console"],
        "level": os.getenv("DJANGO_LOG_LEVEL", "INFO"),
    },
    "loggers": {
        # 요청 실패를 삼키지 않는다. 팀원이 "로그인이 안 된다" 고 할 때
        # 서버 로그에 근거가 남아야 한다.
        "django.request": {
            "handlers": ["console"],
            "level": "WARNING",
            "propagate": False,
        },
        # 카카오 호출 실패는 degraded 로 조용히 넘어간다(clients._get).
        # 경로가 안 나오는 원인을 추적할 수 있게 로그는 남긴다.
        "apps.routing.clients": {
            "handlers": ["console"],
            "level": "INFO",
            "propagate": False,
        },
    },
}
