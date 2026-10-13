# Partly AI-generated (Claude)
"""리포트 테스트 공통 장치. **테스트는 LLM 을 부르지 않는다.**

`test.py` 가 키를 비워 두므로 평소에는 요약이 템플릿으로 떨어진다. 그래도 누가
설정을 바꾸거나 성공 경로 테스트가 가짜 opener 를 빠뜨리면 실제 네트워크로 나갈
수 있다. 그 자리(`llm._urlopen`)를 "부르면 실패" 로 막아 둔다. 성공 경로가
필요한 테스트만 [fake_llm] 으로 가짜 opener 를 끼운다(네트워크 없음).
"""

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from apps.reports import llm


class LlmCalled(BaseException):
    """테스트가 진짜 opener 에 닿았다.

    `BaseException` 인 이유: `llm.chat`·`summary.summary_for` 는 `except Exception`
    으로 모든 실패를 템플릿으로 돌린다. 보통 예외를 던지면 거기서 삼켜져 테스트가
    "템플릿이 나왔다" 로 통과해 버린다.
    """


def _refuse(*args, **kwargs):
    raise LlmCalled("테스트가 LLM 을 불렀다. fake_llm 픽스처를 쓸 것")


@pytest.fixture(autouse=True)
def _no_llm(monkeypatch, settings):
    monkeypatch.setattr(llm, "_urlopen", _refuse)
    # test.py 가 고정하지만 한 번 더 — 요청 주소를 단언하는 테스트가 기댄다.
    settings.OPENAI_BASE_URL = llm.DEFAULT_BASE_URL
    # 백오프 키가 테스트 사이에 남으면 다음 테스트가 LLM 을 부르지 않은 이유가 바뀐다.
    # 하루 호출 수는 DB(`LlmCall`)라 테스트 트랜잭션과 함께 사라진다. (Partly AI-generated (Claude))
    cache.clear()
    yield
    cache.clear()


class FakeResponse:
    def __init__(self, body: bytes):
        self._body = body

    def read(self, *args):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def completion(text: str) -> bytes:
    """OpenAI chat completions 응답 모양."""
    return json.dumps(
        {"choices": [{"index": 0, "message": {"role": "assistant", "content": text}}]},
        ensure_ascii=False,
    ).encode("utf-8")


class FakeOpener:
    """`urllib.request.urlopen` 자리. 받은 요청을 모아 두고 정해 둔 답을 준다.

    - `reply`: 문장, 또는 요청을 받아 문장을 돌려주는 함수
    - `body`: 응답 본문을 그대로(형식 오류 시험용)
    - `error`: 던질 예외
    """

    def __init__(self):
        self.requests: list[tuple[object, float | None]] = []
        self.reply = "요약 문장"
        self.body: bytes | None = None
        self.error: BaseException | None = None

    def __call__(self, request, timeout=None, context=None):
        self.requests.append((request, timeout))
        if self.error is not None:
            raise self.error
        if self.body is not None:
            return FakeResponse(self.body)
        text = self.reply(request) if callable(self.reply) else self.reply
        return FakeResponse(completion(text))

    @property
    def calls(self) -> int:
        return len(self.requests)

    def sent(self, index: int = -1) -> dict:
        """보낸 본문(JSON)."""
        request, _ = self.requests[index]
        return json.loads(request.data.decode("utf-8"))


@pytest.fixture
def fake_llm(monkeypatch, settings):
    """키·모델을 넣고 가짜 opener 를 끼운다. 키는 진짜가 아니다."""
    settings.OPENAI_API_KEY = "sk-test-not-a-real-key"
    settings.OPENAI_MODEL = "gpt-test-mini"
    opener = FakeOpener()
    monkeypatch.setattr(llm, "_urlopen", opener)
    return opener
