"""개발 설정. 에뮬레이터 접근을 전제한다."""

from .base import *  # noqa: F401,F403

DEBUG = True

# 에뮬레이터는 호스트 PC 의 127.0.0.1 을 10.0.2.2 로 본다.
#
# 주의: ALLOWED_HOSTS 가 비어 있을 때만 Django 가 DEBUG 에서 localhost 를 자동
# 허용한다. 10.0.2.2 를 넣는 순간 그 자동 허용이 사라지므로 localhost 와
# 127.0.0.1 을 반드시 함께 명시해야 한다. 이걸 빼면 호스트에서 curl 이 400 을 받는다.
#
# 실기기 테스트 시 개발 PC 의 LAN IP 를 여기에 추가한다.
ALLOWED_HOSTS = ["10.0.2.2", "localhost", "127.0.0.1"]

# Android 클라이언트는 CORS 영향을 받지 않는다. 브라우저 전용 메커니즘이기 때문이다.
# admin 과 향후 웹 대시보드용으로만 좁게 허용하고 전체 허용은 두지 않는다.
CORS_ALLOWED_ORIGINS = [
    "http://localhost:8000",
    "http://127.0.0.1:8000",
]
