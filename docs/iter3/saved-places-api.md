# 저장한 집 API (집 여러 개)

[backend-requests.md](backend-requests.md) R3-2 · R3-3 의 서버 구현이다. 앱이 제안한 계약을 따랐고, 다른 곳은 아래 "제안과 다른 점" 에 적었다.
구현: `backend/apps/accounts/saved_places.py`, 모델 `accounts.SavedPlace`(마이그레이션 `accounts 0007`), `Event.origin_place`(`events 0012`) · 테스트: `backend/apps/accounts/tests/test_saved_places.py`

## 규칙

- 기본 집은 프로필 `home_*` 그대로다(구버전 앱과 `destination_is_home` 이 쓴다). 목록 응답의 `default` 로만 보이고 `SavedPlace` 로 복사하지 않는다.
- 저장한 집은 사용자당 5개까지. 50m 안은 같은 집이다(다른 저장한 집과도, 기본 집과도).
- 행 모양: `{id, client_uuid, label, name, address, lat, lng, created_at}`. `label` 40자 이하. 국내 좌표만.
- 경로 끝의 `/` 는 있어도 없어도 된다(이 저장소의 다른 경로는 `/` 가 없다).
- 모두 로그인 필요. 남의 행은 404.

## 엔드포인트

| 요청 | 성공 | 오류 |
| --- | --- | --- |
| `GET /api/saved-places/` | 200 `{default: {...}\|null, results: [...]}`. `default` 는 `id: null` | |
| `POST /api/saved-places/` `{label, lat, lng, name?, address?, client_uuid?}` | 201 새 행. 같은 `client_uuid` 나 50m 안의 행이 있으면 그 행의 `label` 만 바꿔 **200** | 409 `same_as_default`(기본 집 50m 안), 409 `saved_place_limit`(5개), 400 `invalid` |
| `PATCH /api/saved-places/{id}/` | 200 행. `label`·`name`·`address`·`lat`·`lng` (`client_uuid` 는 바꾸지 않는다) | 409 `same_as_default`, 409 `saved_place_duplicate`(다른 저장한 집 50m 안) |
| `DELETE /api/saved-places/{id}/` | 204. 그 집에서 출발하는 일정의 좌표는 남는다(`origin_place_id` 만 null) | |
| `POST /api/saved-places/{id}/make-default/` | 200, 목록 조회와 같은 모양 | 404 |
| `POST /api/saved-places/bulk/` `{items: [{client_uuid, label, name, address, lat, lng}]}` | 200 `{default, results, skipped: [{client_uuid, reason}]}` | 400 `invalid`(항목 20개 초과, `client_uuid` 빔) |

### 기본 집 바꾸기 (`make-default`)

한 트랜잭션에서 프로필 `home_*` 와 이 행을 **맞바꾼다**. 행 id 는 그대로이고 내용이 예전 기본 집이 된다(`client_uuid` 는 비운다 — 앱의 그 `SavedHome` 은 이제 기본 집이다). 기본 집이 없었으면 행을 지운다.

- 이 집에서 출발하던 **앞으로의** 일정은 출발지를 비운다(이제 기본 집 출발, 좌표가 같다).
- 지난 일정은 그대로 둔다. `origin_place_id` 가 남아 준비 학습에서 집 출발로 계속 센다.
- 프로필 집 수정과 같이 사용자의 알람을 다시 계산한다.

### 한 번 올리기 (`bulk`)

항목마다 `POST` 와 같은 규칙이다. 같은 `client_uuid` 를 다시 보내면 늘지 않고 `label` 만 맞춘다. 저장하지 못한 항목(`same_as_default`, `limit`)은 `skipped` 에 담고 나머지는 저장한다 — 요청 전체를 실패시키지 않는다. 앱은 200 이면 기기 목록을 비워도 된다(`skipped` 는 이미 기본 집이거나 한도 밖이다).

## 일정의 `origin_place_id`

- 생성·수정 요청에 `origin_place_id`(내 저장한 집 id 또는 null)를 받는다. 주면 서버가 `origin_lat`·`origin_lng`·`origin_label` 을 그 집으로 채운다(같이 보낸 좌표보다 우선). 남의 id·없는 id 는 400 `invalid`(`details.origin_place_id`).
- `origin_place_id` 없이 `origin_lat`·`origin_lng` 만 바꾸면 저장한 집을 뗀다(다른 곳 출발).
- 응답에 `origin_place_id` 가 늘었다. `origin_*` 는 지금처럼 채워서 구버전 앱이 그대로 읽는다.
- 반복 일정은 첫 회차의 `origin_place_id` 를 다음 회차에도 쓴다(그 집을 지웠으면 좌표만).
- 저장한 집의 좌표를 나중에 고쳐도 이미 만든 일정의 좌표는 바뀌지 않는다.

## 집 출발 (R3-3)

`Event.departs_from_home` = 출발지를 고르지 않았거나 **저장한 집에서 출발**(`origin_place_id` 가 있음).

- `include_prep` 를 보내지 않아도 저장한 집 출발이면 준비 시간이 들어간다(`prep_applies`).
- GPS 준비 시간 학습(`planning.prep_observations`)에 들어간다. 본가에서 자고 나선 아침도 표본이다.
- `include_prep: false` 는 그대로 우선한다.

## 제안과 다른 점

- **좌표만으로 "기본 집 50m 안이면 집 출발" 로 보지 않는다.** 제안의 괄호 안 대안이었는데, 지금 서버는 "집 앞 카페를 출발지로 고르면 집이 아니다" 를 일부러 지키고 있고 테스트가 그것을 고정한다(`test_event_edit.py` `test_origin_at_the_home_coordinates_drops_prep_without_a_route_lookup`). 저장한 집인지는 사용자가 고른 `origin_place_id` 로만 안다. 앱은 저장한 집을 고르면 `origin_place_id` 를 보내면 된다.
- 기본 집 50m 안의 저장은 409 `same_as_default`, 다른 저장한 집 쪽으로 옮기는 수정은 409 `saved_place_duplicate` 다(제안에 없던 경우).
- `bulk` 응답에 `skipped` 를 더했다.
