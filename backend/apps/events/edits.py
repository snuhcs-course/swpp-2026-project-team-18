# Partly AI-generated (Claude)
"""일정 수정(`PATCH /api/events/{id}`) 뒤 알람 계획을 어떻게 다시 만들지 정한다.

| 실제로 바뀐 것 | 계획 | 카카오 호출 |
| --- | --- | --- |
| 출발지·목적지 좌표, 고른 경로(`route_key`) | 경로부터 다시 계산(`compute_and_store`) | 1~2회 |
| 시작 시각, τ(`tau_override`·태그 기본값), 출발지를 따로 골랐는지 | 지금 경로 원값으로 다시 섞기(`refresh.refresh_edited`) | 0회 |
| 제목, 출발지 이름, 알람 켬/끔 | 그대로 | 0회 |

**보낸 필드가 아니라 값으로 비교한다.** 수정 화면이 모든 필드를 함께 보내도 실제로 바뀐
것만 본다. 같은 장소를 다시 보내면(새 `Place` 행이 생겨도 좌표가 같으면) 경로는 그대로다.
좌표는 집 목적지·프로필 집까지 풀어서(`Event.resolve_origin`·`resolve_destination`) 비교한다.

**시각만 바뀌면 경로를 다시 조회하지 않는다.** 카카오 경로 조회는 출발 시각을 받지 않아
다시 불러도 같은 원값이 오고 쿼터(일 1,000건)만 쓴다. 바뀌는 것은 이동 보정의 시간대
버킷과 준비 블록뿐이고, 둘 다 원값 없이 다시 계산한다. 알람 3시간 전부터는 앱의 경로 갱신
워커가 실제 경로로 다시 계산한다.

원값을 다시 쓸 수 없으면(계산 불가 상태, 이력이 지금 계획과 맞지 않음, 이력이 없는 옛
일정) 경로부터 다시 계산한다. 계획이 아예 없는 일정(반복 회차를 창 밖으로 옮겼다가 다시
안으로 옮긴 경우)도 그렇다.
"""

from __future__ import annotations

from dataclasses import dataclass

from apps.planning import refresh, services
from apps.planning.models import PlanRevision

from .models import Event

ROUTE = "route"
REMIX = "remix"
KEEP = "keep"


@dataclass(frozen=True)
class PlanInputs:
    """알람 계획을 만드는 입력. 수정 전후를 비교한다."""

    #: (출발지 좌표, 목적지 좌표, 고른 경로). 바뀌면 카카오 원값이 달라진다
    route: tuple
    #: (시작 시각, τ, 준비 시간 적용 여부). 바뀌면 분포만 다시 섞는다. 태그는 τ 로만 들어간다
    model: tuple


def plan_inputs(event: Event) -> PlanInputs:
    profile = event.user.profile
    origin = event.resolve_origin(profile)
    destination = event.resolve_destination(profile)
    return PlanInputs(
        route=(
            origin[:2] if origin else None,
            destination[:2] if destination else None,
            event.route_key or "",
        ),
        model=(
            event.start_at,
            event.effective_tau,
            # 출발지를 따로 골랐으면 준비 시간을 넣지 않는다(`services.estimate_components`).
            event.origin_lat is None,
        ),
    )


def classify(before: PlanInputs, after: PlanInputs) -> str:
    if before.route != after.route:
        return ROUTE
    if before.model != after.model:
        return REMIX
    return KEEP


def replan(event: Event, before: PlanInputs, *, start_before) -> str:
    """수정을 저장한 일정의 계획을 다시 만든다. 고른 방법(`ROUTE`·`REMIX`·`KEEP`)을 돌려준다.

    `REMIX` 로 시작했다가 원값을 다시 쓸 수 없으면 `ROUTE` 로 물러난다.
    """
    how = classify(before, plan_inputs(event))
    if how == REMIX and refresh.refresh_edited(event, start_before=start_before) is None:
        how = ROUTE
    if how == ROUTE:
        services.compute_and_store(event, trigger=PlanRevision.Trigger.UPDATE)
    return how
