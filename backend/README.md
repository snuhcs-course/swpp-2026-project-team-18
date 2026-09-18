# JustInTime — Backend (Django)

명세는 `../Spec/back-spec.md`. 현재 상태는 **P0 스켈레톤**이다.
`/api/health` 와 JWT 발급까지만 되어 있고 도메인 API 는 없다.

## 실행

```powershell
cd C:\Users\rlaji\AndroidStudioProjects\SWPP-PJ\backend

# 최초 1회
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
Copy-Item .env.example .env      # 그리고 값 채우기
.\.venv\Scripts\python.exe manage.py migrate

# 실행 — 반드시 0.0.0.0 이어야 에뮬레이터가 접근한다
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

`127.0.0.1:8000` 으로 띄우면 호스트에서는 되지만 **에뮬레이터에서 연결 실패**한다.

## 확인 (검증됨)

| 확인 | 결과 |
| --- | --- |
| `GET /api/health` (localhost) | `200 {"ok":true,"version":"0.1.0"}` |
| `GET /api/health` (`Host: 10.0.2.2:8000`) | `200` |
| `GET /api/health` (`Host: evil.com`) | `400 DisallowedHost` |
| `POST /api/auth/token` (없는 계정) | `401` |
| 에뮬레이터 앱에서 연결 | **연결 성공** `ok=true version=0.1.0` |

에뮬레이터 검증은 로그인 화면 → `로그인 없이 둘러보기` → `서버 연결 확인` 경로로 했다.
`MainActivity` 는 `exported="false"` 이므로 `adb shell am start` 로 직접 실행되지 않는다.

## 현재 구성

| 항목 | 값 | 근거 |
| --- | --- | --- |
| Python | 3.12.10 | back-spec 1절 |
| Django | 5.2.16 | back-spec 1절 |
| DRF | 3.18.1 | back-spec 1절 |
| SimpleJWT | 5.3.1 | access 30분 / refresh 14일 |
| DB | **SQLite** | back-spec 논의 1번. `DATABASE_URL` 로 Postgres 전환 |
| Celery | **없음** | back-spec 논의 2번. P2 에 도입 |

### 앱 3개로 시작

`back-spec.md` 2절은 앱 12개를 정의하지만 P0~P1 에 필요한 것만 만들었다.

```
apps/
├── common/      health
├── accounts/    (모델 미작성)
├── events/      (모델 미작성)
└── planning/    (모델 미작성)
```

나머지 9개(`routines`, `observations`, `routing`, `weather`, `prediction`,
`rooms`, `reports`, `nlp`, `push`)는 해당 페이즈에서 추가한다.

### SQLite 로 시작한 이유

팀원 전원이 도커 없이 개발할 수 있어야 한다. Postgres 를 처음부터 요구하면
도커가 막히는 팀원 한 명이 백엔드 작업 자체를 못 한다. `dj-database-url` 로
`DATABASE_URL` 하나만 바꾸면 전환되고, Postgres 고유 기능(JSONB 인덱스,
ArrayField)을 쓰지 않는 한 마이그레이션도 그대로 돈다.

**전환 시점** — 데모 환경을 Postgres 로 옮기는 것은 P2(BE-P2-08)에서
Celery·Redis 와 함께 한다.

## 설정 분리

```
config/settings/
├── base.py   공통
├── dev.py    DEBUG=True, ALLOWED_HOSTS
└── prod.py   자리만
```

기본값은 `config.settings.dev` 다 (`manage.py`).

### ALLOWED_HOSTS 주의

```python
ALLOWED_HOSTS = ["10.0.2.2", "localhost", "127.0.0.1"]
```

`ALLOWED_HOSTS` 가 **비어 있을 때만** Django 가 DEBUG 에서 localhost 를 자동
허용한다. `10.0.2.2` 를 넣는 순간 자동 허용이 사라지므로 localhost 와
127.0.0.1 을 함께 적어야 한다. 이걸 빼면 호스트에서 `curl` 이 400 을 받는다.

실기기 테스트 시 개발 PC 의 LAN IP 를 추가한다.

## 다음 (P1)

| ID | 작업 |
| --- | --- |
| BE-P1-01 | 데이터 모델 + 마이그레이션 |
| BE-P1-02 | `seed_demo` — 데모 계정, 태그 6종, 기본 블록 6종 |
| BE-P1-03 | `/api/events` CRUD + `/sync` |
| BE-P1-05 | `/api/routes/query` + 3계층 캐싱 |
| BE-P1-07 | `/api/alarms/next` (고정 규칙) |
| BE-P1-08 | `/api/observations/batch` + 멱등성 |

**BE-P0-06 이 선행이다.** 카카오 대중교통 경로 API 응답에 변동성 정보
(백분위·실시간 지연)가 있는지 확인해야 한다. 점 추정만 온다면 이동시간 분포를
`RouteCorrection` 실측으로 만들어야 하고, 그게 이 프로젝트 AI 요소의 핵심 근거다.
키 발급이 선행 조건이다.
