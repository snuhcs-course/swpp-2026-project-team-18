# 팀 셋업 가이드

여러 사람이 같이 개발하기 위한 설정이다. 예전에는 한 사람이 자기 PC 에
`runserver` 를 띄워 둔 동안만 앱이 동작했고 DB 도 각자 것이었다. 이제
**상시 서버 하나 + 공용 DB 하나**를 두고 전원이 같은 데이터를 본다.

---

## 0. 현재 구성

| 항목 | 값 |
| --- | --- |
| 공용 API 서버 | <https://justintime-api.onrender.com> (Render, 무료) |
| 앱 기본 주소 | `APP/gradle.properties` 의 `jitApiBaseUrl` (커밋됨) |
| 공통 확인 계정 | `demo@demo.com` / `demo1234` (집=신림역) |

상태 확인:

```bash
curl https://justintime-api.onrender.com/api/health
# {"ok":true,"version":"0.1.0"}
```

전 기능 확인 (계정 생성 → 경로 → 알람 → 관측까지 51개 항목):

```bash
cd backend
python scripts/check_deployed.py
```

### 남은 작업 하나 — DB 를 Neon 으로 바꿀 것

**지금 서버는 Render 가 자동 생성한 Postgres 를 쓰고 있다.** 기능은 전부
정상이지만 두 가지 문제가 있다.

1. Render 무료 Postgres 는 **생성 30일 후 만료**된다. 학기 중에 데이터가 사라진다.
2. Neon 에 이관해 둔 데이터(계정 3개 · 일정 3건)가 그 DB 에는 없다.

Render 대시보드 → `justintime-api` → **Environment** 에서 `DATABASE_URL` 을
Neon 주소로 바꾸면 끝난다. 저장하면 자동 재배포되고, 컨테이너가 시작할 때
마이그레이션이 돌아 스키마가 맞춰진다.

바꾼 뒤 확인:

```bash
# 이관한 계정으로 로그인되면 Neon 을 쓰는 것이다
python scripts/check_deployed.py
```

어느 DB 에 붙었는지 확실히 보려면 계정을 하나 만들고 Neon 에서 찾아본다.
5절의 표를 참고할 것.

> `render.yaml` 에서 Render Postgres 정의는 제거했다. 다시 Blueprint 를
> 동기화해도 자체 DB 를 만들지 않는다.

---

## 1. 팀원이 해야 할 일 (서버가 이미 떠 있는 경우)

**대부분은 이것만 하면 된다.** 서버 배포는 2절, 그건 한 사람만 한다.

```bash
git clone <레포 주소>
cd SWPP-PJ
```

### 안드로이드

그대로 열어서 빌드하면 된다. 공용 서버 주소가 `APP/gradle.properties` 의
`jitApiBaseUrl` 에 들어 있고 이 파일은 커밋되므로 **추가 설정이 없다.**

```bash
cd APP
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
```

빌드 로그 첫 줄에서 어느 서버를 가리키는지 확인할 수 있다.

```
[JustInTime] API=https://... (emulator=https://..., cleartext=false) ← gradle.properties jitApiBaseUrl
```

계정은 앱에서 직접 만들면 된다(회원가입). 공용 DB 라서 만든 계정은 팀원
누구의 기기에서도 로그인된다. 공통 확인용 계정은 `demo@demo.com` / `demo1234`.

### 백엔드를 고칠 사람만

파이썬 환경이 필요하다.

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
pip install -r requirements/dev.txt

copy .env.example .env          # macOS/Linux: cp .env.example .env
```

`.env` 에 최소 두 개를 채운다.

| 키 | 값 |
| --- | --- |
| `DJANGO_SECRET_KEY` | `python -c "import secrets; print(secrets.token_urlsafe(50))"` |
| `KAKAO_REST_API_KEY` | 팀 공용 키를 받아 넣는다(저장소에 없다) |

`DATABASE_URL` 은 **비워 두는 것을 권한다.** 비우면 로컬 SQLite 라서 마음대로
망가뜨려도 팀에 영향이 없다. 공용 데이터로 확인할 일이 있을 때만 공용 주소를
넣는다.

```bash
python manage.py migrate
python manage.py runserver 0.0.0.0:8000
```

로컬 서버로 앱을 돌리려면 `APP/local.properties` 에 한 줄 적는다. 이 파일은
커밋되지 않으므로 서로의 설정이 충돌하지 않는다.

```properties
# 에뮬레이터
devServerHost=10.0.2.2

# 실기기 (같은 Wi-Fi 의 내 PC IP)
devServerHost=192.168.0.12
```

`devServerHost` 가 있으면 **공용 서버보다 우선한다.** 자기 변경을 자기 PC 에서
확인해야 하기 때문이다. 공용 서버로 되돌리려면 그 줄을 지우고 다시 빌드한다.

---

## 2. 공용 서버 배포 (한 사람만, 최초 1회)

계정이 필요한 작업이라 자동화할 수 없다. 아래 순서대로 하면 된다.

### 2-1. DB 먼저 — Neon (무료, 만료 없음)

1. <https://neon.tech> 에서 GitHub 로 가입
2. 프로젝트 생성 — 리전은 **Singapore (ap-southeast-1)** 가 한국에서 가장 가깝다
3. 데이터베이스 이름을 `justintime` 으로
4. **Connection string** 을 복사한다. `postgres://...?sslmode=require` 모양이다

> Render 가 제공하는 무료 Postgres 는 **30일 후 만료**된다. 학기 내내 쓸
> 것이므로 Neon 을 권한다.

### 2-2. 서버 — Render (무료)

1. <https://render.com> 에서 GitHub 로 가입
2. **New → Blueprint** 를 고르고 이 저장소를 연결한다.
   루트의 `render.yaml` 을 읽어 서비스를 자동으로 만든다
3. 환경변수를 채운다

   | 키 | 값 |
   | --- | --- |
   | `DATABASE_URL` | 2-1 에서 복사한 Neon 주소 |
   | `KAKAO_REST_API_KEY` | 카카오 REST API 키 |
   | `DJANGO_SECRET_KEY` | Render 가 자동 생성한다(건드리지 않는다) |

   > **`DATABASE_URL` 을 반드시 확인할 것.** Render 가 자체 Postgres 를 만들어
   > 거기에 연결해 두면, Neon 에 넣은 데이터가 하나도 보이지 않는다. 그런데
   > `/api/health` 는 200 을 돌려주므로 배포가 성공한 것처럼 보인다. 실제로 이
   > 상태로 한참 갔고, 이관한 데모 계정으로 로그인이 401 이 나서야 알았다.
   >
   > 어느 DB 에 붙었는지 확인하는 방법은 5절에 있다.

4. 배포가 끝나면 주소가 나온다: `https://justintime-api.onrender.com`
5. 확인

   ```bash
   curl https://justintime-api.onrender.com/api/health
   # {"ok":true,"version":"0.1.0"}
   ```

6. 데모 계정을 만든다. 두 방법이 있고 결과는 같다.

   ```bash
   # 로컬에서 HTTP 로 (서버 셸이 필요 없다)
   cd backend && python scripts/seed_demo_via_api.py

   # 또는 Render 대시보드의 Shell 에서
   python manage.py seed_demo
   ```

   둘 다 멱등하고 같은 값을 넣는다 — `demo@demo.com` / `demo1234`, 집=신림역,
   준비 30분. 집 위치를 함께 넣는 이유는 그게 없으면 일정을 넣어도 계획이
   `no_home` 으로만 나와 화면이 비어 보이기 때문이다.

7. 전 기능을 확인한다.

   ```bash
   cd backend && python scripts/check_deployed.py
   ```

**마이그레이션은 자동이다.** 컨테이너가 시작할 때 `migrate` 를 돌린다
(`backend/Dockerfile`). 스키마를 바꿔 푸시하면 다음 배포에서 반영된다.

### 2-3. 앱에 주소 박기

`APP/gradle.properties` 의 한 줄을 채우고 **커밋한다.** 이걸 해야 팀원이
clone 만으로 공용 서버에 붙는다.

```properties
jitApiBaseUrl=https://justintime-api.onrender.com/
```

끝에 슬래시가 있어야 한다(Retrofit 요구사항).

### 2-4. 기존 데이터 옮기기 (선택)

혼자 개발하며 만든 계정·일정을 공용 DB 로 넘기려면:

```bash
cd backend

# 0. 무엇이 넘어갈지 먼저 센다
python scripts/db_counts.py

# 1. 로컬 SQLite 덤프 (DATABASE_URL 이 비어 있어야 한다)
python scripts/migrate_sqlite_to_postgres.py dump

# 2. 공용 DB 로 적재 (직접 엔드포인트 - 호스트에서 -pooler 를 뺀 주소)
$env:DATABASE_URL="postgresql://...ap-southeast-1.aws.neon.tech/neondb?sslmode=require"
python scripts/migrate_sqlite_to_postgres.py load

# 3. 대조
python scripts/db_counts.py
```

**옮기기 전에 테스트 잔여물을 지울 것.** `check_*.py` 들이 만든 임시 계정이
쌓여 있다. 실제로 계정 23개 중 20개가 `tester_*` / `ev_a_*` 같은 쓰레기였고,
그대로 넘기면 팀원이 admin 에서 그 목록을 보게 된다.

이관에서 겪은 함정 셋:

| 증상 | 원인 |
| --- | --- |
| `duplicate key ... accounts_profile_user_id_key` | Profile 자동 생성 시그널이 fixture 적재 중에도 돌았다. `raw=True` 를 확인하게 고쳤다(`apps/accounts/signals.py`) |
| 데모 계정이 중복 생성됨 | `accounts.0002_demo_account` 는 `DEBUG=True` 일 때만 데모를 만든다. `migrate` 를 **prod 설정으로** 돌리면 생기지 않는다 |
| PK 가 바뀜 | `--natural-foreign`/`--natural-primary` 를 쓰므로 정상이다. 참조는 자연키로 이어진다 |

`contenttypes` 와 `auth.permission` 은 제외한다 — 새 DB 의 마이그레이션이
스스로 만들기 때문에 그대로 넣으면 충돌한다.

---

## 3. 알아야 할 제약

### 무료 플랜은 잠든다 — 앱이 견디게 해 두었다

Render 무료 웹 서비스는 **15분 동안 요청이 없으면 잠든다.** 측정값은 이렇다.

| 상태 | 응답 시간 |
| --- | --- |
| 깨어 있음 | 0.14초 |
| 잠들었다 깨어남 | 11초 (문서상 최대 1분) |

종전 앱 타임아웃(연결 10초 / 읽기 15초)으로는 **첫 요청이 그 안에 안 끝나
로그인부터 실패했다.** 이제 앱이 흡수한다.

- 타임아웃: 연결 20초 / 읽기 60초 / 전체 상한 90초
- `ColdStartRetryInterceptor` — 타임아웃이나 502·503·504 를 만나면 재시도
  (1초 → 2초 백오프, 최대 3회)
- `ServerWarmup` — 로그인·회원가입 **전에** `/api/health` 로 먼저 깨운다
- 2초를 넘기면 화면에 "서버를 깨우는 중… 최대 1분 걸림" 을 띄운다

> **재시도는 GET 만 한다.** POST 를 재시도하면 서버가 첫 요청을 이미 처리했는데
> 응답만 늦은 경우 같은 일정이 두 번 생긴다. 요청이 처리됐는지 클라이언트는 알
> 수 없다. 그래서 쓰기 요청은 한 번만 보내고, 콜드 스타트는 `ServerWarmup` 이
> 앞에서 흡수한다. 이 동작은 단위 테스트 8개로 고정해 두었다
> (`ColdStartRetryInterceptorTest`).

그래도 **시연 직전에는 미리 깨워 두는 것이 안전하다.** 첫 화면이 11초 걸리는
것과 0.14초 걸리는 것은 체감이 다르다.

```bash
curl https://justintime-api.onrender.com/api/health
```

15분 안에 발표를 시작하면 계속 깨어 있다.

아예 없애려면 Starter 플랜($7/월)으로 올린다. 외부에서 5분마다 `/api/health` 를
치는 방법도 있지만 무료 인스턴스 시간(월 750시간)을 상시 소모해 한 달을 못 채운다.

### 공용 DB 는 공용이다

`DATABASE_URL` 을 공용 주소로 두고 로컬에서 작업하면 **팀 전체 데이터를
건드린다.** 삭제·초기화 실험은 `DATABASE_URL` 을 비워 SQLite 에서 할 것.

검증 스크립트(`backend/scripts/check_*.py`)는 임시 계정을 만들고 끝에
지우지만, 중간에 끊기면 남는다.

### 시크릿은 저장소에 없다

| 값 | 어디에 |
| --- | --- |
| `KAKAO_REST_API_KEY` | 서버는 Render 환경변수, 로컬은 각자 `.env` |
| `DJANGO_SECRET_KEY` | 서버는 Render 가 생성, 로컬은 각자 생성 |
| `DATABASE_URL`(공용) | Render 환경변수. 필요한 사람에게만 따로 전달 |

카카오 키는 계정당 무료 쿼터가 **앱 1개**에만 붙는다(일 1,000건). 팀원이 각자
키를 만들면 쿼터가 갈라지므로 하나를 공유한다. 저장소·이슈·PR 에 붙여넣지 않는다.

`APP/app/google-services.json` 은 커밋돼 있는데 이건 클라이언트용이라
비밀이 아니다. FCM **서비스 계정 JSON** 은 서버 전권을 가지므로 절대 커밋하지
않는다(`.env` 의 `FCM_CREDENTIALS_PATH` 로 경로만 지정).

---

## 4. 다른 배포처를 쓰려면

실행은 `backend/Dockerfile` 이 하므로 Render 에 묶이지 않는다.

```bash
docker build -t justintime-api ./backend
docker run -p 8000:8000 \
  -e DJANGO_SECRET_KEY=... \
  -e DATABASE_URL=postgres://... \
  -e DJANGO_ALLOWED_HOSTS=api.example.com \
  justintime-api
```

필수 환경변수는 셋이다. 비면 **시작하지 않고 터진다** — 조용히 SQLite 로
떨어져 재배포마다 데이터가 사라지는 것보다 낫다.

| 키 | 필수 | 설명 |
| --- | --- | --- |
| `DJANGO_SECRET_KEY` | O | 개발 키 재사용 금지 |
| `DATABASE_URL` | O | Postgres |
| `DJANGO_ALLOWED_HOSTS` | O | 배포 도메인, 쉼표 구분 |
| `KAKAO_REST_API_KEY` | 사실상 O | 없으면 경로 조회가 전부 실패한다 |
| `DJANGO_SECURE_SSL_REDIRECT` | | TLS 없이 내부망에 띄울 때 `0` |
| `WEB_CONCURRENCY` | | gunicorn 워커 수, 기본 2 |
| `DJANGO_CORS_ORIGINS` | | 웹 대시보드를 붙일 때 |

### 직접 운영하는 서버에 띄우는 경우

```bash
docker run -d --restart=always -p 8000:8000 \
  -e DJANGO_SECRET_KEY=... \
  -e DATABASE_URL=postgres://... \
  -e DJANGO_ALLOWED_HOSTS=api.example.com \
  --name justintime-api justintime-api
```

`--restart=always` 가 재부팅 후 자동 실행을 담당한다. `jitApiBaseUrl` 에는
그 서버의 https 주소를 적는다.

**TLS 는 반드시 둔다.** 앱이 로그인 토큰을 헤더로 보내므로 평문으로 노출하면
같은 네트워크에 있는 누구나 계정을 탈취할 수 있다. 앞단에 nginx·Caddy 를 두고
인증서를 붙일 것. 인증서가 없는 상태로 임시 확인만 할 때는
`DJANGO_SECURE_SSL_REDIRECT=0` 으로 리다이렉트를 끌 수 있지만, 그 구성을
팀 공용으로 쓰지 않는다.

---

## 5. 자주 막히는 지점

| 증상 | 원인 |
| --- | --- |
| 앱에서 "서버에 연결할 수 없다" | 무료 플랜 콜드 스타트. 30초 뒤 재시도 |
| `400 DisallowedHost` | `DJANGO_ALLOWED_HOSTS` 에 도메인이 없다 |
| 무한 리다이렉트 | 프록시 뒤에서 `SECURE_PROXY_SSL_HEADER` 누락. `prod.py` 에 이미 있으니 커스텀 설정을 쓰는 경우만 확인 |
| admin 이 스타일 없이 뜸 | `collectstatic` 실패. 이미지 빌드 로그 확인 |
| 경로가 안 나옴 (`route_failed`) | `KAKAO_REST_API_KEY` 미설정 또는 일 쿼터 초과 |
| 내 백엔드 변경이 앱에 안 보임 | `local.properties` 의 `devServerHost` 가 주석이라 공용 서버에 붙었다 |
| 로컬에서 남의 데이터가 보임 | `.env` 의 `DATABASE_URL` 이 공용 DB 를 가리킨다 |
| 이관한 계정으로 로그인이 401 | 서버가 다른 DB 에 붙어 있다. 아래 참고 |

### 서버가 어느 DB 에 붙었는지 확인하는 방법

`/api/health` 는 DB 와 무관하게 200 을 돌려준다. **배포가 성공한 것처럼
보이지만 엉뚱한 DB 를 쓰고 있을 수 있다.** 실제로 Render 가 자체 Postgres 를
만들어 거기에 붙어 있었고, 이관한 데모 계정 로그인이 401 이 나서야 알았다.

확실한 방법은 서버에 계정을 하나 만들고 그것이 기대하는 DB 에 나타나는지
보는 것이다.

```bash
# 1. 서버에 프로브 계정 생성
curl -X POST https://justintime-api.onrender.com/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"dbprobe@example.com","nickname":"probe","password":"dbprobe12345","password_confirm":"dbprobe12345"}'

# 2. Neon 에서 찾아본다
cd backend
$env:DATABASE_URL="<Neon 직접 주소>"
python -c "import django,os;os.environ.setdefault('DJANGO_SETTINGS_MODULE','config.settings.dev');django.setup();from django.contrib.auth import get_user_model as g;print(list(g().objects.values_list('email',flat=True)))"
```

프로브 계정이 Neon 목록에 있으면 서버가 Neon 을 쓰는 것이고, 없으면 다른
DB 를 쓰는 것이다.

---

## 6. 검증 스크립트

`backend/scripts/` 에 있다. 전부 `python scripts/<이름>.py` 로 돈다.

| 스크립트 | 대상 | 용도 |
| --- | --- | --- |
| `check_deployed.py` | **배포 서버** | 전 기능 51개 항목. HTTP 만 쓰므로 Django 설정이 필요 없다 |
| `check_route_api.py` | 로컬 서버 | 경로 후보·선택 33개 항목 |
| `check_origin_api.py` | 로컬 서버 | 출발지 선택 17개 항목 |
| `check_events_api.py` | 로컬 서버 | 일정 CRUD |
| `check_observations_api.py` | 로컬 서버 | 관측 업로드 |
| `check_external_apis.py` | 외부 API | 카카오·기상청 키 상태 |
| `db_counts.py` | 현재 `DATABASE_URL` | 모델별 행 수. 이관 전후 대조 |
| `seed_demo_via_api.py` | 배포 서버 | 데모 계정 생성(HTTP) |
| `migrate_sqlite_to_postgres.py` | — | SQLite → Postgres 일회성 이관 |

**로컬 대상 스크립트는 임시 계정을 만든다.** 끝에 지우지만 중간에 끊기면
남는다. `DATABASE_URL` 이 공용 DB 를 가리킨 상태로 돌리지 말 것.

`check_deployed.py` 가 만든 계정은 `depcheck_` 접두가 붙는다(사용자 삭제 API 가
없어 남는다). 주기적으로 admin 에서 정리한다.

---

---

## 7. Tailscale 은 팀 공용으로 쓰지 않는다

**앱 동작에 VPN 이 필요 없다.** 공용 서버가 공개 HTTPS 이므로 어느 망에서든
닿는다(실측: 폰에서 13.6ms).

Tailscale 이 남아 있는 용도는 하나뿐이다 — **무선 디버깅으로 자기 PC 와 자기
폰을 잇는 것**이다. 2대짜리 개인 문제이므로 **각자 자기 무료 계정**으로 하면
되고, 팀원이 한 tailnet 에 모일 이유가 없다.

공유 tailnet 은 오히려 손해다.

- 팀원 전원의 개인 노트북·휴대폰이 서로 접근 가능해진다. 수업 프로젝트에서
  감당할 이유가 없는 노출이다.
- 초대·기기 승인 같은 관리 부담이 생긴다.

### 무선 디버깅이 안 될 때만 각자 설치

**eduroam 에서는 안 된다.** 단말 간 통신과 mDNS 를 막아서 `adb pair` 가
까다롭고 `adb mdns services` 가 빈 결과를 준다. 이때 선택지는 셋이다.

| 방법 | 비고 |
| --- | --- |
| USB 케이블 | 가장 단순하다 |
| 집·개인 핫스팟 Wi-Fi | PC 와 폰이 같은 망이면 된다 |
| Tailscale (**각자 계정**) | 망이 바뀌어도 주소가 그대로라 편하다 |

Tailscale 을 쓰면 폰의 무선 디버깅 화면에 Wi-Fi 주소가 아니라 tailnet 주소
(`100.x.x.x`)가 뜬다. 그 주소는 Wi-Fi 를 옮겨도 바뀌지 않는다 — 실제로 폰이
`10.148.x` 에서 `192.168.0.85` 로 옮겼는데 adb 연결이 유지됐다.

**포트는 매번 바뀐다.** 무선 디버깅 화면을 닫고 열면 새 포트를 잡는다.
페어링은 유지되므로 재페어링 없이 새 포트로 `adb connect` 만 하면 된다.

---

실기기 연결은 `docs/device-setup.md`, 기기 디버깅 도구는
`APP/scripts/README.md` 를 볼 것.
