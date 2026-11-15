# 쌓인 기록 초기화 API — 서버 (iter4 BE-RESET)

<!-- Partly AI-generated (Claude) -->

내 정보 › 계정 › **기록 초기화**. 앱 쪽 제안 계약(`front-iter4` 의 `docs/iter4/learning-reset-api.md`)을 그대로 받는다. 경로 · 본문 · 상태 코드가 같아서 앱은 바꾸지 않아도 동작한다(6절은 권장).
구현: `backend/apps/planning/learning_reset.py`(지우기 · 다시 섞기 · 밤 학습과 겹칠 때), `backend/apps/accounts/views_learning.py`(뷰), 업로드 거르기 `apps/observations/views.py` · `apps/routines/views.py`, 밤 학습 `apps/prediction/management/commands/train_models.py` [6] · 테스트: `backend/apps/planning/tests/test_learning_reset.py`, `test_learning_reset_review.py` · 마이그레이션 `accounts/0009_profile_learning_reset_at`

## 1. 요청 · 응답

```
POST /api/auth/me/learning/reset
Authorization: Bearer <access>
(본문 없음)
```

```json
200 OK
{
  "deleted": {
    "trip_observations": 42, "alarm_logs": 17, "trip_reroutes": 3, "block_observations": 20,
    "buffer_adjustments": 2, "weekly_summaries": 4, "model_artifacts": 1, "travel_profiles": 1
  },
  "recomputed_plans": 5,
  "changed_plans": 3,
  "skipped_plans": 1,
  "reset_at": "2026-11-15T21:38:41.408847+09:00"
}
```

- `deleted`: 지운 행 수. 키는 늘 8개이고 값은 정수다(앱 `LearningResetResponse.deleted: Map<String, Int>`). 지울 것이 없으면 모두 0 이고 그래도 200 이다(멱등).
- `recomputed_plans`: 지운 기준으로 다시 섞은 앞으로의 계획 수. `changed_plans`: 그중 알람 값이 실제로 바뀐 수.
- `skipped_plans`: 다시 섞지 **못한** 앞으로의 계산된 계획 수(3절). 이 계획에는 옛 학습값이 남아 있을 수 있다. 앱은 이 값을 읽지 않아도 된다(Gson 이 모르는 키를 무시한다).
- `reset_at`: 서버가 지운 시각(다른 응답과 같은 ISO 8601). 서버는 이 시각을 계정에 남기고(`Profile.learning_reset_at`) 이보다 이른 기록 업로드를 버린다(4절).

| 상태 | 언제 | 앱의 안내(`LearningReset.outcome`) |
| --- | --- | --- |
| 200 | 지웠다(지울 것이 없어도) | DONE |
| 401 | 토큰 없음 · 만료 | SESSION |
| 405 | `POST` 가 아닌 요청 | UNSUPPORTED(앱은 `POST` 만 보낸다) |
| 429 `throttled` | 사용자당 시간당 5번 초과(`learning_reset`). 잘못된 메서드 요청도 센다 | FAILED("잠시 후 다시 시도") |
| 500 `server_error` | 다시 섞다가 실패. **지운 것도 되돌린다**(2절) | FAILED |

## 2. 지우는 것 · 남기는 것 (요청한 사용자 행만)

| 지운다 | 응답 키 | 왜 |
| --- | --- | --- |
| `observations.TripObservation` | `trip_observations` | 출발 · 도착 기록. 이동 보정 · GPS 준비 시간 · 버퍼의 재료 |
| `observations.AlarmLog` | `alarm_logs` | 알람 울림 · 미루기 · 끄기 기록 |
| `observations.TripReroute` | `trip_reroutes` | 경로 바꾸기 기록. 이동 학습의 재료. **이동 중일 수 있는 일정(시작 6시간 전 ~ 6시간 뒤, 바꾸기를 받는 범위)의 행은 남긴다** — `routes/live` · 다시 바꾸기가 바꾼 경로를 기준으로 삼는다 |
| `routines.BlockObservation` | `block_observations` | 블록별 실제 걸린 시간. 준비 시간 분포의 재료 |
| `planning.BufferAdjustment` | `buffer_adjustments` | 지각 이력으로 맞춘 안전 버퍼. 지우면 기본 버퍼로 돌아간다 |
| `reports.WeeklySummary` | `weekly_summaries` | 기록으로 쓴 주간 요약 문장 |
| `prediction.ModelArtifact` 중 **이 사용자의 개인 행** | `model_artifacts` | 개인 블록 분포(밤 학습 `--users`). 전역 행(`user` null)은 건드리지 않는다 |
| `routing.UserTravelProfile` | `travel_profiles` | 도보 기록으로 배운 걷는 속도 |

앱 문서 표에서 빠졌던 것은 `ModelArtifact`(개인 행)와 `UserTravelProfile`(걷는 속도)이다. 둘 다 이 사용자 기록에서만 만든 값이다.

| 남긴다 | 왜 |
| --- | --- |
| 일정 · 반복 · 종류(`events.*`), 블록 체크(`EventBlockSelection`), 고른 경로(`Event.route_key`) | 사용자가 넣거나 고른 것 |
| 직접 정한 알람 시각(`AlarmPlan.user_alarm_at`, BE-H3b) · 확인한 알람 | 사용자가 고른 것. 3절 |
| 일정의 "안 감"(`skipped_at`) · 학습 제외 표시(`prep_learning_excluded_at`) | 일정에 붙은 표시다. 기록이 지워지면 효과도 없다 |
| 집 · 저장한 집 · 준비 시간 · τ(`Profile`, `SavedPlace`), 루틴 블록(`RoutineBlock`), 계정 · 소셜 연결 | 사용자가 넣은 것 |
| `planning.AlarmPlan` · `PlanRevision` | 계획과 그 이력이다. 다시 섞을 때 이력의 카카오 원값을 쓴다. 지난 일정의 이력은 관측이 없으면 학습에 쓰이지 않는다 |
| `routing.RouteCorrection` | 모든 사용자 기록으로 만든 **공용** 이동 보정이라 이 사용자 몫만 뗄 수 없다. 밤 전체 학습(매일 23:17)이 남은 기록으로 다시 만들 때 이 사용자 기록이 빠진다(5절) |
| `reports.LlmCall` | 하루 LLM 호출 상한을 세는 표. 지우면 초기화로 상한을 되살릴 수 있다 |
| `common.ApiRequestLog` | 운영 기록. 보존 기간(30일)이 지나면 따로 지워진다 |

- **한 트랜잭션**에서 초기화 시각을 남기고, 8개 표를 지우고, 다시 섞는다. 중간에 실패하면 모두 되돌린다(일부만 지워진 상태 · 기록은 없는데 알람은 옛 학습값인 상태를 남기지 않는다). 같은 사용자의 초기화 두 개는 프로필 행 잠금으로 줄을 선다.
- 지난 일정은 남으므로 주간 리포트에는 "기록 없는 아침" 으로 보인다(정시율 · 캘리브레이션에서는 빠진다). 리포트의 **학습 카드**(`learning.prep_from_records` · `travel_from_records`)는 초기화 전에 계산한 계획을 기록 출처로 세지 않는다(`plan_count` 에는 든다). 앱 안내 "주간 리포트의 지난 결과도 비워져요" 와 맞춘 것이다. 초기화 뒤에 다시 계산한 계획은 그 뒤에 쌓인 기록만 썼으므로 센다.

## 3. 알람 다시 섞기

- 지운 뒤 앞으로의 일정(가까운 **200개**, 반복 창 안) 알람을 **카카오를 부르지 않고** 다시 섞는다. 루틴 블록 수정 · 밤 학습과 같은 함수다(`planning.refresh.refresh_upcoming`, 계획 이력의 `trigger` 는 `profile`). 사용자가 직접 한 일이라 확인한 알람이 5분 넘게 바뀌어도 "바꿀까요" 제안 없이 바로 바꾼다([alarm-control](server-alarm-control.md)).
- 기록이 없을 때의 기준: 준비 시간은 루틴 블록 범위 · 프로필 준비 시간, 버퍼는 기본값(`buffers.DEFAULT_MINUTES`), 걷는 속도는 1.0. 이동 보정은 공용 값 그대로다.
- **그대로 두는 일정**(`skipped_plans` 로 센다):
  - **진행 중인 아침** — 알람이 울렸거나 출발한 일정. 지우기 **전에** 모아 둔다(지운 뒤에는 그 기록이 없어 알아볼 수 없다). 그 아침의 계획 · 이동 중 경로 변경은 그대로다.
  - **곧 울릴 알람** — 추천 알람이 10분(`RESET_MARGIN`) 안이거나 이미 지났다. 그리고 다시 섞으면 새 알람이 지금부터 10분 안(또는 이미 지난 시각)으로 당겨질 일정도 그대로 둔다. 기기에 걸린 알람이 곧 울리는데 서버만 바꾸면 지난 시각의 알람이 생긴다.
  - 계산 불가 상태(`no_home`, `route_failed` 등)가 아닌데 이력에 쓸 수 있는 카카오 원값이 없는 일정, 출발지 · 목적지가 없는 일정, 반복 창(7일) 밖의 회차, 가까운 200개 밖의 일정. 경로부터 다시 구해야 해 카카오를 써야 한다 — 초기화는 카카오를 부르지 않는다.
  - 이 계획들은 다음 계산에서 새 기준이 들어간다: 앱 경로 갱신 워커(알람 3시간 전부터 `recompute`), 밤 학습 재계산(앞으로 7일), 일정 수정. 기기 알람은 7일 안만 걸리므로 울리기 전에 대부분 바뀐다.
- **직접 정한 알람 시각(BE-H3b)은 그대로다.** 다시 섞기는 추천 시각만 바꾸고 직접 정한 시각의 정시 도착 확률만 새로 구한다(`alarm_control.after_compute`). 그래서 새 기준에서 그 시각의 확률이 70% 아래면 초기화 직후에도 "직접 정한 시각에 일어나면 정시 도착 확률이 N%예요"(`user_low_probability`) 제안이 생길 수 있다 — 사용자가 고른 시각에 대한 안내라 일부러 남긴다.
- 다시 섞은 일정에 답하지 않은 **추천 시각 제안**(`recommended_moved`, 지키던 시각 `held_alarm_at`)이 있으면 거둔다. 옛 학습값이 낸 질문이라 초기화 뒤에는 추천 시각(새 기준)으로 울린다. 알람이 바뀌지 않은 일정도 거둔다.

## 4. 오프라인 큐(초기화 전에 쌓인 기록)

기기는 관측 · 알람 기록 · 블록 기록을 큐에 쌓았다가 나중에 올린다. 초기화는 멱등 키(`client_uuid`)까지 지우므로, 거르지 않으면 초기화 전 기록이 새 행으로 들어가 지운 아침이 다시 학습된다. 서버는 **초기화 시각(`Profile.learning_reset_at`)보다 이른 기록을 받기만 하고 저장하지 않는다**.

| 업로드 | 비교하는 시각 | 응답 |
| --- | --- | --- |
| `POST /api/observations/batch` | `observed_at` | `{"accepted", "duplicated", "ignored", "results"}` |
| `POST /api/alarm-logs/batch` | `occurred_at` | `{"accepted", "duplicated", "unlinked", "ignored", "results"}` |
| `POST /api/routines/observations/batch` | `client_recorded_at` | `{"accepted", "duplicated", "ignored", "results"}` |

- 버린 기록은 `ignored` 로 센다. `accepted` · `duplicated` · `results` 에는 들지 않는다. 상태는 지금처럼 하나라도 저장했으면 201, 아니면 200 — **모두 버려도 2xx** 라 앱 큐가 그대로 비워진다(앱은 2xx 면 보낸 큐를 지운다).
- 같은 시각(`==`)은 받는다. 초기화한 적 없는 계정은 거르지 않는다.
- 시각은 기기 시계다. 기기 시계가 서버보다 많이 빠르거나 느리면 경계 근처의 기록이 반대로 갈릴 수 있다.

## 5. 밤 학습과 겹칠 때 · 남는 한계

- **학습 중 초기화**: 밤 학습은 기록을 읽은 뒤 한참 있다가 개인 학습값을 쓴다. 그 사이에 초기화하면 지운 기록으로 만든 걷는 속도 · 버퍼 조정 · 개인 아티팩트가 다시 생길 수 있다. 학습은 시작 시각을 기억해 두고, 끝에서(`[6]`) 그 뒤에 초기화한 사용자의 이 세 학습값을 다시 지우고 알람을 초기화와 같은 방식으로 다시 섞는다(`learning_reset.redo_after_nightly`). 초기화가 그 사용자 행을 모두 지웠으므로 남은 행은 이번 학습이 쓴 것이다.
- **초기화 뒤 첫 밤**: 공용 이동 보정은 그날 밤 학습에서 이 사용자 기록을 빼고 다시 만든다. 그래서 알람이 움직이면 초기화 때문이다. 그 밤 학습이 이 사용자에게 낸 추천 시각 "바꿀까요" 제안은 거두고 바로 반영한다(`learning_reset.drop_nightly_proposals`). "첫 밤" 은 초기화가 학습 시작 24시간 안에 있었던 경우다(밤 학습은 하루 한 번). GitHub Actions 예약이 늦게 돌면 이틀 밤에 걸쳐 적용될 수 있다. 그 전부터 있던 제안 · 직접 정한 시각에 대한 제안은 그대로다.
- **같은 날의 한계**: 초기화한 날 밤 학습 전까지는 공용 이동 보정에 이 사용자 몫이 남아 있다. 초기화가 다시 섞은 알람의 이동 시간에도 그 보정이 들어간다. 앱 안내("거기서 맞춰진 준비 · 이동 시간이 지워져요")는 개인 값 기준으로는 맞고, 공용 보정은 그날 밤에 빠진다. 안내 문구는 앱이 정한다.
- 학습이 끝난 뒤 · 학습이 쓴 값을 다시 지운 뒤에 겹친 초기화 트랜잭션(밀리초 단위)은 막지 않는다. 다음 밤 학습이 남은 기록으로 다시 맞춘다.

## 6. 앱 문서 4절 질문에 대한 결정

| 질문 | 결정 |
| --- | --- |
| 빠진 '기록에서 만들어지는 것' | 2절 표. `ModelArtifact` 개인 행과 `UserTravelProfile` 을 더 지운다. 공용 `RouteCorrection` 은 남긴다(이유는 2절 · 5절) |
| 오프라인 큐(기기에 아직 못 올린 기록) | **서버가 거른다**(4절). 초기화 시각보다 이른 기록은 2xx 로 받고 저장하지 않는다. 앱도 지우는 것을 권장한다(7절) |
| 남용 방지 | 사용자당 **시간당 5번**(`learning_reset`, 429). 카카오를 부르지 않아 비용은 DB 뿐이다. 하루 단위 제한은 두지 않는다 |
| 경로 | 제안 그대로 `POST /api/auth/me/learning/reset` |

## 7. 앱이 할 일

1. 없음(필수). 경로 · 본문 · 2xx/401/404·405 해석이 제안 계약과 같다. 업로드 응답에 `ignored` 가 늘었을 뿐 2xx 는 그대로라 큐 처리도 같다. 서버 배포 전에는 지금처럼 404 → "아직 서버에서 지원하지 않아요".
2. (권장) **DONE(2xx) 후 대기열에서 `reset_at` 이전 항목 지우기**: 이동 관측 큐의 `observed_at`, 알람 기록 큐의 `occurred_at`, 블록 기록 큐의 `client_recorded_at` 이 `reset_at` 보다 이른 항목. 서버가 버리기는 하지만, 올리기 전에 지우면 업로드 한 번을 아끼고 서버 배포 전 버전과 섞여도 안전하다.
3. (권장) 2xx 뒤 홈 일정 목록을 다시 받는다(이미 하고 있다). 바뀐 알람(`changed_plans`)은 목록의 `alarm_plan`(`effective_alarm_at`)으로 기기에 다시 건다. 직접 정한 시각은 그대로다.
4. (선택) `skipped_plans` 가 0 보다 크면 "곧 울릴 알람과 이미 시작한 일정은 그대로예요" 같은 한 줄을 덧붙일 수 있다. 문구는 앱이 정한다.
5. 429 는 지금처럼 FAILED 문구("잠시 후 다시 시도해 주세요")로 충분하다.

## 8. 배포

마이그레이션 `accounts/0009_profile_learning_reset_at`(`Profile.learning_reset_at`, null 허용 · `db_default` NULL — 옛 서버 버전이 같은 DB 에서 돌아도 된다). 스로틀 범위 `learning_reset`(5/hour)이 `config/settings/base.py` 에 있다. 밤 학습 워크플로는 바꿀 것이 없다(`train_models` 가 끝에서 `[6]` 을 함께 한다).
