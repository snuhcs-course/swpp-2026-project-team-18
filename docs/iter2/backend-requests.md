# 백엔드 요청 — Phase A 에서 나온 것

Phase A(프론트)는 서버 코드를 고치지 않는다. 앱이 보여 줄 수 없거나 규칙이 앱에만 있는 것을 여기 모았다. 필드 이름은 제안이고, 모델에 필드를 더할 때는 이 코드베이스 규칙대로 `db_default` 를 함께 준다. 근거: [phaseA-design.md](phaseA-design.md).

| ID | 요청 | 우선 | 앱 쪽 상태 |
| --- | --- | --- | --- |
| R-1 | 이동 보정에 쓴 기록 수 | 보통 | 출처 문구만 표시 |
| R-2 | 리스크 옵션·분포 API (BE-16) | 높음 | ⑤-a 준비 중 화면 |
| R-3 | 학습 배치(`train_models`) 정기 실행 | 높음 | 문구가 이 전제에 기대고 있음 |
| R-4 | 개인 학습 요약 | 닫음 (Phase B) | ⑧ 기록 반영(`learning`) |
| R-5 | 알람 계획 요약에서 걸음·kcal 제외 (선택) | 낮음 | 서버 문구 그대로 표시 중 |
| R-6 | `onboarding_prep_min` 범위 검증 | 보통 | 앱에만 5~240 |
| R-7 | 가까운 출발·도착 오류 코드 고정 (선택) | 낮음 | `details.destination` 으로 판단 중 |
| R-8 | 회원가입 요청 빈도 제한 (Phase C) | 보통 | 바꿀 것 없음 |
| R-9 | 머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로 (Phase C 알림) | 높음 | 바꿀 것 없음 |
| R-10 | 경로 후보가 모든 수단에 `path` 를 싣는다 · 정적 지도 축척 (10-18 알림) | 보통 | 배포 전에는 자동차·대중교통 미리보기 없음 |
| R-11 | 캘린더 동기화 API `POST /api/events/sync` — 캘린더에서 지우거나 옮긴 일정을 따라가게 | 팀 결정 뒤 높음 | ⑱ 연동은 기기에만 저장, 가져오기는 손으로 |
| R-12 | 앱에서 지운 캘린더 일정을 다시 만들지 않기(지운 표시) | R-11 과 함께 | 가져오기 화면이 "이미 가져옴" 만 표시 |
| R-13 | 일정에 출처 캘린더 키 저장 | R-11 과 함께 | 연동 끈 캘린더의 일정을 정리할 수 없음 |
| R-14 | 자동으로 들어온 일정의 장소 처리 규칙 | R-11 과 함께 | 지금은 가져오기 화면에서 사람이 확인 |

## R-1. 이동 보정에 쓴 기록 수

- 지금: `estimate_travel` 이 `sample_count`·`factor` 를 계산하지만 `AlarmPlan` 에 저장하지 않는다. 앱은 `travel_time_source` 의 `+observed_route`/`+observed_global` 로 "보정됨" 까지만 말할 수 있다.
- 요청: `AlarmPlan.travel_sample_count`(정수, `db_default=0`)를 저장하고 계획 응답에 내려 준다. 보정이 없으면 0.
- 앱: 값이 1 이상이면 ④ 이동 줄에 "· 이동 기록 N회로 보정" 을 붙인다. 필드가 없으면(구버전) 붙이지 않는다.

## R-2. 리스크 옵션·분포 API (task.md BE-16 그대로)

- `GET /api/events/{id}/risk-options` → `{options:[{key, label, tau, alarm_at, probability, sleep_minutes?}], selected_key, auto_adjusted, auto_reason, distribution:{bins:[{min,max,count}], p50, p90, window_days}}`.
- 옵션을 못 만들 때의 응답을 정해 줄 것: `{"options": [], "unavailable_reason": "no_variance"}` 처럼. 이유 코드가 있으면 ⑤-a 의 "선택지 준비 중" 을 이유별 문구로 바꿀 수 있다.
- 저장은 BE-17 확인대로 기존 `PATCH tau_override`(0.5~0.999).
- 지금 ⑤ 가 항상 빈 상태인 이유가 이것이다(관측 부족이 아니라 API 없음). 그래서 앱 문구를 "선택지 준비 중" 으로 바꿨다.

## R-3. 학습 배치 정기 실행

- 지금: `RouteCorrection` 은 관리 명령 `train_models` 를 사람이 돌릴 때만 갱신된다(Render 무료 플랜에 cron 없음). 보정 표본은 모든 사용자의 출발·도착 쌍이다.
- 문제: 앱 문구 "이동 기록이 쌓이면 변동성이 생김"(④)과 알람 화면 추적 카드의 "이 기록이 쌓이면 이동 시간 보정에 쓰임" 은 배치가 돌아야 사실이 된다. 기록이 쌓여도 배치가 돌지 않으면 확률은 계속 나오지 않는다.
- 요청: 정기 실행 방법을 정한다(예: 외부 스케줄러가 부르는 보호된 관리 엔드포인트, 또는 `observations/batch` 가 도착 관측을 일정 수 받으면 보정만 다시 계산). 방식은 백엔드가 고른다.
- 함께: `apps/events/serializers.py` `AlarmPlanSerializer` docstring 표의 "같은 경로를 몇 번 다니면 쌓인다" 를 "이동 기록이 쌓이면" 으로 고친다(주석만, 실제 동작과 맞춤).
- 함께: `backend/README.md` 116행 "앱은 “학습 중”으로 표시한다" 를 지금 앱 동작으로 고친다 — 확률이 null 이면 앱은 숫자를 만들지 않고 홈에서는 확률 자리를 숨기며, ④ 에서는 "정시 도착 확률 · 아직 계산할 수 없음" 과 이유를 쓴다(Phase A, A3-2).

## R-4. 개인 학습 요약 (리포트 단계에서 확정) — 닫음 (Phase B)

- 지금: `prediction.ModelArtifact` 를 만들지만 내려 주는 API 가 없다(블록별 평균·표준편차, 준비 합계 통계, slack 계수, 표본 수).
- 요청: 리포트 화면 설계 때 필요한 항목만 골라 `reports` 응답에 넣는다. Phase A 에서는 화면이 없으므로 이름만 남긴다.
- **닫음(Phase B, [report-design.md](report-design.md) §1·§3)**: 알람 계산은 `ModelArtifact` 를 읽지 않으므로 그 값을 "학습 반영" 으로 보이면 사실이 아니다. 대신 주간 응답 `learning` 이 그 주 계획이 실제로 쓴 출처를 센다(`prep_source == "observed"`, `travel_source` 의 `+observed_*`). ⑧ "기록 반영" 문장이 이것을 쓴다. 서버 변경은 이 브랜치의 `apps/reports` 에 있고 백엔드 담당에게 PR 로 공유한다.

## R-5. (선택) 알람 계획 요약에서 걸음·kcal 제외

- 지금(`3cd1a1f`): 도보 경로의 `summary` 에 " · 1,600걸음 · 60kcal (거리 기반 추정)" 이 붙는다. `resolve_route`/`best_route` 가 같은 함수를 써서 **알람 계획 `route_summary`** 에도 들어간다. 그래서 홈 다음 알람 카드와 ④ 이동 줄이 길어진다.
- 요청: 후보 응답(⑬)에만 붙이고 계획 요약에는 넣지 않는다. 앱은 서버 문장을 다시 조립하지 않으므로 서버에서 정해야 한다.

## R-6. `onboarding_prep_min` 범위 검증

- 지금: `accounts.Profile.onboarding_prep_min` 은 `PositiveIntegerField` 이고 `ProfileSerializer` 에 범위 검사가 없다. 5~240분은 앱 상수(`HomeViewModel.PREP_MIN/PREP_MAX`, 화면·다이얼은 `PrepOnboardingState.range` 로 읽음)에만 있다.
- 요청: 서버가 5~240 을 검증하고 벗어나면 400 `{"onboarding_prep_min": ["…"]}`(한국어). 규칙의 주인을 서버로 옮긴다. 앱은 다이얼에서 같은 범위를 막고 있으므로 정상 사용에서는 400 이 나오지 않는다. 범위를 바꾸려면 프론트에 알려 줄 것.

## R-7. (선택) 가까운 출발·도착 오류 코드

- 지금(`710545a`): `ValidationError` 라 `error.code` 가 DRF 기본값 `"invalid"` 이다. 앱은 `details.destination` 이 있는지로 판단한다.
- 요청: `error.code` 를 `"endpoints_too_close"` 같은 고정 값으로. `details.destination` 과 메시지는 지금처럼 유지. 없어도 앱은 동작한다.

## R-8. 회원가입 요청 빈도 제한 (Phase C)

- 지금: `POST /api/auth/register` 에 throttle 이 없다. 비밀번호 검사(`password_check`)에만 있다.
- 문제: 스크립트로 계정을 계속 만들 수 있다. 계정마다 LLM 사용자 하루 상한 30회가 새로 생기고(Phase B 리뷰 2번) 행도 쌓인다. Phase C 는 LLM 전체 하루 상한(`db-schema.md` A5)으로 비용만 막는다.
- 요청: `ScopedRateThrottle` scope `register`(예: IP 당 시간 10회). 넘으면 429 와 한국어 메시지. 값은 백엔드가 정한다.
- 앱: 429 일 때 보이는 문구만 프론트가 확인한다.

## R-9. 머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로 (Phase C 알림)

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
