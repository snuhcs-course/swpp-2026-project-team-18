# Partly AI-generated (Claude)
"""API 요청 기록 미들웨어(db-schema.md A4).

`/api/` 아래 요청마다 [ApiRequestLog] 한 줄을 남긴다. 무엇을 남기고 지우는지는
`request_log.py` 가 정한다.

## 기록이 응답을 바꾸지 않는다

기록이 실패해도(DB 오류, 마이그레이션 전, 이상한 본문) **응답은 그대로 나간다.** 예외를
삼키고 경고 로그만 남긴다. 기록은 나중에 보는 자료이고, 응답은 사용자가 지금 기다리는
것이다.

## 사용자

DRF 가 인증한 사용자는 뷰가 끝난 뒤 `request.user` 에 보인다(DRF 의 `Request.user` 가
원래 요청에도 같은 값을 넣는다). 그래서 기록은 뷰 **뒤에** 만든다. 본문만 뷰 **앞에**
읽는다 — DRF 가 스트림을 읽고 나면 `request.body` 를 다시 읽을 수 없다.

## 보존

하루 한 번(프로세스마다) 오래된 기록을 지운다(`retention.maybe_purge`). Render 무료
플랜에는 cron 이 없고, 잠들었다 깨면 프로세스가 새로 뜨므로 요청이 있는 날은 돈다.
"""

from __future__ import annotations

import logging
import time

from django.conf import settings
from django.db import connection, transaction

from . import request_log, retention

logger = logging.getLogger(__name__)


class ApiRequestLogMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if not getattr(settings, "API_REQUEST_LOG_ENABLED", True) or not request_log.should_log(
            request.path
        ):
            return self.get_response(request)

        started = time.monotonic()
        body = None
        if request.method in request_log.WRITE_METHODS:
            try:
                body = request_log.read_json_body(request)
            except Exception:  # noqa: BLE001 - 본문을 못 읽어도 요청은 그대로 처리한다
                body = None

        response = self.get_response(request)

        try:
            _record(request, response, body, started)
            retention.maybe_purge()
        except Exception:  # noqa: BLE001 - 기록 실패가 응답을 바꾸지 않는다
            logger.warning("API 요청 기록을 남기지 못했다", exc_info=True)
        return response


def _record(request, response, body, started: float) -> None:
    from .models import ApiRequestLog

    match = getattr(request, "resolver_match", None)
    route = (match.route if match is not None else request.path.lstrip("/"))[:200]
    if route in request_log.SKIPPED_ROUTES:
        return

    method = (request.method or "")[:8]
    status_code = int(getattr(response, "status_code", 0) or 0)

    request_body = {}
    if (
        body is not None
        and method in request_log.WRITE_METHODS
        and route in request_log.REQUEST_BODY_ROUTES
    ):
        request_body = request_log.bounded(
            request_log.sanitize(body), request_log.REQUEST_LIMIT_BYTES
        )

    response_body = {}
    if (method, route) in request_log.RESPONSE_BODY_ROUTES and 200 <= status_code < 300:
        data = getattr(response, "data", None)
        if isinstance(data, (dict, list)):
            response_body = request_log.bounded(
                request_log.sanitize(data), request_log.RESPONSE_LIMIT_BYTES
            )

    query = {}
    if route not in request_log.META_ONLY_ROUTES:
        query = request_log.bounded(
            request_log.sanitize(request_log.query_dict(request)),
            request_log.REQUEST_LIMIT_BYTES,
        )

    row = ApiRequestLog(
        user_id=_user_id(request),
        method=method,
        route=route,
        object_id=_object_id(match),
        status_code=status_code,
        duration_ms=max(0, int((time.monotonic() - started) * 1000)),
        app_version=request_log.app_version(request),
        query=query,
        request_body=request_body,
        response_body=response_body,
        error_code=request_log.error_code(response),
    )
    # 테스트·`ATOMIC_REQUESTS` 처럼 트랜잭션 안이면 저장점을 둔다. 실패해도 바깥
    # 트랜잭션이 깨지지 않는다. 밖이면 INSERT 한 번(왕복 한 번)으로 끝낸다.
    if connection.in_atomic_block:
        with transaction.atomic():
            row.save()
    else:
        row.save()


def _user_id(request) -> int | None:
    user = getattr(request, "user", None)
    try:
        if user is not None and user.is_authenticated:
            return user.pk
    except Exception:  # noqa: BLE001 - 세션 사용자를 읽다 실패해도 익명으로 남긴다
        return None
    return None


def _object_id(match) -> int | None:
    if match is None:
        return None
    raw = match.kwargs.get("pk", match.kwargs.get("event_id"))
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return None
    return value if value >= 0 else None
