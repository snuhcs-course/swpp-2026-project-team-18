# Partly AI-generated (Claude)
"""경로를 다시 조회하지 않는 알람 재계산.

## 왜 필요한가

알람은 `준비 + 이동` 분포와 버퍼에서 나온다. 이동은 카카오 원값에 학습된 보정을 곱한
값이고, 준비는 블록 정의·블록 기록·GPS 출발에서 나온다. 버퍼는 밤 학습이 지각 이력으로
조정한다(`buffers`). 그런데 계획은 카카오를 부르는
`compute_and_store` 로만 다시 계산했다. 카카오 쿼터(일 1,000건) 때문에

- 루틴 블록 정의를 고쳐도 다시 계산하지 않았다(앱이 "다시 계산" 을 누르라고 안내했다)
- 밤 학습(`train_models`)이 이동 보정·버퍼를 바꿔도 계획은 그대로였다
- 새 블록 기록·GPS 출발이 올라와도 다음 계산 전까지 알람에 들어가지 않았다

**바뀐 것은 경로가 아니라 모델이다.** 지금 계획을 만든 카카오 원값
(`PlanRevision.route_minutes_raw`)을 그대로 두고 분포만 다시 섞으면 카카오를 부르지
않는다. 위험별 선택지 미리보기(`risk_options`)가 이미 같은 방식이다.

## 건드리지 않는 일정

- 계산 불가 상태(`no_home` 등)거나 원값이 지금 계획과 맞지 않는 일정. 경로부터 다시
  구해야 한다(`compute_and_store`)
- 알람이 이미 울렸거나 출발한 일정. 그 아침의 계획은 결정이 끝났다
  (`history.decision_revisions`)
- `protect` 안에 알람이 울릴 일정. 밤 배치는 3시간을 둔다. 그 안은 앱의 경로 갱신
  워커가 실제 경로로 다시 계산하고 기기 알람도 바로 다시 건다. 서버만 바꾸면 기기에
  걸린 알람과 서버 계획이 어긋난 채로 울릴 수 있다

경로 필드(경로선·요약·대안)는 직전 값을 그대로 둔다. 결과가 같으면 저장하지 않는다.
`computed_at` 이 화면의 "N분 전 계산" 으로 나가고 결정 시점 판단에도 쓰이기 때문이다.
"""

from __future__ import annotations

from collections import Counter
from datetime import timedelta

from django.contrib.auth import get_user_model
from django.db import transaction
from django.utils import timezone

from apps.events.models import Event
from apps.events.recurrence import in_window

from . import buffers, history, services
from .models import AlarmPlan, PlanRevision

# 사용자 한 명당 한 번에 다시 계산할 일정 수. 집 변경 재계산(`recompute_for_user`)과
# 같은 상한이다. 가까운 일정부터 고른다.
MAX_EVENTS_PER_USER = 50

# 밤 학습 뒤 재계산 범위. 앱이 기기에 알람을 거는 범위(`AlarmScheduler.HORIZON_MILLIS`,
# 7일)와 같다. 더 먼 일정은 이 범위에 들어온 날 밤에 다시 계산된다.
LEARNING_HORIZON = timedelta(days=7)

# 밤 학습 뒤 재계산에서 비워 두는 구간. 앱 경로 갱신 워커가 알람 3시간 전부터
# 실제 경로로 다시 계산한다(`RouteRefreshDecision.REFRESH_HORIZON_HOURS`).
LEARNING_PROTECT = timedelta(hours=3)

# 결과. 배치 로그에 그대로 나간다(공개 로그라 사용자 정보는 넣지 않는다).
CHANGED = "바뀜"
UNCHANGED = "그대로"
SKIP_NOT_OK = "계산 불가 상태"
SKIP_STARTED = "이미 울림·출발"
SKIP_SOON = "곧 울림"
SKIP_NO_ENDPOINT = "출발지·목적지 없음"
SKIP_NO_RAW = "경로 원값 없음"


def refresh_upcoming(
    user,
    *,
    trigger: str,
    protect: timedelta = timedelta(0),
    horizon: timedelta | None = None,
    dry_run: bool = False,
) -> Counter:
    """사용자의 앞으로의 일정 알람을 경로 조회 없이 다시 계산한다. 결과별 건수를 돌려준다."""
    from apps.observations.models import AlarmLog, TripObservation

    now = timezone.now()
    qs = (
        Event.objects.filter(user=user, start_at__gte=now, alarm_plan__isnull=False)
        .select_related("place", "tag", "user__profile", "recurrence", "alarm_plan")
        .order_by("start_at", "id")
    )
    if horizon is not None:
        qs = qs.filter(start_at__lt=now + horizon)
    events = [event for event in qs[:MAX_EVENTS_PER_USER] if in_window(event)]

    outcomes: Counter = Counter()
    if not events:
        return outcomes

    ids = [event.pk for event in events]
    started = set(
        AlarmLog.objects.filter(event_id__in=ids, kind=AlarmLog.Kind.FIRED)
        .values_list("event_id", flat=True)
    ) | set(
        TripObservation.objects.filter(event_id__in=ids, kind=TripObservation.Kind.DEPART)
        .values_list("event_id", flat=True)
    )
    inputs = services.prep_inputs(user.pk)
    buffer = buffers.current(user.pk)
    for event in events:
        outcome = _refresh(
            event,
            trigger=trigger,
            inputs=inputs,
            buffer=buffer,
            started=event.pk in started,
            cutoff=now + protect,
            dry_run=dry_run,
        )
        outcomes[outcome] += 1
    return outcomes


def refresh_after_learning(*, dry_run: bool = False) -> Counter:
    """밤 학습 뒤 모든 사용자의 앞으로 7일 일정을 다시 계산한다. 결과별 건수를 합쳐 돌려준다."""
    now = timezone.now()
    user_ids = set(
        Event.objects.filter(
            start_at__gte=now,
            start_at__lt=now + LEARNING_HORIZON,
            alarm_plan__status=AlarmPlan.Status.OK,
        ).values_list("user_id", flat=True)
    )
    total: Counter = Counter()
    for user in get_user_model().objects.filter(pk__in=user_ids).order_by("pk"):
        total.update(
            refresh_upcoming(
                user,
                trigger=PlanRevision.Trigger.LEARNING,
                protect=LEARNING_PROTECT,
                horizon=LEARNING_HORIZON,
                dry_run=dry_run,
            )
        )
    return total


def _refresh(
    event: Event,
    *,
    trigger: str,
    inputs: tuple[dict, list[float]],
    buffer: buffers.Buffer,
    started: bool,
    cutoff,
    dry_run: bool,
) -> str:
    plan = event.alarm_plan
    if plan.status != AlarmPlan.Status.OK or plan.alarm_at is None:
        return SKIP_NOT_OK
    if started:
        return SKIP_STARTED
    if plan.alarm_at <= cutoff:
        return SKIP_SOON
    profile = event.user.profile
    if event.resolve_origin(profile) is None or event.resolve_destination(profile) is None:
        return SKIP_NO_ENDPOINT
    revision = history.reusable_revision(event, plan)
    if revision is None:
        return SKIP_NO_RAW

    route = {
        "minutes": revision.route_minutes_raw,
        "key": plan.route_key,
        "mode": plan.travel_mode,
    }
    prep, travel = services.estimate_components(event, route, inputs=inputs)
    # 경로 출처(`kakao_transit` 등)는 직전 계획의 출처 앞부분이다(`alarm_fields`).
    route_source = (plan.travel_source or "").split("+", 1)[0]
    fields = services.alarm_fields(
        event, prep, travel, route_source=route_source, buffer=buffer
    )
    if all(getattr(plan, name) == value for name, value in fields.items()):
        return UNCHANGED
    if dry_run:
        return CHANGED

    with transaction.atomic():
        for name, value in fields.items():
            setattr(plan, name, value)
        plan.save(update_fields=[*fields, "computed_at"])
        history.record_revision(
            event, plan, trigger=trigger, route_minutes_raw=revision.route_minutes_raw
        )
    return CHANGED
