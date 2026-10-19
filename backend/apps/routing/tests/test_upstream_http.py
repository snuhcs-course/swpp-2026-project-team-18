"""외부 API 를 부르는 맨 아래 층 — 카카오 `_get`·정적 지도, 서울 실시간 도착정보.

다른 테스트는 이 층을 가짜로 바꿔 끼우고 그 위의 조립·파싱을 본다. 그래서
**이 층 자체가 실패를 어떻게 다루는지**는 아무도 보지 않았다. 여기서 예외가
새면 일정 저장·경로 후보·지도 요청이 전부 500 이 된다.

지키는 것:

- 키는 헤더(`Authorization: KakaoAK …`)로만 간다. URL 에 넣으면 로그에 남는다.
- HTTP 오류·타임아웃·깨진 응답은 예외가 아니라 `degraded` 로 돌아온다.
- 실시간 도착정보는 보조 정보다. 실패하면 비우고, 실패도 잠깐 캐시해 같은 역을
  계속 두드리지 않는다.

실제 네트워크는 쓰지 않는다. `urlopen` 자리에 가짜를 끼운다.
"""

from __future__ import annotations

import io
import json
import socket
import urllib.error

import pytest
from django.core.cache import cache

from apps.routing import clients, realtime


class FakeResponse:
    def __init__(self, body: bytes, content_type: str = "application/json"):
        self._body = body
        self.headers = {"Content-Type": content_type}

    def read(self, *args):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def http_error(code: int, body: bytes = b'{"msg":"quota"}') -> urllib.error.HTTPError:
    return urllib.error.HTTPError("https://dapi.kakao.com/x", code, "error", {}, io.BytesIO(body))


@pytest.fixture
def opener(monkeypatch):
    """`urllib.request.urlopen` 자리. 받은 요청을 모으고 정해 둔 답을 준다."""

    class Opener:
        def __init__(self):
            self.requests = []
            self.reply = FakeResponse(b"{}")

        def __call__(self, req, **kwargs):
            self.requests.append(req)
            if isinstance(self.reply, BaseException):
                raise self.reply
            return self.reply

    o = Opener()
    monkeypatch.setattr(clients.urllib.request, "urlopen", o)
    monkeypatch.setattr(clients.settings, "KAKAO_REST_API_KEY", "test-key")
    return o


# ---------------------------------------------------------------------------
# 카카오 `_get`
# ---------------------------------------------------------------------------


class TestKakaoGet:
    def test_parses_json_and_sends_the_key_as_a_header(self, opener):
        opener.reply = FakeResponse(json.dumps({"documents": [1, 2]}).encode())

        data, degraded = clients._get("https://dapi.kakao.com/v2/local?query=x")

        assert (data, degraded) == ({"documents": [1, 2]}, False)
        req = opener.requests[0]
        assert req.get_header("Authorization") == "KakaoAK test-key"
        assert "test-key" not in req.full_url

    def test_http_error_is_degraded_not_raised(self, opener):
        opener.reply = http_error(429)

        assert clients._get("https://dapi.kakao.com/v2/local?query=x") == (None, True)

    @pytest.mark.parametrize(
        "failure",
        [socket.timeout("timed out"), urllib.error.URLError("dns"), ConnectionResetError()],
        ids=["timeout", "url-error", "reset"],
    )
    def test_network_failure_is_degraded_not_raised(self, opener, failure):
        opener.reply = failure

        assert clients._get("https://dapi.kakao.com/v2/local?query=x") == (None, True)

    def test_broken_json_is_degraded(self, opener):
        opener.reply = FakeResponse(b"<html>gateway</html>")

        assert clients._get("https://dapi.kakao.com/v2/local?query=x") == (None, True)

    def test_missing_key_never_calls_kakao(self, opener, monkeypatch):
        monkeypatch.setattr(clients.settings, "KAKAO_REST_API_KEY", "")

        assert clients._get("https://dapi.kakao.com/v2/local?query=x") == (None, True)
        assert opener.requests == []


# ---------------------------------------------------------------------------
# 정적 지도
# ---------------------------------------------------------------------------


def static_map():
    return clients.static_map(lat=37.48, lng=126.95, level=5, width=360, height=500)


class TestStaticMapFailures:
    def test_image_comes_back_with_its_content_type(self, opener):
        opener.reply = FakeResponse(b"\x89PNG", content_type="image/png")

        assert static_map() == (b"\x89PNG", "image/png", False)
        assert opener.requests[0].get_header("Authorization") == "KakaoAK test-key"

    def test_non_image_reply_is_degraded(self, opener):
        """카카오가 오류를 200 + JSON 으로 줄 때가 있다. 그걸 PNG 로 내보내면 앱이 깨진 그림을 그린다."""
        opener.reply = FakeResponse(b'{"errorType":"AccessDenied"}', content_type="application/json")

        assert static_map() == (None, "", True)

    def test_http_error_is_degraded(self, opener):
        opener.reply = http_error(401)

        assert static_map() == (None, "", True)

    def test_network_failure_is_degraded(self, opener):
        opener.reply = socket.timeout("timed out")

        assert static_map() == (None, "", True)


# ---------------------------------------------------------------------------
# 서울 지하철 실시간 도착정보
# ---------------------------------------------------------------------------


@pytest.fixture
def subway(monkeypatch, settings):
    """`_fetch_bytes` 자리. 부른 URL 을 모으고 정해 둔 답을 준다."""
    cache.clear()
    settings.SEOUL_SUBWAY_API_KEY = "subway-key"

    class Fetch:
        def __init__(self):
            self.urls = []
            self.reply: bytes | BaseException = b"{}"

        def __call__(self, url):
            self.urls.append(url)
            if isinstance(self.reply, BaseException):
                raise self.reply
            return self.reply

    f = Fetch()
    monkeypatch.setattr(realtime, "_fetch_bytes", f)
    yield f
    cache.clear()


def subway_body(rows, status="INFO-000"):
    return json.dumps(
        {"errorMessage": {"status": 200 if status == "INFO-000" else status, "code": status},
         "realtimeArrivalList": rows},
        ensure_ascii=False,
    ).encode()


class TestSubwayRows:
    def test_rows_are_returned_and_cached(self, subway):
        subway.reply = subway_body([{"subwayId": "1002"}])

        first = realtime._subway_rows("서울대입구(관악구청)역")
        second = realtime._subway_rows("서울대입구")

        assert first == second == [{"subwayId": "1002"}]
        assert len(subway.urls) == 1, "괄호·'역' 을 뗀 같은 역은 캐시에서 나온다"
        assert subway.urls[0].endswith("/%EC%84%9C%EC%9A%B8%EB%8C%80%EC%9E%85%EA%B5%AC")

    def test_error_status_is_empty_and_briefly_cached(self, subway):
        subway.reply = subway_body([], status="ERROR-337")

        assert realtime._subway_rows("신림") is None
        assert realtime._subway_rows("신림") is None
        assert len(subway.urls) == 1, "실패도 잠깐 캐시해 같은 역을 계속 두드리지 않는다"

    def test_exception_is_empty_not_raised(self, subway):
        subway.reply = socket.timeout("timed out")

        assert realtime._subway_rows("신림") is None
        assert realtime._subway_rows("신림") is None
        assert len(subway.urls) == 1

    def test_no_key_or_no_station_never_calls(self, subway, settings):
        assert realtime._subway_rows("역") is None
        settings.SEOUL_SUBWAY_API_KEY = "  "
        assert realtime._subway_rows("신림") is None
        assert subway.urls == []


class TestFetchBytes:
    def test_oversized_reply_is_refused(self, monkeypatch):
        """응답 크기를 자르지 않으면 고장 난 상대가 서버 메모리를 다 먹을 수 있다."""
        big = FakeResponse(b"x" * (realtime.MAX_RESPONSE_BYTES + 1))
        monkeypatch.setattr(realtime.urllib.request, "urlopen", lambda req, timeout: big)

        with pytest.raises(ValueError):
            realtime._fetch_bytes("http://swopenapi.seoul.go.kr/api")

    def test_small_reply_passes_through(self, monkeypatch):
        seen = []

        def urlopen(req, timeout):
            seen.append((req.get_header("Accept"), timeout))
            return FakeResponse(b"ok")

        monkeypatch.setattr(realtime.urllib.request, "urlopen", urlopen)

        assert realtime._fetch_bytes("http://swopenapi.seoul.go.kr/api") == b"ok"
        accept, timeout = seen[0]
        assert "application/json" in accept
        assert timeout == realtime.REQUEST_TIMEOUT_SECONDS
