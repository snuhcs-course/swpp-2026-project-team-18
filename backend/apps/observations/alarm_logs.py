# Partly AI-generated (Claude)
"""서버가 직접 남기는 알람 기록(db-schema.md A3).

앱이 올리는 울림·끄기·미루기와 달리 **알람 스위치(켬/끔/자동)** 는 서버가 일정 수정
요청에서 바로 안다. 앱이 따로 올리게 하면 같은 사실이 두 길로 들어온다.

기록이 실패해도 일정 수정은 그대로 성공해야 한다. 이 표는 학습 재료이고, 스위치는
사용자가 지금 바꾸려는 값이다.
"""

from __future__ import annotations

import logging
import uuid

from django.db import DatabaseError, transaction
from django.utils import timezone

from .models import AlarmLog

logger = logging.getLogger(__name__)


def switch_kind(value: bool | None) -> str:
    """스위치 값 → 기록 종류. null 은 "하루 첫 알람 규칙을 따른다" 는 세 번째 상태다."""
    if value is True:
        return AlarmLog.Kind.ENABLED
    if value is False:
        return AlarmLog.Kind.DISABLED
    return AlarmLog.Kind.AUTO


def record_switch(event, before: bool | None, after: bool | None) -> AlarmLog | None:
    """일정의 알람 스위치가 바뀌었으면 한 줄 남긴다. 같으면 아무것도 하지 않는다.

    `alarm_at` 에는 그때 계획의 알람 시각을 넣는다. 앱 기록의 "기기에 등록돼 있던
    시각" 과 같은 자리다. 계산이 안 된 계획이면 null 이다.
    """
    if before == after:
        return None

    plan = getattr(event, "alarm_plan", None)
    try:
        with transaction.atomic():
            return AlarmLog.objects.create(
                user_id=event.user_id,
                event=event,
                kind=switch_kind(after),
                occurred_at=timezone.now(),
                alarm_at=getattr(plan, "alarm_at", None),
                source=AlarmLog.Source.SERVER,
                # 앱 키와 겹치지 않게 접두를 붙인다. 40자 안에 든다.
                client_uuid=f"srv-{uuid.uuid4().hex}",
            )
    except DatabaseError:
        logger.warning("알람 스위치 기록을 남기지 못했다. 일정 수정은 그대로 둔다")
        return None
