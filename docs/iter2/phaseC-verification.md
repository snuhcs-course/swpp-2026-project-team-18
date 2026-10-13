# Phase C 구현 검증 기록 — 데이터 기록(P12)과 DB 추가 변경

- 일시: 2026-10-13. worktree `SWPP-PJ-front-iter2`, 브랜치 `front-iter2`, 기준 `71c9f0d`. 리뷰 승인 뒤 아래 "커밋 전 검증 게이트" 를 다시 통과시키고 front-iter2 에 로컬 커밋했다(push 는 하지 않음).
- 기준 설계: [db-schema.md](db-schema.md) §7 A1~A7 + §9 보존. §8(P1~P9)은 제안이라 손대지 않았다. §5 제약대로 도착 `observed_at` 계산과 `prediction/learning.py`·`reports/services.py` 의 사용은 그대로다.
- 이전 실행이 남긴 미완성 작업(WIP, 백업 `%TEMP%\jit-wf\phaseC-wip-backup`)을 §7 과 항목별로 맞춰 본 뒤 이어서 끝냈다.

## 실행한 명령과 결과

백엔드 (`backend`, 인터프리터 `SWPP-PJ\backend\.venv`):

```
python -m pytest -q
1127 passed, 12 warnings in 15.21s        (기준 71c9f0d 1040, 이어받은 WIP 상태 1041 중 admin 1건 실패)

python scripts\run_local_suite.py --po 8013   (아래 "포트" 참고)
스크립트 8개 · 실패 0개
  check_auth_api 22/22 · check_events_api 21/0/4 · check_route_api 16/0/19
  check_origin_api 14/0/5 · check_observations_api 28/28 · check_timezone 통과
  check_routines_api 39/0/2 · check_app_contract 94/0/4

python scripts\check_db_defaults.py          (DATABASE_URL = 로컬 일회용 SQLite)
vendor = sqlite → "Postgres 가 아니라 건너뛴다" (exit 0)
같은 규칙을 모델 필드로 확인(DB 연결 없음, test 설정)
checked 20 (0001_initial 이후 AddField) · risky 0, entered_at null=True

python manage.py makemigrations --check --dry-run   (test 설정)
No changes detected
```

- 경고 12건은 JWT 테스트 키 길이 경고다(작업 전 11건 + 이번 로그인 기록 테스트 1건).
- 이번에 더한 86건: `test_request_log.py` 36(정리 규칙·미들웨어·보존), `test_alarm_logs.py` 22, `test_plan_revision.py` 14, `test_entered_at.py` 8, `test_summary.py` +6(LLM 호출 기록·상한). `test_admin.py` 는 건수는 같고 새 표 4개를 채워 목록·수정 화면·일정 inline 을 열고 추가 화면이 403(읽기 전용)인지 본다.
- 키·네트워크 없이 돈다. 공용 Neon 에는 붙지 않았다(pytest 는 메모리 SQLite, 회귀는 임시 SQLite).
- **포트.** 8000 은 IDE(Kiro) 프로세스가 잡고 있어 `--port 8013`(argparse 줄임 `--po`)으로 돌렸다. 회귀 스크립트 6개(`check_auth/events/route/origin/observations_api`, `check_timezone`)는 주소가 `127.0.0.1:8000` 으로 고정이라, 저장소 밖 임시 `sitecustomize.py`(`PYTHONPATH`, `JIT_SUITE_REDIRECT`)로 그 주소만 8013 으로 돌렸다. 스크립트·러너는 고치지 않았고 끝난 뒤 지웠다. 8000 이 비어 있는 PC·CI 에서는 그냥 `run_local_suite.py` 로 돈다.
- 회귀가 끝난 임시 DB 를 읽어 보니 요청 기록 186행(사용자 11명)에 비밀번호·토큰 값 0, 좌표 숫자 0. 서버 로그에 기록 실패 경고 없음.

앱 (`APP`, `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`):

```
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
BUILD SUCCESSFUL in 2m 52s
```

- 단위 테스트 **618건, 실패 0, 오류 0**(작업 전 598). 더한 것: `AlarmLogTest` 8, `TripObservationsTest` 7, `AppVersionInterceptorTest` 2, `TripGeofenceTest` +3. `PrefsScopeAuditTest` 목록에 `AlarmLogQueue` 를 더했다(소유자 격리·로그아웃 삭제 검사).
- lint: **오류 0**, 경고 62, 힌트 1(작업 전과 같은 수). `AlarmScheduler.kt` 의 `MissingPermission`·`UseKtx` 경고는 손대지 않은 `register`·`firePendingIntent` 줄의 기존 경고다.

## 커밋 전 검증 게이트 (리뷰 승인 뒤 재실행)

하나라도 실패하면 커밋하지 않는 조건으로 다시 돌렸다. 모두 통과했다.

| # | 명령 | 결과 | 수치 |
| --- | --- | --- | --- |
| 1 | `python -m pytest -q` (backend) | 통과 | 1127 passed, 경고 12 (기준 1040) |
| 2 | `python scripts\run_local_suite.py` (backend) | 통과 | 8/8, 실패 0 |
| 3 | DB 기본값 검사 (`check_db_defaults.py` 규칙) | 통과 | 검사 20 · 위험 0 |
| 4 | `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` (APP) | 통과 | 단위 테스트 618 (실패·오류 0, 기준 598), lint 오류 0 · 경고 62 |

- 2번: 8000 포트를 Kiro 가 잡고 있어 `scripts` 의 임시 복사본에서 주소만 `127.0.0.1:8765` 로 바꾸고 `--port 8765` 로 돌렸다. 복사본은 끝난 뒤 지웠고 저장소 스크립트는 그대로다. DB 는 러너가 고정하는 임시 SQLite 다.
- 3번: 스크립트는 연결된 Postgres 를 직접 보므로 공용 Neon 대신, test 설정에서 `0001_initial` 이후 `AddField` 컬럼이 현재 모델에서 NULL 허용이거나 `db_default` 를 갖는지 확인했다(후속 `AlterField` 반영). 저장소 밖 임시 스크립트로 돌렸다.
- 커밋하면서 새 마이그레이션 4개 맨 위에 `# Partly AI-generated (Claude)` 를 달았다(동작 변화 없음).

## §7 항목별 상태

| 항목 | 상태 | 서버 | 앱 |
| --- | --- | --- | --- |
| A1 도착 `entered_at` | 완료 | `observations_trip.entered_at` NULL. 도착에만, `entered ≤ observed`, 차이 7200초 이하(초 내림). 목록 응답에 추가 | `TripEvent.Arrived.enteredAtMillis`(마감 확정 포함) → `tripObservationOf` → `TripObservationInput.enteredAt`(`entered_at`) → 기존 큐 |
| A2 계획 이력 | 완료 | `planning_plan_revision`. `_upsert` 뒤 같은 트랜잭션·저장점, 결정값이 바뀔 때만. `trigger` 6곳, `route_minutes_raw`. `history.decision_revision` 은 규칙만(학습은 아직 안 씀) | — |
| A3 알람 기록 | 완료 | `observations_alarm_log`, `POST /api/alarm-logs/batch`(멱등·남의 일정 404), 스위치 변경은 `source=server` | `AlarmLogQueue`(소유자 격리·로그아웃 삭제·워커 업로드). 울림·끄기·미루기(5분)·자동 멈춤·등록 시각 변경 |
| A4 API 요청 기록 | 완료 | `apps.common` 등록, `common_api_request_log`, 미들웨어(메타 + 허용 목록 본문, 비밀·좌표 지움, 8/16 KB 자르기, 실패해도 응답 그대로) | `AppVersionInterceptor`(`X-App-Version`, 설계의 선택 항목) |
| A5 LLM 호출 기록 | 완료 | `reports_llm_call`. 부르기 직전 오늘 행 수로 사용자 30회·전체 `LLM_GLOBAL_DAILY_LIMIT`(기본 100) 상한, 넘거나 셀 수 없으면 템플릿. 결과·걸린 시간 기록. `llm_budget` 캐시는 더 쓰지 않음(별칭 정리는 P3) | — |
| A6 주간 요약 | 변경 없음 | 이미 `reports.0001_initial`. A5 가 그다음 번호 | — |
| A7 admin | 완료 | A2~A5 읽기 전용 admin, 일정 화면에 계획 이력·알람 기록·이동 관측 읽기 전용 inline, 섹션 번호 `AppConfig.verbose_name`(1. 계정 … 8. 운영, 마이그레이션 없음) | — |
| §9 보존 | 완료 | `retention.purge`(요청 기록 30일·LLM 호출 90일, ORM 삭제), `manage.py purge_logs [--request-days N] [--llm-days N]`, 미들웨어가 프로세스마다 하루 한 번 | — |

마이그레이션(전부 새 표·nullable 컬럼, `RunPython` 없음): `observations.0003_entered_at_alarm_log`(AddField `entered_at` NULL + CreateModel `AlarmLog`), `planning.0009_plan_revision`, `reports.0002_llm_call`, `common.0001_initial`. 앞 번호는 기준과 같다(observations 0002, planning 0008, reports 0001). 머지 직전에 main 의 최신 번호를 다시 본다(§10).

새 설정(전부 기본값이 있어 비워도 된다, `.env.example` 에 자리): `LLM_GLOBAL_DAILY_LIMIT`(100, 0 이면 부르지 않음), `API_REQUEST_LOG_ENABLED`(켬, `0` 이면 끔), `API_REQUEST_LOG_RETENTION_DAYS`(30), `LLM_CALL_RETENTION_DAYS`(90). `config/settings/test.py` 는 요청 기록을 끄고, 기록 테스트만 켠다(조회 수를 세는 테스트가 기록 INSERT 로 깨지지 않게).
## 설계와 다르게 한 것

설계 문서 본문은 고치지 않았다. 차이는 여기에만 적는다.

- **A3 `auto` 종류.** `Event.alarm_enabled` 는 켬·끔·null(하루 첫 알람 규칙) 세 상태다. null 로 되돌린 것을 켬/끔으로 적으면 거짓이라 `auto`("자동(하루 첫 알람)")를 더했다. 앱은 켬/끔/자동을 보낼 수 없다(400).
- **A4 비밀 규칙의 `*_key`.** `api_key`·`*_api_key` 로 좁혔다. `route_key`·`tag_key` 는 사용자가 고른 경로·일정 종류라 남기려는 값이다. 대신 `password*`·`*token*`·`*secret*`·`authorization`·`cookie`·`sessionid` 등은 넓게 지운다.
- **A4 좌표 규칙을 넓힘.** `lon`·`latitude`·`longitude`·`polyline`·`rect`·`markers`·`coords`·`geometry`·`*_points`·`*_x`·`*_y` 도 지운다. 목록은 점 개수만(`[coords:N]`), null 은 그대로 둔다.
- **A4 본문은 경로 허용 목록.** "쓰기면 모두" 가 아니라 정해 둔 쓰기 경로(`request_log.REQUEST_BODY_ROUTES`)만 요청 본문을 남긴다. 새 경로는 본문 없이 메타만 남고, 무엇이 오가는지 보고 목록에 넣는다. `routes/live` 는 요청만(좌표 지움) 남기고 응답은 남기지 않는다 — 좌표가 없어도 1분 간격의 남은 분·거리가 쌓이면 이동 경과가 된다(N19).
- **A1 상한 비교.** 진입~판정 차이는 초 내림으로 7200 과 비교한다. 앱이 `dwell_seconds` 를 같은 차이의 내림으로 보내므로, 체류가 통과하는 관측을 진입 시각 때문에 막지 않는다(400 이면 앱 큐가 배치를 통째로 버린다). 같은 이유로 앱은 판정보다 늦은 진입 시각을 보내지 않고, 마감 확정의 진입 시각은 `min(진입, 판정)` 이다.
- **A3 업로드 경로.** 별도 워커를 만들지 않고 기존 `ObservationUploadWorker`(관측 큐 둘과 같은 네트워크 창)·`PlanSyncWorker`·홈 새로고침이 알람 기록 큐도 비운다. 성공·400·404 때 **보낸 것만** 지운다(올리는 동안 새로 울린 기록을 지우지 않는다). 구버전 서버(0.11.0)는 경로가 없어 404 → 그 배치를 버린다.
- **A3 `scheduled`.** `AlarmScheduler.sync` 에서 등록 시각이 새로 생기거나 바뀐 일정만 남긴다(`changedRegistrations`). 미루기 재등록·부팅 복원·로그아웃 해제는 남기지 않는다(미루기는 `snoozed` 가 말한다).
- **A2 이력 실패.** 이력 쓰기 오류(표 없음 등)는 저장점에서 삼키고 경고 로그만 남긴다. 알람 계획 저장은 되돌리지 않는다.

## 확인하지 못한 것

- **Postgres 수준 DB 기본값 검사.** `check_db_defaults.py` 는 지금 연결된 Postgres 를 보는 스크립트인데, 공용 Neon 에는 붙지 않기로 했고 로컬 Postgres(Docker 데몬 꺼짐)도 없다. 로컬 SQLite 로 돌리면 건너뛴다. 같은 규칙(0001 이후 AddField 가 NULL 이거나 `db_default`)을 모델 필드로 확인했고 위험 0건이다. Phase C 의 AddField 는 `entered_at`(NULL) 하나뿐이고 나머지는 새 표다. 배포 뒤 `scripts/db_status.py` 로 미적용 0 을, `check_db_defaults.py` 로 위험 0 을 본다.
- **배포·마이그레이션.** 공용 DB 에 `migrate` 하지 않았다. main 머지 뒤 Render 가 적용한다. 그 전까지 공용 서버(0.11.0)는 새 필드·경로를 모른다 — 앱은 `entered_at` 을 보내도 무시되고, 알람 기록은 404 로 버려진다.
- **기기 위 동작.** 알람 울림·끄기·미루기·자동 멈춤이 실제로 큐에 쌓이고 올라가는지는 에뮬레이터·실기기에서 보지 않았다(JVM 테스트는 변환·직렬화·등록 비교만 본다. 큐는 `Context` 가 필요하다). 확인 방법: 알람을 울려 끈 뒤 admin "5. 실측 기록 › 알람 기록" 에 `fired`·`dismissed` 가 생기는지 본다.
- **동시 요청의 상한 넘침.** 두 요청이 같은 순간 세면 LLM 상한을 1~2회 넘길 수 있다(설계 A5 에서 받아들인 것).