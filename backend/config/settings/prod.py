"""배포 설정. 현재는 자리만 잡아둔다."""

import os

from .base import *  # noqa: F401,F403

DEBUG = False

ALLOWED_HOSTS = [h for h in os.getenv("DJANGO_ALLOWED_HOSTS", "").split(",") if h]

SECURE_SSL_REDIRECT = True
SESSION_COOKIE_SECURE = True
CSRF_COOKIE_SECURE = True
