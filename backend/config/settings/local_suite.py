"""일회용 HTTP 회귀 서버: 대량 가입만 허용하고 나머지는 개발 설정을 쓴다."""

from .dev import *  # noqa: F403

REST_FRAMEWORK = {  # noqa: F405
    **REST_FRAMEWORK,  # noqa: F405
    "DEFAULT_THROTTLE_RATES": {
        **REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"],  # noqa: F405
        "register": None,
    },
}
