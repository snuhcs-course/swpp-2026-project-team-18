# Partly AI-generated (Claude)
"""쌓인 기록 초기화(iter4 BE-RESET). 계약은 `docs/iter4/server-learning-reset.md`.

내 정보 › 계정 › [기록 초기화]. 쓰면서 쌓인 기록과 **그 기록에서 맞춰진 값**을 지우고,
앞으로의 알람을 기록이 없을 때의 기준으로 다시 섞는다. 사용자가 직접 넣은 것(일정 · 집 ·
준비 시간 · 루틴 블록 · 계정 · 직접 정한 알람 시각)은 그대로다.

## 지우는 것 (요청한 사용자 행만)

| 키 | 모델 | 왜 |
| --- | --- | --- |
| `trip_observations` | `observations.TripObservation` | 출발 · 도착 기록. 이동 보정 · GPS 준비 시간 · 버퍼의 재료 |
| `alarm_logs` | `observations.AlarmLog` | 아침(알람) 기록. 결정 시점 판단 · 리포트의 재료 |
| `trip_reroutes` | `observations.TripReroute` | 경로 변경 기록. 이동 학습의 재료. **이동 중일 수 있는 일정(시작 6시간 전 ~ 6시간 뒤)의 행은 남긴다** — `routes/live` 가 바꾼 경로를 기준으로 삼는다 |
| `block_observations` | `routines.BlockObservation` | 블록별 실제 걸린 시간. 준비 시간 분포의 재료 |
| `buffer_adjustments` | `planning.BufferAdjustment` | 지각 이력으로 맞춘 버퍼. 마지막 행이 지금 버퍼다 |
| `weekly_summaries` | `reports.WeeklySummary` | 기록으로 쓴 주간 요약 문장 |
| `model_artifacts` | `prediction.ModelArtifact` 중 **이 사용자의 개인 행** | 개인 블록 분포. 전역 행(`user` null)은 건드리지 않는다 |
| `travel_profiles` | `routing.UserTravelProfile` | 도보 기록으로 배운 걷는 속도 |

## 일부러 남기는 것

- `planning.AlarmPlan` · `PlanRevision`: 계획과 그 이력이다. 앞으로의 일정은 지운 뒤 바로 다시
  섞는데, 그때 이력의 카카오 원값을 쓴다(`refresh`). 지난 일정의 이력은 관측이 없으면 학습에
  쓰이지 않는다.
- `routing.RouteCorrection`: 모든 사용자의 기록으로 만든 공용 보정이라 이 사용자 몫만 뗄 수 없다.
  밤 전체 학습(`train_models`)이 남은 기록으로 다시 만들 때 이 사용자 기록이 빠진다.
- `reports.LlmCall`: 하루 LLM 호출 상한을 세는 표다. 지우면 초기화로 상한을 되살릴 수 있다.
- `common.ApiRequestLog`: 운영 기록. 보존 기간이 지나면 따로 지워진다.
- 일정의 `skipped_at`(안 감) · `prep_learning_excluded_at`(학습 제외 표시) · `route_key`(고른 경로),
  `EventBlockSelection`(블록 체크): 사용자가 고르거나 팀이 붙인 표시다. 기록이 없으면 효과도 없다.

## 순서와 트랜잭션

지우기와 다시 섞기를 한 트랜잭션에서 한다. 다시 섞다가 실패하면 지운 것도 되돌린다 — 기록은
지워졌는데 알람은 옛 학습값인 상태를 남기지 않는다. 다시 섞기는 카카오를 부르지 않는다.

1. `Profile.learning_reset_at` 을 지금으로 둔다. 이보다 이른 기록이 오프라인 큐에서 늦게 올라오면
   업로드 API 가 받기만 하고 버린다(`recorded_before_reset`).
2. 지우기 **전에** 이미 울렸거나 출발한 앞으로의 일정을 모은다. 지운 뒤에는 그 기록이 없어
   `refresh` 가 진행 중인 아침을 다시 섞고 만다.
3. 8개 표를 지운다. 이동 중일 수 있는 일정의 경로 변경(`TripReroute`)은 남긴다.
4. 다시 섞을 일정마다 새 알람을 미리 구해 본다. `RESET_MARGIN` 안에 울릴 알람과, 다시 섞으면
   `RESET_MARGIN` 안으로(또는 이미 지난 시각으로) 당겨질 알람은 그대로 둔다 — 기기에 걸린
   알람이 곧 울린다.
5. 가까운 `RESET_EVENT_LIMIT` 개를 다시 섞는다. 다시 섞은 일정에 남은 "바꿀까요"
   (`recommended_moved`) 제안은 거둔다 — 옛 학습값이 낸 질문이다.

다시 섞지 못한 앞으로의 계획(곧 울림 · 이미 울림 · 원값 없음 · 반복 창 밖 · 상한 밖)은
`skipped_plans` 로 센다. 그 계획은 앱 경로 갱신(알람 3시간 전부터, 카카오로 다시 계산) · 밤 학습
재계산 · 일정 수정 때 새 기준으로 바뀐다.

## 밤 학습과 겹칠 때

밤 학습(`train_models`)은 기록을 읽고 한참 뒤에 개인 학습값을 쓴다. 그 사이에 초기화하면 지운
기록으로 만든 값(걷는 속도 · 버퍼 조정 · 개인 아티팩트)이 다시 생긴다. 학습이 끝나면
`redo_after_nightly(run_started)` 가 학습 시작 뒤에 초기화한 사용자의 개인 학습값을 다시 지우고
알람을 다시 섞는다.

초기화 뒤 첫 밤 학습은 공용 이동 보정에서 이 사용자 몫을 뺀다. 그 때문에 움직인 알람은 초기화
때문에 움직인 것이라 `drop_nightly_proposals` 가 그 밤의 "바꿀까요" 제안을 거두고 바로 반영한다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta

from django.db import transaction
from django.utils import timezone

from apps.events.models import Event
from apps.events.recurrence import in_window
from apps.routing.models import walk_factor_for

from . import alarm_control, buffers, history, refresh, services
from .models import AlarmPlan, BufferAdjustment, PlanRevision

logger = logging.getLogger(__name__)

# 이만큼 안에 울릴 알람은 다시 섞지 않고, 다시 섞어도 이보다 앞으로 당기지 않는다.
# 기기에 걸린 알람이 곧 울린다. 서버만 바꾸면 이미 지난 시각의 알람이 생길 수 있다.
RESET_MARGIN = timedelta(minutes=10)

# 초기화가 다시 섞는 가까운 일정 수. 카카오를 부르지 않아 밤 학습(50개)보다 넉넉히 둔다.
RESET_EVENT_LIMIT = 200

# 초기화 뒤 "첫 밤 학습" 으로 보는 범위. 밤 학습은 하루 한 번(23:17)이다. 학습 시작 하루 전
# 이후에 초기화했으면 지난 학습은 초기화 전에 돌았다.
NIGHTLY_GRACE = timedelta(hours=24)


def _targets():
    """(응답 키, 모델). 지우는 순서이기도 하다. 서로 외래 키가 없어 순서는 상관없다."""
    from apps.observations.models import AlarmLog, TripObservation, TripReroute
    from apps.prediction.models import ModelArtifact
    from apps.reports.models import WeeklySummary
    from apps.routines.models import BlockObservation
    from apps.routing.models import UserTravelProfile

    return (
        ("trip_observations", TripObservation),
        ("alarm_logs", AlarmLog),
        ("trip_reroutes", TripReroute),
        ("block_observations", BlockObservation),
        ("buffer_adjustments", BufferAdjustment),
        ("weekly_summaries", WeeklySummary),
        ("model_artifacts", ModelArtifact),
        ("travel_profiles", UserTravelProfile),
    )


def _learned_targets():
    """밤 학습이 사용자별로 쓰는 학습값. 겹친 초기화를 다시 맞출 때 지운다."""
    from apps.prediction.models import ModelArtifact
    from apps.routing.models import UserTravelProfile

    return (BufferAdjustment, ModelArtifact, UserTravelProfile)


@dataclass(frozen=True)
class ResetResult:
    deleted: dict[str, int]
    # 지운 기준으로 다시 섞은 앞으로의 계획 수(바뀐 것 + 같은 것).
    recomputed_plans: int
    # 그중 알람 값이 실제로 바뀐 계획 수.
    changed_plans: int
    # 다시 섞지 못한 앞으로의 계산된 계획 수. 옛 학습값이 남아 있을 수 있다.
    skipped_plans: int
    reset_at: datetime


def recorded_before_reset(user) -> datetime | None:
    """마지막 초기화 시각. 업로드는 이보다 이른 기록을 받기만 하고 버린다. 초기화 전이면 None."""
    from apps.accounts.models import Profile

    if user is None or user.pk is None:
        return None
    return (
        Profile.objects.filter(user_id=user.pk)
        .values_list("learning_reset_at", flat=True)
        .first()
    )


def reset_learning(user) -> ResetResult:
    """`user` 의 쌓인 기록과 학습값을 지우고 앞으로의 알람을 다시 섞는다. 멱등하다."""
    from apps.accounts.models import Profile
    from apps.observations.models import TripReroute

    if user is None or user.pk is None:
        # `filter(user=None)` 은 전역 아티팩트를 고른다. 절대 그 경로로 가지 않게 막는다.
        raise ValueError("기록 초기화에는 저장된 사용자가 필요하다")

    with transaction.atomic():
        # 같은 사용자의 초기화 두 개가 겹치지 않게 프로필 행을 잡는다.
        list(Profile.objects.select_for_update().filter(user_id=user.pk).values_list("pk"))
        reset_at = timezone.now()
        Profile.objects.filter(user_id=user.pk).update(learning_reset_at=reset_at)

        # 지우기 전에 모은다. 지운 뒤에는 진행 중인 아침을 알아볼 기록이 없다.
        events = _upcoming(user, reset_at)
        started = _started_ids([event.pk for event in events])

        deleted: dict[str, int] = {}
        for key, model in _targets():
            qs = model.objects.filter(user_id=user.pk)
            if model is TripReroute:
                qs = qs.exclude(pk__in=list(_live_reroutes(user, reset_at)))
            _total, per_model = qs.delete()
            deleted[key] = per_model.get(model._meta.label, 0)

        outcomes, skipped = _recompute(user, events, now=reset_at, keep=started)

    changed = outcomes.get(refresh.CHANGED, 0)
    result = ResetResult(
        deleted=deleted,
        recomputed_plans=changed + outcomes.get(refresh.UNCHANGED, 0),
        changed_plans=changed,
        skipped_plans=skipped,
        reset_at=reset_at,
    )
    logger.info(
        "기록 초기화 user=%s deleted=%s recomputed=%s changed=%s skipped=%s",
        user.pk, deleted, result.recomputed_plans, changed, skipped,
    )
    return result


def _upcoming(user, now) -> list[Event]:
    """다시 섞을 후보. `refresh.refresh_upcoming` 과 같은 조건 · 순서다."""
    qs = (
        Event.objects.filter(user=user, start_at__gte=now, alarm_plan__isnull=False)
        .select_related("place", "tag", "user__profile", "recurrence", "alarm_plan")
        .order_by("start_at", "id")
    )
    return [event for event in qs[:RESET_EVENT_LIMIT] if in_window(event)]


def _started_ids(event_ids) -> set[int]:
    """이미 알람이 울렸거나 출발한 일정. `refresh` 가 지금 기록에서 구하는 것과 같다."""
    from apps.observations.models import AlarmLog, TripObservation

    if not event_ids:
        return set()
    return set(
        AlarmLog.objects.filter(event_id__in=event_ids, kind=AlarmLog.Kind.FIRED)
        .values_list("event_id", flat=True)
    ) | set(
        TripObservation.objects.filter(event_id__in=event_ids, kind=TripObservation.Kind.DEPART)
        .values_list("event_id", flat=True)
    )


def _live_reroutes(user, now):
    """이동 중일 수 있는 일정의 경로 변경 행. 바꾸기를 받는 범위(`REROUTE_WINDOW`)와 같다."""
    from apps.events.views import REROUTE_WINDOW
    from apps.observations.models import TripReroute

    return TripReroute.objects.filter(
        user_id=user.pk,
        event__start_at__gte=now - REROUTE_WINDOW,
        event__start_at__lte=now + REROUTE_WINDOW,
    ).values_list("pk", flat=True)


def _recompute(user, events: list[Event], *, now, keep: set[int]) -> tuple:
    """지운 기준으로 다시 섞는다. (`refresh` 결과별 건수, 다시 섞지 못한 계산된 계획 수)."""
    remixable, too_close = _preview(user, events, keep=keep, not_before=now + RESET_MARGIN)
    outcomes = refresh.refresh_upcoming(
        user,
        # 사용자가 직접 지운 것이라 "바꿀까요" 제안(`alarm_control.PROPOSAL_TRIGGERS`)을 만들지 않고
        # 바로 반영한다. 그래서 학습(`LEARNING`)이 아니라 사용자 입력 쪽 이유로 남긴다.
        trigger=PlanRevision.Trigger.PROFILE,
        protect=RESET_MARGIN,
        keep_ids=keep | too_close,
        limit=RESET_EVENT_LIMIT,
    )
    _clear_recommended_proposals(remixable)
    recomputed = outcomes.get(refresh.CHANGED, 0) + outcomes.get(refresh.UNCHANGED, 0)
    upcoming = AlarmPlan.objects.filter(
        event__user_id=user.pk, event__start_at__gte=now, status=AlarmPlan.Status.OK
    ).count()
    return outcomes, max(0, upcoming - recomputed)


def _preview(user, events, *, keep, not_before) -> tuple[set[int], set[int]]:
    """(다시 섞을 일정, 다시 섞으면 알람이 `not_before` 보다 이르게 될 일정). 저장하지 않는다.

    `refresh._refresh` · `_remix` 와 같은 조건 · 같은 식이다. 그쪽을 바꾸면 여기도 맞춘다.
    """
    inputs = services.prep_inputs(user.pk)
    buffer = buffers.current(user.pk)
    walk_factor = walk_factor_for(user.pk)
    remixable: set[int] = set()
    too_close: set[int] = set()
    for event in events:
        plan = event.alarm_plan
        if (
            event.pk in keep
            or plan.status != AlarmPlan.Status.OK
            or plan.alarm_at is None
            or plan.alarm_at <= not_before
        ):
            continue
        profile = event.user.profile
        if event.resolve_origin(profile) is None or event.resolve_destination(profile) is None:
            continue
        revision = history.reusable_revision(event, plan)
        if revision is None:
            continue
        route = {
            "minutes": revision.route_minutes_raw,
            "walk_minutes": revision.walk_minutes_raw,
            "key": plan.route_key,
            "mode": plan.travel_mode,
        }
        prep, travel = services.estimate_components(
            event, route, inputs=inputs, buffer_minutes=buffer.minutes, walk_factor=walk_factor
        )
        route_source = (plan.travel_source or "").split("+", 1)[0]
        alarm_at = services.alarm_fields(
            event, prep, travel, route_source=route_source, buffer=buffer
        )["alarm_at"]
        if alarm_at < not_before:
            too_close.add(event.pk)
        else:
            remixable.add(event.pk)
    return remixable, too_close


def _clear_recommended_proposals(event_ids, *, proposed_since=None) -> int:
    """추천 시각에 대한 "바꿀까요" 제안을 거둔다. 지키던 시각을 놓고 추천 시각으로 울린다.

    직접 정한 시각에 대한 제안(`user_low_probability`)은 둔다 — 사용자가 고른 시각이 지금
    기준으로 늦을 수 있다는 알림이라 초기화 뒤에도 맞다. 거둔 제안 수를 돌려준다.
    """
    qs = AlarmPlan.objects.filter(
        event_id__in=list(event_ids), proposal_kind=alarm_control.KIND_RECOMMENDED
    )
    if proposed_since is not None:
        qs = qs.filter(proposed_at__gte=proposed_since)
    cleared = 0
    for plan in qs:
        control = alarm_control.Control.of(plan)
        control._clear_proposal()
        cleared += alarm_control.save(plan, control)
    return cleared


# ---------------------------------------------------------------------------
# 밤 학습(`train_models`)이 학습 · 재계산을 마친 뒤 부른다
# ---------------------------------------------------------------------------


def redo_after_nightly(run_started) -> int:
    """학습 시작(`run_started`) 뒤에 초기화한 사용자를 다시 맞춘다. 맞춘 사용자 수.

    밤 학습이 초기화 전에 읽은 기록으로 개인 학습값(버퍼 조정 · 개인 아티팩트 · 걷는 속도)을
    초기화 뒤에 쓸 수 있다. 초기화가 그 사용자 행을 모두 지웠으므로 지금 남은 행은 이번 학습이
    쓴 것이다. 지우고, 그 값으로 다시 섞였을 수 있는 알람을 초기화처럼 다시 섞는다.
    공용 이동 보정(`RouteCorrection`)은 사용자 몫을 뗄 수 없다 — 다음 밤 학습에서 빠진다.
    """
    from django.contrib.auth import get_user_model

    users = get_user_model().objects.filter(
        profile__learning_reset_at__gte=run_started
    ).order_by("pk")
    count = 0
    for user in users:
        with transaction.atomic():
            for model in _learned_targets():
                model.objects.filter(user_id=user.pk).delete()
            now = timezone.now()
            _recompute(user, _upcoming(user, now), now=now, keep=set())
        count += 1
    return count


def drop_nightly_proposals(run_started) -> int:
    """초기화 뒤 첫 밤 학습이 낸 "바꿀까요" 제안을 거둔다. 거둔 제안 수.

    그 밤 학습은 공용 이동 보정에서 이 사용자 기록을 뺀다. 그래서 움직인 알람은 초기화 때문에
    움직인 것으로 보고, 초기화처럼 묻지 않고 바로 반영한다. 이번 학습이 낸 추천 시각 제안만
    거둔다 — 그 전부터 있던 제안 · 직접 정한 시각에 대한 제안은 그대로다.
    """
    event_ids = AlarmPlan.objects.filter(
        event__user__profile__learning_reset_at__gte=run_started - NIGHTLY_GRACE,
        event__start_at__gte=timezone.now(),
        proposal_kind=alarm_control.KIND_RECOMMENDED,
        proposed_at__gte=run_started,
    ).values_list("event_id", flat=True)
    return _clear_recommended_proposals(event_ids, proposed_since=run_started)
