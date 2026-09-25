"""테스트 설정.

## 이 파일이 존재하는 이유는 안전이다

`dev.py` 로 테스트를 돌리면 `DATABASE_URL` 을 그대로 따른다. `backend/.env` 에
공용 Neon 주소가 들어 있는 상태로 `pytest` 를 실행하면 **pytest-django 가
공용 DB 에 붙어 테스트용 데이터베이스를 만들고 지운다.** 팀 전체가 쓰는 DB 다.

그래서 여기서 `DATABASES` 를 **무조건** 덮어쓴다. 환경변수를 읽지 않는다.
읽으면 "어떤 환경에서는 안전하다" 가 되고, 그 예외가 사고가 된다.

## 외부 API 를 부르지 못하게 한다

키를 빈 문자열로 덮는다. 테스트가 실수로 카카오·OpenAI 를 부르면

  - 무료 쿼터를 테스트가 먹는다 (카카오 일 1,000건)
  - OpenAI 는 선불 잔액이 실제로 줄어든다
  - CI 가 네트워크 상태에 따라 깜빡인다

키가 없으면 클라이언트가 `degraded` 로 떨어지므로, 외부 의존 코드 경로도
"키 없음" 분기로 테스트된다. 실제 호출이 필요한 확인은
`scripts/check_external_apis.py` 가 담당한다.
"""

from .base import *  # noqa: F401,F403

# 테스트는 DEBUG=False 로 돈다. DEBUG=True 면 일부 미들웨어·에러 처리가
# 달라져서 운영에서만 나는 버그를 테스트가 못 잡는다.
DEBUG = False

ALLOWED_HOSTS = ["testserver", "localhost", "127.0.0.1"]

# ---------------------------------------------------------------------------
# DB — 환경변수를 무시하고 메모리 SQLite 로 고정한다
# ---------------------------------------------------------------------------
# 위 docstring 의 안전 규칙. 절대 os.getenv 로 바꾸지 않는다.
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": ":memory:",
        "TEST": {"NAME": ":memory:"},
    }
}

# ---------------------------------------------------------------------------
# 외부 키 차단
# ---------------------------------------------------------------------------
KAKAO_REST_API_KEY = ""
KAKAO_MOBILITY_KEY = ""
KMA_API_KEY = ""
OPENAI_API_KEY = ""
OPENAI_MODEL = ""
FCM_CREDENTIALS_PATH = ""
REDIS_URL = ""
# 실시간 도착 정보. 열린데이터광장은 일일 호출 한도가 있어서 테스트가 태우면
# 정작 기기에서 확인할 때 막힌다.
SEOUL_BUS_API_KEY_ENCODING = ""
SEOUL_BUS_API_KEY_DECODING = ""
SEOUL_SUBWAY_API_KEY = ""

# ---------------------------------------------------------------------------
# 속도
# ---------------------------------------------------------------------------
# 기본 해셔(PBKDF2, 60만 회 반복)는 계정을 만드는 테스트마다 수백 ms 를 먹는다.
# 테스트에서 검증하려는 것은 "비밀번호가 맞는지" 이고 해싱 강도가 아니다.
PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]

# 마이그레이션을 전부 돌리는 대신 스키마를 직접 만들면 빠르지만, 그러면
# 마이그레이션 자체의 결함(제약 누락 등)을 테스트가 못 잡는다. 지금 규모에서는
# 전부 돌려도 몇 초라 그대로 둔다.

# 스로틀은 기본적으로 끈다. 테스트가 연속 호출을 하므로 429 가 섞이면
# 무관한 실패가 난다. 스로틀 자체를 확인하는 테스트는 필요한 클래스만
# 개별로 되살린다.
REST_FRAMEWORK = {  # noqa: F405
    **REST_FRAMEWORK,  # noqa: F405
    "DEFAULT_THROTTLE_RATES": {
        "route": None,
        "route_live": None,
        "nlp": None,
        "observation": None,
    },
}

# 로그를 조용히 한다. 실패 원인은 assert 메시지에서 본다.
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"null": {"class": "logging.NullHandler"}},
    "root": {"handlers": ["null"], "level": "CRITICAL"},
}
