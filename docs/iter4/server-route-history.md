# 최근 이동 경로 · 가장 자주 이용한 경로 (iter4, 서버)

<!-- Partly AI-generated (Claude) -->

"최근 이동 경로" · "가장 자주 이용한 경로" 버튼(팀원 요청, 화면 디자인 전)에 쓸 API 다. 화면이 정해지기 전에
서버를 먼저 만들어 두었다. 외부 API(카카오)를 부르지 않고 DB 만 읽는다.

구현: `backend/apps/events/route_history.py` · `RouteHistoryView`(`apps/events/views.py`) ·
테스트 `apps/events/tests/test_route_history.py`. 마이그레이션 없음.

## 1. 요청

```
GET /api/routes/history?sort=recent&limit=5
Authorization: Bearer <access>
```

| 파라미터 | 값 | 기본 |
| --- | --- | --- |
| `sort` | `recent` = 마지막으로 다녀온 순, `frequent` = 다녀온 횟수 순(같으면 더 최근) | `recent` |
| `limit` | 1~20 | 5 |

모르는 `sort`, 범위 밖·숫자 아닌 `limit` 은 400 `invalid`(`details.sort` / `details.limit`). 로그인 안 하면 401.

## 2. 응답

```json
{
  "sort": "recent",
  "days": 90,
  "results": [
    {
      "origin": {"kind": "home", "saved_place_id": null, "label": "집", "lat": 37.48, "lng": 126.93},
      "destination": {"kind": "place", "name": "관악캠퍼스 302동", "address": "관악로 1",
                      "lat": 37.45, "lng": 126.95, "kakao_place_id": "111"},
      "route_key": "transit:2호선>5513",
      "travel_mode": "transit",
      "route_summary": "2호선 → 5513",
      "travel_minutes": 42,
      "rerouted": false,
      "trip_count": 12,
      "last_trip_at": "2026-11-14T09:00:00+09:00",
      "last_event_id": 381
    }
  ]
}
```

| 필드 | 뜻 |
| --- | --- |
| `origin.kind` | `home`(출발지를 고르지 않음 = 지금 프로필의 집) · `saved_place`(저장한 집, `saved_place_id`) · `point`(그 밖의 좌표) |
| `destination.kind` | `home`(지금 프로필의 집) · `place` |
| `route_key` | 그때 **실제로 탄** 경로. 일정 만들기(`POST /api/events` 의 `route_key`)에 그대로 쓸 수 있다 |
| `travel_mode` · `route_summary` · `travel_minutes` | 마지막으로 다녀온 일정의 계획 값(표시용). 없으면 null |
| `rerouted` | 마지막 이동에서 이동 중에 경로를 바꿨다(`route_key` 는 바꾼 경로, 표시 값은 바꾸기 전 계획 값) |
| `trip_count` | 지난 `days`(90)일 동안 이 경로로 다녀온 횟수 |
| `last_trip_at` · `last_event_id` | 마지막으로 다녀온 일정의 시작 시각과 id |

## 3. 규칙

- **다녀온 이동 하나** = 시작 시각이 지났고 "안 감"(`skipped_at`)이 아닌 일정 하나. 반복 일정은 회차마다 센다(매일
  가는 수업은 실제로 매일 다녀온다). 도착 GPS 기록은 보지 않는다 — 실내라 도착을 못 잡은 날도 다녀온 날이다.
- **탄 경로**는 이동 중에 바꿨으면 마지막으로 바꾼 경로(`TripReroute`), 아니면 계획이 쓴 경로, 아니면 고른 경로다
  (이동 중 기준 경로와 같은 규칙). 경로를 알 수 없는 일정(계산 실패 등)은 세지 않는다.
- **같은 경로**는 출발지 + 도착지 + `route_key` 가 같은 것이다.
  - 출발지: 집 · 저장한 집(id) · 그 밖의 좌표(소수 셋째 자리 격자, 약 100m).
  - 도착지: 집 · 카카오 장소(`kakao_place_id`) · 그 밖의 좌표(같은 격자).
- 기간은 지난 90일, 최근 일정 1000개까지 본다. 쿼리는 몇 번으로 고정이다(일정 수에 따라 늘지 않는다).
- 집은 지금 프로필의 집 좌표·이름으로 보여 준다(알람 계산과 같다).

## 4. 앱이 할 일 (화면 디자인 뒤)

- 버튼을 누르면 `GET /api/routes/history?sort=recent`(또는 `frequent`)로 목록을 받아 "출발지 → 도착지 · 경로 요약 ·
  N번" 으로 보인다. 비어 있으면 "아직 다녀온 경로가 없어요" 류 빈 상태.
- 하나를 고르면 알람 추가 시트에 출발지 · 도착지 · 경로를 채운다.
  - 출발지: `home` 이면 지금처럼 출발지 없음, `saved_place` 면 그 저장한 집, `point` 면 `origin_*` 좌표·이름.
  - 도착지: `home` 이면 `destination_is_home`, `place` 면 장소(이름·주소·좌표·`kakao_place_id`).
  - 경로: `route_key`. 노선이 바뀌어 그 경로가 이번 후보에 없을 수 있으므로, 경로 후보(`GET /api/routes/candidates`)
    를 한 번 불러 같은 key 가 있으면 그것, 없으면 같은 수단의 가장 빠른 후보를 고르게 한다(후보 응답의
    `preferred_key` 규칙과 같다).
- 알람 추가 시트의 "최근 설정한 위치" 칩(H2-8)도 이 응답의 도착지로 채울 수 있다(`sort=recent`, 도착지 중복 제거).

## 5. 정할 것 (팀)

- 버튼 위치와 보여 줄 개수(기본 5).
- 기간 90일이 적당한지(반복 수업 위주라면 30일도 충분).
- 출발지가 달라도 도착지·경로가 같으면 하나로 볼지(지금은 다르게 본다).
