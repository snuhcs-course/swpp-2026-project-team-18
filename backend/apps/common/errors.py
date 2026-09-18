"""공통 에러 응답 포맷.

back-spec.md 5절 공통 규칙:

    {"error": {"code": "...", "message": "...", "details": {...}}}

DRF 기본 응답은 `{"detail": "..."}` 또는 `{"field": ["..."]}` 형태라 클라이언트가
두 가지를 모두 처리해야 한다. 여기서 하나로 통일해 안드로이드 쪽 파싱을 단순화한다.
"""

from __future__ import annotations

from rest_framework.views import exception_handler as drf_exception_handler

FALLBACK_MESSAGE = "요청을 처리할 수 없다."


def _stringify(value):
    """중첩된 ErrorDetail 구조를 순수 str 로 바꾼다."""
    if isinstance(value, dict):
        return {k: _stringify(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_stringify(v) for v in value]
    return str(value)


def _first_message(detail) -> str:
    """사용자에게 그대로 보여줄 수 있는 첫 메시지를 고른다."""
    if isinstance(detail, dict):
        for value in detail.values():
            picked = _first_message(value)
            if picked:
                return picked
        return ""
    if isinstance(detail, (list, tuple)):
        for value in detail:
            picked = _first_message(value)
            if picked:
                return picked
        return ""
    text = str(detail).strip()
    return text


def api_exception_handler(exc, context):
    """DRF 예외를 공통 포맷으로 감싼다.

    DRF 가 처리하지 못하는 예외(= 500)는 그대로 흘려보낸다. 여기서 삼켜버리면
    개발 중 스택트레이스를 잃는다.
    """
    response = drf_exception_handler(exc, context)
    if response is None:
        return None

    detail = getattr(exc, "detail", None)
    code = str(getattr(exc, "default_code", "") or "error")

    if detail is None:
        message = FALLBACK_MESSAGE
        details = {}
    elif isinstance(detail, dict):
        message = _first_message(detail) or FALLBACK_MESSAGE
        details = _stringify(detail)
    elif isinstance(detail, (list, tuple)):
        message = _first_message(detail) or FALLBACK_MESSAGE
        details = {"errors": _stringify(detail)}
    else:
        message = str(detail)
        details = {}

    response.data = {
        "error": {
            "code": code,
            "message": message,
            "details": details,
        }
    }
    return response
