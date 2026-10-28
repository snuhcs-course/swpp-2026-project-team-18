# 토큰 보안 API 계약 (로그아웃, refresh 취소, 회전)

로그인하면 서버가 access(30분)와 refresh(14일)를 준다. 앱은 access 가 만료되면 refresh 로 새
access 를 받는다(`POST /api/auth/token/refresh`). 전에는 서버가 refresh 를 무효로 만들 방법이
없었다. 로그아웃은 앱이 토큰을 지우는 것뿐이라, 새어 나간 refresh 는 14일 동안 계속 새 access 를
받았다. 이제 서버에 **취소 목록**이 있고, 갱신 때 거기 있는 refresh 를 거절한다.

구현: `backend/apps/accounts/tokens.py`, `views.py` `LogoutView`·`LogoutAllView`, SimpleJWT
`token_blacklist` 앱(표 두 개, 마이그레이션은 라이브러리에 들어 있다) · 테스트:
`backend/apps/accounts/tests/test_logout.py`, `scripts/check_auth_api.py` 13-1·13-2

## 1. 단계 (지금 앱이 깨지지 않게)

| 단계 | 누가 | 내용 | 상태 |
| --- | --- | --- | --- |
| 1 | 서버 | 취소 목록, 로그아웃 API, 모든 기기 로그아웃. 회전은 꺼 둔다 | 이 커밋 |
| 2 | 앱 | 로그아웃 때 서버에 알리기, 갱신 응답에 새 refresh 가 오면 저장하기 | 프론트 할 일 |
| 3 | 서버 | 2단계 앱이 배포된 뒤 Render 에 `JWT_ROTATE_REFRESH_TOKENS=1` | 코드 변경 없음 |

**회전을 먼저 켜면 안 된다.** 지금 앱(`AuthInterceptor.kt` `TokenRefreshAuthenticator`)은 갱신
응답의 `access` 만 저장하고 처음 받은 refresh 를 계속 쓴다. 회전을 켜면 첫 갱신에서 옛 refresh 가
취소되고, 두 번째 갱신이 401 이 되어 앱이 세션을 지운다. 설치된 앱이 모두 로그인 30분~1시간 뒤
로그아웃된다.

1단계는 지금 앱에 영향이 없다. 로그인·가입·소셜 로그인·갱신의 요청과 응답이 그대로다(테스트
`TestExistingBehaviour`). 달라지는 것은 서버가 발급한 refresh 를 표에 기록한다는 것뿐이다.

## 2. `POST /api/auth/logout` (이 기기 로그아웃)

인증 헤더 없이 부른다. access 가 이미 만료된 앱도 로그아웃할 수 있어야 하고, refresh 자체가
자격증명이다. 낡은 `Authorization` 헤더가 붙어 와도 무시한다.

```json
{"refresh": "eyJ..."}
```

| 상태 | 뜻 |
| --- | --- |
| 204 | 취소했다. 본문 없음. 이미 취소된 토큰이어도 204(두 번 눌러도, 재시도해도 같다) |
| 400 | `refresh` 가 없다 |
| 401 `token_not_valid` | 서명·만료가 맞지 않거나 refresh 가 아닌 토큰(access 등) |
| 429 | 너무 자주(IP별 시간당 60회) |

- **앱은 응답과 관계없이 저장한 토큰을 지우고 로그인 화면으로 간다.** 네트워크가 없어서 실패해도
  로그아웃은 된 것으로 둔다(서버 쪽 refresh 는 14일 뒤 만료).
- access 는 취소하지 않는다. 매 요청마다 표를 조회하지 않는 SimpleJWT 설계라, 남은 access 는
  최대 30분 동안 통한다. 앱이 지우므로 앱에서는 문제 없다.
- 이 기능 전에 발급된 refresh 도 취소할 수 있다.

## 3. `POST /api/auth/me/logout-all` (모든 기기 로그아웃)

로그인한 사용자만(`Authorization: Bearer <access>`). 본문 없음.

```json
{"revoked": 2}
```

- 그 사용자에게 발급된 refresh 중 살아 있는 것을 모두 취소한다. 이 기기 것도 포함이라, 앱은
  부른 뒤 토큰을 지우고 로그인 화면으로 간다.
- **배포 전에 발급된 refresh 는 기록이 없어서 취소되지 않는다**(최대 14일 뒤 만료). 배포 뒤
  로그인한 기기는 모두 잡힌다.
- 비밀번호 변경 기능은 아직 없다. 만들면 바꿀 때 같은 함수(`tokens.revoke_all`)를 부른다.
- 설정 화면의 "모든 기기에서 로그아웃" 버튼용이다. 필요 없으면 앱에서 안 써도 된다.

## 4. 회전 (3단계)

`JWT_ROTATE_REFRESH_TOKENS=1` 이면 갱신 응답이 이렇게 바뀐다.

```json
{"access": "eyJ...", "refresh": "eyJ...(새 것)"}
```

- 갱신에 쓴 refresh 는 바로 취소된다. 같은 refresh 로 다시 갱신하면 401.
- 앱은 응답에 `refresh` 가 있으면 저장한 refresh 를 바꾼다. 없으면(회전 꺼짐) 그대로 둔다. 이렇게
  짜 두면 서버 설정과 관계없이 동작한다.
- 동시에 두 요청이 갱신하면 하나는 401 이 날 수 있다. 앱의 `TokenRefreshAuthenticator` 는 이미
  `@Synchronized` 로 갱신을 한 번만 하므로 괜찮다.
- 끄려면 변수를 지우거나 `0` 으로. `1` 만 켜짐이다(`true` 는 꺼짐으로 읽는다).

## 5. 앱이 할 일 (2단계)

1. 로그아웃 버튼: `POST /api/auth/logout` `{"refresh": <저장한 refresh>}` 를 부른 뒤(결과 무시)
   `TokenStore` 를 지운다. 인증 헤더는 붙이지 않는다(`AuthInterceptor` 의 예외 목록에
   `/api/auth/logout` 추가).
2. 갱신: `RefreshResponse` 에 `val refresh: String? = null` 을 더하고, 값이 있으면
   `tokenStore` 의 refresh 도 바꾼다(`TokenRefreshAuthenticator.tryRefresh`, 주석 갱신).
3. 선택: 설정에 "모든 기기에서 로그아웃" → `POST /api/auth/me/logout-all` → 토큰 지우고 로그인 화면.
4. 이 앱이 배포되고 이전 버전 사용자가 거의 없어지면 서버에서 회전을 켠다(3단계).
5. 토큰 암호화 저장(Android Keystore)은 별도 항목이다.

## 6. 운영

- 표: `token_blacklist_outstandingtoken`(발급 기록, 로그인마다 한 줄),
  `token_blacklist_blacklistedtoken`(취소 기록). 관리 화면 "Token Blacklist" 에서 본다. 발급
  기록은 읽기 전용이고, 취소 기록은 관리자가 특정 토큰을 손으로 취소할 때 추가할 수 있다.
- 만료된 기록 지우기: `python manage.py flushexpiredtokens`(SimpleJWT 내장). 로그인 한 번에 한 줄이라
  급하지 않다. 정기 실행은 아직 붙이지 않았다.
- 배포: 마이그레이션은 컨테이너 시작 때 자동이다. 새 환경변수는 3단계 전까지 필요 없다.
