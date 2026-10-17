# Partly AI-generated (Claude)
"""알람 계획 이력(db-schema.md A2). 쌓는 쪽과 "결정 시점" 을 고르는 쪽.

`AlarmPlan` 은 재계산이 덮어쓴다. 이 모듈이 계산 결과를 [PlanRevision] 에 남기고,
나중에 학습이 "사용자가 따른 계획" 을 고를 때 쓰는 규칙을 한곳에 둔다.

**결정 시점은 저장하지 않는다.** 그 일정의 첫 알람 울림 전에 계산된 마지막 이력이
결정이다. 울림 기록이 없으면 실제 출발 전 마지막 이력이고, 둘 다 없으면 마지막
이력이다. 규칙을 바꿔도 다시 쓸 행이 없게 하려는 것이다.
"""

from __future__ import annotations

import logging

from django.db import DatabaseError, transaction

from .models import AlarmPlan, PlanRevision

logger = logging.getLogger(__name__)

# AlarmPlan 에서 같은 이름으로 복사하는 필드. 경로 좌표는 크기 때문에 뺀다.
COPIED_FIELDS = (
    "status",
    "alarm_at",
    "depart_by",
    "arrive_at",
    "prep_minutes",
    "travel_minutes",
    "buffer_minutes",
    "tau_used",
    "on_time_probability",
    "confidence_basis",
    "prep_source",
    "prep_breakdown",
    "total_quantile_minutes",
    "travel_mode",
    "travel_source",
    "route_key",
    "route_detail",
    "route_distance_m",
)

# Risk previews reuse these raw inputs. They can change while the rounded alarm
# decision stays identical, so deduplicate the complete reusable snapshot.
DEDUP_FIELDS = (*PlanRevision.DECISION_FIELDS, "requested_route_key", "route_minutes_raw")


def record_revision(
    event,
    plan: AlarmPlan,
    *,
    trigger: str = "",
    route_minutes_raw: int | None = None,
) -> PlanRevision | None:
    """방금 저장한 계획을 이력에 남긴다. 결정값과 경로 입력이 모두 같으면 None.

    `event` 는 계산에 쓴 그 객체다. `plan.event` 를 다시 읽지 않는다 — 갱신 경로에서는
    DB 를 한 번 더 조회한다.

    **실패해도 계획 저장을 되돌리지 않는다.** 저장점(savepoint) 안에서 쓰고 DB 오류는
    삼킨다. 알람 계획이 이 앱의 핵심이고 이력은 학습 재료다 — 이력 표가 없거나(마이그레이션
    전) 쓰기가 실패했다고 알람이 사라지면 안 된다.
    """
    values = {name: getattr(plan, name) for name in COPIED_FIELDS}
    values.update(
        event_start_at=event.start_at,
        requested_route_key=(event.route_key or "")[:120],
        route_minutes_raw=route_minutes_raw,
        trigger=trigger or "",
    )

    try:
        with transaction.atomic():
            last = (
                PlanRevision.objects.filter(event_id=event.pk)
                .order_by("-computed_at", "-id")
                .values(*DEDUP_FIELDS)
                .first()
            )
            if last is not None and all(
                last[name] == values[name] for name in DEDUP_FIELDS
            ):
                return None
            return PlanRevision.objects.create(
                user_id=event.user_id,
                event_id=event.pk,
                computed_at=plan.computed_at,
                **values,
            )
    except DatabaseError:
        logger.warning("계획 이력을 남기지 못했다. 알람 계획은 그대로 저장한다")
        return None


def decision_revision(event_id: int) -> PlanRevision | None:
    """사용자가 따른 계획으로 볼 이력. 없으면 None.

    1. 첫 알람 울림(`AlarmLog` `fired`) 전에 계산된 마지막 이력
    2. 울림 기록이 없으면 첫 실제 출발(`TripObservation` `depart`) 전 마지막 이력
    3. 둘 다 없으면 마지막 이력

    울림·출발 뒤의 재계산은 이미 집을 나선 사람이 볼 수 없던 계획이다. 그 값과 실측을
    비교하면 G1(비교 기준이 바뀐다)이 그대로 남는다.
    """
    # 앱 사이 import 방향을 지키려고 함수 안에서 가져온다(planning → observations).
    from apps.observations.models import AlarmLog, TripObservation

    revisions = PlanRevision.objects.filter(event_id=event_id).order_by("-computed_at", "-id")

    fired = (
        AlarmLog.objects.filter(event_id=event_id, kind=AlarmLog.Kind.FIRED)
        .order_by("occurred_at")
        .values_list("occurred_at", flat=True)
        .first()
    )
    if fired is not None:
        return revisions.filter(computed_at__lte=fired).first()

    departed = (
        TripObservation.objects.filter(event_id=event_id, kind=TripObservation.Kind.DEPART)
        .order_by("observed_at")
        .values_list("observed_at", flat=True)
        .first()
    )
    if departed is not None:
        return revisions.filter(computed_at__lte=departed).first()

    return revisions.first()
