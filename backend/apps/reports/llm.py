# Partly AI-generated (Claude)
"""OpenAI 호환 chat completions 클라이언트. 주간 리포트 요약(P6)이 쓴다.

`apps/routing/clients.py` 와 같은 방식이다 — 표준 라이브러리 `urllib` 만 쓰고
**예외를 밖으로 던지지 않는다.** `chat()` 은 `(문장, None)` 이나 `(None, 실패 이름)`
을 돌려주고, 부르는 쪽(`summary.py`)이 실패마다 백오프를 정한다.

## 가정 — OpenAI 호환 API

    POST {OPENAI_BASE_URL}/chat/completions
    Authorization: Bearer <OPENAI_API_KEY>
    {"model": ..., "messages": [...]}  →  choices[0].message.content

학교가 줄 주소도 이 모양이라고 가정한다(쿼리 문자열 없음). Azure 식(`api-key`
헤더, `?api-version=`)이면 이 파일만 바꾸면 된다. 키를 넣은 뒤에는
`manage.py probe_report_summary` 로 한 번 확인한다.

## 보내지 않는 것

- `temperature`·`max_tokens`: 추론형 모델은 이 값을 거부해 매번 400 이 난다.
  길이는 프롬프트와 `summary.check_text` 가 막는다.
- 키·요청 본문·응답 본문은 **로그에 남기지 않는다.** OpenAI 의 401 본문에는 가린
  키 일부가 들어 있다. 상태 코드와 응답의 `error.code` 만 남긴다.

## 리다이렉트로 키를 보내지 않는다

`urlopen` 은 POST 에 301/302/303 이 오면 새 주소로 GET 을 다시 보내고, 그때 일반
헤더(`add_header`)를 그대로 싣는다 — 다른 호스트·평문 http 로 내려가도 따라간다.
`endpoint()` 의 https·계정 정보 검사는 첫 주소만 본다. 그래서 키는
`add_unredirected_header` 로 붙인다. 리다이렉트된 요청에는 키가 없어 대개 401/405
(`http_4xx`, ERROR 로그)나 다른 모양의 응답(`bad_response`)으로 끝난다. POST 의
307/308 은 urllib 이 따라가지 않는다(`http_4xx`).

## 시간 제한

`TIMEOUT_SECONDS` 는 연결·TLS·읽기 **단계마다** 적용된다(DNS 조회에는 적용되지
않는다). 그래서 전체 대기는 8초보다 길 수 있지만 gunicorn `--timeout 60` 과 앱의
읽기 60초 안에 든다.
"""

from __future__ import annotations

import json
import logging
import ssl
import urllib.error
import urllib.parse
import urllib.request

from django.conf import settings

logger = logging.getLogger(__name__)

DEFAULT_BASE_URL = "https://api.openai.com/v1"
CHAT_PATH = "/chat/completions"
TIMEOUT_SECONDS = 8

# 평문 http 를 허용하는 호스트. 원격 평문 http 로는 키를 보내지 않는다.
LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1"})

# 실패 이름. summary.py 가 백오프를 고르는 열쇠다.
NOT_CONFIGURED = "not_configured"
BAD_BASE_URL = "bad_base_url"
TIMEOUT = "timeout"
NETWORK = "network"
HTTP_429 = "http_429"
HTTP_5XX = "http_5xx"
HTTP_4XX = "http_4xx"
BAD_RESPONSE = "bad_response"

# 테스트가 바꿔 끼우는 자리. 리포트 테스트는 이것을 "부르면 실패" 로 막아 둔다
# (apps/reports/tests/conftest.py). 그래서 테스트는 네트워크를 쓰지 않는다.
_urlopen = urllib.request.urlopen


def configured() -> bool:
    """키와 모델이 있는가. 주소는 보지 않는다.

    잘못된 주소는 여기서 걸러 조용히 넘기지 않고 `chat()` 이 `bad_base_url` 로
    알린다 — 그래야 설정 실수가 로그에 남는다.
    """
    key = (getattr(settings, "OPENAI_API_KEY", "") or "").strip()
    model = (getattr(settings, "OPENAI_MODEL", "") or "").strip()
    return bool(key and model)


def endpoint() -> str | None:
    """요청 주소. 쓸 수 없는 주소면 None.

    빈 값은 OpenAI 기본 주소다. 끝의 `/` 와 `/chat/completions` 는 있어도 된다.
    `https://` 이거나 `http://localhost`·`http://127.0.0.1` 일 때만 쓴다. 쿼리·
    조각(`?api-version=`)이나 주소 안의 계정 정보는 지원하지 않는다.
    """
    raw = (getattr(settings, "OPENAI_BASE_URL", "") or "").strip() or DEFAULT_BASE_URL
    raw = raw.rstrip("/")
    if raw.endswith(CHAT_PATH):
        raw = raw[: -len(CHAT_PATH)].rstrip("/")

    try:
        parts = urllib.parse.urlsplit(raw)
        host = parts.hostname or ""
        has_credentials = bool(parts.username or parts.password)
    except ValueError:
        return None

    if not host or parts.query or parts.fragment or has_credentials:
        return None
    if parts.scheme == "https":
        return raw + CHAT_PATH
    if parts.scheme == "http" and host in LOCAL_HOSTS:
        return raw + CHAT_PATH
    return None


def chat(system: str, user: str) -> tuple[str | None, str | None]:
    """대화형 요청 한 번. `(문장, None)` 또는 `(None, 실패 이름)`.

    예외를 던지지 않는다. 실패는 전부 복구 가능하고 사용자는 고정 문장을 받는다.
    """
    if not configured():
        return None, NOT_CONFIGURED

    url = endpoint()
    if url is None:
        logger.warning(
            "OPENAI_BASE_URL 을 쓸 수 없다(https 아님·쿼리 포함 등). 키를 보내지 않는다."
        )
        return None, BAD_BASE_URL

    body = json.dumps(
        {
            "model": settings.OPENAI_MODEL.strip(),
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    # 리다이렉트를 따라간 요청에는 싣지 않는다(위 "리다이렉트로 키를 보내지 않는다").
    request.add_unredirected_header("Authorization", f"Bearer {settings.OPENAI_API_KEY.strip()}")
    request.add_header("Content-Type", "application/json")

    try:
        with _urlopen(
            request, timeout=TIMEOUT_SECONDS, context=ssl.create_default_context()
        ) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        return None, _http_failure(error)
    except urllib.error.URLError as error:
        if isinstance(error.reason, TimeoutError):
            logger.warning("LLM 요청 시간 초과(%s초)", TIMEOUT_SECONDS)
            return None, TIMEOUT
        logger.warning("LLM 요청 실패(연결) %s", type(error.reason).__name__)
        return None, NETWORK
    except TimeoutError:
        logger.warning("LLM 요청 시간 초과(%s초)", TIMEOUT_SECONDS)
        return None, TIMEOUT
    except Exception as error:  # TLS·연결 끊김 등. 메시지에 주소가 섞일 수 있어 이름만 남긴다
        logger.warning("LLM 요청 실패 %s", type(error).__name__)
        return None, NETWORK

    text = _content(raw)
    if text is None:
        logger.warning("LLM 응답 형식이 다르다(choices[0].message.content 없음)")
        return None, BAD_RESPONSE
    return text, None


def _http_failure(error: urllib.error.HTTPError) -> str:
    """상태 코드로 실패를 나눈다. 본문은 `error.code` 만 읽고 남기지 않는다."""
    status = error.code
    code = _error_code(error)
    if status == 429 or status >= 500:
        logger.warning("LLM 요청 실패 status=%s code=%s", status, code)
        return HTTP_429 if status == 429 else HTTP_5XX
    # 400·401·403·404 — 키·모델·주소 설정 오류다. 사람이 고쳐야 하므로 ERROR.
    logger.error("LLM 요청 실패 status=%s code=%s (설정 확인)", status, code)
    return HTTP_4XX


def _error_code(error: urllib.error.HTTPError) -> str:
    """오류 본문의 `error.code`. 짧은 식별자만 돌려준다(메시지는 읽지 않는다)."""
    try:
        payload = json.loads(error.read(4096).decode("utf-8", "replace"))
        code = (payload.get("error") or {}).get("code")
    except Exception:
        return "-"
    if isinstance(code, str) and code.replace("_", "").replace("-", "").isalnum():
        return code[:64]
    return "-"


def _content(raw: bytes) -> str | None:
    """`choices[0].message.content`. 문자열이 아니라 조각 목록이면 `text` 를 잇는다."""
    try:
        payload = json.loads(raw.decode("utf-8"))
        content = payload["choices"][0]["message"]["content"]
    except Exception:
        return None

    if isinstance(content, list):
        content = "".join(
            part["text"]
            for part in content
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        )
    if not isinstance(content, str) or not content.strip():
        return None
    return content
