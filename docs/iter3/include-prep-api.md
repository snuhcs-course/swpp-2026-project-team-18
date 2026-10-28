# 준비 시간 포함 여부 API 계약 (`include_prep`)

출발지를 따로 고른 일정은 0.6.0부터 "이미 밖에 있다" 로 보고 알람에서 준비 시간을 뺐다.
친구 집·숙소에서 자고 바로 나서는 아침도 출발지가 집이 아니라서, 그날은 알람이 준비
시간만큼 늦게 울렸다. 이제 일정마다 준비 시간을 넣을지 고를 수 있다.

구현: `Event.include_prep`·`Event.prep_applies`(마이그레이션 `events 0009`),
`planning/services.py` `estimate_components` · 테스트:
`backend/apps/events/tests/test_include_prep.py`, `planning/tests/test_prep_scope.py`

## 1. 규칙

| `include_prep` | 집에서 출발(`origin_lat` 없음) | 출발지를 따로 고름 |
| --- | --- | --- |
| `null` (기본) | 넣는다 | 뺀다 |
| `true` | 넣는다 | **넣는다** |
| `false` | **뺀다** | 뺀다 |

- **기본값은 `null` 이고 지금 규칙 그대로다.** 기존 일정과 이 필드를 모르는 앱 버전의
  알람은 하나도 바뀌지 않는다. 출발지를 따로 고르는 경우 대부분이 "이미 밖에 있다"
  여서, 자고 나서는 경우만 사용자가 고르게 했다.
- 넣을 때 준비 시간은 집 출발과 같은 값이다(루틴 블록, 블록 관측, GPS 준비 학습, 온보딩
  값 순). 남의 집이라고 따로 줄이지 않는다.
- 뺀 일정의 `alarm_plan.prep_source` 는 출발지 때문이든 사용자가 뺐든 `not_from_home`,
  `prep_minutes` 는 0 이다. 지금 앱이 이 값으로 준비 항목을 숨기므로 값을 나누지 않았다.
- GPS 준비 학습(알람 끄기 → 출발)은 집에서 준비한 아침만 쓴다. 준비를 뺀 집 출발과,
  준비를 넣은 친구 집 출발은 학습에 넣지 않는다.

## 2. 요청

`POST /api/events`, `PATCH /api/events/{id}` 에 필드 하나가 늘었다.

| 필드 | 값 | 비고 |
| --- | --- | --- |
| `include_prep` | `true` / `false` / `null` | 보내지 않으면 생성은 `null`, 수정은 그대로. `null` 을 보내면 기본(출발지로 정함)으로 돌아간다 |

- 불리언이 아닌 값(`"maybe"`, `2` 등)은 400이고 아무것도 저장하지 않는다.
- 반복 일정은 생성할 때 고른 값을 모든 회차가 물려받는다. 회차 하나를 PATCH 하면 그
  회차만 바뀐다.
- 출발지를 바꿔도 `include_prep` 는 그대로다. 출발지를 집으로 되돌릴 때 사용자가 고른
  값을 지우려면 `include_prep: null` 을 같이 보낸다.

## 3. 응답

일정 객체(`GET /api/events`, `GET /api/events/{id}`, 생성·수정 응답)에 두 필드가 붙는다.

```json
{"origin_lat": 37.4979, "origin_lng": 127.0276, "origin_label": "친구 집",
 "include_prep": true, "prep_applies": true,
 "alarm_plan": {"prep_minutes": 30, "prep_source": "onboarding", "...": "..."}}
```

| 필드 | 뜻 |
| --- | --- |
| `include_prep` | 사용자가 고른 값. `null` 이면 고르지 않았다 |
| `prep_applies` | 위 규칙의 결과. 지금 알람에 준비 시간이 들어가 있는가. 읽기 전용(보내도 무시) |

기존 앱은 모르는 필드를 무시하므로 영향이 없다.

## 4. 다시 계산

- `include_prep` 를 바꿔 `prep_applies` 가 바뀌면 경로를 다시 조회하지 않고 지금 경로
  원값으로 다시 계산한다(카카오 0회). 알람이 준비 시간만큼 당겨지거나 늦춰지고 출발
  시각은 그대로다. 수정 분류 표는 [event-edit-api.md](event-edit-api.md) 3절.
- 결과가 같으면(집 출발에서 `null` → `true`) 계획을 다시 만들지 않는다.
- 위험 선택지(`risk_options`)와 경로 갱신 워커, 야간 재계산도 같은 규칙을 쓴다.

## 5. 앱이 할 일

1. **출발지를 집이 아닌 곳으로 골랐을 때만 선택지를 보여 준다.** 예: 경로 선택이나 일정
   화면의 출발지 아래에 스위치 "여기서 자고 출발해요 (준비 시간 포함)". 켜면
   `include_prep: true`, 끄면 `null` 을 보낸다.
2. 출발지 종류로 보여 주고 싶으면 이렇게 바꾼다. 집 = 출발지 없음, 외출 중 = 출발지 +
   `include_prep: null`, 숙소·친구 집 = 출발지 + `include_prep: true`.
3. 집 출발에서 준비를 빼는 `false` 는 고급 설정으로 두거나 보여 주지 않아도 된다.
4. `EventUpdateRequest`·생성 요청에 `include_prep` 를 더한다. **기본으로 돌리는
   `null` 은 실제로 보내야 한다**(Gson 기본은 `null` 필드를 빼고 보낸다,
   [event-edit-api.md](event-edit-api.md) 6절 2번과 같다).
5. 저장 뒤에는 다른 수정과 같이 목록을 다시 불러오고 기기 알람을 다시 건다.
6. 계산 방법 화면은 지금처럼 `prep_source == "not_from_home"` 이면 준비 항목을 숨긴다.
   문구를 나누고 싶으면 `include_prep == false` 일 때 "준비 시간을 빼기로 했어요", 아니면
   "집에서 출발하지 않아 준비 시간이 없어요".

## 6. 직접 확인 (배포 뒤)

1. 출발지를 다른 곳으로 고른 일정을 만든다 → 알람 계획의 준비 시간이 0분.
2. `PATCH /api/events/{id}` `{"include_prep": true}` → 응답의 `prep_applies` 가 `true`,
   `alarm_plan.alarm_at` 이 준비 시간만큼 이르다. `depart_by` 는 그대로.
3. `{"include_prep": null}` → 다시 0분.
