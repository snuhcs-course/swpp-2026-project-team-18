"""
공통 설정. 환경별 차이는 dev.py / prod.py 에 둔다.

back-spec.md 3절 기준.
"""

from pathlib import Path

import dj_database_url
from dotenv import load_dotenv
import os
import tempfile

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
    # back-spec.md 2절은 앱 12개를 정의하지만 필요한 것부터 추가한다.
    # 나머지(weather, rooms, nlp, push)는 해당 페이즈에서 붙인다.
    "apps.accounts",
    "apps.events",
    "apps.planning",
    # 앱이 GPS 로 판별한 실제 출발·도착 시각. 분포 학습의 재료다.
    "apps.observations",
    # 아침 루틴 블록과 블록별 관측. 준비 시간 분포의 재료다.
    "apps.routines",
    # 카카오 경로·로컬 검색 클라이언트 + 경로 보정 계수(RouteCorrection).
    "apps.routing",
    # 학습 결과 아티팩트. 전역·개인 분포 모수를 담는다.
    "apps.prediction",
    # 주간 리포트·캘리브레이션. **모델이 없다** — 기존 관측에서 요청 시점에
    # 계산한다. 앱으로 등록하는 것은 위치를 명시하기 위한 것이고, 나중에
    # 배치 생성이 필요해지면 여기에 모델이 생긴다.
    "apps.reports",
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
# DB 는 `DATABASE_URL` 하나로 결정된다.
#
# **팀은 Neon Postgres 하나를 쓴다.** 로컬 SQLite 를 개발용으로 두지 않는다 —
# 두면 로컬 데이터와 팀 데이터가 갈라지고 "지금 어느 DB 인가" 가 매번 질문이 된다.
# 어느 설정 모듈도 조용히 로컬 DB 로 떨어지지 않는다.
#
#   config.settings.dev   비어 있으면 **시작을 거부한다**
#   config.settings.prod  비어 있으면 **시작을 거부한다**
#   config.settings.test  DATABASES 를 메모리 SQLite 로 **무조건 덮어쓴다**
#
# 그래서 아래 기본값은 실제로 도달하지 않는다. 그럼에도 남겨 두는 이유는
# `dj_database_url.parse("")` 가 예외를 던져서, 설정 모듈을 새로 추가한 사람이
# 원인을 알기 어려운 스택 트레이스를 보게 되기 때문이다. 도달하더라도 저장소에
# 파일을 만들지 않도록 **임시 폴더**를 쓴다.
#
# dj_database_url.config() 는 쓰지 않는다. 그 함수는 DATABASE_URL 이 "빈 문자열로
# 존재"하는 경우를 값이 있는 것으로 보고 default 를 무시해 DATABASES={} 를 돌려준다.
_DATABASE_URL = os.getenv("DATABASE_URL", "").strip()

_UNREACHABLE_FALLBACK = (
    "sqlite:///" + str(Path(tempfile.gettempdir()) / "jit_unconfigured.sqlite3").replace("\\", "/")
)

DATABASES = {
    "default": dj_database_url.parse(
        _DATABASE_URL or _UNREACHABLE_FALLBACK,
        conn_max_age=600,
        # **죽은 연결을 재사용하지 않게 한다. 이게 없으면 산발적으로 500 이 난다.**
        #
        # conn_max_age=600 은 연결을 10분간 재사용한다. 그런데 Neon 무료 티어는
        # 유휴 시 컴퓨트를 중단하고(scale to zero), 그러면 Django 가 들고 있던
        # 연결이 죽는다. 다음 요청이 그 죽은 연결로 쿼리를 던져 OperationalError
        # 로 500 이 되고, Django 가 연결을 버린 뒤 **그 다음** 요청은 성공한다.
        #
        # 그래서 증상이 "가끔 500, 새로고침하면 정상" 이다. 원인을 짐작하기
        # 어렵고 /api/health 는 DB 를 쓰지 않아 200 이라 더 헷갈린다. 실제로
        # 로그인·가입이 간헐적으로 500 이 나 배포 코드를 의심했다.
        #
        # CONN_HEALTH_CHECKS 는 재사용 직전에 연결이 살아 있는지 확인하고
        # 죽었으면 조용히 다시 연결한다(Django 4.1+). 비용은 요청당 가벼운
        # 핑 한 번이고, 그 대가로 사용자가 보는 500 이 사라진다.
        conn_health_checks=True,
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
    # observation 은 외부 API 를 부르지 않으므로 넉넉하다. 한 아침에 출발·도착
    # 2건이지만 오프라인 큐가 재전송하면 같은 건이 여러 번 올 수 있다.
    "DEFAULT_THROTTLE_RATES": {
        "route": "120/hour",
        # 이동 중 1분마다 부르는 경로 조회(`RouteLiveView`). 정상 사용이 시간당
        # 60회라 `route` 와 같은 통을 쓰면 다른 경로 기능이 굶는다. 통을 나눠
        # 폭주가 재계산·장소 검색까지 막지 못하게 한다.
        #
        # **이 값이 카카오 쿼터를 지키는 장치는 아니다.** 80 × 24 = 1,920 으로
        # 하루 1,000건을 넘는다. 쿼터를 지키는 것은 앱 쪽의 "움직인 거리가 적으면
        # 건너뛴다" 규칙이고(`LiveRouteDecision`), 이 값은 한 계정의 폭주 폭을
        # 묶는 용도다.
        "route_live": "80/hour",
        "nlp": "30/hour",
        "observation": "600/hour",
    },
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

# ---------------------------------------------------------------------------
# 실시간 도착 정보
# ---------------------------------------------------------------------------
#
# **카카오 경로 API 에는 실시간 정보가 없다.** 응답의 step 속성은 distance,
# guidance, stops, time, type, vehicles 뿐이고 실시간 지연·배차 간격이 전혀
# 없다(checklist "API 호출 검증 결과", BE-P0-06). 정류장에 차가 언제 오는지를
# 보여 주려면 별도 데이터원이 필요해서 둘을 더 쓴다.
#
# 두 키 모두 **비어 있어도 동작한다.** 그때는 도착 시간 줄이 뜨지 않고
# 체크포인트 목록만 보인다. 키가 없다고 경로 조회가 실패하면 안 된다.

# 서울시 버스도착정보 (공공데이터포털 data.go.kr).
#
# 서비스는 서울시 자체 서버(ws.bus.go.kr)가 한다 — 포털의 End Point 칸이 비어
# 있는 이유다. **승인 후 그 서버로 전파되는 데 시간이 걸린다**(몇 시간~익일).
# 전파 전에는 401 "등록되지 않은 서비스키" 가 온다.
#
# 정류소 ARS 번호가 필요한데 카카오는 정류소 **이름만** 준다. 좌표로 근처
# 정류소를 찾아 번호를 얻는 단계가 한 번 더 들어간다.
#
# ## 왜 키를 두 개 두는가
#
# data.go.kr 은 같은 키를 **Encoding 본과 Decoding 본** 두 가지로 보여 준다.
# Decoding 본이 원문이고 Encoding 본은 그것을 URL 인코딩한 것이다. 포털도
# "둘을 적용해 보고 구동되는 키를 쓰라" 고 안내한다 — 서비스마다 어느 쪽을
# 받는지가 다르기 때문이다.
#
# 키에 `+` `/` `=` 가 없으면 두 값이 같아서 아무 쪽이나 통한다. 그런데 있으면
# **한쪽만 통하고 다른 쪽은 401 이 난다.** 그때 "키가 아직 전파되지 않았다" 와
# 구분이 안 돼서 원인을 엉뚱한 데서 찾게 된다. 그래서 둘을 따로 받아 두고
# 클라이언트가 순서대로 시도한다.
SEOUL_BUS_API_KEY_ENCODING = os.getenv("SEOUL_BUS_API_KEY_ENCODING", "")
SEOUL_BUS_API_KEY_DECODING = os.getenv("SEOUL_BUS_API_KEY_DECODING", "")

# 서울시 지하철 실시간 도착정보 (서울 열린데이터광장).
#
# 버스보다 조건이 좋다 — **역 이름으로 바로 조회된다.** 카카오가 주는
# stops[0] 을 그대로 넣을 수 있어 ID 해석이 필요 없고, barvlDt 가 초 단위라
# "3분 30초 뒤" 를 파싱 없이 만든다.
#
# 주의: 한 역의 응답에 **여러 노선과 양방향이 섞여** 온다. subwayId(노선 코드)
# 와 trainLineNm(방면)으로 두 겹으로 걸러야 한다. 안 걸르면 반대 방향 열차
# 시각을 보여 준다.
SEOUL_SUBWAY_API_KEY = os.getenv("SEOUL_SUBWAY_API_KEY", "")

# F12 자연어 파싱. Structured Outputs(strict) 를 쓰므로 모델도 설정으로 뺀다.
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "")

FCM_CREDENTIALS_PATH = os.getenv("FCM_CREDENTIALS_PATH", "")
REDIS_URL = os.getenv("REDIS_URL", "")

# /api/health 가 반환하는 값. 클라이언트가 서버 버전을 확인하는 데 쓴다.
#
# **배포할 때마다 올린다.** 이 값이 고정이면 "새 코드가 떴는지" 를 알 방법이
# 없다. 실제로 그 때문에 막혔다 — 마이그레이션은 적용됐는데 코드는 구버전이라
# INSERT 가 500 나는 상태였고, /api/health 는 200 이고 version 도 그대로여서
# 원인을 좁히는 데 시간이 걸렸다.
#
#   0.1.0  P1 — 고정 규칙 알람
#   0.2.0  P2 — 분포 기반 확신도 알람, 루틴 블록, 경로 보정
#   0.3.0  프로토타입 완성 — 캘린더 가져오기, 주간 리포트·캘리브레이션
#   0.4.0  경로 구간·체크포인트와 버스·지하철 실시간 도착정보
#   0.5.0  장소 검색·정적 지도, 고른 경로의 폴리라인 보존(route_path)
#   0.6.0  집에서 출발하지 않는 일정의 준비 시간 제외, 재계산 throttle,
#          JSON 기본값이 문자열로 들어가던 결함 수정
#   0.7.0  지금 더 빠른 대안 경로를 알람 계획에 함께 담는다(alt_route_*).
#          고른 경로는 바꾸지 않고 앱이 지도에 겹쳐 보여 준다
#   0.8.0  이동 중 현재 위치부터 목적지까지의 최단 경로를 1분마다 다시 계산한다.
#          앱이 백그라운드여도 추적 서비스가 경로를 최신 상태로 유지한다
#
# 버전만으로 판별하지 않는 것이 더 확실하다. `scripts/check_deployed.py` 와
# 같은 방식으로 **새 엔드포인트의 404 여부**를 보면 버전을 올리는 것을
# 잊었더라도 드러난다 — 인증이 필요한 경로는 배포됐으면 401, 미배포면 404 다.
APP_VERSION = "0.8.0"
