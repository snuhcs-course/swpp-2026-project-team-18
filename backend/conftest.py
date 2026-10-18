"""백엔드 테스트 전체에 걸리는 장치 — 단위/통합 구분을 지킨다.

## 통합 테스트란

이 저장소에서 **통합(integration)** 은 테스트 클라이언트(`APIClient`·Django
`Client`)로 HTTP 요청을 보내는 테스트다. 요청 하나가 URL → 미들웨어 → 뷰 →
직렬화 → 권한 → DB 를 한 번에 지난다. 그 밖의 테스트는 **단위(unit)** 다 —
함수·클래스를 직접 부르고, DB 는 픽스처를 담는 곳으로만 쓴다.

통합 테스트에는 `@pytest.mark.integration` 을 붙인다(파일 전체면
`pytestmark`). 그래서 둘을 따로 돌리고 개수를 따로 셀 수 있다.

    python -m pytest -m "not integration"     단위만
    python -m pytest -m integration           통합만

## 이 파일이 하는 일

표시는 손으로 붙이므로 틀릴 수 있다. 여기서 **실제로 요청을 보냈는지** 보고
표시와 다르면 그 테스트를 실패시킨다. 빠뜨린 표시가 조용히 남으면 문서에 적는
단위/통합 개수가 틀린다.

- 요청을 보냈는데 표시가 없다 → 표시를 붙인다.
- 표시가 있는데 요청을 보내지 않았다 → 표시를 뗀다(단위 테스트다).

테스트가 다른 이유로 실패하거나 건너뛰면 이 검사는 하지 않는다.
"""

from __future__ import annotations

import pytest

_current = {"item": None, "sent": False}


def pytest_configure(config):
    # 테스트 클라이언트의 모든 요청은 `Client.request` 를 지난다. DRF `APIClient` 도
    # 결국 여기로 온다. Django 를 불러야 하므로 설정이 잡힌 뒤(여기)에 감싼다.
    from django.test import client as django_client

    original = django_client.Client.request

    def request(self, **kwargs):
        if _current["item"] is not None:
            _current["sent"] = True
        return original(self, **kwargs)

    django_client.Client.request = request


@pytest.hookimpl(wrapper=True)
def pytest_runtest_call(item):
    _current["item"], _current["sent"] = item, False
    try:
        # 테스트 자체가 실패·건너뜀이면 여기서 예외가 나가고 아래 검사는 돌지 않는다.
        result = yield
    finally:
        sent = _current["sent"]
        _current["item"], _current["sent"] = None, False

    marked = item.get_closest_marker("integration") is not None
    if sent and not marked:
        pytest.fail(
            "테스트 클라이언트로 HTTP 요청을 보낸 통합 테스트다. "
            "@pytest.mark.integration 을 붙일 것 (backend/conftest.py 참고)",
            pytrace=False,
        )
    if marked and not sent:
        pytest.fail(
            "integration 표시가 있지만 HTTP 요청을 보내지 않았다. 단위 테스트이므로 "
            "표시를 뗄 것 (backend/conftest.py 참고)",
            pytrace=False,
        )
    return result
