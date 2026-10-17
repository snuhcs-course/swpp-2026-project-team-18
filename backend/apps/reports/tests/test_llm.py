# Partly AI-generated (Claude)
"""OpenAI 호환 클라이언트 테스트. 네트워크 없이 가짜 opener 로만 본다.

지키는 것
    - 원격 평문 http 로 키를 보내지 않는다
    - 리다이렉트를 따라간 요청에 키를 싣지 않는다
    - temperature·max_tokens 를 보내지 않는다(추론형 모델이 거부한다)
    - 실패를 이름으로 나누고 예외를 던지지 않는다
    - 키·응답 본문을 로그에 남기지 않는다
"""

from __future__ import annotations

import io
import json
import logging
import ssl
import urllib.error
import urllib.request

import pytest

from apps.reports import llm

DEFAULT_CHAT = "https://api.openai.com/v1/chat/completions"


class TestEndpoint:

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("", DEFAULT_CHAT),
            ("   ", DEFAULT_CHAT),
            ("https://llm.example.ac.kr/v1", "https://llm.example.ac.kr/v1/chat/completions"),
            ("https://llm.example.ac.kr/v1/", "https://llm.example.ac.kr/v1/chat/completions"),
            (
                "https://llm.example.ac.kr/v1/chat/completions",
                "https://llm.example.ac.kr/v1/chat/completions",
            ),
            (
                " https://llm.example.ac.kr/v1/chat/completions/ ",
                "https://llm.example.ac.kr/v1/chat/completions",
            ),
            ("http://localhost:8080/v1", "http://localhost:8080/v1/chat/completions"),
            ("http://127.0.0.1:1234", "http://127.0.0.1:1234/chat/completions"),
        ],
    )
    def test_accepted(self, settings, raw, expected):
        settings.OPENAI_BASE_URL = raw
        assert llm.endpoint() == expected

    @pytest.mark.parametrize(
        "raw",
        [
            "http://llm.example.ac.kr/v1",  # 원격 평문 http
            "https://llm.example.ac.kr/v1?api-version=2024-06-01",  # Azure 식 쿼리
            "https://llm.example.ac.kr/v1#frag",
            "https://user:secret@llm.example.ac.kr/v1",
            "ftp://llm.example.ac.kr/v1",
            "llm.example.ac.kr/v1",
            "https:///v1",
        ],
    )
    def test_rejected(self, settings, raw):
        settings.OPENAI_BASE_URL = raw
        assert llm.endpoint() is None


class TestConfigured:

    def test_needs_key_and_model(self, settings):
        assert llm.configured() is False
        settings.OPENAI_API_KEY = "sk-test"
        assert llm.configured() is False
        settings.OPENAI_MODEL = "  "
        assert llm.configured() is False
        settings.OPENAI_MODEL = "gpt-test-mini"
        assert llm.configured() is True


class TestChat:

    def test_not_configured_never_sends(self):
        # conftest 가 진짜 opener 를 막아 둔다. 불리면 여기서 터진다.
        assert llm.chat("시스템", "사용자") == (None, llm.NOT_CONFIGURED)

    def test_remote_http_is_refused_before_sending(self, settings, fake_llm):
        settings.OPENAI_BASE_URL = "http://llm.example.ac.kr/v1"
        assert llm.chat("시스템", "사용자") == (None, llm.BAD_BASE_URL)
        assert fake_llm.calls == 0

    def test_success_and_request_shape(self, fake_llm):
        fake_llm.reply = "이 주 요약임."
        assert llm.chat("시스템 지시", "집계값") == ("이 주 요약임.", None)

        request, timeout = fake_llm.requests[0]
        assert request.full_url == DEFAULT_CHAT
        assert request.get_method() == "POST"
        assert request.get_header("Authorization") == "Bearer sk-test-not-a-real-key"
        assert request.get_header("Content-type") == "application/json"
        assert timeout == llm.TIMEOUT_SECONDS
        assert fake_llm.sent() == {
            "model": "gpt-test-mini",
            "messages": [
                {"role": "system", "content": "시스템 지시"},
                {"role": "user", "content": "집계값"},
            ],
        }

    @pytest.mark.parametrize("status", [301, 302, 303])
    def test_redirected_request_carries_no_key(self, fake_llm, status):
        """urllib 은 POST 의 301/302/303 을 GET 으로 따라가며 일반 헤더를 그대로 싣는다.

        가짜 opener 는 리다이렉트를 거치지 않으므로, 만든 요청을 표준 라이브러리의
        리다이렉트 처리에 직접 넣어 본다. 새 주소가 다른 호스트·평문 http 여도
        키가 따라가지 않아야 한다.
        """
        fake_llm.reply = "이 주 요약임."
        llm.chat("s", "u")
        request, _ = fake_llm.requests[0]

        redirected = urllib.request.HTTPRedirectHandler().redirect_request(
            request, None, status, "Found", {}, "http://evil.example/"
        )

        assert redirected.full_url == "http://evil.example/"
        assert redirected.get_header("Authorization") is None
        # 원래 요청에는 그대로 있다.
        assert request.get_header("Authorization") == "Bearer sk-test-not-a-real-key"

    def test_list_content_is_joined(self, fake_llm):
        fake_llm.body = json.dumps({
            "choices": [{"message": {"content": [
                {"type": "text", "text": "앞 "},
                {"type": "image", "url": "x"},
                {"type": "text", "text": "뒤"},
            ]}}],
        }).encode()
        assert llm.chat("s", "u") == ("앞 뒤", None)

    @pytest.mark.parametrize(
        "status,failure",
        [
            (400, llm.HTTP_4XX),
            (401, llm.HTTP_4XX),
            (403, llm.HTTP_4XX),
            (404, llm.HTTP_4XX),
            (429, llm.HTTP_429),
            (500, llm.HTTP_5XX),
            (503, llm.HTTP_5XX),
        ],
    )
    def test_http_errors_are_named(self, fake_llm, status, failure):
        fake_llm.error = urllib.error.HTTPError(DEFAULT_CHAT, status, "x", {}, io.BytesIO(b"{}"))
        assert llm.chat("s", "u") == (None, failure)

    def test_http_error_logs_the_code_but_not_the_body_or_key(self, fake_llm, caplog):
        caplog.set_level(logging.WARNING, logger="apps.reports.llm")
        body = b'{"error":{"code":"invalid_api_key","message":"Incorrect API key: sk-tes***-key"}}'
        fake_llm.error = urllib.error.HTTPError(DEFAULT_CHAT, 401, "x", {}, io.BytesIO(body))

        assert llm.chat("s", "u") == (None, llm.HTTP_4XX)
        assert "invalid_api_key" in caplog.text
        assert "401" in caplog.text
        assert "Incorrect" not in caplog.text
        assert "sk-tes" not in caplog.text
        assert "sk-test-not-a-real-key" not in caplog.text

    @pytest.mark.parametrize(
        "error,failure",
        [
            (urllib.error.URLError(ConnectionRefusedError()), llm.NETWORK),
            (urllib.error.URLError("dns failure"), llm.NETWORK),
            (urllib.error.URLError(TimeoutError()), llm.TIMEOUT),
            (TimeoutError("timed out"), llm.TIMEOUT),
            (ssl.SSLError("bad handshake"), llm.NETWORK),
            (ConnectionResetError(), llm.NETWORK),
        ],
    )
    def test_transport_errors_are_named(self, fake_llm, error, failure):
        fake_llm.error = error
        assert llm.chat("s", "u") == (None, failure)

    @pytest.mark.parametrize(
        "body",
        [
            b"<html>gateway</html>",
            b"{}",
            b'{"choices": []}',
            b'{"choices": [{"message": {}}]}',
            b'{"choices": [{"message": {"content": "   "}}]}',
            b'{"choices": [{"message": {"content": null}}]}',
        ],
    )
    def test_bad_responses_are_named(self, fake_llm, body):
        fake_llm.body = body
        assert llm.chat("s", "u") == (None, llm.BAD_RESPONSE)
