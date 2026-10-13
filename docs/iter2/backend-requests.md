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
