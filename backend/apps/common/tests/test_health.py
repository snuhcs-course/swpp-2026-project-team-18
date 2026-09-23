"""`/api/health` 응답 형태를 고정한다.

`realtime` 은 배포 서버에서 도착정보가 안 뜨는 원인을 가리기 위해 있다.
`realtime.py` 는 외부 실패를 예외로 올리지 않고 빈 결과로 닫으므로(경로 후보를
살리려고), 키 미설정과 외부 호출 실패가 화면에서 똑같이 보인다. 실제로 0.4.0
배포 직후 같은 경로가 로컬에서는 도착정보가 붙고 배포 서버에서는 안 붙었는데,
둘을 구분할 수단이 없어 진단이 막혔다.

키 값이 새어 나가면 안 된다. 그래서 참/거짓만 담는지도 검사한다.
"""

from __future__ import annotations

import json

import pytest
from django.urls import reverse
from rest_framework.test import APIClient

SUBWAY = "6e69426b6f6b6a6837357545696c5a"
BUS = "52946bbceb164c6d51e445be7b3259db"


@pytest.fixture
def client() -> APIClient:
    return APIClient()


def _get(client: APIClient) -> dict:
    res = client.get(reverse("health"))
    assert res.status_code == 200, res.status_code
    return res.json()


def test_버전과_ok_를_담는다(client, settings):
    body = _get(client)
    assert body["ok"] is True
    assert body["version"] == settings.APP_VERSION


def test_키가_없으면_realtime_이_전부_거짓이다(client, settings):
    settings.SEOUL_SUBWAY_API_KEY = ""
    settings.SEOUL_BUS_API_KEY_ENCODING = ""
    settings.SEOUL_BUS_API_KEY_DECODING = ""

    assert _get(client)["realtime"] == {"subway": False, "bus": False}


def test_공백만_있는_키는_설정된_것으로_보지_않는다(client, settings):
    settings.SEOUL_SUBWAY_API_KEY = "   "
    settings.SEOUL_BUS_API_KEY_ENCODING = "\t"
    settings.SEOUL_BUS_API_KEY_DECODING = ""

    assert _get(client)["realtime"] == {"subway": False, "bus": False}


def test_키가_있으면_참이다(client, settings):
    settings.SEOUL_SUBWAY_API_KEY = SUBWAY
    settings.SEOUL_BUS_API_KEY_ENCODING = BUS
    settings.SEOUL_BUS_API_KEY_DECODING = BUS

    assert _get(client)["realtime"] == {"subway": True, "bus": True}


def test_버스는_한쪽_표현만_있어도_참이다(client, settings):
    """포털이 Encoding/Decoding 두 값을 준다. 한쪽만 채워도 호출은 시도한다."""
    settings.SEOUL_SUBWAY_API_KEY = ""
    settings.SEOUL_BUS_API_KEY_ENCODING = ""
    settings.SEOUL_BUS_API_KEY_DECODING = BUS

    assert _get(client)["realtime"] == {"subway": False, "bus": True}


def test_키_값이_응답에_담기지_않는다(client, settings):
    """참/거짓만 알린다. 값이 새면 안 된다."""
    settings.SEOUL_SUBWAY_API_KEY = SUBWAY
    settings.SEOUL_BUS_API_KEY_ENCODING = BUS
    settings.SEOUL_BUS_API_KEY_DECODING = BUS

    raw = json.dumps(_get(client), ensure_ascii=False)
    assert SUBWAY not in raw
    assert BUS not in raw


def test_realtime_은_불리언만_담는다(client, settings):
    """길이나 앞자리 같은 파생값도 담지 않는다는 뜻이다."""
    settings.SEOUL_SUBWAY_API_KEY = SUBWAY
    settings.SEOUL_BUS_API_KEY_ENCODING = BUS
    settings.SEOUL_BUS_API_KEY_DECODING = BUS

    realtime = _get(client)["realtime"]
    assert set(realtime) == {"subway", "bus"}
    assert all(isinstance(v, bool) for v in realtime.values()), realtime


def test_인증_없이_접근된다(client):
    """앱의 서버 확인 화면이 로그인 전에도 부른다."""
    assert client.get(reverse("health")).status_code == 200
