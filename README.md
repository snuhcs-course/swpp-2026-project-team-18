# JustInTime

**도착 확률을 정하는 알람.** 몇 시에 깨울지를 고르는 대신, 얼마나 안전하게 갈지를 고른다.

기존 알람은 사용자가 "7시 40분"을 직접 정한다. 그런데 정말 정하고 싶은 것은 시각이
아니라 **"지각하지 않을 확신"** 이다. JustInTime 은 일정 시작 시각에서 거꾸로
`준비 시간 + 이동 시간 + 안전 버퍼` 를 빼서 알람을 계산하고, **그 근거를 함께 보여준다.**

![화면 구성](docs/screens-overview.png)

> Figma 전체 화면(13개). 같은 단계는 오른쪽으로, 하위 화면은 아래로 배치했다.

---

## Features

| | 기능 | 상태 |
| --- | --- | --- |
| ✅ | **알람 역산** — 일정 시작 − 안전 버퍼 − 이동 시간 − 준비 시간 | 구현 |
| ✅ | **카카오 실측 경로** — 집 → 목적지 소요시간을 실제로 조회 | 구현 |
| ✅ | **경로 선택** — 카카오가 주는 후보 중에서 고른다. 23분(환승 1회)과 27분(환승 없음)처럼 서로 다른 선택지를 제시 | 구현 |
| ✅ | **계산 근거 공개** — 준비·이동·버퍼를 분해해 보여주고, 무엇이 실측이고 무엇이 고정값인지 구분 | 구현 |
| ✅ | **일정 종류별 안전 여유(τ)** — 시험·발표·기차는 더 일찍 깨운다 | 구현 |
| 🚧 | **정시 도착 확률** — 관측이 쌓이면 분포로 계산. 지금은 "학습 중" | 예정 |
| 🚧 | **정확 알람 발화** — 잠금화면 위 full-screen intent, Doze 대응 | 예정 |
| 🚧 | **출발 전·이동 중 재계획** — 늦었을 때의 대안 제시 | 예정 |
| 🚧 | **약속방** — 여러 명의 도착 예상을 함께 확인 | 예정 |

### 확률을 아직 보여주지 않는 이유

정시 도착 확률을 만들려면 **소요시간 분포**가 있어야 한다. 그런데 카카오 경로 응답에는
변동성 정보가 없다 — `totalTime` 점추정치 하나뿐이다. 사용자 관측도 아직 0건이다.

그래서 확률 자리를 비워 두고 "학습 중" 으로 표시한다. 임의의 90% 를 채우면 화면은
그럴싸해지지만 거짓이 된다. 관측이 쌓이면 분위수로 계산한다.

---

## Tech Stack

| 영역 | 선택 |
| --- | --- |
| 앱 | Android 네이티브 · Kotlin · **Jetpack Compose** · Material3 |
| 상태 관리 | `ViewModel` + `StateFlow` + Coroutines |
| 네트워크 | Retrofit + Gson + OkHttp |
| 서버 | **Django + Django REST Framework** |
| DB | SQLite (Postgres 전환 가능) |
| 인증 | SimpleJWT (이메일 · 비밀번호) |
| 외부 API | 카카오맵(경로·장소검색), OpenAI, 기상청, FCM |
| 디자인 | Figma |

크로스플랫폼(Flutter · React Native)을 쓰지 않았다. 이 앱의 핵심이 **정확 알람**이라서
`AlarmManager.setAlarmClock`, Doze 예외, full-screen intent, 부팅 후 재등록을 직접
다뤄야 하고, 여기에 플러그인을 한 겹 끼우면 디버깅이 어려워진다.

---

## Getting Started

앱만으로는 동작하지 않는다. **백엔드를 먼저 띄워야 한다.**

### Prerequisites

- Android Studio (2025.1 이상) + Android SDK 37
- 에뮬레이터 또는 실기기 — **Android 14 (API 34) 이상**
- Python 3.12
- JDK — Android Studio 내장 JBR 사용
- 카카오 REST API 키 ([발급 방법](#외부-api-키))

### Installation

**1. 백엔드**

```powershell
cd backend

# 최초 1회
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
Copy-Item .env.example .env      # 그리고 키 값 채우기
.\.venv\Scripts\python.exe manage.py migrate

# 실행 — 0.0.0.0 이어야 에뮬레이터가 접근한다
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

`127.0.0.1:8000` 으로 띄우면 호스트에서는 되지만 **에뮬레이터에서 연결 실패**한다.

데모 계정은 `demo@demo.com` / `demo1234` 다 (`DEBUG=True` 일 때만 생성).
관리자 화면은 `http://127.0.0.1:8000/admin/`.

**2. 앱**

```powershell
cd APP
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

에뮬레이터에서 호스트 PC 는 `10.0.2.2` 다 (`BuildConfig.BASE_URL`).
실기기로 붙일 때는 `app/build.gradle.kts` 의 `BASE_URL` 을 개발 PC 의 LAN IP 로 바꾼다.

**3. 에뮬레이터 초기 설정**

안 하면 앱 버그로 오해한다.

```powershell
# 시간대. 기본이 GMT 라 시각이 9시간 어긋난다
adb shell cmd alarm set-timezone Asia/Seoul

# 소프트 키보드. 하드웨어 키보드 모드면 키보드가 뜨지 않고
# 호스트 자판의 ASCII 가 그대로 전달돼 한글 조합이 일어나지 않는다
adb shell settings put secure show_ime_with_hard_keyboard 1
```

한글 입력은 **설정 → Languages → Add a language → 한국어(대한민국)** 를 추가해야 한다.
Gboard 기본값이 영어뿐이라 그냥은 한글을 입력할 수 없다.

### 사용 흐름

```
로그인 → 집 위치 설정 → 일정 추가 → 경로 선택 → 알람 확인
```

집 위치가 알람 계산의 출발지다. 설정하지 않으면 이동 시간을 구할 수 없어 알람이
계산되지 않는다. 홈 화면 상단 안내에서 바로 설정할 수 있다.

---

## Project Structure

```
├── APP/                        Android (Kotlin + Compose)
│   └── app/src/main/java/com/swpp/wakeup/
│       ├── data/               API · 로컬 저장 · 저장소
│       ├── domain/model/       화면용 모델
│       └── ui/                 auth · home · events · alarm · common · nav · theme
├── backend/                    Django + DRF
│   ├── apps/
│   │   ├── accounts/           User · Profile
│   │   ├── events/             Place · EventTag · Event
│   │   ├── planning/           AlarmPlan (알람 계산)
│   │   ├── routing/            카카오 클라이언트
│   │   └── common/             공통 에러 포맷
│   └── scripts/                API 검증 스크립트
└── docs/                       스크린샷
```

설계 문서(명세 · 체크리스트)는 **Wiki** 로 관리한다.

---

## API

```
POST  /api/auth/register              회원가입
POST  /api/auth/token                 로그인 (access + refresh)
POST  /api/auth/token/refresh         토큰 갱신
GET   /api/auth/me                    토큰 유효성 확인

GET   PATCH  /api/profile             집 위치 · 준비 시간 · 기본 τ

GET   POST   /api/events              일정 목록 · 생성
GET   PATCH  DELETE  /api/events/{id}
POST  /api/events/{id}/recompute      알람만 다시 계산
GET   /api/events/tags                일정 종류 6종

GET   /api/places/search?q=           카카오 장소 검색 (프록시)
GET   /api/routes/candidates          경로 후보
```

**카카오를 앱에서 직접 부르지 않는다.** APK 를 뜯으면 키가 나오기 때문에 서버가
프록시한다.

### 검증

전부 실제 HTTP 호출이다. 서버를 띄운 상태에서 돌린다.

```powershell
cd backend
.\.venv\Scripts\python.exe scripts\check_auth_api.py      # 인증 18 케이스
.\.venv\Scripts\python.exe scripts\check_events_api.py    # 일정 20 케이스
.\.venv\Scripts\python.exe scripts\check_route_api.py     # 경로 30 케이스
.\.venv\Scripts\python.exe scripts\db_status.py           # DB 요약
```

---

## 외부 API 키

`backend/.env` 에 넣는다. `.env.example` 을 복사해서 쓴다.
**`.env` 는 커밋되지 않는다** (`.gitignore`).

| 변수 | 발급 | 비고 |
| --- | --- | --- |
| `KAKAO_REST_API_KEY` | [developers.kakao.com](https://developers.kakao.com) | 경로 3종 + 장소 검색 + 자동차 전부 이 키 하나 |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) | 자연어 일정 파싱 (예정) |
| `KMA_API_KEY` | [기상청 API 허브](https://apihub.kma.go.kr) | 단기예보. 활용신청 필요 |
| `FCM_CREDENTIALS_PATH` | Firebase 콘솔 | JSON **경로**. 파일은 저장소 밖에 둔다 |

카카오맵은 심사가 없다. `앱 > 제품 설정 > 카카오맵` 에서 사용 설정만 켜면 된다.
**무료 쿼터는 계정당 앱 1개에만 주므로 팀이 앱 하나를 만들고 키를 공유한다.**

---

## Team

SNU SWPP 2026 Fall · Team 18
