"""설정 회귀 테스트.

값 하나가 빠져서 운영에서만 나는 장애가 있었다. 그런 설정은 테스트로 고정한다.
"""

from __future__ import annotations

import importlib

import pytest


class TestPersistentConnectionSafety:
    """`CONN_HEALTH_CHECKS` 가 꺼지면 산발적 500 이 돌아온다.

    ## 실제로 있었던 장애

    `conn_max_age=600` 은 연결을 10분간 재사용한다. 그런데 Neon 무료 티어는
    유휴 시 컴퓨트를 중단하고(scale to zero) 그러면 Django 가 들고 있던 연결이
    죽는다. 다음 요청이 그 죽은 연결로 쿼리를 던져 `OperationalError` → 500 이
    되고, Django 가 연결을 버린 뒤 **그 다음** 요청은 성공한다.

    증상이 "가끔 500, 새로고침하면 정상" 이라 원인을 짐작하기 어려웠다.
    `/api/health` 는 DB 를 쓰지 않아 200 이라 더 헷갈렸다. 배포 코드를 의심해
    시간을 썼다.

    Neon 에 직접 붙여 재현했다.

        CONN_HEALTH_CHECKS = False -> OperationalError
        CONN_HEALTH_CHECKS = True  -> 조용히 재연결, 성공
    """

    def test_health_checks_are_enabled_in_base_settings(self):
        """테스트 설정은 메모리 SQLite 를 강제하므로 base 를 직접 확인한다."""
        base = importlib.import_module("config.settings.base")
        db = base.DATABASES["default"]
        assert db.get("CONN_HEALTH_CHECKS") is True, (
            "CONN_HEALTH_CHECKS 가 꺼져 있다. Neon 이 컴퓨트를 중단하면 "
            "죽은 연결을 재사용해 산발적으로 500 이 난다."
        )

    def test_persistent_connections_are_on(self):
        """연결 재사용 자체는 필요하다. 매 요청 연결하면 지연이 커진다."""
        base = importlib.import_module("config.settings.base")
        db = base.DATABASES["default"]
        assert db.get("CONN_MAX_AGE", 0) > 0

    def test_health_checks_required_whenever_connections_persist(self):
        """둘은 함께 가야 한다. 재사용하면서 확인하지 않으면 그게 장애다."""
        base = importlib.import_module("config.settings.base")
        db = base.DATABASES["default"]
        if db.get("CONN_MAX_AGE", 0):
            assert db.get("CONN_HEALTH_CHECKS") is True


class TestTestSettingsProtectTheSharedDatabase:
    """테스트가 공용 DB 를 건드리지 못하게 한 것을 고정한다.

    실제로 검증 스크립트가 공용 Neon 에 테스트 계정 21개를 만든 사고가 있었다.
    `config/settings/test.py` 가 `DATABASE_URL` 을 무시하고 메모리 SQLite 를
    강제한다. 그 보호가 실수로 풀리면 다음 사고는 테스트가 낸다.
    """

    def test_database_is_in_memory_sqlite(self, settings):
        """이름 문자열이 아니라 **속성**을 검사한다.

        Django 는 테스트 실행 중 `:memory:` 를
        `file:memorydb_default?mode=memory&cache=shared` 로 바꾼다(스레드 간
        공유를 위해). 문자열을 비교하면 Django 구현에 묶이므로, 확인해야 하는
        것만 확인한다 — SQLite 인가, 메모리인가, 원격 호스트가 아닌가.
        """
        db = settings.DATABASES["default"]
        assert "sqlite" in db["ENGINE"]
        assert "memory" in str(db["NAME"]), f"디스크 파일이다: {db['NAME']}"
        assert not db.get("HOST"), f"원격 호스트가 붙었다: {db.get('HOST')}"
        assert not db.get("PORT")

    @pytest.mark.parametrize(
        "key",
        [
            "KAKAO_REST_API_KEY",
            "KAKAO_MOBILITY_KEY",
            "KMA_API_KEY",
            "OPENAI_API_KEY",
            "FCM_CREDENTIALS_PATH",
            # 실시간 도착 정보. 열린데이터광장은 일일 호출 한도가 있어서
            # 테스트가 태우면 정작 기기에서 확인할 때 막힌다.
            "SEOUL_BUS_API_KEY_ENCODING",
            "SEOUL_BUS_API_KEY_DECODING",
            "SEOUL_SUBWAY_API_KEY",
        ],
    )
    def test_external_keys_are_blank(self, settings, key):
        """테스트가 외부 API 를 부르면 쿼터와 선불 잔액을 먹는다."""
        assert getattr(settings, key) == ""

    def test_debug_is_off(self, settings):
        """DEBUG=True 면 일부 에러 처리가 달라져 운영 버그를 못 잡는다."""
        assert settings.DEBUG is False


class TestThrottleRatesAreDefined:
    def test_scopes_used_by_views_exist(self, settings):
        """뷰가 쓰는 scope 가 설정에 없으면 DRF 가 예외를 던진다."""
        rates = settings.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
        for scope in ("route", "static_map", "observation", "nlp"):
            assert scope in rates, f"{scope} 가 DEFAULT_THROTTLE_RATES 에 없다"


class TestTimezone:
    def test_stores_utc_and_displays_kst(self, settings):
        """시각은 UTC 로 저장하고 KST 로 보여준다. 둘이 섞이면 9시간 어긋난다."""
        assert settings.USE_TZ is True
        assert settings.TIME_ZONE == "Asia/Seoul"


class TestStaticMapDailyBudget:
    def test_default_leaves_headroom_below_kakao_quota(self, settings):
        assert settings.STATIC_MAP_DAILY_UPSTREAM_LIMIT == 900


class TestProductionCacheIsSharedBetweenWorkers:
    """워커가 캐시를 나눠 쓰지 못하면 카카오 하루 한도가 절반이 된다.

    ## 실제로 있었던 일

    `CACHES` 를 아예 두지 않아 Django 기본값인 `LocMemCache` 가 쓰였다. 그것은
    **프로세스마다 따로** 있고 `render.yaml` 이 `WEB_CONCURRENCY=2` 다. 배포
    서버에 같은 지도를 연속으로 두 번 요청했더니 둘 다
    `X-Jit-Map-Cache: miss` 였다(`jit-tools/check_deployed_new.py`).

    정적 지도는 하루 1,000건이고 지도를 한 번 끌면 여러 장을 부른다. 캐시가
    절반만 들으면 쓸 수 있는 한도도 절반이 된다. 지하철 20초·버스 15초·정류소
    24시간 캐시도 같은 이유로 절반만 듣고 있었다.

    설정이 **없어서** 난 문제이므로, 없어지면 다시 난다. 그래서 고정한다.
    """

    @pytest.fixture
    def prod(self, monkeypatch):
        # prod.py 는 비어 있으면 안 되는 값을 import 시점에 터뜨린다.
        monkeypatch.setenv("DJANGO_SECRET_KEY", "test-only-not-a-real-key")
        monkeypatch.setenv("DATABASE_URL", "postgres://u:p@127.0.0.1:5432/db")
        monkeypatch.setenv("DJANGO_ALLOWED_HOSTS", ".onrender.com")
        module = importlib.import_module("config.settings.prod")
        return importlib.reload(module)

    def test_cache_backend_is_not_per_process(self, prod):
        backend = prod.CACHES["default"]["BACKEND"]
        assert "locmem" not in backend.lower(), (
            "LocMemCache 는 프로세스마다 따로다. 워커가 2개면 캐시가 절반만 듣고 "
            "카카오 하루 한도도 절반이 된다."
        )

    def test_cache_has_a_location_workers_can_share(self, prod):
        config = prod.CACHES["default"]
        assert config.get("LOCATION"), "공유할 자리가 없으면 워커마다 따로 만든다"

    def test_cache_entry_count_is_bounded(self, prod):
        """지도 한 장이 수십~수백 KB 다. 상한이 없으면 임시 디스크를 채운다."""
        options = prod.CACHES["default"].get("OPTIONS", {})
        assert options.get("MAX_ENTRIES", 0) > 0

    def test_static_map_budget_cache_is_shared_and_isolated(self, prod):
        image_cache = prod.CACHES["default"]
        budget_cache = prod.CACHES["static_map_budget"]

        assert "locmem" not in budget_cache["BACKEND"].lower()
        assert budget_cache["LOCATION"] != image_cache["LOCATION"]
        assert budget_cache["TIMEOUT"] > 24 * 60 * 60
        assert budget_cache["OPTIONS"]["MAX_ENTRIES"] > 0

    def test_importing_prod_does_not_mutate_shared_middleware(self, prod):
        """`from .base import *` 는 **같은 리스트 객체**를 가져온다.

        복사하지 않고 insert 하면 prod 를 import 한 것만으로 base 의
        MIDDLEWARE 가 바뀌고, 같은 리스트를 쓰는 test 설정에도 whitenoise 가
        끼어든다. 이 테스트가 그 경로를 막는다.
        """
        base = importlib.import_module("config.settings.base")
        assert "whitenoise.middleware.WhiteNoiseMiddleware" not in base.MIDDLEWARE
        assert "whitenoise.middleware.WhiteNoiseMiddleware" in prod.MIDDLEWARE
