# Partly AI-generated (Claude)
"""refresh 토큰 취소. SimpleJWT 의 취소 목록(`token_blacklist`)을 쓴다.

로그인하면 access(30분)와 refresh(14일)를 준다. 전에는 서버가 refresh 를 무효로 만들 방법이
없었다. 로그아웃은 앱이 토큰을 지우는 것뿐이라, 새어 나간 refresh 는 14일 동안 계속 새
access 를 받았다. 이제 서버가 취소 목록을 두고 갱신(`token/refresh`) 때 거기 있는 refresh 를
거절한다.

| 취소하는 곳 | 무엇을 |
| --- | --- |
| `POST /api/auth/logout` | 보낸 refresh 하나(이 기기) |
| `POST /api/auth/me/logout-all` | 그 사용자에게 발급된 refresh 전부(모든 기기) |
| 갱신 회전(`ROTATE_REFRESH_TOKENS`, 기본 꺼짐) | 갱신에 쓴 옛 refresh |

**access 는 취소하지 않는다.** 매 요청마다 목록을 조회하지 않으려는 SimpleJWT 설계라서, 취소한
뒤에도 남은 access 는 만료(최대 30분)까지 통한다. 앱은 로그아웃할 때 access 도 지운다.

**회전은 기본으로 꺼 둔다.** 회전을 켜면 갱신 응답에 새 refresh 가 오고 옛것은 취소된다. 지금
앱(`AuthInterceptor.kt`)은 새 refresh 를 저장하지 않아서, 켜는 순간 설치된 앱이 첫 갱신(로그인
30분 뒤) 다음에 모두 로그아웃된다. 앱이 저장하도록 고쳐 배포한 뒤 환경변수
`JWT_ROTATE_REFRESH_TOKENS=1` 로 켠다.
"""

from __future__ import annotations

from django.utils import timezone
from rest_framework_simplejwt.exceptions import InvalidToken, TokenBackendError
from rest_framework_simplejwt.settings import api_settings
from rest_framework_simplejwt.state import token_backend
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken
from rest_framework_simplejwt.tokens import RefreshToken


def revoke(raw: str) -> None:
    """refresh 토큰 하나를 취소한다. 이미 취소됐으면 그대로 둔다(두 번 불러도 같다).

    서명·만료·종류가 맞지 않으면 `InvalidToken`(401 `token_not_valid`)이다.

    `RefreshToken(raw)` 로 검증하지 않는다. 그건 취소 여부까지 봐서, 로그아웃이 두 번 오면(앱의
    재시도, 두 번 누름) 두 번째가 401 이 된다. 서명·만료만 검증하고 취소 목록에 넣는다.
    """
    try:
        payload = token_backend.decode(raw, verify=True)
    except TokenBackendError as exc:
        raise InvalidToken(str(exc)) from exc
    if payload.get(api_settings.TOKEN_TYPE_CLAIM) != RefreshToken.token_type:
        raise InvalidToken("refresh 토큰이 아니다.")
    if api_settings.JTI_CLAIM not in payload:
        raise InvalidToken("토큰에 식별자(jti)가 없다.")
    # 이 기능 전에 발급된 토큰은 발급 기록(OutstandingToken)이 없다. `blacklist` 가 만든다.
    RefreshToken(raw, verify=False).blacklist()


def revoke_all(user) -> int:
    """`user` 에게 발급된 refresh 중 아직 살아 있는 것을 모두 취소한다. 취소한 개수를 돌려준다.

    발급 기록이 있는 토큰만 취소할 수 있다. 이 기능을 배포하기 전에 발급된 refresh 는 기록이
    없어서 남는다(최대 14일 뒤 만료). 비밀번호 변경 기능을 만들면 여기를 부른다.
    """
    live = OutstandingToken.objects.filter(
        user=user, expires_at__gt=timezone.now(), blacklistedtoken__isnull=True,
    )
    rows = [BlacklistedToken(token=token) for token in live]
    BlacklistedToken.objects.bulk_create(rows, ignore_conflicts=True)
    return len(rows)
