"""예약 학습 전용. 명시한 Postgres에만 연결하고 웹서버 서명 키는 공유하지 않는다."""

import os
import secrets

from django.core.exceptions import ImproperlyConfigured

if not os.environ.get("DATABASE_URL", "").strip().startswith(("postgres://", "postgresql://")):
    raise ImproperlyConfigured("예약 학습에는 명시적인 Postgres DATABASE_URL이 필요합니다.")

from .base import *  # noqa: E402,F401,F403

DEBUG = False
SECRET_KEY = secrets.token_urlsafe(48)
