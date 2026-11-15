# 쌓인 기록 초기화 API — 서버 (iter4 BE-RESET)

<!-- Partly AI-generated (Claude) -->

내 정보 › 계정 › **기록 초기화**. 앱 쪽 제안 계약(`front-iter4` 의 `docs/iter4/learning-reset-api.md`)을 그대로 받는다. 경로 · 본문 · 상태 코드가 같아서 앱은 바꿀 것이 없다(5절은 권장).
구현: `backend/apps/planning/learning_reset.py`(지우기 · 다시 섞기), `backend/apps/accounts/views_learning.py`(뷰) · 테스트: `backend/apps/planning/tests/test_learning_reset.py` · 마이그레이션 없음

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
  "reset_at": "2026-11-15T21:38:41.408847+09:00"
}
```

- `deleted`: 지운 행 수. 키는 늘 8개이고 값은 정수다(앱 `LearningResetResponse.deleted: Map<String, Int>`). 지울 것이 없으면 모두 0 이고 그래도 200 이다(멱등).
- `recomputed_plans`: 지운 기준으로 다시 섞은 앞으로의 계획 수. `changed_plans`: 그중 알람 값이 실제로 바뀐 수.
- `reset_at`: 서버가 지운 시각(다른 응답과 같은 ISO 8601). 5절의 오프라인 큐 정리에 쓴다.

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
| `observations.TripReroute` | `trip_reroutes` | 경로 바꾸기 기록. 이동 학습의 재료 |
| `routines.BlockObservation` | `block_observations` | 블록별 실제 걸린 시간. 준비 시간 분포의 재료 |
| `planning.BufferAdjustment` | `buffer_adjustments` | 지각 이력으로 맞춘 안전 버퍼. 지우면 기본 버퍼로 돌아간다 |
| `reports.WeeklySummary` | `weekly_summaries` | 기록으로 쓴 주간 요약 문장 |
| `prediction.ModelArtifact` 중 **이 사용자의 개인 행** | `model_artifacts` | 개인 블록 분포(밤 학습 `--users`). 전역 행(`user` null)은 건드리지 않는다 |
| `routing.UserTravelProfile` | `travel_profiles` | 도보 기록으로 배운 걷는 속도 |

앱 문서 표에서 빠졌던 것은 `ModelArtifact`(개인 행)와 `UserTravelProfile`(걷는 속도)이다. 둘 다 이 사용자 기록에서만 만든 값이다.

| 남긴다 | 왜 |
| --- | --- |
| 일정 · 반복 · 종류(`events.*`), 블록 체크(`EventBlockSelection`), 고른 경로(`Event.route_key`) | 사용자가 넣거나 고른 것 |
| 일정의 "안 감"(`skipped_at`) · 학습 제외 표시(`prep_learning_excluded_at`) | 일정에 붙은 표시다. 기록이 지워지면 효과도 없다 |
| 집 · 저장한 집 · 준비 시간 · τ(`Profile`, `SavedPlace`), 루틴 블록(`RoutineBlock`), 계정 · 소셜 연결 | 사용자가 넣은 것 |
| `planning.AlarmPlan` · `PlanRevision` | 계획과 그 이력이다. 다시 섞을 때 이력의 카카오 원값을 쓴다. 지난 일정의 이력은 관측이 없으면 학습에 쓰이지 않는다 |
| `routing.RouteCorrection` | 모든 사용자 기록으로 만든 **공용** 이동 보정이라 이 사용자 몫만 뗄 수 없다. 밤 전체 학습(매일 23:17)이 남은 기록으로 다시 만들 때 이 사용자 기록이 빠진다 |
| `reports.LlmCall` | 하루 LLM 호출 상한을 세는 표. 지우면 초기화로 상한을 되살릴 수 있다 |
| `common.ApiRequestLog` | 운영 기록. 보존 기간(30일)이 지나면 따로 지워진다 |

- **한 트랜잭션**에서 8개 표를 지우고 다시 섞는다. 중간에 실패하면 모두 되돌린다(일부만 지워진 상태 · 기록은 없는데 알람은 옛 학습값인 상태를 남기지 않는다).
- 지난 일정은 남으므로 주간 리포트에는 "기록 없는 아침" 으로 보인다(정시율 · 캘리브레이션에서는 빠진다).

## 3. 알람 다시 섞기

- 지운 뒤 앞으로의 일정(가까운 50개, 반복 창 안) 알람을 **카카오를 부르지 않고** 다시 섞는다. 루틴 블록 수정 · 밤 학습과 같은 함수다(`planning.refresh.refresh_upcoming`, 계획 이력의 `trigger` 는 `profile`). 사용자가 직접 한 일이라 확인한 알람이 5분 넘게 바뀌어도 "바꿀까요" 제안 없이 바로 바꾼다([alarm-control](server-alarm-control.md)).
- 기록이 없을 때의 기준: 준비 시간은 루틴 블록 범위 · 프로필 준비 시간, 버퍼는 기본값(`buffers.DEFAULT_MINUTES`), 걷는 속도는 1.0. 이동 보정은 공용 값 그대로다.
- **이미 울렸거나 지금 울려야 할 알람**(`alarm_at` 이 지금 이전)은 그대로 둔다. 기기에 걸린 알람과 서버 계획이 어긋나지 않게 한다.
- 계산 불가 상태(`no_home`, `route_failed` 등)거나 이력에 원값이 없는 일정은 건드리지 않는다. 다음 계산(일정 수정 · 재계산 · 밤 배치)에서 새 기준이 들어간다.
- 사용자가 직접 정한 알람 시각(BE-H3b)은 아직 서버에 없다. 생기면 다시 섞기(`refresh`)가 그 값을 덮지 않아야 한다 — 초기화도 같은 함수를 쓰므로 거기서 한 번 막으면 된다.

## 4. 앱 문서 4절 질문에 대한 결정

| 질문 | 결정 |
| --- | --- |
| 빠진 '기록에서 만들어지는 것' | 2절 표. `ModelArtifact` 개인 행과 `UserTravelProfile` 을 더 지운다. 공용 `RouteCorrection` 은 남긴다(이유는 2절) |
| 오프라인 큐(기기에 아직 못 올린 기록) | **서버는 초기화 뒤에 올라온 기록을 그대로 받는다**(이번 iter 에서 서버 쪽 거르기 없음 — 거르려면 계정에 시각 컬럼이 필요하다). 대신 응답에 `reset_at` 을 준다. 앱은 2xx 를 받으면 대기 중인 관측 · 알람 기록 · 블록 기록 큐를 비우는 것을 권장한다(5절) |
| 남용 방지 | 사용자당 **시간당 5번**(`learning_reset`, 429). 카카오를 부르지 않아 비용은 DB 뿐이다. 하루 단위 제한은 두지 않는다 |
| 경로 | 제안 그대로 `POST /api/auth/me/learning/reset` |

## 5. 앱 할 일

1. 없음(필수). 경로 · 본문 · 2xx/401/404·405 해석이 제안 계약과 같다. 서버 배포 전에는 지금처럼 404 → "아직 서버에서 지원하지 않아요".
2. (권장) 2xx 를 받으면 아직 올리지 않은 관측 큐를 비운다. 큐를 남겨 두려면 `reset_at` 보다 이른 `observed_at`(관측) · `occurred_at`(알람 기록) · `client_recorded_at`(블록 기록) 항목을 버린다. 그대로 올리면 지운 아침이 다시 학습에 들어간다.
3. (권장) 2xx 뒤 홈 일정 목록을 다시 받는다(이미 하고 있다). 바뀐 알람(`changed_plans`)은 목록의 `alarm_plan` 으로 기기에 다시 건다.
4. 429 는 지금처럼 FAILED 문구("잠시 후 다시 시도해 주세요")로 충분하다.

## 6. 배포

마이그레이션이 없다. 스로틀 범위 `learning_reset`(5/hour)이 `config/settings/base.py` 에 더해졌다.
