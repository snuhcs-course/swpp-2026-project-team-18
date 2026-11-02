# 백엔드 요청 — Iteration 3 프론트에서 나온 것
`front-iter3` 은 서버 코드를 고치지 않는다. 앱이 보여 줄 수 없거나 서버에 없어서 기기에만 둔 것, 운영에서 할 일을 여기 모았다. 필드 이름은 제안이고, 모델에 필드를 더할 때는 이 코드베이스 규칙대로 `db_default` 를 함께 준다. 번호는 iter2 의 [R-1 ~ R-14](../iter2/backend-requests.md) 와 겹치지 않게 `R3-n` 으로 붙였다. 앱 쪽 내용은 [front-iter3.md](front-iter3.md), Figma 쪽은 [figma-changes.md](figma-changes.md) 에 있다.

| ID | 요청 | 우선 | 앱 쪽 상태 |
| --- | --- | --- | --- |
| R3-1 | `docs/iter3/reroute-api.md` · `travel-factors.md` 를 main 에 올리기 (코드가 인용하는데 파일이 없음) | 낮음 | 서버 코드와 테스트에서 계약을 읽어 구현함 |
| R3-2 | 집 여러 개: `SavedPlace`, 일정의 `origin_place_id` | 보통 | 기기에만 저장(최대 5개), 서버에는 기본 집 하나 |
| R3-3 | 저장한 집에서 출발한 일정을 집 출발로 학습 | R3-2 와 함께 | 저장한 집을 고르면 `include_prep: true` 를 보냄 |
| R3-4 | 소셜 로그인: 이 PC 의 디버그 키를 카카오·구글 콘솔에 등록 | 높음 (배포 전 확인) | 코드 완료, 실기기·실계정 확인 못 함 |
| R3-5 | `JWT_ROTATE_REFRESH_TOKENS=1` 은 이 브랜치 앱이 퍼진 뒤에 켜기 | 높음 (켜는 때) | 회전한 refresh 를 저장함 |
| R3-6 | 주간 리포트 ⑧⑨ 의 수면·미루기·요일별 조정 패널에 쓸 필드 | 보통 | 패널은 그리지 않음 (Figma 에는 없음) |
| R3-7 | 리포트 요약 LLM 호출의 전체 시간 제한(약 15초) | 높음 | 영향 없음 (서버 템플릿으로 내려오면 그대로 표시) |
| R3-8 | 리포트 요약 검사의 한글 수 처리 · 추천 모델 · Render 환경 변수 | 보통 | 영향 없음 |

## R3-1. 계약 문서 두 개가 없다 (2026-11-03)
- main 의 코드가 아래 위치에서 `docs/iter3/reroute-api.md` 를 인용하는데 파일이 없다: `backend/apps/events/views.py` 220행(`EventRerouteView` 독스트링), 1033행(`RouteLiveView` 독스트링). `docs/iter3/travel-factors.md` 는 `docs/learning-logic.md` 17행이 인용한다. `todo.md` 와 앱 주석(`EventsApi.kt`, `EventRepository.kt`, `TripTrackingService.kt`, `RerouteCard.kt`, `HomeViewModel.kt`, `RerouteRepositoryTest.kt`)도 같은 파일 이름을 적었다.
- 앱은 문서 대신 `backend/apps/events/views.py` 의 `RouteLiveView`·`EventRerouteView`, `backend/apps/planning/live_switch.py`, `backend/apps/events/tests/test_live_switch.py` 를 읽고 맞췄다. 앱이 믿는 계약은 아래와 같다. 서버 쪽 문서를 쓸 때 어긋나는 곳이 있으면 알려 주면 앱을 고친다.
  - `POST /api/routes/live` 응답에 `arrival`(기준 경로로 지금 가면 닿는 시각, `late_minutes`)과 `switch_suggestion`(`route_key`, `label`, `late_minutes`, `saves_minutes`, `reason`)이 온다. 안 늦으면 둘 다 null. 구버전 서버는 필드가 없다.
  - `POST /api/events/{id}/reroute` `{route_key, reason?}` → 201. 400 `invalid_route_key`·`invalid_reason`, 409 `outside_trip`(시작 ±6시간 밖)·`same_route`. 이번 이동만 바뀌고 `Event.route_key` 는 그대로다. `reason` 은 `late_now` 이거나 비운다.
- 요청: 두 문서를 main 에 올리거나, 올리지 않을 거면 인용을 지운다.

## R3-2. 집 여러 개 — `SavedPlace` (제안)
- 지금: 서버는 프로필 `home_*` 하나만 안다. 앱은 다른 집(본가·자취방)을 기기의 `SavedHomesStore` 에 계정별로 두고(최대 5개, 50m 안은 같은 집), 출발지 검색 맨 위에 보여 준다. 기기를 바꾸거나 앱을 지우면 사라진다.
- 모델 `SavedPlace`(사용자별): `id`, `client_uuid`(앱의 `SavedHome.id`, 사용자당 유일), `label`(40자 이하, 앱은 12자), `name`, `address`, `lat`, `lng`, `created_at`. 사용자당 5개 이하. 같은 자리(50m 안)가 있으면 새로 만들지 않고 그 행의 `label` 을 바꿔 200 으로 돌려준다.
- API(제안):
  - `GET /api/saved-places/` → `{default: {...}|null, results: [...]}`. `default` 는 프로필 `home_*` 를 같은 모양으로 보인 것이다(읽기 전용).
  - `POST /api/saved-places/`, `PATCH /api/saved-places/{id}/`, `DELETE /api/saved-places/{id}/`.
  - `POST /api/saved-places/{id}/make-default/`: 프로필 `home_*` 와 이 행을 한 트랜잭션에서 맞바꾼다. 앱은 지금 이 동작을 "기본으로" 로 하고 있다(프로필에 저장하고 예전 기본 집을 목록에 남김).
  - `POST /api/saved-places/bulk/` `{items:[{client_uuid, label, name, address, lat, lng}]}`: 위 5개 한도와 50m 중복 규칙을 지키는 일괄 올리기. 같은 `client_uuid` 를 다시 보내도 늘지 않는다.
- 일정: `Event.origin_place_id`(nullable FK, `SET_NULL`)를 생성·수정 요청과 응답에 둔다. 응답의 `origin_lat`·`origin_lng`·`origin_label` 은 지금처럼 채워서 구버전 앱이 그대로 읽는다. 집을 지워도 일정의 출발지 좌표는 남는다.
- 프로필: `home_*` 는 기본 집으로 그대로 둔다(구버전 앱과 `destination_is_home` 이 쓴다). 기존 프로필 집은 `SavedPlace` 로 복사하지 않는다. 기본 집은 목록의 `default` 로만 보인다.
- 앱의 한 번 올리기: 서버가 위 API 를 내면, 앱은 로그인 뒤 처음 한 번 기기 목록을 `bulk` 로 올리고 성공하면 기기 목록을 비운다(계정 단위, 5개 이하, 50m 중복 제거). 실패하면 기기 목록을 그대로 쓴다. 이 부분은 서버가 나온 뒤에 앱이 만든다.

## R3-3. 저장한 집에서 출발한 일정의 학습
- 지금 GPS 준비 시간 학습은 출발지를 따로 고른 일정을 건너뛴다: `backend/apps/planning/prep_observations.py` 186행 `event__origin_lat__isnull=True`. 알람 계산도 출발지를 고르면 `prep_source = not_from_home`(준비 시간 0)으로 본다(`planning/estimators.py` 584행 주석, 644행). 앱은 이 일에 `include_prep`(켜면 `true`)로 준비 시간을 넣는다.
- 요청: `origin_place_id` 가 저장한 집을 가리키면(또는 출발지가 기본 집 50m 안이면) "집 출발" 로 센다. 그러면 준비 시간 학습에 들어가고, `include_prep` 를 보내지 않아도 준비 시간이 들어간다. 본가에서 자고 나선 아침도 학습 표본이 된다.

## R3-4. 소셜 로그인 운영 — 키 등록
- 콘솔에는 지금 김수연의 디버그 키만 등록돼 있다. 이 PC 의 디버그 키로 만든 앱은 카카오·구글 로그인이 거절된다. 값은 공개 식별자라 문서에 적어도 된다.
  - 카카오 키 해시: `mYUAJAhiEuimsuyh2JBputJQNtk=`
  - 구글 Android OAuth SHA-1: `99:85:00:24:08:62:12:E8:A6:B2:EC:A1:D8:90:69:BA:D2:50:36:D9`
  - 패키지: `com.swpp.wakeup`(디버그), `com.swpp.wakeup.qa`(e2e 빌드). 두 패키지 모두 등록한다.
- Render 에는 `KAKAO_NATIVE_APP_KEY`·`GOOGLE_WEB_CLIENT_ID` 가 이미 있다(11-01 `/api/health` 의 `social` 이 true).
- 앱의 키(카카오 네이티브 앱 키, 구글 웹 클라이언트 ID)는 `APP/gradle.properties` 에 있고 `local.properties` 로 덮어쓸 수 있다. 릴리스용 서명 키를 쓰게 되면 그 키도 같은 두 곳에 등록해야 한다.
- 확인은 실기기에서만 된다: 카카오 로그인, 구글 로그인, 약관 화면, 같은 이메일 계정 안내(409), 설정의 연결·끊기.

## R3-5. refresh 회전 켜기
- 앱은 이제 갱신 응답의 새 `refresh` 를 저장한다(`TokenRefreshAuthenticator`). 옛 APK 는 `access` 만 저장해서, 회전을 켜면 첫 갱신 뒤에 로그아웃된다.
- 요청: `JWT_ROTATE_REFRESH_TOKENS=1` 은 팀원이 이 브랜치의 앱으로 바꾼 뒤에 켠다. 켜기 전에 팀에 알린다. README 432행에도 같은 말이 있다.

## R3-6. 주간 리포트 ⑧⑨ 의 수면·미루기·요일별 조정 패널
- Figma ⑧⑨ 에는 수면, 알람 미루기, 요일별 자동 조정 패널이 있다. 앱은 `AlarmLog`(`fired`·`snoozed`·`dismissed`)를 iter2 부터 올리지만 조회 API 가 없다(`backend/apps/observations/models.py` 186행 독스트링: "조회 API도 두지 않는다"). 그래서 이 패널은 그리지 않았다([figma-changes.md](figma-changes.md) "그리지 않은 것").
- 제안: `reports` 주간 응답에 `wake` 블록을 둔다. 모두 nullable, 기록이 없으면 `null`.
  - `alarms_fired`: 그 주 울린 알람 수 (`fired`)
  - `snooze_count`: 미룬 횟수 (`snoozed`), `snoozed_alarms`: 한 번 이상 미룬 알람 수
  - `wake_seconds_median`: 첫 `fired` 에서 `dismissed` 까지 걸린 시간의 중앙값
  - `by_weekday[]`: `{weekday, fired, snooze_count, wake_seconds_median}`
- 수면 시간은 앱이 기록하는 값이 없다. 만들 거면 수면 데이터를 어디서 받을지부터 정해야 한다. 정하기 전에는 위 `wake_seconds_median`("깨는 데 걸린 시간")으로 그 자리를 채우자고 제안한다.
- 요일별 자동 조정은 학습이 요일별 값을 따로 갖고 있을 때만 의미가 있다. 제안 필드는 `weekday_adjustments[]` = `{weekday, prep_delta_min, travel_delta_min, sample_count}` 이고, 학습이 시간대 구간만 쓴다면 이 필드는 비워 두고 패널을 빼는 쪽이 맞다.
- 앱은 필드가 오면 패널을 그리면 되고, 그 전까지는 지금 화면 그대로다.

## R3-7. 리포트 요약 LLM: 전체 시간 제한
- `backend/apps/reports/llm.py` 의 `TIMEOUT_SECONDS = 8`(56행)은 `urlopen(..., timeout=...)`(147행)에 들어가서 연결·TLS·읽기 단계마다 적용된다. 응답을 조금씩 계속 보내는 서버에는 전체 시간이 제한되지 않는다. 독스트링(36~38행)은 "gunicorn `--timeout 60` 안에 든다" 고 하지만, OpenRouter 로 부른 실험에서 48초와 65초가 걸린 호출이 끝까지 통과했다. 65초 호출은 gunicorn 60초 제한(`backend/Dockerfile` 53행)을 넘어 리포트 요청 자체가 끊길 수 있다.
- 요청: 호출 전체에 약 15초 마감을 둔다(마감 시각을 정해 두고 단계마다 남은 시간으로 timeout 을 준다). 넘으면 `TIMEOUT` 으로 보고 템플릿 문장으로 내려준다.
- LLM 크레딧은 쓰지 않았다. 위 시간은 실험에서 관찰한 값이다.

## R3-8. 리포트 요약 검사 · 추천 모델 · 환경 변수
- 한글 수 검사: `backend/apps/reports/summary.py` 181행 정규식(`(?<![가-힣])(?:한|두|세|…)\s*(?:번|건|회|차례|분|시간)`)이 "세분화" 의 "세분" 을 "세 분" 으로 읽어 문장을 버린다. "한 번" 같은 한글 수도 숫자 표기("1번")는 통과하는데 한글 표기는 떨어진다. 검사 전에 한글 수를 숫자로 바꾸거나, 낱말 일부(뒤에 한글이 이어지는 경우)는 건너뛰어 달라. 프롬프트(170행)는 이미 아라비아 숫자로 쓰라고 한다.
- 추천 모델: `google/gemini-4-argon`. 42건이 서버 검사를 모두 통과했고, 중앙값 4.6초, 호출당 약 $0.0027 이다. 차선은 `openai/gpt-6-luna`.
- Render 환경 변수: `OPENAI_API_KEY`, `OPENAI_BASE_URL=https://openrouter.ai/api/v1`, `OPENAI_MODEL`. 키 값은 이 문서에 적지 않는다. 설정 뒤 `manage.py probe_report_summary` 로 한 번 확인한다(`llm.py` 16행).
