# Partly AI-generated (Claude)
"""계정 앱의 시스템 검사(`manage.py check` · `runserver` · `migrate` 가 돌린다).

## `accounts.E001` — `ATOMIC_REQUESTS` 를 켜면 로그인 잠금이 세지 않는다 (iter4 A3-4)

`login_lock.begin` 은 시도를 **먼저 저장한 뒤** 401 · 423 을 낸다. 요청 전체를 트랜잭션으로
묶으면(`ATOMIC_REQUESTS`) DRF 의 예외 처리(`set_rollback`)가 오류 응답과 함께 그 저장을 되돌린다.
그러면 몇 번을 틀려도 실패 횟수가 늘지 않고, 아무 오류 없이 잠금이 사라진다. 비밀번호 재설정 ·
이메일 인증 코드의 시도 횟수(`codes`)도 같은 이유로 되돌려진다. 그래서 켜진 설정은 서버가 뜨지 않게 한다.
"""

from __future__ import annotations

from django.conf import settings
from django.core import checks


@checks.register()
def check_atomic_requests_off(app_configs=None, **kwargs):
    errors = []
    for alias, config in settings.DATABASES.items():
        if config.get("ATOMIC_REQUESTS"):
            errors.append(
                checks.Error(
                    f"DATABASES[{alias!r}]['ATOMIC_REQUESTS'] 가 켜져 있다.",
                    hint=(
                        "로그인 실패 횟수 · 확인 코드 시도 횟수는 오류 응답 전에 저장해야 한다. "
                        "요청 트랜잭션이 오류 응답과 함께 되돌리면 잠금이 동작하지 않는다 "
                        "(apps/accounts/login_lock.py). 필요한 뷰만 transaction.atomic 으로 묶는다."
                    ),
                    id="accounts.E001",
                )
            )
    return errors
