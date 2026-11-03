# 백엔드 요청 — Iteration 3 프론트에서 나온 것
`front-iter3` 은 서버 코드를 고치지 않는다. 앱이 보여 줄 수 없거나 서버에 없어서 기기에만 둔 것, 운영에서 할 일을 여기 모았다. 필드 이름은 제안이고, 모델에 필드를 더할 때는 이 코드베이스 규칙대로 `db_default` 를 함께 준다. 번호는 iter2 의 [R-1 ~ R-14](../iter2/backend-requests.md) 와 겹치지 않게 `R3-n` 으로 붙였다(R3-9 ~ R3-11 은 11-03 밤에 더했다). 앱 쪽 내용은 [front-iter3.md](front-iter3.md), Figma 쪽은 [figma-changes.md](figma-changes.md) 에 있다.

| ID | 요청 | 우선 | 앱 쪽 상태 |
| --- | --- | --- | --- |
| R3-1 | `docs/iter3/reroute-api.md` · `travel-factors.md` 를 main 에 올리기 (코드가 인용하는데 파일이 없음) | 낮음 | 서버 코드와 테스트에서 계약을 읽어 구현함 |
| R3-2 | 집 여러 개: `SavedPlace`, 일정의 `origin_place_id` | 보통 | 기기에만 저장(최대 5개), 서버에는 기본 집 하나 |
| R3-3 | 저장한 집에서 출발한 일정을 집 출발로 학습 | R3-2 와 함께 | 저장한 집을 고르면 `include_prep: true` 를 보냄 |
| R3-4 | 소셜 로그인: 이 PC 의 디버그 키를 카카오·구글 콘솔에 등록 | 높음 (배포 전 확인) | 코드 완료, 실기기·실계정 확인 못 함 |
| R3-5 | `JWT_ROTATE_REFRESH_TOKENS=1` 은 이 브랜치 앱이 퍼진 뒤에 켜기 | 높음 (켜는 때) | 회전한 refresh 를 저장함 |
| R3-6 | 주간 리포트 ⑧⑨ 의 수면·미루기·요일별 조정 패널에 쓸 필드 | 보통 | 패널은 Figma 에만 있고 앱에는 그리지 않음 |
| R3-7 | 리포트 요약 LLM 호출의 전체 시간 제한(약 15초) | 높음 | 영향 없음 (서버 템플릿으로 내려오면 그대로 표시) |
| R3-8 | 리포트 요약 검사의 한글 수 처리 · 추천 모델 · Render 환경 변수 | 보통 | 영향 없음 |

| R3-9 | 비밀번호 찾기·변경 엔드포인트 셋과 메일 발송 설정 | 보통 | 화면과 클라이언트 완료. 404·405 면 "아직 서버에서 지원하지 않아요" |
| R3-10 | 이메일 인증(선택, 로그인을 막지 않음) | 낮음 | 계약만, 화면 없음 |
| R3-11 | 출발 전 재계획: `routes/live` 의 출발 전 사용 확인과 학습 정책 | 보통 | 앱만으로 구현함 |
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

## R3-9. 비밀번호 찾기 · 변경 (제안, 2026-11-03)
- 지금: 서버에는 비밀번호를 잊었을 때 쓰는 길도, 로그인한 채 바꾸는 길도 없다. 앱은 화면과 호출을 만들어 두었다(로그인의 "비밀번호 찾기" 두 화면, 설정의 "비밀번호" 행). 이 엔드포인트가 404·405 로 답하면 "아직 서버에서 지원하지 않아요"를 보인다. 서버가 생기면 앱을 고치지 않아도 동작한다.
- 모든 요청과 응답은 JSON, 오류는 공통 형식이다. `invalid` 는 `details.{필드}: [메시지]` 를 가진 필드 검사 오류다.
1. `POST /api/auth/password/reset` (인증 없음, 스로틀 `password_reset` IP당 5/시간·이메일당 3/시간)
   - 요청 `{"email": "a@b.co"}` (앞뒤 공백 제거·소문자, 로그인과 같음).
   - **202** `{"sent": true, "expires_in": 600}`. 가입한 이메일이든 아니든, 소셜로만 가입한 계정이든 **같은 답**을 준다(계정 존재 여부를 알리지 않기 위해). 메일은 쓸 수 있는 비밀번호가 있는 계정에만 보낸다.
   - 코드는 6자리, 10분 유효, 해시로 저장한다. 새로 요청하면 이전 코드는 무효다.
   - 오류: 400 `invalid`(`details.email`), 429 `throttled`.
2. `POST /api/auth/password/reset/confirm` (인증 없음, 스로틀 `password_reset_confirm` IP당 10/시간)
   - 요청 `{"email": "...", "reset_code": "123456", "new_password": "...", "new_password_confirm": "..."}`.
   - **200** `{"access": "...", "refresh": "...", "user": {UserSerializer}}` (로그인 응답과 같은 모양). 비밀번호를 바꾸고, **새 토큰을 내기 전에** `tokens.revoke_all(user)` 로 기존 refresh 를 모두 취소하고, 코드를 사용 처리한다.
   - 오류: 400 `invalid_reset_code`(코드가 틀렸거나 없는 이메일, 같은 답), 400 `reset_code_expired`, 400 `too_many_attempts`(코드 하나당 5번 틀리면 그 코드는 죽음), 400 `invalid`(`details.new_password` 는 Django 검증 메시지, `details.new_password_confirm` 은 "비밀번호가 일치하지 않는다."), 429 `throttled`.
3. `POST /api/auth/me/password` (로그인 필요, 스로틀 `password_change` 사용자당 10/시간)
   - 요청 `{"current_password": "...", "new_password": "...", "new_password_confirm": "..."}`.
   - **200** `{"access": "...", "refresh": "...", "revoked": 3}`. 현재 비밀번호를 확인하고 `validate_password(new, user)` 로 검사한 뒤 저장한다. `revoked = tokens.revoke_all(user)` 로 이 기기를 포함한 모든 기기의 refresh 를 취소하고, 이 기기에 새 쌍을 낸다. 앱은 새 쌍을 저장하고 다른 기기는 로그아웃된다고 안내한다.
   - 오류: 400 `invalid_current_password`(**401 이 아니다**: 401 이면 앱이 토큰 갱신·세션 만료 경로를 탄다), 400 `password_unchanged`, 400 `invalid`, 409 `password_login_unavailable`(쓸 수 있는 비밀번호가 없는 계정), 401(access 토큰이 나쁠 때만), 429 `throttled`.
- 메일 발송: `EMAIL_BACKEND` 가 필요하다. 로컬은 콘솔 백엔드, Render 는 SMTP 환경 변수를 둔다(값은 이 문서에 적지 않는다). 메일 문구 예: "JustInTime 비밀번호 재설정 코드: 123456 (10분 동안 유효)".
- 앱 쪽: 두 재설정 URL 은 인증 헤더 없이 부르고(공개 경로), 401 이 와도 토큰 갱신을 시도하지 않는다. 비밀번호 변경은 인증을 붙인다.

## R3-10. 이메일 인증 (선택, 제안)
- 로그인을 막지 않는다. 앱 화면은 아직 없고 계약만 적는다.
- `User.email_verified_at`(nullable, `db_default=None`). `UserSerializer` 에 `email_verified: bool` 을 더한다.
- `POST /api/auth/me/email/verify` → 202 `{"sent": true, "expires_in": 600}`. 409 `email_already_verified`, 409 `email_not_deliverable`(소셜이 만든 가짜 이메일, `social.PLACEHOLDER_DOMAIN`). 스로틀 3/시간.
- `POST /api/auth/me/email/verify/confirm` `{"code": "123456"}` → 200 `{"user": {...}}`. 오류: 400 `invalid_verify_code`·`verify_code_expired`·`too_many_attempts`.
- 로그인과 다른 모든 API 는 이 값을 보지 않는다.

## R3-11. 출발 전 재계획 신호
- 앱이 한 일: 알람이 울리기 전에는 서버가 주는 `alt_*`(대안 경로)가 5분 이상 빠르면 일정에 저장한 경로를 PATCH 로 바꾼다. 알람을 끌 때와 계획한 출발 10분 전, 최대 두 번 `POST /api/routes/live` 로 지금 나가도 늦는지 보고, 늦으면 `POST /api/events/{id}/reroute` 를 제안한다(이번 이동만 바뀜).
- 서버 코드에서 읽은 것: `routes/live` 는 단계나 시각을 검사하지 않는다(`events/views.py` 1044~1127행). 그래서 출발 전에도 부를 수 있고, 이때 `arrival` 은 "지금 나가면"의 도착이다(`live_switch.py` 102~120행). `reroute` 는 시작 ±6시간 안이면 받는다(`views.py` 244행).
- 요청:
  1. 위 두 동작을 지금처럼 두고 R3-1 의 계약 문서에 적어 달라.
  2. `routes/live` 본문에 선택 항목 `"context": "pre_departure"` 를 받아 로그에 남기고, 스로틀 범위를 따로 달라. 앱은 서버가 모르는 키를 받는다고 알려 준 뒤에만 보낸다(지금 서버는 추가 키를 무시한다).
  3. 학습 정책을 정해 달라: 출발 전 경로 바꾸기는 지금 이동 중 바꾸기와 같이 학습에서 빠진다(`TripReroute`). 그대로 둘지, 출발 전에 바꾼 이동은 새 경로 기준으로 학습할지.
  4. 나중에: `RouteRefreshWorker` 의 `recompute` 가 기준 경로로 늦는 것을 보면 계획(`alarm_plan.depart_check`)에 `switch_suggestion` 을 넣어 달라. 그러면 앱이 따로 부르지 않아도 된다.

## 서버 처리 (2026-11-03, 서버 0.18.0)

| ID | 처리 | 문서 |
| --- | --- | --- |
| R3-1 | 두 문서를 올렸다 | [reroute-api.md](reroute-api.md), [travel-factors.md](travel-factors.md) |
| R3-2 | `SavedPlace` API 와 일정의 `origin_place_id`. 끝의 `/` 는 있어도 없어도 된다 | [saved-places-api.md](saved-places-api.md) |
| R3-3 | `origin_place_id` 가 있으면 집 출발(준비 시간·준비 학습). **좌표로 기본 집 50m 안을 판정하지는 않는다** — 문서의 "제안과 다른 점" | [saved-places-api.md](saved-places-api.md) |
| R3-4 | 운영(콘솔에 키 등록). 코드 변경 없음 | |
| R3-5 | 운영(켜는 때). 코드 변경 없음 | |
| R3-6 | 주간 응답에 `wake` 블록. 수면·`weekday_adjustments` 는 없다(재료 없음, 학습은 평일·주말 두 칸) | [../iter2/report-design.md](../iter2/report-design.md) §3 |
| R3-7 | LLM 호출 전체 15초 마감 | `backend/apps/reports/llm.py` |
| R3-8 | "세분화" 등은 한글 수로 보지 않는다. 모델·환경 변수는 운영 | `backend/apps/reports/summary.py` |
| R3-9 | 세 엔드포인트와 SMTP 설정 | [password-api.md](password-api.md) |
| R3-10 | 두 엔드포인트, `UserSerializer.email_verified` | [password-api.md](password-api.md) |
| R3-11 | 1·2: 계약 문서에 적고 `context: pre_departure` 와 따로 센 스로틀. 3: **출발 전에 바꾼 이동은 새 경로 기준으로 학습**(사용자 11-03). 4: 아직 | [reroute-api.md](reroute-api.md) 4절 |
