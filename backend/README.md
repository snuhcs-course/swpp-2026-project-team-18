# JustInTime — Backend (Django)

설계 문서는 Wiki 의 `back-spec` 을 본다. 이 파일은 실행과 현재 상태만 다룬다.

## 실행

```powershell
cd backend

# 최초 1회
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
Copy-Item .env.example .env      # 그리고 값 채우기
.\.venv\Scripts\python.exe manage.py migrate

# 실행 — 반드시 0.0.0.0 이어야 에뮬레이터가 접근한다
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

`127.0.0.1:8000` 으로 띄우면 호스트에서는 되지만 **에뮬레이터에서 연결 실패**한다.

데모 계정 `demo@demo.com` / `demo1234` (`DEBUG=True` 일 때만 마이그레이션으로 생성).
관리자 화면 `http://127.0.0.1:8000/admin/`.

## 구성

| 항목 | 값 |
| --- | --- |
| Python | 3.12.10 |
| Django | 5.2.16 |
| DRF | 3.18.1 |
| SimpleJWT | 5.3.1 — access 30분 / refresh 14일 |
| DB | SQLite. `DATABASE_URL` 로 Postgres 전환 |
| Celery | 없음. P2 에 도입 |

```
apps/
├── accounts/    커스텀 User(email 로그인) + Profile(집 위치·준비시간·기본 τ)
├── events/      Place(전역 공유) · EventTag(6종 시드) · Event(사용자별)
├── planning/    AlarmPlan — 알람 계산 결과
├── routing/     카카오 클라이언트 (경로·장소검색)
└── common/      health, 공통 에러 포맷
```

미작성: `routines`, `observations`, `weather`, `prediction`, `rooms`, `reports`,
`nlp`, `push`. 해당 페이즈에서 추가한다.

## 엔드포인트

```
GET   /api/health                     인증 없음. 연결 진단용

POST  /api/auth/register              회원가입 (201, access+refresh+user)
POST  /api/auth/token                 로그인
POST  /api/auth/token/refresh         갱신
GET   /api/auth/me                    토큰 유효성 확인

GET   PATCH  /api/profile             집 위치 변경 시 알람 자동 재계산

GET   POST   /api/events              목록은 LimitOffsetPagination
GET   PATCH  DELETE  /api/events/{id}
POST  /api/events/{id}/recompute
GET   /api/events/tags

GET   /api/places/search?q=           카카오 프록시
GET   /api/routes/candidates?dest_lat=&dest_lng=
```

**목록 응답은 배열이 아니라 페이지 객체다** (`{count, next, previous, results}`).
`DEFAULT_PAGINATION_CLASS` 가 전역 설정이기 때문이다. `APIView` 로 직접 쓴
`/api/events/tags` 와 `/api/places/search` 는 페이지네이션을 타지 않는다.

**남의 일정 접근은 403 이 아니라 404 다.** 403 은 "그 id 의 일정이 존재한다" 는 사실을
알려준다. `_UserScopedMixin.get_queryset()` 한 곳에서 걸러 목록과 상세가 같은 규칙을
쓰게 했다.

## 알람 계산

```
alarm_at  = start_at − buffer − travel − prep
buffer    = 10분 (고정)
prep      = profile.onboarding_prep_min ?? 30 (고정)
travel    = 카카오 실측
            event.route_key 있으면 resolve_route(key)   ← 사용자가 고른 경로
            없으면          best_route()               ← 도보·대중교통 중 최단
```

`on_time_probability` 는 **null 이다.** 관측이 없고 카카오 응답에도 변동성 정보가
없어서 분포를 만들 재료가 없다. 임의값을 넣지 않는다.

`AlarmPlan.status` 는 `ok` / `no_home` / `no_place` / `route_failed` 중 하나이며,
`ok` 가 아니면 시각·분 필드가 전부 null 이다. 이 규칙은 `CheckConstraint` 로 DB 에
박혀 있다.

## 검증

전부 실제 HTTP 호출이다. 서버를 띄운 상태에서 돌린다.

```powershell
.\.venv\Scripts\python.exe scripts\check_auth_api.py          # 18 케이스
.\.venv\Scripts\python.exe scripts\check_events_api.py        # 20 케이스
.\.venv\Scripts\python.exe scripts\check_route_api.py         # 30 케이스
.\.venv\Scripts\python.exe scripts\check_timezone.py          # KST 오프셋 처리
.\.venv\Scripts\python.exe scripts\check_external_apis.py     # 외부 API 도달
.\.venv\Scripts\python.exe scripts\db_status.py               # DB 요약
```

## 설정

```
config/settings/
├── base.py   공통
├── dev.py    DEBUG=True, ALLOWED_HOSTS
└── prod.py   자리만
```

기본값은 `config.settings.dev` (`manage.py`).

### ALLOWED_HOSTS 주의

```python
ALLOWED_HOSTS = ["10.0.2.2", "localhost", "127.0.0.1"]
```

`ALLOWED_HOSTS` 가 **비어 있을 때만** Django 가 DEBUG 에서 localhost 를 자동 허용한다.
`10.0.2.2` 를 넣는 순간 자동 허용이 사라지므로 localhost 와 127.0.0.1 을 함께 적어야
한다. 이걸 빼면 호스트에서 `curl` 이 400 을 받는다.

`testserver` 는 넣지 않았다. 그래서 `django.test.Client` 를 쓸 수 없고 검증
스크립트는 `requests` 로 `127.0.0.1:8000` 을 부른다.

### DATABASE_URL 주의

`.env` 에 `DATABASE_URL=` 을 빈 값으로 두면 `dj_database_url.config()` 가 이를 유효한
설정으로 착각해 `DATABASES = {}` 를 만든다. DB 가 조용히 죽는다. `base.py` 는
`parse()` + 빈 문자열 필터를 쓴다.
