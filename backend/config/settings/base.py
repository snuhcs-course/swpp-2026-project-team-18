"""
공통 설정. 환경별 차이는 dev.py / prod.py 에 둔다.

back-spec.md 3절 기준.
"""

from pathlib import Path

import dj_database_url
from dotenv import load_dotenv
import os

# backend/config/settings/base.py → backend/
BASE_DIR = Path(__file__).resolve().parent.parent.parent

load_dotenv(BASE_DIR / ".env")

SECRET_KEY = os.getenv("DJANGO_SECRET_KEY", "dev-only-insecure-key")

DEBUG = False
ALLOWED_HOSTS: list[str] = []

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    # 3rd party
    "rest_framework",
    "corsheaders",
    # local
    # back-spec.md 2절은 앱 12개를 정의하지만, P0~P1 에 필요한 3개로 시작한다.
    # 나머지(routines, observations, routing, weather, prediction, rooms,
    # reports, nlp, push)는 해당 페이즈에서 추가한다.
    "apps.accounts",
    "apps.events",
    "apps.planning",
    # 카카오 경로·로컬 검색 클라이언트. 모델이 없고 clients.py 만 있다.
    "apps.routing",
]

MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"
ASGI_APPLICATION = "config.asgi.application"

# ---------------------------------------------------------------------------
# 데이터베이스
# ---------------------------------------------------------------------------
# back-spec.md 논의 1번: Postgres 를 목표로 두되 초기에는 SQLite 로 간다.
# 팀원 전원이 도커 없이 개발할 수 있어야 하고, DATABASE_URL 하나로 전환된다.
# Postgres 고유 기능(JSONB 인덱스, ArrayField)을 쓰지 않는 한 마이그레이션도 그대로 돈다.
# dj_database_url.config() 를 쓰지 않는다. 그 함수는 DATABASE_URL 이 "빈 문자열로
# 존재"하는 경우를 값이 있는 것으로 보고 default 를 무시해 DATABASES={} 를 돌려준다.
# .env.example 에 `DATABASE_URL=` 를 자리만 잡아둔 상태로 복사하면 그대로 터진다.
# 빈 문자열을 명시적으로 걸러 SQLite 로 떨어뜨린다.
_DATABASE_URL = os.getenv("DATABASE_URL", "").strip()

DATABASES = {
    "default": dj_database_url.parse(
        _DATABASE_URL or f"sqlite:///{BASE_DIR / 'db.sqlite3'}",
        conn_max_age=600,
    )
}

# ---------------------------------------------------------------------------
# 인증
# ---------------------------------------------------------------------------
# back-spec.md 4.1 은 "Django 기본"으로 적었으나 커스텀 User 로 바꿨다.
# 로그인 식별자가 이메일이고, 내장 User 의 email 은 unique 가 아니다.
# 근거는 apps/accounts/models.py 상단에 있다.
AUTH_USER_MODEL = "accounts.User"

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

# ---------------------------------------------------------------------------
# 국제화
# ---------------------------------------------------------------------------
LANGUAGE_CODE = "ko-kr"
TIME_ZONE = "Asia/Seoul"
USE_I18N = True

# 시각은 UTC 로 저장하고 API 는 ISO 8601 UTC 로 내린다. 로컬 변환은 클라이언트 몫이다.
USE_TZ = True

STATIC_URL = "static/"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# ---------------------------------------------------------------------------
# DRF
# ---------------------------------------------------------------------------
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": (
        "rest_framework_simplejwt.authentication.JWTAuthentication",
    ),
    "DEFAULT_PERMISSION_CLASSES": ("rest_framework.permissions.IsAuthenticated",),
    "DEFAULT_RENDERER_CLASSES": ("rest_framework.renderers.JSONRenderer",),
    "DEFAULT_THROTTLE_RATES": {"route": "120/hour", "nlp": "30/hour"},
    # back-spec.md 5절 공통 에러 포맷 {"error": {code, message, details}}
    "EXCEPTION_HANDLER": "apps.common.errors.api_exception_handler",
    "DEFAULT_PAGINATION_CLASS": "rest_framework.pagination.LimitOffsetPagination",
    "PAGE_SIZE": 20,
}

from datetime import timedelta  # noqa: E402

SIMPLE_JWT = {
    "ACCESS_TOKEN_LIFETIME": timedelta(minutes=30),
    "REFRESH_TOKEN_LIFETIME": timedelta(days=14),
}

# ---------------------------------------------------------------------------
# 외부 API 키
# ---------------------------------------------------------------------------
KAKAO_REST_API_KEY = os.getenv("KAKAO_REST_API_KEY", "")
KAKAO_MOBILITY_KEY = os.getenv("KAKAO_MOBILITY_KEY", "")
KMA_API_KEY = os.getenv("KMA_API_KEY", "")

# F12 자연어 파싱. Structured Outputs(strict) 를 쓰므로 모델도 설정으로 뺀다.
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "")

FCM_CREDENTIALS_PATH = os.getenv("FCM_CREDENTIALS_PATH", "")
REDIS_URL = os.getenv("REDIS_URL", "")

# /api/health 가 반환하는 값. 클라이언트가 서버 버전을 확인하는 데 쓴다.
APP_VERSION = "0.1.0"
