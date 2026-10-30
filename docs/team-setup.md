# 팀 셋업 — 공용 서버·DB·배포

앱 실행은 [APP/README](../APP/README.md), 로컬 백엔드는 [backend/README](../backend/README.md)를 따른다.
이 문서는 공용 서버와 DB를 운영할 때 알아야 할 것만 적는다.

## 구성

| 항목 | 값 |
| --- | --- |
| 공용 API | <https://justintime-api.onrender.com> (Render 무료, Docker, 싱가포르) |
| DB | **Neon Postgres 하나.** 개발과 배포가 같은 DB를 본다 |
| 앱 기본 주소 | `APP/gradle.properties`의 `jitApiBaseUrl`(커밋됨). `local.properties`의 `devServerHost`가 있으면 그쪽이 우선 |
| 공통 확인 계정 | `demo@demo.com` / `demo1234` (집=신림역) |
| 비밀 값 | 저장소에 없다. 서버는 Render 환경변수, 로컬은 각자 `backend/.env` |

`APP/app/google-services.json`은 클라이언트용이라 커밋돼 있다. 카카오 키는 계정당 무료 쿼터가 앱 1개에만 붙으므로 팀이 하나를 공유한다.

## 공용 DB는 유일한 사본이다

- `dev`·`prod` 설정은 `DATABASE_URL`이 비면 시작하지 않는다. 조용히 로컬 SQLite로 떨어지지 않는다.
- `pytest`(메모리 SQLite)와 `run_local_suite.py`(임시 SQLite)만 공용 DB를 쓰지 않는다.
- 로컬 `migrate`·`flush`·셸 삭제는 **팀 데이터에 바로 반영된다.** 위험한 작업 전에 `python scripts/backup_db.py`.
  백업에는 비밀번호 해시가 들어 있으니 커밋하지 않는다.
- `check_deployed.py`가 만든 `depcheck_` 계정은 `python scripts/purge_test_accounts.py`(기본 dry-run → `--yes`)로 지운다.

## 배포

`main`에 push하면 Render가 자동 배포한다. 컨테이너가 시작할 때 `migrate`를 돌린 뒤 gunicorn을 띄운다.

1. 서버 `APP_VERSION`과 앱 `versionName`/`versionCode`를 같은 커밋에서 올린다.
2. 배포 뒤 `/api/health`의 `version`을 확인한다. **200만으로는 새 코드가 떴다는 뜻이 아니다.**
3. 버전이 낮으면 Render 대시보드에서 Auto-Deploy·브랜치(`main`)·Events 탭의 실패를 확인하고
   Manual Deploy → Deploy latest commit을 누른다.
4. `python scripts/check_deployed.py`로 기능을 확인한다.

**마이그레이션이 코드보다 먼저 적용되는 틈이 있다.** 그 사이 구버전 코드의 INSERT가 새 NOT NULL 컬럼을
비우면 쓰기만 500이 난다. 새 컬럼에는 `db_default`를 주고 `python scripts/check_db_defaults.py`로 확인한다.
매일 학습 workflow는 운영 DB의 마이그레이션이 덜 적용돼 있으면 멈춘다.

서버가 뒤처지면 앱이 오류 메시지 뒤에 "서버가 구버전" 이유를 붙인다(`ServerWarmup`).

### Render를 떠나야 할 때

`backend/Dockerfile`이 실행을 담당하므로 어디서나 같은 이미지로 띄운다(GCP 무료 크레딧 등).
필수 환경변수는 `DJANGO_SECRET_KEY`·`DATABASE_URL`·`DJANGO_ALLOWED_HOSTS`이고, 나머지는 [README](../README.md#configuration)를 따른다.
TLS를 반드시 앞에 두고, 새 주소를 `jitApiBaseUrl`에 넣는다.

```bash
docker build -t justintime-api ./backend
docker run -d --restart=always -p 8000:8000 \
  -e DJANGO_SECRET_KEY=... -e DATABASE_URL=postgres://... -e DJANGO_ALLOWED_HOSTS=api.example.com \
  justintime-api
```

Neon은 **direct** 주소(`-pooler` 없는 쪽)를 쓴다. pooler는 세션 기능이 없어 마이그레이션이 깨질 수 있다.

## 제약

- **무료 플랜은 15분 무요청이면 잠든다.** 깨는 데 30~60초. 앱은 로그인 전에 `/api/health`로 깨우고,
  GET만 재시도하며(POST는 중복 생성 위험), 2초가 넘으면 화면에 알린다. 시연 직전에는 미리 깨운다.
- **실시간 버스 키(data.go.kr)는 서비스 단위로 승인된다.** 401이면 "정류소정보조회" 서비스 승인을 먼저 확인한다.
  로컬 `.env`는 Render에 올라가지 않으므로 키는 대시보드에도 넣는다. 설정 여부는 `/api/health`가 알려 준다.

## 검증 스크립트 (`backend/scripts/`)

| 스크립트 | 대상 | 용도 |
| --- | --- | --- |
| `run_local_suite.py` | 로컬 서버 | `check_*.py` 8개를 한 번에. 서버를 띄우고 내린다. CI도 이것을 쓴다 |
| `check_app_contract.py` | 로컬 서버 + 앱 소스 | 앱 `@SerializedName`이 서버 응답에 다 있는지 |
| `check_deployed.py` | 배포 서버 | 전 기능. 어느 DB에 붙었는지는 보지 않는다 |
| `check_same_db.py` | 배포 서버 + 내 DB | 배포 서버가 Neon을 보는지(0 같음 · 2 다름) |
| `check_db_defaults.py` | Neon | 새 NOT NULL 컬럼의 DB 기본값 |
| `backup_db.py` · `purge_test_accounts.py` | 현재 `DATABASE_URL` | 백업 · 검증 계정 정리 |
| `seed_demo_via_api.py` | 배포 서버 | 데모 계정 생성 |

`run_local_suite.py`는 8000 포트가 이미 잡혀 있으면 멈춘다. 남은 서버가 헬스체크에 답하면 구버전 코드로 통과하기 때문이다.
카카오 키가 없으면 경로 항목은 실패가 아니라 건너뜀이다.

## 코드에서 지킬 것

- **Gson은 이름이 틀린 필드와 Kotlin 기본값을 조용히 버린다.** DTO 필드는 `check_app_contract.py`로,
  디스크에서 되살리는 객체는 `DiskCompat.withDiskDefaults()`와 `DiskCompatTest`로 막는다.
- **로컬 저장소는 소유자를 확인한다.** 새 SharedPreferences 저장소는 읽을 때 `discardIfForeign`, 쓸 때 `stampOwner`를 부르고
  `LocalStores.wipeAll`에 추가한다(`PrefsScopeAuditTest`가 확인).
- **토큰 갱신 실패를 한 종류로 보지 않는다.** refresh가 400·401·403이면 로그아웃, 5xx·네트워크 오류면 토큰을 유지한다.
- 외부 API 파싱 테스트의 픽스처는 실측 응답에서 옮긴다.
