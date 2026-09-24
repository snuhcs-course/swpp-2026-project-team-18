# 팀 셋업 가이드

여러 사람이 같이 개발하기 위한 설정이다. 예전에는 한 사람이 자기 PC 에
`runserver` 를 띄워 둔 동안만 앱이 동작했고 DB 도 각자 것이었다. 이제
**상시 서버 하나 + 공용 DB 하나**를 두고 전원이 같은 데이터를 본다.

---

## 0. 현재 구성

| 항목 | 값 |
| --- | --- |
| 공용 API 서버 | <https://justintime-api.onrender.com> (Render, 무료) |
| **DB** | **Neon Postgres 하나** — 개발도 배포도 같은 DB를 본다 |
| 앱 기본 주소 | `APP/gradle.properties` 의 `jitApiBaseUrl` (커밋됨) |
| 공통 확인 계정 | `demo@demo.com` / `demo1234` (집=신림역) |

### DB 는 하나다

로컬 SQLite 를 개발용으로 두지 않는다. 두면 로컬 데이터와 팀 데이터가 갈라지고
"지금 어느 DB 를 보고 있나" 가 매번 질문이 된다.

그래서 **어느 설정도 조용히 로컬 DB 로 떨어지지 않는다.**

| 설정 | `DATABASE_URL` 이 비면 |
| --- | --- |
| `config.settings.dev` | **시작을 거부한다** (안내 문구가 나온다) |
| `config.settings.prod` | **시작을 거부한다** |
| `config.settings.test` | 메모리 SQLite 로 무조건 덮어쓴다 (아래 참고) |

예외가 둘 있는데, 둘 다 **관리하는 DB 가 아니라 실행 중에만 존재하는 것**이다.

- `pytest` 591건 — 메모리 SQLite. pytest 는 테스트 데이터베이스를 **만들고
  지운다.** Neon 에 대고 하면 팀 DB 인스턴스에 `test_neondb` 를 만들었다 지우게
  되고, 싱가포르 왕복(질의당 약 75ms)이라 7초짜리 테스트가 몇 분이 된다.
- `scripts/run_local_suite.py` — 임시 폴더의 일회용 SQLite. 검증 스크립트 8개가
  한 번 돌 때 **계정 100개 가까이** 만든다. Neon 이 유일한 사본이 된 뒤로는 거기서
  만들 이유가 더 없다.

### 되돌릴 사본이 없다 — 작업 전에 백업할 것

로컬 DB 가 없어졌으므로 실수를 되돌릴 안전망도 없어졌다. 아래는 전부 한 줄이고
전부 팀 데이터를 지운다.

```
manage.py flush                    전체 삭제
purge_test_accounts.py --yes       계정 삭제 (cascade 로 일정·관측까지)
User.objects.filter(...).delete()  셸에서 한 줄
```

위험한 작업 전에 파일 하나 만들어 두면 된다.

```bash
cd backend
python scripts/backup_db.py          # 임시 폴더에 JSON 으로 저장
python scripts/backup_db.py --list   # 받아 둔 백업 목록
```

**백업 파일에는 비밀번호 해시가 들어 있다.** 그래서 기본 저장 위치를 저장소 밖에
둔다. 커밋하지 말 것.

상태 확인:

```bash
curl https://justintime-api.onrender.com/api/health
# {"ok":true,"version":"0.3.0"}
```

**version 이 0.3.0 미만이면 배포가 뒤처진 것이다.** 200 이 온다고 새 코드라는
뜻이 아니다 — 판별 방법은 8절에 있다.

전 기능 확인 (계정 → 경로 → 알람 → 관측 → 루틴 블록 → 캘린더 → 리포트):

```bash
cd backend
python scripts/check_deployed.py     # 82개 항목
```

**현재 상태 (0.3.0 배포 확인 완료):** 통과 82 · 실패 0. 카카오 경로까지 살아
있어서 알람이 실제로 계산된다.

> 이 스크립트는 `depcheck_` 계정 두 개를 **Neon 에** 만든다. 사용자 삭제 API 가
> 없으므로 끝나고 치울 것. `python scripts/purge_test_accounts.py` (기본 dry-run,
> 목록을 확인한 뒤 `--yes`)

### 전환 완료 기록 — 서버가 Neon 을 본다

배포 서버의 `DATABASE_URL` 은 Neon **direct** 주소를 쓴다. 확인한 상태는 이렇다.

| | |
| --- | --- |
| `check_same_db.py` | `rc=0` — 배포 서버가 만든 계정이 Neon 에서 보인다 |
| `check_deployed.py` | 53 통과 / 0 실패 / 0 건너뜀 |
| Neon 데이터 | 계정 3개 · 일정 3건 (`Algorithms Lecture` · `Dinner Plans` · `시험 준비`) |

한동안 Render 가 자동 생성한 Postgres 에 붙어 있었다. 두 가지가 문제였다.

1. Render 무료 Postgres 는 **생성 30일 후 만료**된다. 학기 중에 데이터가 사라진다.
2. Neon 에 이관해 둔 데이터가 그 DB 에는 없었다.

**그런데 겉으로는 정상으로 보였다.** `/api/health` 는 200 이었고
`check_deployed.py` 는 51개 항목을 전부 통과했다. 둘 다 DB 를 보지 않기
때문이다 — 검증 스크립트는 자기 계정을 새로 만들어 쓴다. 일정 id 가
`7·8·9·10` 인 것을 Neon 의 `3·9·13` 과 대조해서야 알아챘다.

그래서 `check_same_db.py` 를 만들었다. 앞으로 DB 주소를 건드리면 이걸 돌린다.

```powershell
cd backend
$env:DATABASE_URL="<Neon direct 주소>"
python scripts/check_same_db.py   # 0 같은 DB / 2 다른 DB
python scripts/check_deployed.py  # 기능 53개 항목
```

주소를 바꾸면 저장 시 자동 재배포된다. **바로 확인하면 아직 이전 DB 가 나온다** —
Docker 빌드가 끝나야 새 컨테이너로 넘어간다. 1~3분 뒤에 다시 돌릴 것.

> `render.yaml` 에서 Render Postgres 정의는 제거했다. 다시 Blueprint 를
> 동기화해도 자체 DB 를 만들지 않는다. 남아 있는 Render Postgres 인스턴스는
> 쓰이지 않으므로 대시보드에서 지워도 된다.

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

`.env` 에 세 개를 채운다. **셋 다 저장소에 없다** — 관리자에게 받는다.

| 키 | 값 |
| --- | --- |
| `DATABASE_URL` | Neon **direct** 주소 (`-pooler` 없는 쪽). 비우면 서버가 시작하지 않는다 |
| `DJANGO_SECRET_KEY` | `python -c "import secrets; print(secrets.token_urlsafe(50))"` |
| `KAKAO_REST_API_KEY` | 팀 공용 키 |

`DATABASE_URL` 을 비워 두면 안내 문구와 함께 시작이 거부된다. 예전에는 조용히
로컬 SQLite 로 떨어졌는데, 그러면 `.env` 를 아직 못 받은 사람이 **빈 DB 에 붙고도
그 사실을 모른다** — 화면에 아무것도 없는 이유를 앱 버그로 오해하게 된다.

```bash
python manage.py migrate        # 스키마가 이미 최신이면 아무것도 하지 않는다
python manage.py runserver 0.0.0.0:8000
```

> `migrate` 는 **팀 DB 에 즉시 반영된다.** 모델을 고치는 중이라면 새 컬럼에
> `db_default` 를 줬는지 확인할 것 — 안 주면 배포가 교체되기 전까지 구버전
> 컨테이너의 쓰기가 500 이 된다. `python scripts/check_db_defaults.py` 가 검사한다.

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

   **`-pooler` 없는 direct 주소를 넣는다.** Neon 은 두 엔드포인트를 준다.

   | | 용도 | 제약 |
   | --- | --- | --- |
   | `ep-xxx.` (direct) | **이걸 쓴다** | 연결 한도 약 104개(0.25 CU) |
   | `ep-xxx-pooler.` | 연결이 수백 개로 늘면 | `PREPARE` 등 세션 기능 없음 → 마이그레이션이 깨질 수 있다 |

   이 컨테이너는 시작할 때 `migrate` 를 돌린 뒤 `gunicorn` 을 띄우므로
   (`backend/Dockerfile`) `DATABASE_URL` 하나가 둘을 겸한다. 그러면 위험한
   쪽(마이그레이션)에 맞춰야 한다. pooler 는 연결 고갈을 푸는 도구인데 우리는
   그 문제가 없다 — 워커 2개에 `conn_max_age=600` 이라 동시 연결이 2개 수준이다.

4. 배포가 끝나면 주소가 나온다: `https://justintime-api.onrender.com`
5. 확인

   ```bash
   curl https://justintime-api.onrender.com/api/health
   # {"ok":true,"version":"0.3.0"}
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

# 2. 공용 DB 로 적재 (direct 주소 - 호스트에 -pooler 가 없는 쪽)
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

### 공용 DB 는 공용이다 — 이제 유일한 사본이기도 하다

개발도 Neon 을 보기로 했으므로, 로컬에서 하는 모든 쓰기가 **팀 전체 데이터**다.
그리고 로컬 사본이 없으니 되돌릴 곳도 없다.

- 삭제·초기화 실험은 **일회용 DB 에서** 한다.
  `$env:DATABASE_URL="sqlite:///$env:TEMP/jit_scratch.sqlite3"`
- 위험한 작업 전에는 `python scripts/backup_db.py` 를 돌린다.
- 검증 스크립트(`backend/scripts/check_*.py`)는 임시 계정을 만들고 끝에 지우지만
  중간에 끊기면 남는다. 그래서 `_local_guard.py` 가 Neon 에서는 실행을 거부한다.
  `run_local_suite.py` 로 돌리면 일회용 DB 를 알아서 쓴다.

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
| `SEOUL_SUBWAY_API_KEY` | | 지하철 실시간 도착. 없으면 도착정보만 빠지고 경로는 나온다 |
| `SEOUL_BUS_API_KEY_ENCODING` | | 버스 실시간 도착. 아래 절을 볼 것 |
| `SEOUL_BUS_API_KEY_DECODING` | | 같은 키의 다른 표현. 한쪽만 통할 때가 있다 |
| `DJANGO_SECURE_SSL_REDIRECT` | | TLS 없이 내부망에 띄울 때 `0` |
| `WEB_CONCURRENCY` | | gunicorn 워커 수, 기본 2 |
| `DJANGO_CORS_ORIGINS` | | 웹 대시보드를 붙일 때 |

실시간 키 세 개는 `render.yaml` 에 `sync: false` 로만 적혀 있다. 값은 저장소에
넣지 않고 Render 대시보드에서 직접 넣는다. **로컬 `backend/.env` 는 Render
컨테이너에 올라가지 않는다** — 로컬에서는 도착정보가 붙고 배포 서버에서는 안
붙는 일이 실제로 있었다.

설정됐는지는 `/api/health` 가 알려 준다. 키 값도 길이도 담지 않는다.

```bash
curl.exe -s https://justintime-api.onrender.com/api/health
# {"ok":true,"version":"0.4.0","realtime":{"subway":true,"bus":true}}
```

### data.go.kr 401 은 "키가 틀렸다" 가 아니다

버스 도착정보에서 겪은 것이다. 승인이 났고 활용기간도 유효한데 계속
`유효하지 않은 서비스키입니다: 등록되지 않은 서비스키` 가 돌아왔다. 전파
지연으로 보고 기다렸지만 원인이 아니었다.

**data.go.kr 은 서비스 단위로 승인한다.** 승인받은 서비스와 다른 서비스를
부르면 키가 정상이어도 401 이다. 메시지가 "키가 없다" 처럼 읽혀서 전파 지연과
구분되지 않는다.

같은 키로 엔드포인트별로 찔러 보면 즉시 갈린다.

| 엔드포인트 | 소속 서비스 | 결과 |
| --- | --- | --- |
| `arrive/getArrInfoByRouteAll` | 버스도착정보조회 | 200 |
| `arrive/getLowArrInfoByStId` | 버스도착정보조회 | 200 |
| `stationinfo/getStationByUid` | 정류소정보조회 | **401** |
| `stationinfo/getStationByPos` | 정류소정보조회 | **401** |
| `busRouteInfo/getBusRouteList` | 노선정보조회 | **401** |

우리 구현은 `getStationByPos`(좌표 → 정류소)와 `getStationByUid`(그 정류소의
전체 노선 도착정보)를 쓴다. 둘 다 **정류소정보조회 서비스**다. 그래서
`버스도착정보조회` 만 승인된 상태로는 동작하지 않는다.

`getArrInfoByRouteAll` 로 우회할 수는 있다. 노선 하나의 전체 정류소를
`arsId`·`stNm`·`arrmsg`·`exps` 까지 준다. 하지만 입력이 `busRouteId` 이고,
노선명(카카오가 주는 `5511`)에서 그 값을 얻으려면 `getBusRouteList`(노선정보
조회)가 필요해 역시 막힌다. 응답도 노선당 200KB 를 넘는다.

**정류소정보조회 서비스를 추가로 활용신청해서 풀었다.** 진단은
`jit-tools/probe_bus_services.py` 가 엔드포인트별로 찍어 준다. 승인되면
`stationinfo` 두 줄이 401 에서 200 으로 바뀐다.

### 401 이 풀린 뒤에 진짜 버그가 나왔다

승인 직후에도 도착정보가 하나도 붙지 않았다. **키가 막혀 있던 동안 이 코드가
실데이터로 돌아본 적이 없어서**, 필드 이름이 세 곳 다 다른 오퍼레이션
(`getArrInfoByRouteAll`)의 것으로 쓰여 있었다. 단위 테스트는 같은 이름으로 만든
가짜 응답을 검사했으므로 전부 통과했다.

| 코드가 읽던 이름 | 실제 이름 | 비고 |
| --- | --- | --- |
| `stNm` (getStationByPos) | `stationNm` | 같은 서비스인데 오퍼레이션마다 다르다 |
| `exps1` / `exps2` | `traTime1` / `traTime2` | `arrmsgSec` 는 이름과 달리 초가 아니라 문구다 |
| `brerde_Div` + `brdrde_Num` | `congestion1` / `congestion2` | 3 여유 / 4 보통 / 5 혼잡, 0 은 정보 없음 |

`arrmsg` 는 분 단위로 내림한다. 실측에서 `arrmsg1='2분후'` 인데
`traTime1=188`(3분 8초)였다. 초 단위를 쓰려면 `traTime` 이어야 한다.

**교훈: 외부 응답을 파싱하는 테스트의 픽스처는 실측 응답에서 옮긴다.**
지어낸 필드 이름은 통과하는 테스트와 동작하지 않는 기능을 동시에 만든다.
`test_realtime.py` 의 버스 절에 실측 응답 기반 테스트를 두 개 넣어 고정했다.

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

**`check_deployed.py` 로는 못 잡는다.** 그 스크립트는 자기 계정을 새로 만들어
검증하므로 어느 DB 에 붙어 있어도 통과한다. 실제로 51개 항목이 전부 통과한
상태에서 배포 서버가 Neon 이 아닌 DB 를 보고 있었다.

전용 스크립트를 쓴다. **배포 서버 API 로 쓰고 내 `DATABASE_URL` 로 직접 읽어**
같은 DB 인지 본다. 시드 데이터를 비교하지 않으므로 거짓 통과가 없다.

```powershell
cd backend
$env:DATABASE_URL="<Neon direct 주소>"
python scripts/check_same_db.py
```

| 종료 코드 | 뜻 |
| --- | --- |
| `0` | 같은 DB. 확인용 계정은 스크립트가 지운다 |
| `2` | **다른 DB.** Render 의 `DATABASE_URL` 을 고쳐야 한다 |
| `1` | 확인 실패(서버 무응답, `DATABASE_URL` 이 비어 SQLite 등) |

---

## 6. 검증 스크립트

`backend/scripts/` 에 있다. 전부 `python scripts/<이름>.py` 로 돈다.

**먼저 이것부터.** 로컬 서버를 상대로 도는 8개를 한 번에 돌린다. 서버 기동, DB
고정, 포트 확인을 알아서 하므로 손으로 창을 두 개 열 필요가 없다.

```bash
cd backend
python scripts/run_local_suite.py                          # 8개 전부
python scripts/run_local_suite.py --only check_events_api.py   # 하나만
```

| 스크립트 | 대상 | 용도 |
| --- | --- | --- |
| `run_local_suite.py` | 로컬 서버 | **아래 8개를 한 번에.** 서버를 띄우고 끝나면 내린다. CI 도 이것을 부른다 |
| `check_db_defaults.py` | **Neon** | 나중에 추가된 NOT NULL 컬럼에 DB 기본값이 있는지. 없으면 배포 중 쓰기가 500 이 된다 |
| `backup_db.py` | 현재 `DATABASE_URL` | JSON 백업. Neon 이 유일한 사본이라 위험한 작업 전에 돌린다 |
| `_capabilities.py` | — | 카카오 키 유무 판정. "키가 없어서 못 함" 과 "깨져서 실패" 를 가른다 |
| `_local_guard.py` | — | 팀 DB 로 검증 스크립트가 도는 것을 막는다 |
| `check_deployed.py` | **배포 서버** | 전 기능. HTTP 만 쓰므로 Django 설정이 필요 없다. **어느 DB 에 붙었는지는 보지 않는다** |
| `check_same_db.py` | 배포 서버 + 내 DB | 둘이 같은 DB 인지. API 로 쓰고 DB 에서 직접 읽어 대조 |
| `check_app_contract.py` | 로컬 서버 + **앱 소스** | 앱 DTO 필드 이름이 서버 응답과 맞는지 95개 항목. 아래 설명 참고 |
| `check_route_api.py` | 로컬 서버 | 경로 후보·선택 33개 항목 |
| `check_origin_api.py` | 로컬 서버 | 출발지 선택 17개 항목 |
| `check_routines_api.py` | 로컬 서버 | 루틴 블록 CRUD·일정별 체크·블록 관측 45개 항목 |
| `check_events_api.py` | 로컬 서버 | 일정 CRUD |
| `check_observations_api.py` | 로컬 서버 | 관측 업로드 |
| `check_timezone.py` | 로컬 서버 | KST↔UTC 변환. 어긋나면 알람이 9시간 틀어진다 |
| `check_auth_api.py` | 로컬 서버 | 회원가입·로그인·토큰 갱신 |
| `check_external_apis.py` | 외부 API | 카카오·기상청 키 상태 |
| `db_counts.py` | 현재 `DATABASE_URL` | 모델별 행 수. 이관 전후 대조 |
| `seed_demo_via_api.py` | 배포 서버 | 데모 계정 생성(HTTP) |
| `purge_test_accounts.py` | 현재 `DATABASE_URL` | 검증 스크립트가 남긴 `depcheck_`·`dbprobe_` 계정 정리. 기본 dry-run |
| `migrate_sqlite_to_postgres.py` | — | SQLite → Postgres 일회성 이관 |

### `check_app_contract.py` — 앱이 조용히 필드를 버리는 것을 잡는다

앱은 **Gson** 으로 역직렬화한다. Gson 은 JSON 에 없는 필드를 조용히 null 로
두고, 이름이 어긋난 필드도 **예외 없이** 버린다. 그래서
`@SerializedName("prep_breakdown")` 을 잘못 적어도 앱은 정상 동작하는 것처럼
보이고 화면에만 값이 비어 보인다. 그 증상은 "서버가 아직 안 주는 것" 과 구별되지
않는다.

실제로 `confidence_basis` 와 `prep_breakdown` 이 서버에는 있는데 앱 DTO 에는
없어서 몇 주 동안 버려졌다. 근거 카드가 비어 있는 이유를 "학습이 덜 됐다" 로
오해했다.

이 스크립트는 앱의 Kotlin 파일에서 `@SerializedName` 을 읽어 **와이어 이름**을
뽑고, 실제 서버 응답에 그 이름이 전부 있는지 본다. 반대 방향(서버가 주는데 앱이
읽지 않는 필드)도 경고로 알린다.

```bash
cd backend
python scripts/run_local_suite.py --only check_app_contract.py
```

#### 같은 함정의 다른 얼굴 — Gson 은 Kotlin 기본값도 모른다

이름이 어긋난 필드를 버리는 것과 **같은 원인**으로 생기는 사고가 하나 더 있다.
Gson 은 생성자를 건너뛰고 필드를 리플렉션으로 채우기 때문에, JSON 에 키가 없으면
그 필드는 **선언이 non-null 이어도 null 로 남는다.** Kotlin 의 기본값은 적용되지
않고, 컴파일러는 경고도 하지 않는다.

디스크에 저장한 JSON 에서 이게 터진다. 구버전 앱이 저장한 알람 사본에는 뒤에
추가한 `prepBlocks` 키가 없어서, 업그레이드 직후 `canLogBlocks` 가 그 null 을
읽고 죽었다 — **알람을 해제하는 순간**, 사용자가 앱을 가장 필요로 하는 시점이다.

그래서 되살리는 모든 경로가 `data/local/DiskCompat.kt` 의 `withDiskDefaults()` 를
지난다. `AlarmSchedule` 이나 `MorningSession` 에 non-null 참조 필드를 추가하면
거기도 고쳐야 하는데, 잊어도 `DiskCompatTest` 가 잡는다 — 필드 이름을
하드코딩하지 않고 "정상 인스턴스에는 있는데 되살린 인스턴스에는 없는 키" 를
찾기 때문이다.

### 포트가 이미 잡혀 있으면 멈출 것

로컬 검증에서 가장 위험한 실수다. 남아 있던 서버가 8000 을 잡고 있으면 새로
띄운 서버는 바인드에 실패하는데, 헬스체크는 **옛 서버의 200** 을 보고 기동
성공으로 읽는다. 그 뒤 모든 검증이 구버전 코드를 상대로 돌면서 통과한다.

`run_local_suite.py` 는 이걸 확인하고 **조용히 재사용하지 않고 멈춘다.** 직접
서버를 띄워 돌릴 때는 아래로 확인할 것.

```powershell
Get-NetTCPConnection -LocalPort 8000 -State Listen | Select-Object OwningProcess
Stop-Process -Id <PID>
```

배포 쪽의 같은 함정이 아래 "배포된 코드가 뒤처졌는지 판별" 이다.

**로컬 대상 스크립트는 임시 계정을 만든다.** 끝에 지우지만 중간에 끊기면
남는다. `DATABASE_URL` 이 공용 DB 를 가리킨 상태로 돌리지 말 것.

`check_deployed.py` 가 만든 계정은 `depcheck_` 접두가 붙는다. 사용자 삭제 API 가
없어 공용 DB 에 남으므로 `purge_test_accounts.py` 로 정리한다.

```powershell
cd backend
$env:DATABASE_URL="<공용 DB 주소>"
python scripts/purge_test_accounts.py        # 무엇이 지워지는지만 본다
python scripts/purge_test_accounts.py --yes  # 실제로 지운다
```

기본이 dry-run 이다. 계정을 지우면 딸린 일정·알람계획·관측도 cascade 로
사라지므로, 지울 목록과 남는 목록을 먼저 출력한다. **실행 전에 남는 목록에
실제 계정이 다 있는지 눈으로 확인할 것.**

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

---

## 8. 배포된 코드가 뒤처졌는지 판별

**`/api/health` 가 200 이어도 새 코드가 떴다는 뜻이 아니다.** Render 자동 배포가
멈추면 옛 컨테이너가 그대로 돌면서 200 을 준다. 그 상태에서 "배포 서버 검증
통과" 는 **구버전 결과**다. 실제로 그 함정에 빠져 여러 커밋이 배포되지 않은 채
검증을 통과했다.

버전 문자열만 믿지도 않는다. `APP_VERSION` 을 올리는 것을 잊을 수 있다. 그래서
**새 엔드포인트의 응답 코드**로 판별한다 — 인증이 필요한 경로는 배포됐으면 401,
미배포면 404 다. 이 구분이 핵심이다.

```powershell
$base = "https://justintime-api.onrender.com"

# 1) 버전
curl.exe "$base/api/health"
#    {"ok":true,"version":"0.3.0"}  ← 0.3.0 미만이면 구버전

# 2) 엔드포인트 존재 (토큰 없이 부른다)
#    401 = 배포됨 · 404 = 미배포
curl.exe -s -o NUL -w "%{http_code} routines/blocks`n" "$base/api/routines/blocks"
curl.exe -s -o NUL -w "%{http_code} events/import`n"   "$base/api/events/import"
curl.exe -s -o NUL -w "%{http_code} reports/weekly`n"  "$base/api/reports/weekly"
```

미배포로 나오면 대시보드에서 확인한다.

1. Settings > Build & Deploy > **Auto-Deploy 가 Yes 인가**
2. 연결 **Branch 가 `main`** 인가
3. **Events 탭에 실패한 배포**가 있는가 (빌드 오류·메모리 초과)
4. Manual Deploy > **Deploy latest commit** 으로 즉시 띄울 수 있다

### 401 은 "존재한다" 일 뿐이다

위 방법은 라우팅이 붙었는지만 본다. **라우팅은 붙었는데 직렬화나 권한이 깨진
경우를 놓친다.** 그래서 배포 뒤에는 기능까지 돌려 본다.

```bash
python scripts/check_deployed.py     # 82개 항목
```

0.3.0 기능(루틴 블록, 블록 관측, 캘린더 가져오기, 리포트)은 [11]~[15] 구간에서
확인한다. 몇 주 동안 404 였던 엔드포인트라서 존재 여부와 동작을 나눠 본다.

### 마이그레이션과 코드가 어긋나면 500 이 난다

한 번 겪은 조합이다. Neon 에 마이그레이션은 적용됐는데 컨테이너는 구버전이었다.
새로 추가한 NOT NULL 컬럼을 구버전 코드가 INSERT 에 넣지 않아 **쓰기만** 500 이
됐다. 읽기와 `/api/health` 는 정상이어서 겉으로는 멀쩡해 보였다.

**이 상황은 배포가 두 단계인 한 반드시 생긴다** — 마이그레이션은 DB 에 즉시
적용되고 코드는 컨테이너가 교체될 때 바뀐다. 그 사이가 항상 존재한다. 개발도
Neon 을 보므로 로컬 `migrate` 한 번으로 그 상태가 만들어진다.

그래서 검사를 스크립트로 만들었다. 새 컬럼을 추가하면 돌릴 것.

```bash
python scripts/check_db_defaults.py   # 검사 10건 · 위험 0건
```

그래서 새 컬럼에는 `db_default` 를 반드시 준다(Django 5.0+). Django 의 `default`
는 파이썬 쪽 기본값이라 `AddField` 가 기존 행을 채운 뒤 **DB 기본값을 남기지
않는다** — 구버전 코드의 INSERT 가 그 컬럼을 비운 채 보내면 제약 위반이 된다.

```python
# 이렇게 쓴다
confidence_basis = models.CharField(max_length=32, blank=True, default="", db_default="")
```

### 앱이 스스로 알려 준다

이제 앱이 `/api/health` 의 `version` 을 받아 자기 `versionName` 과 비교한다.
서버가 더 낮으면 로그에 `Log.e` 로 남기고, 화면의 실패 메시지 뒤에 이유를 붙인다.

```
ServerWarmup  E  서버가 구버전입니다 (서버 0.1.0 · 앱 0.3.0). 새 기능이 404 로
                 실패합니다. Render 대시보드 > Manual Deploy > Deploy latest
                 commit 으로 배포하세요.
```

**이 값은 처음부터 응답에 있었고 앱이 버리고 있었다.** 배포가 뒤처지면 새
엔드포인트가 404 로 돌아오는데, 그 증상은 앱 버그와 구별되지 않아서 실제로 팀이
앱을 의심하며 시간을 썼다. 이제 그 자리에서 원인을 말한다.

서버가 **더 높은** 경우는 경고하지 않는다 — 서버를 먼저 올리고 앱을 배포하는 것이
정상 순서다. 읽을 수 없는 버전 문자열도 경고하지 않는다. 틀린 경고를 한 번 보면
다음 진짜 경고도 무시되기 때문이다. `ServerVersionTest` 가 그 경계를 고정한다.

---

## 9. 지금 구현된 것 (0.3.0)

프로토타입 범위가 닫혔다. 아래가 실제로 도는 것이다.

### 서버

| 영역 | 내용 |
| --- | --- |
| 분포 엔진 | Normal·Empirical·Mixture, `convolve`(정규+정규는 해석해), `max_of`(병렬 블록), 베이지안 갱신, shrinkage. **numpy 를 쓰지 않는다** |
| 확신도 | `on_time_probability` 는 준비·이동 **양쪽** 변동성이 있을 때만 채운다. 없으면 `confidence_basis` 가 이유를 말한다(4종) |
| 루틴 블록 | 사용자별 정의 CRUD, 일정별 체크(저장 시 그 일정만 재계산), 블록 관측 배치 |
| 학습 | 관측 기반 모수 갱신, 경로 보정 계수, `slack_coef`. Celery 대신 관리 커맨드 |
| 캘린더 | `POST /api/events/import` — `external_id` 로 upsert. **변경 없으면 재계산하지 않는다**(카카오 쿼터) |
| 리포트 | `/api/reports/weekly`, `/api/reports/calibration` — 모델 없이 기존 관측에서 파생 |

### 앱

| 영역 | 내용 |
| --- | --- |
| 근거 카드 | 준비 블록별 내역, 신고 범위 대비 실측, 확률이 없는 이유와 **사용자가 할 일** |
| 루틴 편집기 | 정의 편집 / 일정별 체크 두 모드. 일정별은 저장 한 번에 묶어 보낸다 |
| 오프라인 | Room 캐시. 응답 JSON 을 그대로 저장해 온라인·오프라인 화면이 같은 매핑을 탄다 |
| 배경 동기화 | WorkManager — 관측 업로드(연결되는 순간), 계획 동기화(6시간) |
| 캘린더 | 목록을 보여주고 **고른 것만** 보낸다. 장소는 찾아 주되 확정하지 않는다 |
| 리포트 | 약속한 확률과 실제 정시율을 나란히. 표본 부족이면 판정하지 않는다 |
| 아침 기록 | 알람 해제 시점에 세션이 시작된다. 블록당 탭 한 번(종료)으로 소요와 **그 블록 시작 시점의 슬랙**을 기록해 올린다. 이것이 준비 시간 학습의 유일한 재료다 |
| 로그인 유지 | 한 번 로그인하면 **로그아웃할 때까지** 유지된다. access 30분이 만료되면 refresh 로 자동 갱신하고, **네트워크 오류로는 로그아웃되지 않는다** |

### 검증 규모

```
백엔드 pytest              591
앱 단위 테스트             303
로컬 HTTP 검증 스크립트     8개 전부 통과 (항목 합계 265, 필드 계약 95항목 포함)
배포 서버 전 기능           82건 통과 · 실패 0 (check_deployed.py)
DB 기본값 검사              10건 · 위험 0 (check_db_defaults.py)
빌드                       assembleDebug / assembleRelease 성공
CI                         .github/workflows/ci.yml — 위 셋을 푸시·PR 마다 돌린다
```

세 가지를 손으로 돌리는 방법이다. CI 가 돌리는 것과 같다.

```bash
cd backend && python -m pytest                       # 591
cd backend && python scripts/run_local_suite.py      # 8개 스크립트, 서버 기동까지 알아서 한다
cd APP && ./gradlew testDebugUnitTest lintDebug assembleDebug  # 303 + lint
```

### CI

`.github/workflows/ci.yml` 에 잡 셋이 있다. 나눈 이유는 실패 원인을 제목에서
바로 읽기 위한 것이다 — 하나로 합치면 "CI 실패" 만 보이고 로그를 열어야 어디가
깨졌는지 알 수 있다.

| 잡 | 하는 일 |
| --- | --- |
| `백엔드 pytest` | `config.settings.test` 로 591건. 메모리 SQLite 고정이라 환경변수를 하나도 주지 않는다 |
| `앱-서버 계약 검사` | 서버를 실제로 띄우고 `check_*.py` 8개. pytest 가 못 잡는 라우팅 누락·직렬화 모양을 잡는다 |
| `앱 단위 테스트 · 디버그 빌드` | JDK 25(데몬) + 21(툴체인), SDK `platforms;android-37.0` |

**시크릿을 쓰지 않는다.** `KAKAO_REST_API_KEY` 를 넣지 않았다 — 무료 쿼터가
계정당 하루 단위라 CI 가 그것을 태우면 사람이 개발을 못 한다.

#### "키가 없어서 못 함" 과 "깨져서 실패" 를 구분한다

이 구분을 안 하면 두 방향으로 거짓말을 한다.

- 키가 없을 때 **실패**로 적으면 CI 가 항상 빨갛다. 빨간불이 상수가 되면 팀이
  그것을 무시하고, 그때부터 CI 는 없는 것과 같다.
- 경로 실패를 전부 **건너뜀**으로 적으면 진짜 경로 회귀가 조용히 숨는다.

그래서 판정 기준을 결과가 아니라 **키 유무**로 둔다(`scripts/_capabilities.py`).

```
키가 없다             → 건너뜀 (검사할 수 없었다)
키가 있는데 경로 실패  → 실패   (회귀다)
```

키가 없어도 **서버 자체 로직은 계속 검사한다.** 좌표 검증, 권한, `route_key`
형식, 소유자 격리는 카카오와 무관하다. 게다가 "경로를 못 구했을 때 500 이 아니라
200 과 이유를 돌려주는가" 는 키가 없을 때만 확인할 수 있어서, 그 항목은 오히려
CI 에서만 검사된다.

확인한 수치다. 같은 코드로 두 조건에서 돌렸다.

| | 카카오 키 있음 | 키 없음(CI 조건) |
| --- | --- | --- |
| `check_events_api.py` | 통과 20 | 통과 18 · 건너뜀 4 |
| `check_route_api.py` | 통과 33 | 통과 16 · 건너뜀 18 |
| `check_origin_api.py` | 통과 17 | 통과 14 · 건너뜀 5 |
| `check_routines_api.py` | 통과 45 | 통과 39 · 건너뜀 2 |
| `check_app_contract.py` | 통과 95 | 통과 85 · 건너뜀 2 |
| 나머지 3개 | 전부 통과 | 전부 통과 |
| **실패** | **0** | **0** |

CI 는 `.env` 와 `local.properties` 가 **없는** 상태로 돈다. 그래서 커밋되지 않은
파일에 기대는 코드가 섞여 들어오면 여기서 드러난다. `local.properties` 를 치우고
`ANDROID_HOME` 만 준 상태로 `assembleDebug` 도 확인했다 — 성공하고 서버 주소가
`gradle.properties` 의 `jitApiBaseUrl` 로 폴백했다.

러너는 **아무것도 검사하지 않고 0 을 돌려준 스크립트를 실패로 뒤집는다.** 다만
건너뜀이 있으면 판정하지 않는다 — 키가 없어 전부 건너뛴 것은 정상이다.

### 로그인 유지 — 무엇이 세션을 끝내는가

토큰은 `TokenStore`(SharedPreferences)에 남고, `LoginActivity` 가 시작할 때
`isLoggedIn` 을 보고 로그인 화면을 건너뛴다. 서버 설정은 **access 30분 /
refresh 14일**이다(`SIMPLE_JWT`).

access 가 만료되면 `TokenRefreshAuthenticator` 가 401 을 받아 refresh 로 갱신하고
요청을 한 번 재시도한다. 여기서 **실패를 한 종류로 보면 안 된다.**

| refresh 응답 | 판정 | 토큰 |
| --- | --- | --- |
| 200 + `access` | 갱신 | 새 access 저장 |
| 400 · 401 · 403 | **거절** | 삭제 → 로그인 화면 |
| 5xx · 404 · 429 | 보류 | **유지** |
| IOException·타임아웃 | 보류 | **유지** |

애매한 경우를 유지 쪽으로 기울인 것이 의도다. 잘못 지우면 사용자가 다시
로그인해야 하고, 잘못 유지하면 다음 요청이 401 을 한 번 더 받을 뿐이다. 비용이
비대칭이다. 예전에는 모든 실패를 만료로 봐서 **지하철에서 앱을 열면 로그아웃**됐다.

배포 서버에 직접 물어 분기를 맞췄다. 정상 refresh 는 200 이고 본문 키가
`['access']` 하나, 망가진 토큰은 401, 필드 누락은 400 이다. 같은 refresh 를 두 번
써도 200 이라 **회전이 꺼져 있다**(`ROTATE_REFRESH_TOKENS` 미설정).

> 서버에서 회전을 켜면 앱도 고쳐야 한다. 앱은 응답의 새 refresh 를 저장하지
> 않으므로 `BLACKLIST_AFTER_ROTATION` 까지 켜는 순간 전원이 로그아웃된다.

세션이 정말 끝나면 `SessionState` 가 화면에 알리고 `MainActivity` 가 로그인 화면으로
되돌린다. 이 통보가 없으면 사용자는 홈에 남아 "다시 로그인해야 한다" 만 반복해서
보고 나갈 길을 스스로 찾아야 한다 — 로그인 유지를 넣는 순간 드러나는 구멍이라
함께 막았다.

### 로컬 저장소를 추가하는 사람에게 — 소유자 격리

기기를 공유하거나 계정을 바꿨을 때 **앞 사용자의 데이터가 보이면 안 된다.** 이
보장은 "로그아웃 때 지운다" 가 아니라 **조회가 소유자를 요구한다** 는 성질에서
나온다. 지우는 호출은 앱이 강제 종료되면 돌지 않는다.

Room 캐시는 처음부터 그렇게 만들었는데(`ownerEmail` 칸 + 모든 조회가 요구),
뒤에 추가한 SharedPreferences 저장소들이 그 규칙 밖에 있었다. 실제로 새고 있었다
— A 가 로그아웃하고 같은 기기에서 B 가 로그인하면 `HomeViewModel.init` 이
`reloadMorning()` 을 부르면서 **B 가 A 의 아침 기록을 봤다.** 블록 이름("샤워",
"약 먹기")과 진행 상황까지.

새 SharedPreferences 저장소를 만들면 두 줄을 넣어야 한다.

```kotlin
fun read(): T? {
    if (prefs.discardIfForeign(appContext)) return null   // 읽을 때
    ...
}

fun write(value: T) {
    prefs.edit { ... }
    prefs.stampOwner(appContext)                          // 쓸 때
}
```

그리고 `LocalStores.wipeAll` 목록에 추가한다. 잊으면 `PrefsScopeAuditTest` 가
잡는다 — 그 테스트는 저장소 목록을 들고 있고, 읽기·쓰기 양쪽과 지우기 목록을
모두 확인한다.

**판정 규칙이 느슨한 것은 의도다.**

```
저장된 소유자 != 지금 로그인한 계정   → 막는다
저장된 소유자 == 지금 로그인한 계정   → 쓴다
지금 로그인한 계정을 모른다           → 막지 않는다
저장된 소유자가 없다(구버전 데이터)    → 막지 않는다
```

아래 두 줄을 조이면 **알람이 울리지 않는다.** refresh 토큰이 만료되면
`AuthInterceptor` 가 토큰을 지우는데, 그 상태에서 재부팅하면 `BootReceiver` 가
알람을 다시 등록해야 한다. 거기서 막으면 세션이 끊긴 사용자의 아침 알람이 사라진다
— 세션이 만료됐어도 9시 수업은 그대로 있다. 구버전 데이터도 같은 이유로 막지
않는다. 교차 노출은 "다른 계정이 로그인해 있다" 가 확인될 때만 일어난다.

`PrefsOwnerRuleTest` 가 이 네 경우를 전부 고정한다.

### 아직 없는 것

- **푸시(FCM)** — 의존성만 있고 코드가 없다. P4 범위다
- **자연어 일정 입력** — OpenAI 연동 미착수
- **날씨 보정** — 기상청 클라이언트는 있으나 계획에 반영하지 않는다
- **실기기 검증 — 일부만 했다.** SM-S921N(Android 16)에서 설치·실행까지
  확인했다. 남은 것은 앱 완전 종료 후 로그인 유지, 비행기 모드에서
  로그아웃되지 않는지, 알람이 잠금화면 위에 뜨는지, 앱 업데이트
  (`MY_PACKAGE_REPLACED`) 후 알람 재등록이다. `APP/scripts/device_*.ps1` 로 한다

  **이걸 건너뛰면 무엇을 놓치는지 이미 겪었다.** 단위 테스트 154개와
  lint 오류 0, 배포 계약 82건이 전부 초록인 상태로 앱이 켜지지도 않은
  커밋이 여러 개 올라갔다. `HomeViewModel.init` 이 자기보다 아래에 선언된
  저장소를 읽어 시작하자마자 `NullPointerException` 이 났다. 자동 검사는
  이 종류를 볼 수 없다 — `AndroidViewModel` 은 `Application` 이 필요해
  단위 테스트가 생성조차 하지 않고, 초기화 순서는 타입 오류가 아니라
  컴파일도 통과한다. 그 뒤 `HomeViewModelInitOrderTest` 로 이 순서만은
  막아 두었지만, **한 번 실행해 보는 것을 대신하지는 못한다.**
