# 쌓인 기록 초기화 (iter4, BE-RESET)

<!-- Partly AI-generated (Claude) -->

내 정보 › 계정 › **기록 초기화**. 쓰면서 쌓인 기록과 거기서 맞춰진 값을 지우고 처음 가입했을 때의 기준으로
되돌린다. 기록은 서버에 있어 서버 API 가 있어야 동작한다.

서버: **아직 없음 — 백엔드에 요청(iter4 BE-RESET)**. 그 전까지 앱은 404/405 를 받고 "아직 서버에서 지원하지
않아요. 기록은 그대로예요" 라고 알린다(아무것도 바꾸지 않는다).

앱 구현: `domain/model/LearningReset.kt`(문구 · 응답 해석), `ui/me/LearningResetDialog.kt`,
`HomeViewModel.askLearningReset/confirmLearningReset`, `AuthApi.resetLearning` · 테스트 `LearningResetTest`

## 1. 요청

```
POST /api/auth/me/learning/reset
Authorization: Bearer <access>
(본문 없음)
```

경로는 제안이다. 백엔드가 다른 경로가 낫다고 보면 알려 주면 앱의 `AuthApi.resetLearning` 한 줄만 바꾼다.

## 2. 지우는 것 · 남기는 것 (요청한 사용자 것만)

| 지운다 | 모델(지금 코드 기준) |
| --- | --- |
| 출발 · 도착 기록 | `observations.TripObservation` |
| 아침(알람 · 준비) 기록 | `observations.AlarmLog` |
| 다시 잡은 경로 기록 | `observations.TripReroute` |
| 준비 항목별 실제 걸린 시간 | `routines.BlockObservation` |
| 기록에서 맞춰진 여유 조정 | `planning.BufferAdjustment` |
| 주간 리포트 요약(기록에서 만든 것) | `reports.WeeklySummary` |
| 이 사용자 기록으로 학습한 값 | 사용자별 분포 · 보정값이 있으면 함께 (`prediction`) |

| 남긴다 | |
| --- | --- |
| 일정 · 반복 · 종류 | `events.*` |
| 집 주소 · 저장한 다른 집 | `Profile`, saved places |
| 사용자가 넣은 준비 시간 · 아침 루틴 항목 | `Profile.prep_minutes`, `routines.RoutineBlock` |
| 계정 · 로그인 연결 · 안내 봤음 표시 | `accounts.*` |

- 한 트랜잭션으로 지운다(일부만 지워진 상태가 남지 않게).
- 지운 뒤 **앞으로의 일정 알람 계획을 다시 계산**한다(기록이 없을 때의 기본 기준으로). 사용자가 직접 정한
  알람 시각(BE-H3b)이 생기면 그것은 그대로 둔다.
- 전체 사용자 공용 모델(`prediction.ModelArtifact`)은 건드리지 않는다.
- 멱등하다. 지울 것이 없어도 200 이다.

## 3. 응답

```
200 OK
{"deleted": {"trip_observations": 42, "alarm_logs": 17, "trip_reroutes": 3, "block_observations": 20}}
```

`deleted` 는 선택이다(앱은 로그로만 남긴다). 본문 없는 204 도 앱은 성공으로 본다.

| 상태 | 앱의 안내 |
| --- | --- |
| 2xx | "기록을 모두 지웠어요. 알람은 처음 기준으로 다시 맞춰요" + 홈 목록 다시 받기 |
| 404 · 405 · 501 | "아직 서버에서 지원하지 않아요. 기록은 그대로예요" |
| 401 | "로그인이 끝나 기록을 지우지 못했어요. 다시 로그인해 주세요" |
| 그 밖 · 5xx | "기록을 지우지 못했어요. 잠시 후 다시 시도해 주세요" |
| 연결 실패 | "서버에 연결되지 않아 기록을 지우지 못했어요" |

## 4. 백엔드에 묻는 것

- 위 표에서 빠진 '기록에서 만들어지는 것'이 있는지(모델 이름은 앱 쪽에서 코드를 읽고 추정한 것이다).
- 기기에 아직 올리지 못한 기록(오프라인 큐)이 초기화 뒤에 올라오면 받을지 버릴지. 버리려면 응답에
  `reset_at` 을 주고 그 이전 `observed_at` 은 무시하는 방법이 있다. 앱은 지금 큐를 비우지 않는다.
- 남용 방지(예: 하루 몇 번)가 필요한지.
