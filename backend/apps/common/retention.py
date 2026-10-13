# Partly AI-generated (Claude)
"""기록 표의 보존 기간(db-schema.md §9-2).

| 표 | 보존 | 이유 |
| --- | --- | --- |
| API 요청 기록 | `API_REQUEST_LOG_RETENTION_DAYS`(기본 30일) | 가장 크다. 학습 값은 다른 표에 있다 |
| LLM 호출 | `LLM_CALL_RETENTION_DAYS`(기본 90일) | 상한은 오늘 행만 센다. 나머지는 비용 확인용 |

학습 재료(일정·계획·계획 이력·관측·알람 기록·블록 관측·주간 요약)는 지우지 않는다.

**ORM 으로 지운다.** 공용 DB 의 FK 는 DB 수준 `ON DELETE` 가 없어 원시 SQL 로 지우면
커밋 때 FK 오류가 난다(db-schema.md §1). 두 표 모두 자식이 없어 DELETE 한 번이다.
0 이하로 두면 그 표는 지우지 않는다.
"""

from __future__ import annotations

from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone

# 이 프로세스가 마지막으로 지운 날(서버 시간대). gunicorn 워커마다 따로 센다.
_last_purged_on = None


def purge(*, now=None, request_days: int | None = None, llm_days: int | None = None) -> dict:
    """보존 기간이 지난 행을 지우고 지운 건수를 돌려준다."""
    from apps.reports.models import LlmCall

    from .models import ApiRequestLog

    now = now or timezone.now()
    if request_days is None:
        request_days = int(getattr(settings, "API_REQUEST_LOG_RETENTION_DAYS", 30))
    if llm_days is None:
        llm_days = int(getattr(settings, "LLM_CALL_RETENTION_DAYS", 90))

    deleted = {"api_request_log": 0, "llm_call": 0}
    if request_days > 0:
        cutoff = now - timedelta(days=request_days)
        deleted["api_request_log"], _ = ApiRequestLog.objects.filter(
            created_at__lt=cutoff
        ).delete()
    if llm_days > 0:
        cutoff = now - timedelta(days=llm_days)
        deleted["llm_call"], _ = LlmCall.objects.filter(created_at__lt=cutoff).delete()
    return deleted


def maybe_purge(*, today=None) -> dict | None:
    """오늘 이 프로세스에서 처음이면 [purge] 를 한 번 부른다. 아니면 None.

    실패해도 그날은 다시 시도하지 않는다 — 요청마다 같은 실패를 되풀이하지 않게 한다.
    """
    global _last_purged_on
    today = today or timezone.localdate()
    if _last_purged_on == today:
        return None
    _last_purged_on = today
    # 바깥이 트랜잭션 안이면(테스트·`ATOMIC_REQUESTS`) 저장점이 된다. 지우다 실패해도 바깥
    # 트랜잭션이 깨지지 않는다. 예외는 부른 쪽(미들웨어)이 삼킨다.
    with transaction.atomic():
        return purge()
