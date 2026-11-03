# 비밀번호 찾기 · 변경 · 이메일 인증 API

[backend-requests.md](backend-requests.md) R3-9 · R3-10 의 서버 구현이다. 앱이 제안한 계약을 그대로 따랐다.
구현: `backend/apps/accounts/views_password.py`, `codes.py`, 모델 `EmailCode`·`User.email_verified_at`
(마이그레이션 `accounts 0006`) · 테스트: `backend/apps/accounts/tests/test_password_reset.py`

오류는 공통 형식 `{"error": {"code", "message", "details"}}` 이다. `invalid` 는 `details.{필드}: [메시지]` 를 가진 필드 검사 오류다.

## 코드 규칙 (재설정·인증 공통)

| 규칙 | 값 |
| --- | --- |
| 모양 | 6자리 숫자 |
| 유효 시간 | 10분 (`expires_in: 600`) |
| 틀릴 수 있는 횟수 | 코드 하나당 5번. 그 뒤로는 맞아도 `too_many_attempts` |
| 다시 요청 | 이전 코드는 무효 |
| 저장 | 해시만 (`salted_hmac`). 평문은 메일에만 있다 |

## 1. `POST /api/auth/password/reset` (인증 없음)

- 요청 `{"email": "a@b.co"}` — 앞뒤 공백 제거·소문자(로그인과 같음).
- **202** `{"sent": true, "expires_in": 600}`. 가입한 이메일이든 아니든, 소셜로만 가입한 계정이든 **같은 답**이다. 메일은 쓸 수 있는 비밀번호가 있는 계정에만 보낸다. 메일 발송이 실패해도 202 다(서버 로그에 ERROR).
- 오류: 400 `invalid`(`details.email`), 429 `throttled`.
- 스로틀: `password_reset` IP당 5/시간, `password_reset_email` 이메일당 3/시간.

## 2. `POST /api/auth/password/reset/confirm` (인증 없음)

- 요청 `{"email", "reset_code", "new_password", "new_password_confirm"}`.
- **200** `{"access", "refresh", "user": {UserSerializer}}` — 로그인 응답과 같은 모양. 비밀번호를 바꾸고, 새 토큰을 내기 **전에** 기존 refresh 를 모두 취소하고, 코드를 사용 처리한다.
- 오류: 400 `invalid_reset_code`(틀린 코드·없는 이메일·이미 쓴 코드, 같은 답), 400 `reset_code_expired`, 400 `too_many_attempts`, 400 `invalid`(`details.new_password` 는 Django 검증 메시지, `details.new_password_confirm` 은 "비밀번호가 일치하지 않는다."), 429 `throttled`.
- 코드를 먼저 확인하고 비밀번호를 검사한다. 비밀번호 검사에서 떨어지면 코드는 그대로 살아 있다(다시 보내면 된다).
- 스로틀: `password_reset_confirm` IP당 10/시간.

## 3. `POST /api/auth/me/password` (로그인 필요)

- 요청 `{"current_password", "new_password", "new_password_confirm"}`.
- **200** `{"access", "refresh", "revoked": 3}`. 이 기기를 포함한 모든 기기의 refresh 를 취소하고 이 기기에 새 쌍을 낸다. 앱은 새 쌍을 저장하고 다른 기기는 로그아웃된다고 안내한다.
- 오류: 400 `invalid_current_password`(**401 이 아니다** — 401 이면 앱이 토큰 갱신 경로를 탄다), 400 `password_unchanged`, 400 `invalid`, 409 `password_login_unavailable`(쓸 수 있는 비밀번호가 없는 계정), 401(access 토큰이 나쁠 때만), 429 `throttled`.
- 스로틀: `password_change` 사용자당 10/시간.

## 4. 이메일 인증 (선택, 로그인을 막지 않는다)

- `UserSerializer` 에 `email_verified: bool` 이 늘었다(`/api/auth/me`, 로그인·가입·소셜 응답의 `user`).
- `POST /api/auth/me/email/verify` → **202** `{"sent": true, "expires_in": 600}`. 409 `email_already_verified`, 409 `email_not_deliverable`(소셜이 만든 `@social.invalid` 주소). 스로틀 `email_verify` 3/시간.
- `POST /api/auth/me/email/verify/confirm` `{"code": "123456"}` → **200** `{"user": {...}}`. 오류: 400 `invalid_verify_code`·`verify_code_expired`·`too_many_attempts`, 409 `email_already_verified`. 스로틀 `email_verify_confirm` 10/시간.
- 재설정 코드로는 인증되지 않는다(용도가 다르다).
- 로그인과 다른 모든 API 는 이 값을 보지 않는다.

## 메일 설정

| 환경 변수 | 뜻 |
| --- | --- |
| `EMAIL_HOST` | SMTP 서버. 비우면 콘솔 백엔드(서버 로그에 찍힘) |
| `EMAIL_PORT` | 기본 587 |
| `EMAIL_HOST_USER` · `EMAIL_HOST_PASSWORD` | SMTP 계정 |
| `EMAIL_USE_TLS` | 기본 `1` |
| `DEFAULT_FROM_EMAIL` | 보내는 주소. 비우면 `EMAIL_HOST_USER` |
| `EMAIL_BACKEND` | 직접 고를 때만 |

로컬은 아무것도 넣지 않으면 콘솔에 메일 본문이 찍힌다. Render 에는 SMTP 값을 대시보드에서 넣는다(값은 저장소에 적지 않는다).
메일 본문: `JustInTime 비밀번호 재설정 코드: 123456 (10분 동안 유효)`.
