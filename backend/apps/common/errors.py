"""공통 에러 응답 포맷.

back-spec.md 5절 공통 규칙:

    {"error": {"code": "...", "message": "...", "details": {...}}}

DRF 기본 응답은 `{"detail": "..."}` 또는 `{"field": ["..."]}` 형태라 클라이언트가
두 가지를 모두 처리해야 한다. 여기서 하나로 통일해 안드로이드 쪽 파싱을 단순화한다.
"""

from __future__ import annotations

import logging

from django.conf import settings
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import exception_handler as drf_exception_handler
from rest_framework.views import set_rollback

logger = logging.getLogger(__name__)

FALLBACK_MESSAGE = "요청을 처리할 수 없다."
# 예상하지 못한 오류(500). 앱은 이 문구를 그대로 보여 주고 입력을 지우지 않는다.
SERVER_ERROR_MESSAGE = "서버에 문제가 생겨 처리하지 못했다. 잠시 뒤 다시 시도할 것."


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

    DRF 가 처리하지 못하는 예외(= 500)도 배포에서는 같은 포맷(`server_error`)으로 돌려준다
    (11-04 피드백 "에러 났을 때 서버에서 처리"). 전에는 Django 의 HTML 500 이 나가서 앱이
    코드도 메시지도 읽지 못했다. 스택트레이스는 로그에 남긴다. `DEBUG` 에서는 전처럼 흘려보내
    개발 중 오류 화면을 본다. (Partly AI-generated (Claude))
    """
    response = drf_exception_handler(exc, context)
    if response is None:
        if settings.DEBUG:
            return None
        view = context.get("view")
        request = context.get("request")
        logger.error(
            "처리하지 못한 오류: %s %s (%s)",
            getattr(request, "method", "?"),
            getattr(request, "path", "?"),
            type(view).__name__ if view is not None else "?",
            exc_info=exc,
        )
        set_rollback()
        return Response(
            {"error": {"code": "server_error", "message": SERVER_ERROR_MESSAGE, "details": {}}},
            status=status.HTTP_500_INTERNAL_SERVER_ERROR,
        )

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
