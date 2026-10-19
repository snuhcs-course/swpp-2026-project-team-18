# 백엔드 요청 — Phase A 에서 나온 것

Phase A(프론트)는 서버 코드를 고치지 않는다. 앱이 보여 줄 수 없거나 규칙이 앱에만 있는 것을 여기 모았다. 필드 이름은 제안이고, 모델에 필드를 더할 때는 이 코드베이스 규칙대로 `db_default` 를 함께 준다. 근거: [phaseA-design.md](phaseA-design.md).

| ID | 요청 | 우선 | 앱 쪽 상태 |
| --- | --- | --- | --- |
| R-1 | 이동 보정에 쓴 기록 수 | 완료 | 실제 표본 N회 표시 |
| R-2 | 리스크 옵션·분포 API (BE-16) | 완료 | 조회·저장·기기 예약 연결 |
| R-3 | 학습 배치(`train_models`) 정기 실행 | 검증 대기 | GitHub 수동 학습 저장 성공 / 실제 예약 이벤트 미확인 |
| R-4 | 개인 학습 요약 | 닫음 (Phase B) | ⑧ 기록 반영(`learning`) |
| R-5 | 알람 계획 요약에서 걸음·kcal 제외 (선택) | 낮음 | 서버 문구 그대로 표시 중 |
| R-6 | `onboarding_prep_min` 범위 검증 | 완료 | 서버·앱 모두 5~240 |
| R-7 | 가까운 출발·도착 오류 코드 고정 (선택) | 낮음 | `details.destination` 으로 판단 중 |
| R-8 | 회원가입 요청 빈도 제한 (Phase C) | 완료 | 서버 한국어 429 표시 |
| R-9 | 머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로 (Phase C 알림) | 높음 | 바꿀 것 없음 |
| R-10 | 경로 후보가 모든 수단에 `path` 를 싣는다 · 정적 지도 축척 (10-18 알림) | 보통 | 배포 전에는 자동차·대중교통 미리보기 없음 |
| R-11 | 캘린더 동기화 API `POST /api/events/sync` — 캘린더에서 지우거나 옮긴 일정을 따라가게 | 팀 결정 뒤 높음 | ⑱ 연동은 기기에만 저장, 가져오기는 손으로 |
| R-12 | 앱에서 지운 캘린더 일정을 다시 만들지 않기(지운 표시) | R-11 과 함께 | 가져오기 화면이 "이미 가져옴" 만 표시 |
| R-13 | 일정에 출처 캘린더 키 저장 | R-11 과 함께 | 연동 끈 캘린더의 일정을 정리할 수 없음 |
| R-14 | 자동으로 들어온 일정의 장소 처리 규칙 | R-11 과 함께 | 지금은 가져오기 화면에서 사람이 확인 |

## R-1. 이동 보정에 쓴 기록 수 — 완료 (2026-10-17)

- 기존 추정기의 `TravelEstimate.sample_count`를 `AlarmPlan`·`PlanRevision.travel_sample_count`에 저장한다(`default=0`, `db_default=0`). 경로별·전역 보정 수는 기존 추정기 의미를 그대로 따른다.
- 계획 API로 내려 주고 앱 ④ 이동 줄에 "이동 기록 N회로 보정"을 표시한다. 보정 없음·계산 실패는 0이며 구버전 응답은 기존 출처 문구를 유지한다.
- 계획 이력은 표본 수만 바뀌어도 갱신하며 리스크 미리보기의 알람 결정값 비교는 유지한다.
- [전후 검증·직접 테스트](account-ranges-and-travel-counts.md).

## R-2. 리스크 옵션·분포 API (task.md BE-16) — 완료 (2026-10-17)

- 실제 API·화면·`PATCH tau_override`·기기 예약까지 연결했다.
- 분포는 모형 결과이므로 `bins:[{min,max,mass}]`, `source:"model"`, `window_days:null`이다. 실측 건수나 최근 30일 통계로 표시하지 않는다.
- 옵션을 계산할 근거가 없으면 `options:[]`, `distribution:null`, 한국어 `unavailable_reason`을 반환한다. 현재 τ가 프리셋과 다르면 선택 없이 바꾸지 않는다.
- [검증·직접 테스트](route-risk-home.md).

## R-3. 학습 배치 정기 실행 / 실제 예약 확인 대기 (2026-10-18)

- GitHub Actions가 `main`에서 매일 한국 시간 23:17에 `train_models --users --verbosity 0`을 실행한다. DB는 비공개 Secret `TRAINING_DATABASE_URL`을 사용한다. 스키마 누락 시 중단하며 공개 로그에는 집계만 남긴다.
- G1/G2 수정: 첫 알람 울림 전 마지막 계획(없으면 첫 출발 전 계획)의 `route_minutes_raw`로 학습한다. 원값 없는 과거 이동은 제외하며 유효하지 않은 옛 학습 보정은 정리한다. 수동 보정은 보존한다.
- 학습/조회가 같은 현지 시간대 기준으로 시간/요일 보정을 선택한다. 갱신된 이동 보정은 다음 정상 알람 재계산에 반영한다.
- GitHub `dry-run`/수동 `train`과 운영 DB 저장(관측 13건)을 확인했다. 일회성 예약을 두 번 설정했으나 실제 `schedule` 이벤트는 확인되지 않아 완료 처리를 보류한다. [설정/실제 실행/전후 검증](daily-training.md).

## R-4. 개인 학습 요약 (리포트 단계에서 확정) — 닫음 (Phase B)

- 지금: `prediction.ModelArtifact` 를 만들지만 내려 주는 API 가 없다(블록별 평균·표준편차, 준비 합계 통계, slack 계수, 표본 수).
- 요청: 리포트 화면 설계 때 필요한 항목만 골라 `reports` 응답에 넣는다. Phase A 에서는 화면이 없으므로 이름만 남긴다.
- **닫음(Phase B, [report-design.md](report-design.md) §1·§3)**: 알람 계산은 `ModelArtifact` 를 읽지 않으므로 그 값을 "학습 반영" 으로 보이면 사실이 아니다. 대신 주간 응답 `learning` 이 그 주 계획이 실제로 쓴 출처를 센다(`prep_source == "observed"`, `travel_source` 의 `+observed_*`). ⑧ "기록 반영" 문장이 이것을 쓴다. 서버 변경은 이 브랜치의 `apps/reports` 에 있고 백엔드 담당에게 PR 로 공유한다.

## R-5. (선택) 알람 계획 요약에서 걸음·kcal 제외

- 지금(`3cd1a1f`): 도보 경로의 `summary` 에 " · 1,600걸음 · 60kcal (거리 기반 추정)" 이 붙는다. `resolve_route`/`best_route` 가 같은 함수를 써서 **알람 계획 `route_summary`** 에도 들어간다. 그래서 홈 다음 알람 카드와 ④ 이동 줄이 길어진다.
- 요청: 후보 응답(⑬)에만 붙이고 계획 요약에는 넣지 않는다. 앱은 서버 문장을 다시 조립하지 않으므로 서버에서 정해야 한다.

## R-6. `onboarding_prep_min` 범위 검증 — 완료 (2026-10-17)

- 프로필 PATCH에서 5~240분을 양 끝 포함해 검증한다. 범위 밖이면 400과 `error.details.onboarding_prep_min`의 한국어 오류를 반환하며 동반 필드도 저장하지 않는다.
- 기존 `null`·필드 생략 동작과 앱 다이얼 범위를 유지한다. [검증·직접 테스트](account-ranges-and-travel-counts.md).

## R-7. (선택) 가까운 출발·도착 오류 코드

- 지금(`710545a`): `ValidationError` 라 `error.code` 가 DRF 기본값 `"invalid"` 이다. 앱은 `details.destination` 이 있는지로 판단한다.
- 요청: `error.code` 를 `"endpoints_too_close"` 같은 고정 값으로. `details.destination` 과 메시지는 지금처럼 유지. 없어도 앱은 동작한다.

## R-8. 회원가입 요청 빈도 제한 — 완료 (2026-10-17)

- `POST /api/auth/register`에 기존 `ScopedRateThrottle` scope `register`를 적용한다. IP당 성공·실패 합산 시간당 10회이며 초과하면 한국어 429와 `Retry-After`를 반환한다.
- 차단 요청은 계정을 만들지 않으며 다른 IP·비밀번호 검사 한도는 별개다. 앱은 기존 서버 오류 표시를 재사용한다.
- 대량 가입하는 HTTP 회귀 서버만 `config.settings.local_suite`에서 가입 제한을 끈다. 전용 API 테스트·에뮬레이터는 실제 10/hour 제한을 검증한다. [검증·직접 테스트](account-ranges-and-travel-counts.md).

## R-9. 머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로 (Phase C 알림)

- BE-20 후속(2026-10-18): `events.0008`은 반복 규칙 `events_recurrence`와 날짜별 회차 연결을 추가한다. 사용자 삭제 도구는 이 모델도 아는 최신 코드에서 실행한다. 회차 삭제 예외는 기존 일정 DELETE API가 기록한다. [반복 일정](weekly-recurrence.md).

- 바뀌는 것: 이 브랜치가 main 에 머지되면 Render 가 새 표 넷을 만든다 — 계획 이력 `planning_plan_revision`, 알람 기록 `observations_alarm_log`, API 요청 기록 `common_api_request_log`, LLM 호출 `reports_llm_call`. 넷 다 사용자(앞의 둘은 일정도)를 FK 로 가리킨다. FK 는 기존 표처럼 Django 기본(`DEFERRABLE INITIALLY DEFERRED`, DB 수준 `ON DELETE` 없음)이다.
- 문제: 이 모델을 모르는 코드가 공용 DB 에서 ORM 으로 사용자·일정을 지우면, 새 표에 남은 행 때문에 커밋에서 `IntegrityError` 가 나고 삭제 전체가 되돌려진다. 요청 기록은 로그인한 요청마다 생기므로 배포 뒤에는 거의 모든 사용자가 해당된다. 해당하는 것: main 을 받기 전 브랜치에서 돌린 `scripts/purge_test_accounts.py`·`scripts/emu_verify_cleanup.py`, 배포가 겹치는 동안의 옛 인스턴스.
- 요청: 머지 뒤에는 main 을 받은 브랜치에서만 이 스크립트를 돌린다. 새 코드의 ORM 은 새 표까지 CASCADE·SET_NULL 로 지우므로 따로 고칠 것은 없다.
- 근거: [db-schema.md](db-schema.md) §10, Phase C 리뷰 #1([phaseC-verification.md](phaseC-verification.md) "리뷰 반영").

## R-10. 경로 후보가 모든 수단에 `path` 를 싣는다 · 정적 지도 축척 (2026-10-18 알림)

- 바뀐 것(이 브랜치 `apps/routing/clients.py`): `route_candidates` 가 자동차·대중교통 후보에도 `path` 를 싣는다. 같은 카카오 응답의 좌표라 호출 수는 그대로다. 모든 후보 경로는 `preview_path`(Douglas–Peucker 1m, 소수 6자리)로 줄이고 `path_distance_m` 을 줄인 점으로 다시 잰다. 신림역 → 서울대에서 카카오가 준 후보 경로 8개를 합하면 1,051점이 656점이 된다. 알람 계획 경로(`resolve_route`·`best_route`)는 바꾸지 않았다.
- 정적 지도 축척: 마커 없는 지도의 실측은 요청 단위당 `2^(lv-3)` m 다(`STATIC_MAP_BASE_LEVEL` 4 → 3). 예전 값은 마커를 넣은 지도로 쟀는데, 카카오는 마커가 다 보이게 줌·중심을 바꾼다. 서버 로직은 이 상수를 쓰지 않고 문서·테스트만 바뀐다. 앱은 같은 축척과 EPSG:5181 좌표 변환으로 선·핀을 그린다.
- 요청: back-iter2 에서 같은 함수를 고치고 있으면 머지할 때 이 변경을 살린다. 배포 전 서버는 자동차·대중교통 좌표를 주지 않으므로 앱은 그 카드에 "지도 미리보기를 받지 못했음" 을 보인다.
- 근거: `apps/routing/tests/test_route_path.py`(후보 경로), `apps/routing/tests/test_place_search.py` `TestScale`(실측 축척), [figma-changes.md](figma-changes.md) F-14.

## 캘린더 연동 (R-11 ~ R-14, 2026-10-18)

지금 앱이 하는 것(서버 변경 없음): 가입 직후 ⑱ 에서, 그리고 ⑯ 설정의 스위치로 **어느 기기 캘린더를 읽을지** 고른다. 고른 목록은 기기에만 저장한다(`CalendarContract` 의 캘린더 id 가 기기마다 다르다). 캘린더 가져오기 화면은 연동한 캘린더의 일정만 보여 주고, 그중 사용자가 고른 것만 기존 `POST /api/events/import` 로 보낸다. 자동으로 올리지 않는다.

아래는 연동을 "**자동으로 따라가기**" 로 넓힐 때 서버가 해야 할 일이다. US-12 의 "No auto-sync worker"(제목이 민감함, 앱에서 지운 일정이 되살아남) 결정을 바꾸는 일이라 **PM·팀 결정이 먼저**다. 결정 전에는 만들지 않아도 앱은 동작한다.

### R-11. 캘린더 동기화 API `POST /api/events/sync`

- 지금 문제: 가져온 뒤 캘린더에서 일정을 지우거나 시간을 옮겨도 앱 일정과 알람은 그대로다. **캘린더에서 지운 일정은 남아 알람이 울린다.** 옮긴 일정을 다시 가져오면 갱신이 아니라 새 일정이 하나 더 생긴다 — 지금 `external_id` 에 시작 시각이 들어 있어서다(아래).
- 요청: 본문 `{window_start, window_end, calendar_keys: [...], events: [{external_id, calendar_key, title, start_at, location?}]}`(한 번에 200건 이하). 처리:
  1. `(user, external_id)` upsert. 분 단위 비교로 바뀐 것만 재계산(지금 import 규칙과 같음).
  2. **같은 창 안·같은 `calendar_key` 에서 본문에 없는 `source=calendar` 일정을 지운다**(계획 포함). 창 밖과 다른 캘린더의 일정은 건드리지 않는다.
  3. 응답 `{created, updated, unchanged, deleted, results}`.
- 쿼터: 새 일정·바뀐 일정마다 카카오 경로를 부른다. 하루 상한이 필요한지는 백엔드가 정한다.
- 알아 둘 것: 지금 `external_id` 는 `"<기기 이벤트 id>:<시작 밀리초>"` 다. 반복 일정의 회차를 가르려고 시작 시각을 붙였는데, 그 때문에 시간을 옮기면 다른 일정이 되고 기기를 바꾸면 같은 일정이 다른 값이 된다. 동기화를 하면 앱이 iCal UID(`UID_2445`)와 원래 회차 시각 기반으로 바꿀 계획이다. 바뀌는 시점의 중복(같은 제목·같은 시각) 정리 방법을 정해 줄 것.

### R-12. 앱에서 지운 캘린더 일정을 다시 만들지 않기

- 요청: `source=calendar` 일정을 `DELETE /api/events/{id}` 로 지우면 `(user, external_id)` 를 남긴다(예: `CalendarDismissal(user, external_id, dismissed_at)` 또는 일정 소프트 삭제). R-11 은 그 id 를 다시 만들지 않는다. `events/import` 는 사용자가 목록에서 다시 고른 경우라 되살려도 된다 — 지금 화면 규칙("이미 가져온 일정은 기본으로 꺼짐")과 같다.
- 새 필드·표는 이 코드베이스 규칙대로 `db_default` 를 준다.

### R-13. 일정에 출처 캘린더 키

- 요청: `Event.calendar_key`(문자열, null 허용). 앱이 정하는 불투명 키다(같은 기기에서 같은 캘린더면 같은 값).
- 용도: ⑯ 에서 연동을 끈 캘린더의 일정을 정리하는 것, 일정 상세에 "수업 시간표에서 가져옴" 을 적는 것. 끈 캘린더의 일정을 지울지 남길지는 팀이 정한다(지금 앱은 남긴다).

### R-14. 자동으로 들어온 일정의 장소 처리 규칙

- 지금 가져오기 화면은 캘린더의 장소 문자열을 검색해 **후보만 보여 주고 사람이 확인**한다. 첫 검색 결과를 말없이 쓰면 엉뚱한 좌표로 알람이 잡힌다.
- 요청: 동기화로 들어온 일정도 같은 규칙을 지킨다. 예: 서버가 검색한 결과를 후보로만 저장하고(확정 표시 없음), 확정 전에는 계획 상태를 "장소 확인 필요" 로 둔다. 앱은 홈의 "알람 미계산" 줄에서 확인 화면으로 보낸다(프론트 후속 작업).
