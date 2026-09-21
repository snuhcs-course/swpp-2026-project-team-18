# 팀 셋업 가이드

여러 사람이 같이 개발하기 위한 설정이다. 예전에는 한 사람이 자기 PC 에
`runserver` 를 띄워 둔 동안만 앱이 동작했고 DB 도 각자 것이었다. 이제
**상시 서버 하나 + 공용 DB 하나**를 두고 전원이 같은 데이터를 본다.

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

   `render.yaml` 에 Render 자체 Postgres 정의가 들어 있다. Neon 을 쓰려면 그
   블록을 지우거나, 만들어진 뒤 `DATABASE_URL` 을 Neon 주소로 덮어쓴다.

4. 배포가 끝나면 주소가 나온다: `https://justintime-api.onrender.com`
5. 확인

   ```bash
   curl https://justintime-api.onrender.com/api/health
   # {"ok":true,"version":"0.1.0"}
   ```

6. 데모 계정을 만든다. Render 대시보드의 **Shell** 에서

   ```bash
   python manage.py seed_demo
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

# 1. 로컬 SQLite 덤프 (DATABASE_URL 이 비어 있어야 한다)
python scripts/migrate_sqlite_to_postgres.py dump

# 2. 공용 DB 로 적재
$env:DATABASE_URL="postgres://...neon.../justintime?sslmode=require"
python scripts/migrate_sqlite_to_postgres.py load
```

`contenttypes` 와 `auth.permission` 은 제외한다 — 새 DB 의 마이그레이션이
스스로 만들기 때문에 그대로 넣으면 충돌한다.

---

## 3. 알아야 할 제약

### 무료 플랜은 잠든다

Render 무료 웹 서비스는 **15분 동안 요청이 없으면 잠든다.** 다시 깨는 데
30~60초 걸린다. 팀원이 "로그인이 안 된다" 고 할 때 대개 이것이고, 잠시 뒤
다시 시도하면 된다.

앱의 타임아웃은 연결 10초 / 읽기 15초다(`ApiClient`). 콜드 스타트 첫 요청은
이 안에 안 끝날 수 있다. 시연 전에는 미리 한 번 찔러 깨워 둘 것.

없애려면 Starter 플랜($7/월)으로 올리거나, 외부에서 5분마다 `/api/health` 를
치는 방법이 있다(무료 플랜에서 권장되지 않는다).

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
| 내 백엔드 변경이 앱에 안 보임 | `local.properties` 의 `devServerHost` 가 없어 공용 서버에 붙었다 |
| 로컬에서 남의 데이터가 보임 | `.env` 의 `DATABASE_URL` 이 공용 DB 를 가리킨다 |

실기기 연결은 `docs/device-setup.md`, 기기 디버깅 도구는
`APP/scripts/README.md` 를 볼 것.
