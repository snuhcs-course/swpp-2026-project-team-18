# 로그인 잠금 — 비밀번호 5번 틀리면 15분 (서버, iter4 BE-A3)

<!-- Partly AI-generated (Claude) -->

`todos/iter4-todo.md` 1절 A3 · 3절 BE-A3 의 서버 구현이다. 앱이 세면 재설치 · 다른 기기로 우회되므로 서버가 센다.
구현: `backend/apps/accounts/login_lock.py`, `serializers.LoginSerializer`(`LoginFailed` · `AccountLocked`),
`views_password.PasswordResetConfirmView`, 모델 `LoginAttempt`(마이그레이션 `accounts 0008_login_attempt_and_guide_seen`),
설정 `LOGIN_MAX_FAILURES` · `LOGIN_LOCK_SECONDS`(`config/settings/base.py`), 공통 오류에 `extra_details`(`apps/common/errors.py`) ·
테스트: `backend/apps/accounts/tests/test_login_lock.py`

## 규칙

| 규칙 | 값 |
| --- | --- |
| 세는 단위 | 정규화한 이메일(앞뒤 공백 제거 · 소문자, 로그인과 같다). 사용자 FK 가 아니다 |
| 없는 이메일 | 있는 이메일과 **똑같이** 세고 똑같은 모양으로 답한다(계정이 있는지 드러나지 않게) |
| 잠그는 때 | 연속 5번째로 틀린 **그 시도**부터 423 (`LOGIN_MAX_FAILURES = 5`) |
| 잠그는 시간 | 15분 (`LOGIN_LOCK_SECONDS = 900`). 잠긴 동안의 시도는 세지 않고 잠금을 늘리지도 않는다 |
| 잠긴 동안 | 비밀번호가 맞아도 423. 비밀번호를 보지 않는다 |
| 0 으로 | 로그인 성공, 비밀번호 재설정 완료(`password/reset/confirm`), admin 에서 행 삭제 |
| 잊는 때 | 마지막 시도에서 15분 동안 시도가 없으면 실패 횟수도 0 부터(한 달 전 오타가 남아 있지 않게) |
| 세지 않는 것 | 칸이 빠진 400(`invalid`), 비밀번호 변경의 `invalid_current_password`, 소셜 로그인 |
| 저장 | DB(`accounts_login_attempt`). 캐시가 아니다 — 배포 캐시는 `/tmp` 파일이라 재시작하면 사라져 잠금이 풀린다 |

비활성 계정(`is_active=false`)은 지금처럼 "틀린 비밀번호" 와 같은 401 이고, 같은 규칙으로 센다.

## `POST /api/auth/token`

요청 · 성공 응답(200 `{access, refresh, user}`)은 그대로다.

### 401 — 틀린 이메일 · 비밀번호 (모양은 전과 같고 `details` 에 한 칸이 늘었다)

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="api"

{"error":{"code":"authentication_failed","message":"이메일 또는 비밀번호가 올바르지 않다.","details":{"remaining_attempts":4}}}
```

- `details.remaining_attempts`(정수): 앞으로 몇 번 더 틀리면 잠기는지. 첫 실패 4 → 3 → 2 → 1. 다섯 번째 실패는 401 이 아니라 아래 423 이다.
- `code` · `message` 는 전과 같다(전에는 `details: {}`).

### 423 — 잠김 `account_locked`

```http
HTTP/1.1 423 Locked
Retry-After: 900

{"error":{"code":"account_locked","message":"비밀번호를 5번 틀려서 로그인할 수 없어요. 15분 뒤에 다시 해 주세요. 비밀번호를 재설정하면 바로 풀려요.","details":{"locked_until":"2026-11-15T21:51:40+09:00","retry_after_seconds":900}}}
```

- `details.locked_until`: 풀리는 시각(ISO 8601, 다른 API 와 같은 서버 시간대 표기 `+09:00`). 초 단위로 **올림**한다 — 그 시각에 다시 눌러도 잠겨 있는 일이 없게.
- `details.retry_after_seconds`(정수, 1 이상) = 응답 헤더 `Retry-After`(초).
- `message` 의 분은 남은 시간을 올림한 값이다. 지금 앱은 이 문구를 그대로 보인다.
- 다섯 번째로 틀린 시도, 잠긴 동안의 모든 시도(맞는 비밀번호 포함)가 이 응답이다.

### 423 을 고른 이유 (429 가 아니라)

지금 앱(`origin/front-iter4` `AuthRepository.login` → `call`)은 로그인 실패에서 상태 코드와 관계없이
`error.message` 를 보이고, 본문을 못 읽을 때만 상태 코드 문구로 떨어진다(`defaultMessage` 는 423 · 429 모두
"알 수 없는 오류"). 그래서 **지금 앱에서는 둘 다 위 문구가 그대로 뜬다.** 재시도 인터셉터
(`ColdStartRetryInterceptor`)는 502 · 503 · 504 만, 토큰 갱신(`TokenRefreshAuthenticator`)은 401 만, 그것도 토큰
경로는 건너뛰므로 둘 다 영향이 없다.

그래도 423 으로 한 것은:

1. 429 는 이미 스로틀(`throttled`, IP · 사용자 단위 "요청이 많음")에 쓴다. 앱의 비밀번호 찾기 · 이메일 인증 화면은
   429 를 **코드와 관계없이** "요청이 많음. 잠시 뒤 다시 시도" 로 바꾼다(`passwordFlowMessage` · `emailVerifyMessage`).
   로그인 오류 처리를 그 함수에 맞춰 정리하면 잠금 해제 시각이 사라진다. 423 은 그런 일반 규칙에 걸리지 않는다.
2. 나중에 로그인에 IP 스로틀(429)을 더해도 "잠김" 과 "너무 잦음" 이 상태 코드로 갈린다.

## 비밀번호 재설정으로 풀기

`POST /api/auth/password/reset/confirm` 이 200 으로 끝나면 그 이메일의 실패 기록과 잠금을 지운다(팀 결정).
메일로 코드를 받은 본인만 할 수 있으므로 잠금을 남길 이유가 없다. 코드가 틀린 confirm(400)은 풀지 않는다.
없는 이메일의 잠금은 재설정으로 풀 수 없다(메일이 가지 않는다). 15분을 기다리면 된다.

## 동시 요청

시도를 **먼저 실패로 센 뒤** 비밀번호를 본다(`login_lock.begin`, 행 잠금 `select_for_update` · 새 행은 `get_or_create`).
맞으면 행을 지운다. 그래서 틀린 시도를 한꺼번에 보내도 다섯 개만 비밀번호 검사까지 가고 나머지는 423 이다.
로컬 Postgres 에서 12개를 동시에 보내 확인했다: 실패 번호 1~5 가 한 번씩, 나머지 7개는 잠김.

## 운영

- 잠긴 사용자 문의: Django admin **로그인 실패 기록**에서 그 이메일 행을 지우면 바로 풀린다. 추가 · 수정은 막았다.
- 누구나 남의 이메일로 5번 틀려 15분 잠글 수 있다(계정 기준 잠금의 성질). 데모 계정도 같다. 재설정으로 바로 풀 수 있다.
- 없는 이메일로 시도한 행도 남는다. 다음 성공 · 재설정이 없으면 지워지지 않는다(행 하나가 작고, 15분 뒤에는 효력이 없다).

## 앱이 할 일 (A3 🟢)

- `AuthRepository.login` 오류 매핑: 423 `account_locked` → `details.locked_until`(없으면 지금 + `retry_after_seconds`)로
  잠금 화면 · 카운트다운, [로그인] 끔, [비밀번호 찾기]는 켜 둔다. 구버전 서버에는 이 코드가 없다(401 만 온다).
- 401 의 `details.remaining_attempts` 가 있으면 "N번 더 틀리면 15분 동안 잠겨요". 없으면(구버전 서버) 지금처럼.
- 비밀번호 재설정을 마치면 잠금이 풀린다 — 재설정 성공 응답이 곧 로그인 응답이므로 따로 할 일은 없다.
