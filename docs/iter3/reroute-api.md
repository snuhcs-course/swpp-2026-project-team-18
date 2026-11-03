# 이동 중 "늦으니 이 경로로 바꾸자" API 계약

이동 중에는 앱이 1분마다 `POST /api/routes/live`를 부르고 지도에 현재 위치부터의 경로를 그린다.
지금까지는 더 빠른 대안이 있어도 지도에 "N분 빠름" 한 줄만 보였다. 이제 서버가 **지금 경로로 가면
일정 시작에 늦는지** 판정하고, 늦으면 더 일찍 닿는 대안을 `switch_suggestion`으로 준다. 앱은 알림을
한 번 띄우고, 사용자가 [바꾸기]를 누르면 `POST /api/events/{id}/reroute`로 알린다.

- 늦지 않으면 알리지 않는다. 안 늦는데 몇 분 빠른 대안은 지도 표시로 충분하다(사용자 10-31).
- 자동으로 바꾸지 않는다. 바꾸는 것은 사용자가 누를 때뿐이다.
- 이번 이동만 바꾼다. 일정의 고른 경로(`route_key`)와 알람 계획은 그대로다.
- 카카오 호출은 늘지 않는다. 실시간 버스·지하철 도착은 늦을 수도 있을 때만 부른다.

구현: `planning/live_switch.py`, `events/views.py` `RouteLiveView`·`EventRerouteView`,
`observations.TripReroute`(마이그레이션 `observations 0005`·`0006`) · 테스트:
`backend/apps/events/tests/test_live_switch.py`

## 1. `POST /api/routes/live` 응답에 더한 것

요청은 예전과 같다(`{"event_id", "lat", "lng"}`). `route`·`degraded`도 그대로다. 아래 두 키가 늘
들어간다(판정을 못 하면 null).

```json
{
  "route": { "route_key": "transit:2호선", "...": "예전과 같음" },
  "degraded": false,
  "arrival": {
    "route_key": "transit:5516",
    "start_at": "2026-11-02T09:00:00+09:00",
    "arrive_at": "2026-11-02T09:10:00+09:00",
    "late_minutes": 10,
    "realtime": true
  },
  "switch_suggestion": {
    "reason": "late_now",
    "route_key": "transit:2호선",
    "label": "2호선",
    "arrive_at": "2026-11-02T08:58:00+09:00",
    "late_minutes": 0,
    "saves_minutes": 12,
    "realtime": false
  }
}
```

| 칸 | 뜻 |
| --- | --- |
| `arrival` | 지금 기준 경로로 가면. 기준 경로는 마지막으로 바꾼 경로, 없으면 계획의 경로다 |
| `arrival.late_minutes` | 일정 시작보다 늦는 분(반올림). 안 늦으면 0 |
| `arrival.realtime` | 첫 승차 대기를 실시간 다음 차로 바꿔 계산했는지 |
| `switch_suggestion` | 바꾸자고 할 때만 채운다. 아니면 null |
| `switch_suggestion.reason` | 지금은 `late_now` 하나다 |
| `switch_suggestion.route_key` | [바꾸기]를 누르면 `reroute`에 보낼 값. `route.route_key`와 같다 |
| `switch_suggestion.late_minutes` | 대안으로 가도 늦는 분. 0 이면 대안으로는 안 늦는다 |
| `switch_suggestion.saves_minutes` | 기준 경로보다 일찍 닿는 분 |

`route`가 null이면(경로 없음, 조회 실패) 두 키도 null이다.

## 2. 언제 `switch_suggestion`이 오나

1. 기준 경로의 도착 예상이 일정 시작보다 1분 이상 늦다(반올림).
2. 같은 카카오 응답 안의 대안이 더 일찍 닿는다. 대안은 대중교통만 있다(도보·자전거·자동차를
   고른 경우에는 대안이 없어 제안도 없다).
3. 대안으로 안 늦으면 몇 분 차이든 제안한다. 대안도 늦으면 2분 이상 덜 늦을 때만 제안한다
   (`MIN_SAVING_WHEN_STILL_LATE`).

**도착 예상은 알람 계산과 같은 식이다.** 카카오 원값에 걷는 속도·시간대 계수(지금 시각의 구간)·경로
보정을 곱한 평균이다([travel-factors.md](travel-factors.md)). 카카오 원값끼리 비교하지 않는다.

**실시간 도착.** 카카오 대중교통 시간에는 첫 승차 전 대기가 평균값으로 들어 있다. 대안이 있고 기준
경로의 여유가 5분보다 적으면 두 경로의 첫 승차 실시간 도착을 불러 대기를 실제 다음 차로 바꾼다.
정류장까지 걷는 동안 떠나는 차는 못 탄다고 본다. 같은 구간의 대체 버스도 탈 수 있는 차로 본다.
실시간을 못 받으면 카카오 대기 그대로다. 여유가 넉넉하면 부르지 않는다(버스 API 쿼터).

## 3. `POST /api/events/{id}/reroute` (바꾸기)

로그인 필요. 본문 `{"route_key": "transit:2호선", "reason": "late_now"}`. `reason`은 알림을 보고 눌렀으면
`switch_suggestion.reason` 값, 알림 없이 바꿨으면 생략한다.

| 상태 | 뜻 |
| --- | --- |
| 201 | 기록했다. 본문 `{"event_id", "from_route_key", "to_route_key", "reason", "rerouted_at"}` |
| 400 `invalid_route_key` | `walk`·`bicycle`·`car`이거나 `transit:`로 시작해야 한다(120자 이하) |
| 400 `invalid_reason` | `reason`은 비우거나 `late_now` |
| 404 | 없는 일정이거나 남의 일정 |
| 409 `same_route` | 지금 기준 경로와 같다 |
| 409 `outside_trip` | 일정 시작 6시간 전부터 6시간 뒤까지만 받는다 |

- 여러 번 바꿀 수 있다. 행이 하나씩 쌓이고, `from_route_key`는 직전 기준 경로다.
- 바꾼 뒤 `routes/live`는 바꾼 경로를 기준으로 비교한다. 바꾼 경로가 최단이면 `switch_suggestion`은 null이다.
- 되돌리기 API는 없다. 원래 경로로 다시 바꾸려면 그 key로 한 번 더 보낸다.

## 4. 학습

| 언제 바꿨나 | 이동 시간 학습 |
| --- | --- |
| **GPS 출발 뒤**(이동 중) | 뺀다. 버린 이유 "이동 중 경로 변경". 결정 시점 계획의 카카오 원값은 원래 경로의 것이라 실측과 맞지 않는다 |
| **GPS 출발 전**(알람을 끌 때·출발 10분 전, R3-11) | **새 경로 기준으로 학습한다**(사용자 11-03). 예측값은 바꿀 때 남긴 새 경로의 카카오 원값(`TripReroute.route_minutes_raw`), 시간대 구간은 계획한 출발 시각 |
| 출발 전인데 원값이 없다 | 뺀다. 버린 이유 "출발 전 경로 변경, 원값 없음" |

- 출발 전·후는 바꾼 시각(`rerouted_at`)과 그 일정의 GPS 출발 기록(`TripObservation` depart)을 밤 학습 때 비교해 정한다. 출발 기록이 늦게 올라와도 맞게 갈린다. 한 번이라도 출발 뒤에 바꿨으면 뺀다.
- 새 경로의 원값은 직전 `routes/live` 응답이 서버에 30분 동안 남긴다(`live_switch.remember_routes`). 그 응답의 기준 경로나 대안으로 바꾸면 원값이 남는다. 카카오를 다시 부르지 않는다. `routes/live` 를 부르지 않고 바꾸면 원값이 없다.
- 준비 시간, 안전 버퍼(지각 이력), 리포트에는 언제 바꿨든 그대로 쓴다. 도착 시각 자체는 사실이다.

## 4-1. 출발 전에 쓰기 (R3-11)

- `routes/live` 는 단계나 시각을 검사하지 않는다. 출발 전에 집 좌표로 부르면 `arrival` 은 "지금 나가면" 의 도착이다.
- `reroute` 는 일정 시작 ±6시간 안이면 받는다. 출발 전에도 같다.
- `routes/live` 본문에 선택 항목 `"context": "pre_departure"` 를 보낼 수 있다. 응답은 같고, 서버는 로그에 남기고 스로틀을 따로 센다(`route_live_pre_departure` 30/시간, 이동 중 1분 갱신은 `route_live` 80/시간). 모르는 값이나 생략은 이동 중 갱신으로 센다.
- 아직 없는 것: 밤 갱신(`RouteRefreshWorker` 의 `recompute`)이 기준 경로로 늦는 것을 보면 계획(`alarm_plan.depart_check`)에 `switch_suggestion` 을 넣는 것(R3-11 의 4번, 나중).

## 5. 앱 할 일 (프론트)

1. `switch_suggestion`이 오면 알림 한 번: "지금 경로로는 N분 늦어요. ○○로 가면 안 늦어요"
   (`late_minutes`가 0이 아니면 "M분 덜 늦어요") + [바꾸기]/[무시].
2. 같은 `route_key`로는 다시 띄우지 않는다. 다른 대안이 오거나, 무시한 뒤 지각이 더 커지면 다시 띄운다.
3. [바꾸기] → `reroute` 호출 → 지도·체크포인트를 대안 구간으로. 도착 판정(목적지 100m)은 그대로다.
4. 버스·지하철에 타고 있을 때(속도로 판단)와 도착 몇 분 전부터는 띄우지 않는다. 서버는 탑승 중인지 모른다.
5. 응답의 두 새 키는 모르는 앱이 무시해도 된다(지금 앱은 Gson이라 문제없다).
