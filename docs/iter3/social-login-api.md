# 소셜 로그인 API 계약 (카카오·구글)

앱의 카카오·구글 로그인 버튼을 위한 서버 계약이다. 앱은 SDK로 로그인해서 받은 **ID 토큰**만
서버에 보낸다. 서버가 제공자 공개키로 검증하고 이메일 로그인과 같은 JWT(access·refresh)를
돌려준다.

구현: `backend/apps/accounts/social.py`, `views_social.py`, 모델 `SocialAccount`(마이그레이션
`accounts 0005`) · 테스트: `backend/apps/accounts/tests/test_social_login.py`

## 1. 콘솔 설정 (한 번)

| | 카카오 | 구글 |
| --- | --- | --- |
| 어디서 | developers.kakao.com → 팀이 쓰는 그 앱 | Google Cloud Console → API 및 서비스 → 사용자 인증 정보 |
| 켤 것 | 카카오 로그인 사용 ON, **OpenID Connect 활성화 ON**(꺼져 있으면 ID 토큰이 안 나온다) | OAuth 동의 화면, OAuth 클라이언트 ID 두 개: **웹 애플리케이션**(서버용)과 **Android**(패키지 이름 + 서명 SHA-1) |
| 동의 항목 | 닉네임(선택), 카카오계정 이메일(선택) | `openid`, `email`, `profile` (기본) |
| 앱에 넣을 값 | 네이티브 앱 키(SDK 초기화), 키 해시 등록 | 웹 클라이언트 ID를 `setServerClientId` 에 |
| Render 환경변수 | `KAKAO_NATIVE_APP_KEY` = 네이티브 앱 키 | `GOOGLE_WEB_CLIENT_ID` = 웹 클라이언트 ID |

- 두 값은 ID 토큰의 `aud` 와 비교한다. 앱 SDK에 넣은 값과 **같아야** 한다. 개발용·배포용 앱을
  따로 쓰면 쉼표로 둘 다 넣는다.
- 값이 비어 있으면 그 제공자 로그인만 503(`social_not_configured`)이다. 다른 기능은 그대로다.
- 넣었는지는 `GET /api/health` 의 `"social": {"kakao": true, "google": false}` 로 확인한다(값은
  나가지 않는다).

## 2. 로그인

`POST /api/auth/social/{provider}` · `provider` = `kakao` 또는 `google` · 인증 없음

```json
{"id_token": "eyJ...", "terms_agreed": true, "nickname": "수연"}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `id_token` | 예 | 카카오 `OAuthToken.idToken`, 구글 `GoogleIdTokenCredential.idToken` |
| `terms_agreed` | 새 계정일 때 | JSON `true` 만 동의로 본다. 이미 가입한 사용자는 보내지 않아도 된다 |
| `nickname` | 아니오 | 새 계정의 닉네임(20자). 없으면 토큰의 닉네임, 그것도 없으면 "카카오 사용자" |

응답은 회원가입과 같은 모양에 `created` 가 붙는다.

```json
{"user_id": 7, "created": true, "access": "...", "refresh": "...",
 "user": {"id": 7, "email": "me@kakao.com", "nickname": "수연",
          "date_joined": "...", "login_methods": ["kakao"]}}
```

| 상태 | `error.code` | 뜻 | 앱이 할 일 |
| --- | --- | --- | --- |
| 200 | | 이미 연결된 사용자 | 로그인 완료 |
| 201 | | 새 계정을 만들었다 | 가입 완료(온보딩으로) |
| 400 | `social_terms_required` | 새 계정인데 약관 동의가 없다. 아무것도 만들지 않았다 | 약관 화면을 띄우고 **같은 `id_token`** 에 `terms_agreed: true` 를 붙여 다시 보낸다(카카오 토큰은 12시간, 구글은 1시간 유효) |
| 409 | `social_email_in_use` | 그 이메일로 가입한 계정이 이미 있다. 자동으로 합치지 않는다 | `error.message` 를 보여 주고, `details.login_methods`(예: `["password"]`)로 원래 로그인 방법을 안내한다. 그 방법으로 로그인한 뒤 설정에서 연결(3절) |
| 401 | `social_token_invalid` | 서명·발급자·앱(aud)·만료가 맞지 않거나 사용할 수 없는 계정 | SDK 로그인을 다시 |
| 503 | `social_not_configured` | 서버에 키가 없다 | "지금은 이메일로 로그인해 주세요" |
| 503 | `social_unavailable` | 제공자 공개키를 받지 못했다 | 잠시 뒤 다시 |
| 404 | | 모르는 제공자 | |
| 429 | | 너무 자주(시간당 60회) | |

순서: 토큰 검증 → 연결된 계정 → 이메일 겹침(409) → 약관(400) → 가입. 그래서 이미 계정이 있는
사람에게 약관을 먼저 보여 주는 일은 없다.

## 3. 연결·끊기 (설정 화면)

로그인한 상태에서만 부른다(`Authorization: Bearer <access>`).

- `POST /api/auth/me/social/{provider}` `{"id_token": "..."}` → 200 `{"user": {...}}`
  - 같은 계정을 다시 연결하면 그대로 200.
  - 409 `social_already_linked`: 그 카카오 계정은 다른 사용자에게 붙어 있다.
  - 409 `social_provider_linked`: 이 사용자에게 이미 다른 카카오 계정이 붙어 있다. 먼저 끊는다.
  - 이메일이 달라도 연결된다. 로그인한 사람이 그 카카오 토큰도 가졌으니 두 계정 모두 본인이다.
- `DELETE /api/auth/me/social/{provider}` → 200 `{"user": {...}}`
  - 404: 연결이 없다.
  - 400 `social_last_login_method`: 그게 유일한 로그인 방법이다(소셜로만 가입해 비밀번호가 없음).

## 4. 사용자 응답의 새 필드

`user.login_methods` 가 회원가입·로그인·`GET /api/auth/me`·소셜 응답에 모두 붙는다. 값은
`password`, `kakao`, `google` 중 가진 것이다. 설정 화면의 "카카오 연결됨" 과 끊기 버튼(마지막
방법이면 숨김)에 쓴다. 기존 앱은 모르는 필드를 무시하므로 영향이 없다.

## 5. 알아 둘 것

- **이메일을 주지 않은 소셜 계정**(카카오에서 이메일 동의를 안 한 경우, 구글에서 인증 안 된
  이메일)은 `kakao-1234567890@social.invalid` 같은 자리 표시 주소를 갖는다. 메일이 갈 수 없는
  예약 도메인이다. **`@social.invalid` 로 끝나면 화면에 이메일을 보여 주지 않는다.**
- 소셜로 가입한 계정은 비밀번호가 없어서 이메일 로그인이 안 된다.
- 로그아웃은 지금과 같다(앱이 토큰을 지운다). 카카오·구글 SDK 로그아웃도 같이 부르면 다음
  로그인 때 계정 선택이 다시 뜬다.
- 구글 이메일은 `email_verified` 가 참일 때만 쓴다. 카카오 ID 토큰의 이메일에는 인증 여부가
  없다. 그래서 둘 다 **이메일로 기존 계정에 자동으로 붙이지 않는다**(2절 409).
- `nonce` 는 아직 쓰지 않는다. 쓰려면 서버가 nonce 를 발급하고 앱이 SDK 로그인에 넘기는
  단계가 더 필요하다(후속).

## 6. 앱이 할 일

1. 카카오 SDK(`com.kakao.sdk:v2-user`) 초기화(네이티브 앱 키), `loginWithKakaoTalk` 실패 시
   `loginWithKakaoAccount`. `OAuthToken.idToken` 이 null 이면 콘솔의 OpenID Connect 가 꺼져 있다.
2. 구글은 Credential Manager(`androidx.credentials` + `googleid`)의 `GetGoogleIdOption` 에
   `setServerClientId(웹 클라이언트 ID)`. 결과에서 `GoogleIdTokenCredential.createFrom(...).idToken`.
3. 받은 토큰으로 `POST /api/auth/social/{provider}`. 400 `social_terms_required` 면 약관 화면 →
   같은 토큰으로 재요청. 409 면 안내 문구와 이메일 로그인으로.
4. 성공하면 이메일 로그인과 똑같이 access·refresh 를 저장한다(`AuthRepository`).
5. 설정에 "카카오 연결/끊기"(3절)와 `login_methods` 표시.
6. `@social.invalid` 이메일 숨기기.

## 7. 직접 확인 (배포 뒤)

1. `GET /api/health` → `social.kakao`·`social.google` 이 `true`.
2. 앱에서 카카오 로그인 → 처음이면 약관 → 홈. 관리 화면 "소셜 계정 연결" 에 한 줄 생긴다.
3. 로그아웃 뒤 다시 카카오 로그인 → 약관 없이 같은 계정(응답 200, `created: false`).
4. 이메일로 가입한 계정과 같은 이메일의 카카오로 로그인 → 409 안내. 이메일로 로그인 → 설정에서
   카카오 연결 → 로그아웃 → 카카오 로그인이 그 계정으로 들어간다.
