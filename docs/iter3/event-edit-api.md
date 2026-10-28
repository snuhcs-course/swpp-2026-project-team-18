# 일정 수정 API 계약 (알람 결정 뒤 수정)

앱의 일정 수정 화면(제목·시각·장소·출발지·경로·태그·지각 위험)을 위한 서버 계약이다.
요청·응답 형식은 지금 배포된 서버와 같아서 앱 작업은 바로 시작할 수 있다. 이 문서와 같은
커밋에서 바뀐 것은 서버 안쪽 동작 두 가지다. 시각·τ만 바꾸면 경로를 다시 조회하지 않고,
다른 날로 옮기면 옮겨 간 날의 수동 켬/끔을 자동으로 돌린다(3·4절).

구현: `backend/apps/events/edits.py`, `EventDetailView.update`, `planning/refresh.py`
`refresh_edited` · 테스트: `backend/apps/events/tests/test_event_edit.py`

## 1. 요청

`PATCH /api/events/{id}` · JSON · 부분 수정이다. 보낸 필드만 바꾼다.

| 필드 | 값 | 지우기(기본값으로) | 비고 |
| --- | --- | --- | --- |
| `title` | 문자열(앞뒤 공백 제거) | 지울 수 없음 | 빈 문자열은 400 |
| `start_at` | ISO 8601, 오프셋 포함 | 지울 수 없음 | 예: `2026-11-02T10:00:00+09:00` |
| `place` | `{name, lat, lng, address?, kakao_place_id?}` 전체 객체 | `null` → 목적지 없음(`no_place`) | 일부 필드만 보내면 400. 보내면 `destination_is_home` 이 자동으로 `false` |
| `destination_is_home` | `true` / `false` | | `true` 면 `place` 를 비운다. 집 주소가 없으면 400. `place` 와 함께 `true` 를 보내면 400 |
| `origin_lat`, `origin_lng` | 국내 좌표 | 둘 다 `null` → 집에서 출발 | **둘을 함께** 보낸다. 하나만 보내면 저장된 짝과 합쳐 보고, 짝이 없으면 400 |
| `origin_label` | 문자열(80자) | `""` | 표시용. 바꿔도 다시 계산하지 않는다 |
| `route_key` | `walk` · `bicycle` · `car` · `transit:...` | `""` → 서버가 도보·대중교통 중 빠른 쪽 | `null` 은 400. `GET /api/routes/candidates` 가 준 key 만 |
| `tag_key` | 태그 key | `null` 또는 `""` → 태그 없음 | 없는 key 는 400 |
| `tau_override` | 0.5 ~ 0.999 | `null` → 태그 기본값, 없으면 프로필 기본값 | |
| `alarm_enabled` | `true` / `false` | `null` → 자동(하루 첫 알람) | |
| `include_prep` | `true` / `false` | `null` → 출발지로 정함(집이면 넣고 따로 골랐으면 뺌) | 준비 시간을 알람에 넣을지. [include-prep-api.md](include-prep-api.md) |
| `repeat_weekdays` | | | **보내면 400.** 반복 규칙은 생성할 때만 정한다 |

출발지·목적지가 50m 안이면 400이다(생성과 같은 규칙, 도착 판정 반경). 장소·출발지 필드를 보낼 때만
검사하므로 이미 저장된 가까운 일정도 제목·켬/끔은 계속 고칠 수 있다.

## 2. 응답

- **200**: `GET /api/events/{id}` 와 같은 일정 객체. `alarm_plan` 은 다시 계산한 새 계획이고
  `alarm_on`·`is_first_alarm` 도 새 계획 기준이다.
- **400**: `{"error": {"code": "...", "message": "...", "details": {"필드": ["..."]}}}`.
  아무것도 저장하지 않는다.
- **404**: 없는 일정이거나 다른 사용자의 일정.

## 3. 무엇이 다시 계산되나

서버는 **보낸 필드가 아니라 값으로** 비교한다. 수정 화면이 모든 필드를 함께 보내도 실제로
바뀐 것만 본다. 같은 장소를 다시 보내면(좌표가 같으면) 경로는 그대로다.

| 실제로 바뀐 것 | 알람 계획 | 카카오 경로 호출 |
| --- | --- | --- |
| 출발지·목적지 좌표, `route_key` | 경로부터 다시 계산 | 1~2회 |
| `start_at`, τ(`tau_override`·태그 기본값), 준비 시간을 넣는지(`include_prep`·출발지, [include-prep-api.md](include-prep-api.md)) | 지금 경로 소요시간으로 다시 계산 | 0회 |
| `title`, `origin_label`, `alarm_enabled` | 그대로 | 0회 |

- **시각만 바꾸면 경로를 다시 조회하지 않는다.** 카카오 경로 조회는 출발 시각을 받지 않아
  다시 불러도 같은 소요시간이 온다. 준비 시간·이동 보정(시간대별)·버퍼·τ 는 새 시각으로
  다시 계산한다. 알람 3시간 전부터는 앱의 경로 갱신 워커가 실제 경로로 다시 계산한다.
- 경로 필드(`route_path`, `route_summary`, `route_detail`, `alt_route_*`)는 시각·τ 수정에서
  그대로다.
- 지금 계획이 계산 불가 상태(`no_home`·`no_place`·`route_failed`)이거나 이력이 없는 옛
  일정이면 시각만 바꿔도 경로부터 다시 계산한다.
- 장소·경로를 바꿨는데 카카오 조회가 실패하면 `alarm_plan.status` 가 `route_failed`,
  `alarm_at` 이 `null` 이다. 입력이 바뀌었으니 예전 계획을 남기지 않는다. 앱은 "경로를
  가져오지 못했어요" 와 다시 계산(`POST /api/events/{id}/recompute`)을 안내한다.
- 이미 알람이 울렸거나 출발한 일정도 고치면 다시 계산한다. 그 아침의 학습 기준(결정 시점
  계획)은 울림·출발 전 계획이라 바뀌지 않는다.

## 4. 알람 켬/끔

- 같은 날 안에서 시각만 옮기면 수동 켬/끔은 그대로다. 자동(`null`)인 일정은 하루 첫 알람
  규칙으로 다시 정해진다.
- **다른 날로 옮기면 옮겨 간 날 다른 일정들의 수동 켬/끔이 자동으로 돌아간다.** 그날에 일정이
  새로 들어온 것이라 일정 생성과 같은 규칙(BE-25)이다. 옮긴 일정 자신의 선택은 그대로다.
  떠나온 날의 일정은 건드리지 않는다. 날짜는 사용자 시간대(프로필 `timezone`) 기준이다.
- 그래서 시각·날짜를 바꾸면 **다른 일정의 `alarm_on` 도 바뀔 수 있다.**

## 5. 반복 일정의 회차

- PATCH 는 **그 회차만** 바꾼다. 반복 규칙(요일·시각·장소)은 그대로고, 다음 주 회차는 원래
  규칙대로 생긴다. 옮긴 회차는 원래 날짜 자리를 지켜서 같은 날짜에 회차가 또 생기지 않는다.
- 회차를 7일 창(오늘 00:00 ~ 7일 뒤 00:00) 밖으로 옮기면 `alarm_plan` 이 `null` 이 되고
  목록에서 빠진다. 창 안에 들어오는 날 계산돼 다시 보인다. 창 안으로 다시 옮기면 바로
  계산한다. 계획이 없으니 경로부터 조회한다.
- 회차 하나 지우기는 `DELETE /api/events/{id}` 다(그 날짜만 빠진다). 반복 전체 수정·종료는
  아직 없다.

## 6. 앱이 할 일

1. **`EventUpdateRequest` 에 `origin_lat`·`origin_lng`·`origin_label` 을 더한다.** 지금은 없다
   (`EventsApi.kt`).
2. **지우기는 `null` 을 실제로 보내야 한다.** Retrofit 의 기본 Gson 은 `null` 필드를 빼고
   보낸다. 그래서 지금 `EventUpdateRequest` 로는 `tau_override`·`alarm_enabled`·
   `origin_lat/lng`·`place` 를 지울 수 없다(빠진 필드는 "그대로"). 지우는 요청은
   `JsonObject` 나 `Map<String, Any?>` 로 만들거나 `serializeNulls()` Gson 을 따로 쓴다.
   `route_key` 를 지울 때는 `null` 이 아니라 `""` 다.
3. **장소나 출발지를 바꾸면 경로를 다시 고르게 한다.** 새 출발지·목적지로
   `GET /api/routes/candidates` 를 부르고 고른 `route_key` 를 같은 PATCH 에 함께 보낸다.
   고르지 않으면 `route_key: ""` 를 보낸다. 예전 대중교통 key 를 그대로 두면 서버가 새 구간에서
   같은 노선을 찾고, 없으면 대중교통 최단으로 바꾼다(`route_choice_honored: false`).
4. 바뀐 필드만 보내도 되고 폼 전체를 보내도 된다. 결과는 같다(3절). 시각만 고친 저장이
   카카오를 쓰지 않으려면 장소·출발지·경로 값을 바꾸지 않고 보내면 된다.
5. **저장 뒤 목록을 다시 불러오고 기기 알람을 다시 건다**(`loadHomeOnline` 뒤
   `AlarmScheduler.sync`). 4절 때문에 고친 일정 말고 다른 일정의 알람도 바뀔 수 있다.
   켬/끔 저장(`setAlarmEnabled`)이 저장 뒤 `loadHomeOnline` 을 부르는 것과 같은 이유다.
6. 다른 날로 옮길 때 안내 문구를 둘지 정한다. 예: "옮긴 날의 알람 켬/끔을 자동으로 다시
   정했어요".
7. 반복 회차 수정 화면에는 "이 회차만 바뀌어요" 를 보여 주고 반복 요일은 고칠 수 없게 둔다.

## 7. 예시

시각만 바꾸기(경로 조회 없음):

```http
PATCH /api/events/42
{"start_at": "2026-11-02T10:00:00+09:00"}
```

장소와 경로 함께 바꾸기(경로 조회 1회):

```http
PATCH /api/events/42
{"place": {"name": "중앙도서관", "lat": 37.4664, "lng": 126.9520},
 "route_key": "transit:5513"}
```

집에서 출발로 되돌리고 서버가 경로를 고르게 하기:

```http
PATCH /api/events/42
{"origin_lat": null, "origin_lng": null, "origin_label": "", "route_key": ""}
```

지각 위험을 기본값으로 되돌리기(경로 조회 없음):

```http
PATCH /api/events/42
{"tau_override": null}
```
