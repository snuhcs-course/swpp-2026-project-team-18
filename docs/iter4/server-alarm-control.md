<!-- Partly AI-generated (Claude) -->
# 알람 직접 정하기 · 확인 · "바꿀까요" 서버 계약 (iter4 H2-3)

휴리스틱 평가 심각도 3 "사용자가 알람을 고치거나 승인할 수 없다"에 대한 서버 쪽이다. 세 가지를 넣었다.

| ID | 기능 | 화면(Figma) |
| --- | --- | --- |
| BE-H3b | **직접 정한 알람 시각** 저장 · 그 시각의 정시 도착 확률 · [추천 시각으로 되돌리기] | 시트 `546:1595`, 직접 정한 알람 `546:2398` |
| BE-H3a | **확인 전 / 확인함** 상태 · [이 시각으로 알람 맞추기] | 확인 전·후 `568:2079`, 알람 확인 시트 `547:2095` |
| BE-H3d | 다시 계산으로 5분 넘게 바뀌면 **"바꿀까요" 제안** · 수락/거절 · 답이 없을 때 규칙 | 묻기 `547:2766` |
| BE-H3e | risk-options 에 **추천 칸**(`recommended_key`) | 시트 "빠르게 고르기" |

BE-H3c(임의 시각 → 확률)는 11-20 에 **risk-options 의 1분 단위 표(`probability_table`)** 로 넣었다(3.7절). 같은 날
BE-P100 으로 확률은 100 을 내지 않고 소수 한 자리 값(`*_precise`)도 준다(5절).

구현: `planning/alarm_control.py`(규칙 전부), `planning/views.py`·`planning/urls.py`(API), `AlarmPlan` 새 필드
(마이그레이션 `planning 0014_alarm_control`, events 마이그레이션 없음), 재계산 훅 `services._upsert`·`refresh._remix`,
응답 `events/serializers.py` `AlarmPlanSerializer.to_representation`, τ 선택 훅 `events/views.py` `EventDetailView.update`,
`planning/risk_options.py` · 테스트: `planning/tests/test_alarm_control.py`(상태 전이),
`planning/tests/test_alarm_control_api.py`(HTTP)

## 1. 세 가지 시각

| 이름 | 응답 필드 | 뜻 |
| --- | --- | --- |
| 추천 시각 | `alarm_at` = `recommended_alarm_at` | 서버 계산 결과. 다시 계산하면 바뀐다. **예전 앱이 읽는 `alarm_at` 은 그대로 추천 시각이다** |
| 직접 정한 시각 | `user_alarm_at` | 사용자가 정한 시각. 다시 계산해도 바뀌지 않는다(확률만 새로 구한다) |
| **울릴 시각** | `effective_alarm_at` | 직접 정한 시각 → (제안에 답을 기다리는 동안) 원래 시각 → 추천 시각 순으로 고른다 |

**앱은 기기 알람을 `effective_alarm_at` 으로 건다.** `alarm_at` 으로 걸면 직접 정한 시각과 "그대로 두기" 가 무시된다.

## 2. 응답 필드 (`alarm_plan` 안, 모두 읽기 전용)

일정 객체를 돌려주는 모든 응답(목록 · 상세 · 생성 · 수정 · recompute · skip · 아래 새 API)에 붙는다. 기존 필드는 그대로다.

| 필드 | 타입 | 뜻 |
| --- | --- | --- |
| `alarm_source` | `"recommended"` \| `"user"` | 울릴 시각이 추천인지 직접 정한 것인지. `user` 면 ④ 카드에 "직접 정한 시각" 회색 알약, 홈 줄에 "직접" |
| `recommended_alarm_at` | 시각 \| null | 추천 시각(`alarm_at` 과 같다). 계산하지 못했으면 null |
| `effective_alarm_at` | 시각 \| null | 울릴 시각. 계산하지 못했으면(`status` ≠ `ok`) null |
| `effective_on_time_probability` | 0~99 \| null | 울릴 시각에 일어날 때의 정시 도착 확률. 준비 · 이동 둘 다 점추정이면 null(`on_time_probability` 와 같은 조건) |
| `effective_on_time_probability_precise` | 0.0~99.9 \| null | 같은 값의 소수 한 자리(BE-P100). 정수는 이 값을 반올림하되 99 를 넘지 않는다 |
| `on_time_probability_precise` | 0.0~99.9 \| null | 추천 시각 확률(`on_time_probability`)의 소수 한 자리 |
| `user_alarm_at` | 시각 \| null | 직접 정한 시각. `alarm_source` 가 `user` 일 때만 |
| `user_alarm_reason` | `""` \| `"picked"` \| `"kept"` | `picked` 휠로 골랐다, `kept` "그대로 두기"(또는 답이 없어 서버가 지킴)로 원래 시각을 지켰다. 동작은 같다 |
| `user_alarm_probability` | 0~99 \| null | 직접 정한 시각의 확률. 다시 계산할 때마다 새로 구한다 |
| `user_alarm_probability_precise` | 0.0~99.9 \| null | 같은 값의 소수 한 자리 |
| `alarm_confirmed` | bool | 울릴 시각을 사용자가 확인했는가. 새 일정은 `false` |
| `alarm_confirmed_at` | 시각 \| null | 확인한 순간. 확인 전이면 null |
| `alarm_proposal` | 객체 \| null | 답을 기다리는 "바꿀까요" 제안. 아래 표 |

`alarm_proposal`:

| 필드 | 뜻 |
| --- | --- |
| `kind` | `recommended_moved`(추천 알람이 5분 넘게 움직임) 또는 `user_low_probability`(직접 정한 시각의 확률이 70% 미만) |
| `current_alarm_at` | 지금 울릴 시각(= `effective_alarm_at`). 답할 때까지 이 시각에 울린다 |
| `proposed_alarm_at` | 바꾸자는 시각(= 새 추천 시각). 홈 카드 [7:28로 앞당기기] 의 시각 |
| `move_minutes` | `proposed − current`(분). 음수 = 앞당기기, 양수 = 늦추기 |
| `reason` · `reason_text` | `travel` · `prep` · `buffer` · `model` · `user_low_probability` 와 문장(예: "이동 시간이 12분 늘었어요", "직접 정한 시각에 일어나면 정시 도착 확률이 46%예요") |
| `keep_probability` | 그대로 둘 때의 확률(새 분포 기준) |
| `change_probability` | 바꿀 때의 확률(= 새 추천의 `on_time_probability`) |
| `keep_probability_precise` · `change_probability_precise` | 위 두 값의 소수 한 자리 |
| `created_at` | 처음 물은 시각. 같은 제안을 다시 내면(15분마다 다시 계산) 그대로다 |
| `answer_by` | 답할 마감 = `min(current_alarm_at, proposed_alarm_at) − 10분`(6절). 앞당기는 제안이면 제안 시각의 10분 전 |

예(직접 정한 시각 8:10, 추천 7:54, 실제 로컬 서버 응답):

```json
"alarm_at": "2026-11-17T07:54:00+09:00", "on_time_probability": 97,
"alarm_source": "user", "recommended_alarm_at": "2026-11-17T07:54:00+09:00",
"effective_alarm_at": "2026-11-17T08:10:00+09:00", "effective_on_time_probability": 46,
"user_alarm_at": "2026-11-17T08:10:00+09:00", "user_alarm_reason": "picked", "user_alarm_probability": 46,
"alarm_confirmed": true, "alarm_confirmed_at": "2026-11-15T21:41:19.875627+09:00",
"alarm_proposal": {"kind": "user_low_probability", "current_alarm_at": "2026-11-17T08:10:00+09:00",
  "proposed_alarm_at": "2026-11-17T07:54:00+09:00", "move_minutes": -16, "reason": "user_low_probability",
  "reason_text": "직접 정한 시각에 일어나면 정시 도착 확률이 46%예요", "keep_probability": 46,
  "change_probability": 97, "created_at": "2026-11-15T21:41:27.363226+09:00",
  "answer_by": "2026-11-17T07:44:00+09:00"}
```

## 3. API

모두 로그인 필요(Bearer). 응답은 **일정 객체**(`GET /api/events/{id}` 와 같은 모양)다. 카카오를 부르지 않는다.
없는 일정 · 남의 일정은 본문 없는 404, 로그인 안 됨은 401. 오류 본문은 `{"error": {"code", "message", "details"}}`.

공통 409 `alarm_unavailable` — 계획을 계산하지 못했다(`status` ≠ `ok`, 계획 없음). "알람을 아직 계산하지 못했어요…"

### 3.1 `PUT /api/events/{id}/alarm/time` — 직접 정한 시각 저장 (BE-H3b)

```json
{"alarm_at": "2026-11-17T08:10:00+09:00"}
```

- 초 이하는 버린다. 5분 단위는 강제하지 않는다(휠이 5분 단위다).
- **오프셋을 붙여 보낸다.** 오프셋이 없는 시각(`2026-11-17T08:10:00`)은 서버 시간대가 아니라 **사용자 시간대**
  (프로필 `timezone`)의 벽시계 시각으로 읽는다. `confirm` · `proposal/*` 의 시각도 같다.
- 저장하면 **확인한 것으로 둔다**(사용자가 고른 시각이므로 `alarm_confirmed: true`). 기다리던 제안은 지운다.
- **추천 시각과 같은 분을 고르면 직접 정한 시각으로 굳히지 않는다.** `DELETE …/alarm/time` 과 같다(추천 시각 ·
  확인함 · `alarm_source: "recommended"`). 굳히면 그 뒤 다시 계산해도 그 시각에 묶여 추천을 따라가지 않는다.
- 확률은 지금 계획의 분포로 바로 구한다(`user_alarm_probability`). 이 PUT 자체는 70% 미만이어도 묻지 않는다 — 시트가 이미 빨간 "지각할 수 있어요" 를 보여 준 뒤 고른 것이다.

| 상태 | code | 언제 |
| --- | --- | --- |
| 200 | — | 저장 |
| 400 | `invalid_alarm_time` | 없거나 ISO 8601 이 아니다 |
| 400 | `alarm_time_past` | 지금 이전 |
| 400 | `alarm_time_too_late` | 일정 시작 5분 전보다 늦다 |
| 400 | `alarm_time_too_early` | 일정 시작 24시간 전보다 이르다 |
| 409 | `alarm_unavailable` | 위 공통 |

범위 오류의 `details` 는 `{"earliest", "latest"}`(시작 − 24시간, 시작 − 5분)다. 양 끝은 포함한다.

### 3.2 `DELETE /api/events/{id}/alarm/time` — 추천 시각으로 되돌리기

본문 없음. 직접 정한 시각 · 제안 · 거절 기록을 지우고 **추천 시각을 확인한 것으로 둔다.** 직접 정한 시각이 없어도 200.

### 3.3 `POST /api/events/{id}/alarm/confirm` — 이 시각으로 알람 맞추기 (BE-H3a)

본문은 비워도 된다. `{"alarm_at": "<화면에 보인 effective_alarm_at>"}` 을 보내면 그새 바뀌었을 때 409 로 알려 준다.

| 상태 | code | 언제 |
| --- | --- | --- |
| 200 | — | 지금 울릴 시각을 확인함. 두 번 불러도 같다(처음 확인한 순간을 둔다) |
| 400 | `invalid_alarm_time` | `alarm_at` 을 읽을 수 없다 |
| 409 | `alarm_changed` | 보낸 `alarm_at` 이 지금 울릴 시각과 다르다. `details.effective_alarm_at` 에 지금 시각 |
| 409 | `alarm_unavailable` | 공통 |

제안이 기다리는 중에 확인하면 **지금 울릴 시각(`current_alarm_at`)** 을 확인한다. 제안은 그대로 남는다.

### 3.4 `POST /api/events/{id}/alarm/proposal/accept` · `/reject` — 제안에 답하기 (BE-H3d)

본문은 비워도 된다. `{"proposed_alarm_at": "<받은 proposed_alarm_at>"}` 을 보내면 제안이 바뀌었을 때 409.

- **accept**([7:28로 앞당기기]): 추천 시각으로 바꾼다. 직접 정한 시각에 대한 제안이면 직접 정한 시각을 지운다. 확인함.
- **reject**([그대로 두기]): 지금 시각을 지킨다. 확인함. 추천 시각에 대한 제안을 거절하면 지키던 시각이
  **직접 정한 시각(`user_alarm_reason: "kept"`)** 이 된다(7절 결정 3). 같은 제안(5분 안)은 다시 묻지 않는다.

| 상태 | code | 언제 |
| --- | --- | --- |
| 200 | — | 답함. 응답의 `alarm_proposal` 은 null |
| 400 | `invalid_alarm_time` | `proposed_alarm_at` 을 읽을 수 없다 |
| 409 | `no_alarm_proposal` | 기다리는 제안이 없다 |
| 409 | `alarm_proposal_changed` | 보낸 시각이 지금 제안과 다르다. `details.proposed_alarm_at` 에 지금 제안 |
| 409 | `alarm_proposal_expired` | 마감이 지나 서버가 이미 정했다(6절). `details.effective_alarm_at` 이 정한 시각 |
| 409 | `alarm_unavailable` | 공통 |

### 3.5 빠르게 고르기 세 칸(τ) — 기존 `PATCH /api/events/{id}` `{"tau_override": …}`

**`tau_override` 를 보낸 PATCH 는 직접 정한 시각 · 기다리는 제안 · 거절 기록을 지운다**(값이 같거나 null 이어도,
다시 계산이 실패해 계획이 `ok` 가 아니어도). **확인하지는 않는다**(리뷰 H3-4, 리드 결정). 지금 앱(front-iter4)은 칸을
PATCH 로 고르고 기기 알람을 `alarm_at` 으로 걸며 확인 API 를 부르지 않는다. PATCH 가 확인함으로 두면 그 뒤 경로 갱신이
아무도 보지 않는 "바꿀까요" 를 만들고, 답이 없을 때 서버가 지킨 시각(`kept`)이 기기에 걸린 알람과 어긋난다.
**확인은 명시적인 조작에서만 생긴다** — `POST …/alarm/confirm`, `PUT …/alarm/time`, `DELETE …/alarm/time`, 제안 수락·거절.
그래서 "바꿀까요" 는 새 앱에서 사용자가 직접 확인한 알람에만 생긴다. 새 시트(Figma `568:2139`)에서 칸을 고르는 것이
"이 시각으로 맞추기" 라면 앱이 PATCH 뒤에 `POST …/alarm/confirm {"alarm_at": <응답의 effective_alarm_at>}` 을 부른다
(휠로 고르면 PUT 이 이미 확인한다).

다른 필드만 고친 PATCH(제목, 종류 `tag_key`, 장소 …)는 직접 정한 시각을 건드리지 않는다. 단 **`start_at` 이 바뀌면
직접 정한 시각 · 기다리는 제안은 버린다**(9:00 일정의 7:50 은 14:00 으로 옮긴 일정에 맞지 않다). 버리면 새 추천 시각이
확인 전으로 나온다.

반복 일정을 "이후 일정 모두" 로 고칠 때 다른 회차의 직접 정한 시각을 지우려면 `alarm_control.clear_user_choice(event)`
(한 회차) · `clear_user_choices(event_ids)`(여러 회차)를 쓴다. 둘 다 확인하지 않고 계획 상태와 관계없이 지운다.

### 3.6 `GET /api/events/{id}/risk-options` 추가 필드 (BE-H3e)

| 필드 | 뜻 |
| --- | --- |
| `recommended_key` | 일정 종류의 기본 τ(종류에 없으면 프로필 기본 τ)와 같은 칸의 key(`safe`·`normal`·`gamble`). 같은 칸이 없으면 null |
| `default_tau` | 그 기본 τ |

`selected_key`(지금 적용 중인 칸)는 그대로다. 사용자가 τ 를 직접 고른 뒤에도 "추천" 을 `recommended_key` 에 붙이면 된다.
예: τ 0.97 로 바꾼 뒤 `selected_key: "safe"`, `recommended_key: "normal"`, `default_tau: 0.9`.

`options[]` 에는 `probability_precise`(0.1~99.9, 소수 한 자리)가 더해졌다. `probability` 는 1~99 정수다(BE-P100).

### 3.7 `GET /api/events/{id}/risk-options` 의 `probability_table` — 휠 시각의 확률 (BE-H3c)

```json
"probability_table": {
  "unit": "minutes_before_start",
  "precise": [0.0, 0.1, 0.1, …, 87.4, 91.2, …, 99.9],
  "whole":   [0,   1,   1,   …, 87,   91,   …, 99],
  "max_minutes": 74
}
```

- `precise[i]` · `whole[i]` 는 **일정 시작 i 분 전**에 일어날 때의 확률이다. 앱은 `i = (일정 시작 − 휠 시각)` 을 분으로 세어(초는
  버림) 그 칸을 읽는다. `i > max_minutes` 면 마지막 칸(상한 99.9 / 99)이다. `i ≤ 0`(일정 시작과 같거나 뒤)이면 0 이다.
- 세 칸(`options[]`), 계획(`on_time_probability`, `effective_on_time_probability`), 저장한 직접 시각(`user_alarm_probability`)과
  **같은 식 · 같은 분포**다. 같은 시각이면 같은 숫자가 나온다(테스트 `planning/tests/test_on_time.py`).
- 확률을 낼 수 없으면(둘 다 점추정, 계획 없음) null 이고 `unavailable_reason` 을 준다.
- `distribution`(10칸 구간)은 그대로 준다. 휠 확률은 이제 표에서 읽는다.

## 4. 다시 계산할 때의 규칙

다시 계산은 일정 생성 · 수정 · `POST /recompute`(앱의 경로 갱신 워커 포함) · 캘린더 가져오기 · 프로필/집 변경 · 블록 체크 ·
루틴 수정 · 종류 τ 수정 · 밤 학습(`train_models`, `exclude_prep_mornings`)이다. 모두 `services._upsert` 또는 `refresh._remix`
를 지나고, 거기서 `alarm_control.after_compute` 가 아래 순서로 상태를 고친다. 이 단계가 실패해도 알람 계획 저장은 그대로다.
사용자 상태는 **계획 행을 잠그고(`select_for_update`) DB 에서 다시 읽은 값**으로 고친다. 재계산 경로는 계획을 일찍
읽어 두므로(밤 재계산은 일정 50개를 한 번에), 그사이 들어온 `PUT …/alarm/time` 을 옛 상태로 덮지 않게 한다.

1. 일정 시작이 바뀌었으면 직접 정한 시각(정할 때의 시작과 다르면) · 기다리는 제안 · 거절 기록을 버린다. 재계산 전
   계획의 시작은 `arrive_at + buffer_minutes` 로 안다. **마감 처리보다 먼저** 한다 — 먼저 처리하면 "그대로 두기" 가
   옛 7:40 을 14:00 으로 옮긴 일정에 묶는다.
2. 시작이 그대로면 마감이 지난 제안부터 처리한다(6절).
3. 계산하지 못했으면(`route_failed` 등) 제안을 버린다. 직접 정한 시각은 남겨 두고 다시 계산되면 그대로 쓴다(그동안 `effective_alarm_at` 은 null).
4. **직접 정한 시각이 있으면** 시각은 그대로, `user_alarm_probability` 만 새로 구한다. 그 확률이 **70% 미만**이고 새 추천이
   직접 정한 시각보다 **5분 넘게 이르고**, 이미 거절한 제안(5분 안)이 아니면 `user_low_probability` 제안을 낸다.
   아니면 기다리던 제안을 지운다(확률이 회복되면 사라진다). 이 규칙은 어떤 재계산에서도 적용한다.
5. **추천 시각이면**, 재계산 이유가 **경로 갱신(`recompute`) 또는 밤 학습(`learning`)** 이고, 직전 울릴 시각을 **확인한** 상태에서
   새 추천이 **5분 넘게**(앞당기든 늦추든) 움직이면 바꾸지 않는다. 직전 시각을 지키고(`effective_alarm_at` 그대로)
   `recommended_moved` 제안을 낸다. 그 밖(5분 이하, 확인 전, 사용자가 입력을 바꾼 재계산)은 **바로 바꾼다.**
6. 이미 마감이 지난 제안(더 이른 쪽 10분 전 안의 경로 갱신)은 바로 처리한다(6절의 "마감 뒤에 생긴 제안").

밤 재계산(`refresh_upcoming`)이 "곧 울림"(`LEARNING_PROTECT` 3시간 안)을 건너뛰는 판단과 그날 첫 알람(`is_first_alarm`)
순서는 추천 시각이 아니라 **울릴 시각**(`effective_alarm_at`, `alarm_control.effective_alarm_at`)으로 본다.

- **기다리는 제안이 있는데 또 다시 계산하면 제안을 새것으로 바꾼다**(지키는 시각은 그대로, 제안 시각만 새 추천).
  새 추천이 지키는 시각과 5분 안으로 돌아오면 제안을 지우고 새 추천을 쓴다.
- **확인 상태는 저장값이 아니라 비교다.** 확인한 시각(`confirmed_alarm_at`)과 울릴 시각이 1분이라도 다르면 확인 전이다
  (`CONFIRM_TOLERANCE = 0`). 그래서 5분 이하로 조용히 바뀐 알람도 확인 전이 된다(Figma `568:2139` "다시 계산해 시각이
  바뀌면 확인 전으로"). 지키는 동안은 시각이 그대로라 확인함이 유지된다.
- 새 일정 · 새로 계산된 계획은 확인 전이다. 이번 배포 전 계획도 모두 확인 전으로 보인다(확인 기록이 없다).
- `reset_alarm_overrides`(직접 추가한 일정과 같은 날의 켬/끔 선택을 자동으로 돌림)는 **켬/끔만** 돌린다. 직접 정한
  시각은 그 일정만의 선택이라 그대로 둔다.

## 5. 확률 계산 (하나의 식, `planning/on_time.py`)

시각 `T` 에 일어날 때의 확률 = `P(준비 + 이동 ≤ 시작 − T)`. 분포는 `estimators.total_distribution` 이고 버퍼도 쓸 수 있는
시간에 든다. 추천 시각이면 `시작 − T` = τ 분위수(분 단위로 반올림) + 버퍼다. 계획 · 세 칸 · 휠 표 · 직접 정한 시각 · 제안의
두 확률이 모두 이 식이다.

- **언제 내는가(11-20 결정):** 준비 · 이동 중 **한쪽이라도** 변동성을 알면 낸다. 모르는 쪽은 예상값(카카오 예상 시간, 입력한
  준비 시간)에 고정되므로 조금 높게 나올 수 있다. 그 사실은 `confidence_basis`(`travel_variance_unknown` ·
  `prep_variance_unknown`)가 알려 준다. 둘 다 점추정이면 null 이다.
- **숫자(BE-P100):** 소수 한 자리 `*_precise` 는 0.1~99.9(시간이 남아 있을 때), 시각이 일정 시작과 같거나 뒤면 0.0. 정수는
  소수를 반올림하되 1~99 다. **100 은 내지 않는다** — 모델은 확실하다고 말할 수 없다. 정수 필드를 소수로 바꾸지 않은 이유는
  배포된 앱(Gson `Int?`)이 `99.8` 을 받으면 파싱이 깨지기 때문이다.
- 흔들림이 아주 작고 버퍼가 크면 세 칸이 99.9 / 99.9 / 99.8 처럼 거의 같다. 설명 문장은 주지 않는다(11-20 결정).
- 0.21.0 이 저장한 행(소수 칸이 빈 행)은 정수 값을 쓰되 100 은 99 · 99.9 로 내보낸다. 다음 재계산에서 소수 칸이 채워진다.
- 소수 칸은 정수 칸과 맞을 때만 쓴다(`on_time.stored`). 배포 사이에 0.21.0 서버가 같은 DB 에서 정수 칸만 고쳐 쓰면 소수 칸이
  옛 값으로 남기 때문이다.
- "직접 정한 시각이 70% 아래" 제안은 소수 값으로 판단하고, 문장(`reason_text`)도 소수 값으로 쓴다(예: "…확률이 69.6%예요").
- 주간 리포트 `outcomes[].on_time_probability` 도 같은 정수 규칙(1~99)이다.

## 6. 답이 없을 때 (잠정, Figma 캡션의 예시)

**마감 `answer_by` = `min(current_alarm_at, proposed_alarm_at)` − 10분** — 두 시각 중 **더 이른 쪽**의 10분 전이다.
그때까지 답이 없으면 **더 이른(안전한) 쪽**으로 정한다. (예전에는 지금 시각의 10분 전이었다. 7:40 을 지키며 7:15 를
제안하면 마감이 7:30 이라 이미 지난 7:15 로 바꾸게 됐다 — 리뷰 H3-1.)

| 제안 | 더 이른 쪽 | 마감 | 결과 |
| --- | --- | --- | --- |
| 앞당기기(`move_minutes` < 0) | 제안 시각 | 제안 시각 − 10분 | 바꾼다(= accept). 사람이 받아들인 게 아니므로 **확인 전** |
| 늦추기(`move_minutes` > 0) | 지금 시각 | 지금 시각 − 10분 | 지킨다(= reject). 추천 제안이면 지키던 시각이 `kept` 가 된다. 확인 상태 유지 |
| 직접 정한 시각의 70% 제안 | 추천 시각(항상 더 이르다) | 추천 시각 − 10분 | 직접 정한 시각을 지우고 추천 시각. 확인 전 |

**정하는 순간과 "이미 지난 시각으로 바꾸지 않는다"** (`Control.settle`, `SETTLE_MIN_AHEAD` = 1분):

- 정하는 순간 = `max(answer_by, created_at)`. 보통은 마감이다. 마감에는 더 이른 쪽이 늘 10분 뒤라 그대로 그 시각으로 정한다.
- **마감이 이미 지난 뒤에 생긴 제안**(예: 7:40 을 지키는데 7:32 의 경로 갱신이 7:28 을 제안)은 생긴 순간에 바로 정한다.
  그 순간 제안 시각이 이미 지났거나 1분도 남지 않았으면(7:28 ≤ 7:33) **바꾸지 않고 지금 시각(7:40)을 지킨다.**
  아직 오지 않은 시각이 지금 시각뿐이기 때문이다. 서버는 세 번째 시각("지금 바로")을 만들지 않는다. 1분 넘게 남았으면
  제안 시각으로 바꾼다.
- 서버가 늦게 읽어도(마감 뒤 몇 분이 지나서 읽어도) **정하는 순간 기준**으로 판단한다. 그래서 앱이 마감에 기기에서
  정한 것과 서버가 다음에 읽을 때의 결론이 같다. 그 시각이 이미 울렸다면 응답의 `effective_alarm_at` 은 지난 시각이다(울린 시각).

**서버에는 스케줄러도 푸시(FCM)도 없다.** 이 규칙은 서버가 일정을 읽거나 다시 계산할 때 늦게 적용된다.

- 읽기(`GET` 목록·상세 등)는 마감이 지난 제안을 **이미 처리한 모습**으로 내보낸다(DB 는 쓰지 않는다).
- DB 는 다음 다시 계산 · 확인 · 답하기 · 직접 정하기 때 맞춘다. 마감 뒤에 accept/reject 를 부르면 처리 결과를 저장하고 409 `alarm_proposal_expired` 다.
- **앱이 기기에서 같은 규칙을 적용해야 한다.** 밤사이 앱이 서버를 부르지 않으면 아무도 처리하지 않는다. 앱은 제안을 받으면
  **서버가 준 `answer_by`** 에 기기 작업을 걸고(직접 계산하지 않는다), 그때 답이 없으면 `min(current_alarm_at, proposed_alarm_at)`
  으로 기기 알람을 다시 걸고 알림에 이유(`reason_text`)를 보인다. 마감에는 그 시각이 늘 10분 뒤다. 응답으로 받은
  제안은 마감 전이다 — 마감이 지난 제안은 서버가 이미 처리해서 내보낸다(`alarm_proposal: null`, 정한 `effective_alarm_at`).
  서버에 따로 알릴 필요는 없다 — 다음에 읽을 때 서버도 같은 결론을 낸다.
  (서버를 부를 수 있으면 accept/reject 를 불러도 되지만, 마감 뒤라 409 `alarm_proposal_expired` 가 온다. 그 응답의 `details.effective_alarm_at` 이 정한 시각이다.)

## 7. 결정한 것

1. **`alarm_at` 은 추천 시각으로 둔다.** 새 필드 `effective_alarm_at` 이 울릴 시각이다. `alarm_at` 의 뜻을 바꾸면 그 값으로
   계산된 `depart_by` · `prep_minutes` · 이력 · 학습(결정 시점 계획)이 모두 어긋난다. 예전 앱은 계속 추천 시각에 울린다
   (예전 앱에는 직접 정하는 화면이 없으므로 달라지는 것이 없다).
2. **상태는 `AlarmPlan` 에 둔다**(별도 표 없음). 일정 목록이 이미 `alarm_plan` 을 `select_related` 로 읽으므로 추가 조회가 없다.
   계획이 지워지면(반복 회차를 창 밖 날짜로 옮김) 함께 사라진다.
3. **추천 제안을 거절하면 그 시각을 직접 정한 시각(`kept`)으로 굳힌다.** 그냥 "지키는 중" 으로 두면 15분마다 오는 경로 갱신이
   같은 질문을 계속 만든다. 굳힌 뒤에는 직접 정한 시각과 같은 규칙(확률 70% 미만일 때만, 거절한 제안 ±5분은 묻지 않음)을 따른다.
   [추천 시각으로 되돌리기] 로 푼다.
4. **묻는 재계산은 경로 갱신과 밤 학습뿐이다.** 일정 수정 · 생성 · 프로필 · 블록 · 루틴 · 종류는 사용자가 입력을 바꾼 결과라 바로 적용한다(확인 전이 된다).
5. **직접 정하기 · 되돌리기 · 수락 · 거절은 그 결과 시각을 확인한 것으로 둔다.** 사용자가 그 시각을 직접 골랐다. 앱이 그 뒤에
   confirm 을 또 불러도 같다. **τ 칸 고르기(PATCH `tau_override`)는 확인하지 않는다**(3.5절, 리뷰 H3-4 리드 결정).
   지금 앱은 확인 API 를 모르므로, 확인함이 없으면 "바꿀까요" 도 생기지 않아 서버 상태가 기기 알람과 어긋나지 않는다.
6. 직접 정할 수 있는 범위: 지금 이후 · 시작 5분 전까지 · 시작 24시간 전부터(앱 휠은 하루 안의 시각만 고른다).
7. 기준값은 `alarm_control.py` 상수다: `PROPOSAL_THRESHOLD`(5분), `ANSWER_LEAD`(10분), `USER_PROBABILITY_FLOOR`(70),
   `CONFIRM_TOLERANCE`(0분), `SETTLE_MIN_AHEAD`(1분). 팀이 "작은 변화는 확인함 유지" 를 원하면 `CONFIRM_TOLERANCE` 만 5분으로 바꾸면 된다.
8. **휠로 추천 시각과 같은 분을 고르면 추천 시각 · 확인함**(3.1절). 직접 정한 시각으로 영구히 묶지 않는다.
9. **오프셋 없는 시각은 사용자 시간대로 읽는다**(3.1절). 거절하지 않은 이유: 앱 휠이 벽시계 시각을 다루므로 그 뜻이 맞다.

## 8. 앱이 할 일 (front-iter4)

| 기능 | 읽을 것 / 부를 것 |
| --- | --- |
| 기기 알람 | **`effective_alarm_at`** 으로 건다(`AlarmScheduler`). `alarm_at` 은 "추천" 표시에만. 제안을 기다리는 동안도 `effective_alarm_at`(= `current_alarm_at`) |
| 휠 [맞추기](H2-3 시트) | 세 칸과 같은 시각이 아니어도 `PUT …/alarm/time {"alarm_at"}`(**오프셋 포함**). 400 이면 `message` 를 그대로 보여 준다. 추천 시각과 같은 분이면 응답이 `alarm_source: "recommended"` 다 |
| 세 칸(빠르게 고르기) | 지금처럼 `PATCH tau_override`(서버가 직접 정한 시각 · 제안을 지운다). **확인하지 않는다.** 새 시트에서 칸 고르기가 "이 시각으로 맞추기" 면 PATCH 응답의 `effective_alarm_at` 으로 `POST …/alarm/confirm` 을 이어 부른다 |
| "직접 정한 시각" 알약 · 홈 "직접" | `alarm_source == "user"`. 확률은 `effective_on_time_probability`(= `user_alarm_probability`), 추천과의 차이는 `recommended_alarm_at` 과 비교 |
| [추천 시각으로 되돌리기] | `DELETE …/alarm/time` |
| 시트의 "추천" 칸 | risk-options `recommended_key`(null 이면 "추천" 을 붙이지 않는다). `AlarmTimeChoice.recommendedKey` 의 "직접 고른 값 → 보통" 추측은 필요 없다 |
| 확인 전 태그 · 노란 테두리 | `alarm_confirmed == false` |
| [이 시각으로 알람 맞추기](④, 알람 확인 시트) | `POST …/alarm/confirm {"alarm_at": effective_alarm_at}`. 409 `alarm_changed` 면 일정을 다시 받아 새 시각을 보여 준다. 확인 안 해도 `effective_alarm_at` 에 울린다 |
| "바꿀까요" 카드 · 헤드업 알림 | `alarm_proposal != null`. [n:nn로 앞당기기] = `POST …/proposal/accept {"proposed_alarm_at"}`, [그대로 두기] = `…/reject`. 문구는 `reason_text`, 확률은 `keep_probability`/`change_probability`. 409 면 일정을 다시 받는다 |
| 바꾼 뒤 스낵바 [되돌리기] | 원래 시각(`current_alarm_at`)으로 `PUT …/alarm/time`(직접 정한 시각 `picked` 가 된다) |
| 답이 없을 때 | 6절. **서버가 준 `answer_by`**(= 더 이른 쪽 − 10분, 앞당기기면 제안 시각 − 10분)에 기기 작업을 걸고 더 이른 쪽으로 기기 알람을 다시 건다. 마감을 `current_alarm_at − 10분` 으로 직접 계산하지 않는다(앞당기는 제안이 이미 지난 뒤가 된다). 서버도 다음에 읽을 때 같은 결론이다 |
| 경로 갱신 워커 | 응답의 `effective_alarm_at` 으로 다시 건다. 새 `alarm_proposal` 이 생겼으면 앱이 꺼져 있을 때 헤드업 알림 |

예전 앱(새 필드를 모름)은 그대로 동작한다 — `alarm_at` 이 추천 시각이고 제안 · 확인은 보이지 않을 뿐이다.

**리뷰 수정(2026-11-15)으로 앱이 맞춰야 할 것:**

1. 답이 없을 때의 기기 작업 시각은 `answer_by` 를 그대로 쓴다. 앞당기는 제안이면 `proposed_alarm_at − 10분` 이다(예전 문서의 `current_alarm_at − 10분` 이 아니다).
2. τ 칸 PATCH 는 더 이상 확인하지 않는다. 새 시트에서 확인까지 하려면 PATCH 뒤에 `POST …/alarm/confirm` 을 부른다. 지금 앱(확인을 모름)은 할 일이 없다 — 확인하지 않은 알람에는 "바꿀까요" 가 생기지 않는다.
3. `PUT …/alarm/time` 등에 시각을 보낼 때 오프셋을 붙인다(없으면 사용자 시간대로 읽는다).
4. 휠로 추천 시각 그대로를 고르면 응답이 `alarm_source: "recommended"` 다. "직접 정한 시각" 알약을 응답 기준으로 그린다.

## 9. 남은 것 · 한계

- 이력(`PlanRevision`)과 학습은 추천 시각 기준이다. 직접 정한 시각으로 일어난 아침도 학습은 추천 계획과 비교한다. 필요하면 이력에 울릴 시각을 더한다.
- 그날 "첫 알람"(`is_first_alarm`) 순서는 울릴 시각으로 정한다(리뷰 H3-5). 일정 목록 응답마다 그 사용자의 계획을 한 번
  읽어 파이썬에서 정렬한다(예전과 같은 쿼리 1번).
- 관리 화면(`AlarmPlanAdmin`)에는 새 필드를 넣지 않았다. 목록의 "정시 확률" 은 소수 한 자리로 보인다(예전엔 퍼센트에 100 을 한 번 더 곱했다).
