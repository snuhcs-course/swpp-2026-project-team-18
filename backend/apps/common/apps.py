# Partly AI-generated (Claude)
from django.apps import AppConfig


class CommonConfig(AppConfig):
    """공통 오류 형식·헬스 체크·API 요청 기록(db-schema.md A4).

    모델이 생기면서 `INSTALLED_APPS` 에 들어갔다. 그 전에는 모듈만 있는 패키지였다.
    """

    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.common"
    verbose_name = "8. 운영"

    def ready(self):
        _use_throttle_cache()


THROTTLE_CACHE_ALIAS = "throttle"


def _use_throttle_cache() -> None:
    """모든 DRF 스로틀이 `CACHES["throttle"]` 에 기록을 두게 한다(iter4 X-1).

    DRF 는 스로틀 캐시를 고르는 설정이 없다 — `SimpleRateThrottle.cache` 클래스 속성이 기본
    캐시다. 뷰마다 `ScopedRateThrottle` 을 직접 쓰고 하위 클래스도 여럿이라(`RouteLiveThrottle`,
    `PasswordResetEmailThrottle` …) 기반 클래스 속성 하나를 바꿔 한 번에 옮긴다. 하위 클래스가
    `cache` 를 따로 두면 그 값이 이긴다. 별칭이 없는 설정에서는 기본 캐시를 그대로 쓴다.

    `ConnectionProxy` 라 스레드마다 `caches["throttle"]` 을 그때 찾는다(`django.core.cache.cache`
    와 같다). `override_settings(CACHES=...)` 도 따른다. (Partly AI-generated (Claude))
    """
    from django.conf import settings
    from django.core.cache import caches
    from django.utils.connection import ConnectionProxy
    from rest_framework.throttling import SimpleRateThrottle

    if THROTTLE_CACHE_ALIAS in settings.CACHES:
        SimpleRateThrottle.cache = ConnectionProxy(caches, THROTTLE_CACHE_ALIAS)
