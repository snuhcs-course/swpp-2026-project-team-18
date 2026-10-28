# Partly AI-generated (Claude)
"""안전 버퍼를 지각 이력으로 조정한다.

## 버퍼와 τ 가 맡는 것

    알람 = 일정 시작 − 버퍼 − (준비 + 이동) 분포의 τ 분위수

확률 모델은 준비·이동의 흔들림을 이미 안다. 계획의 정시 확률(`on_time_probability`)은
τ 분위수에 버퍼까지 더한 시간 안에 도착할 확률이다. 모델이 맞다면 그 확률만큼 정시에
오고 나머지는 늦는다. **그 지각까지 버퍼로 막으면 위험을 두 번 센다.** 사용자가 고른
τ 를 버퍼가 몰래 올리는 셈이다.

그래서 버퍼는 지각 횟수 자체가 아니라 **모델이 약속한 것과 실제의 차이**를 본다.

| 판단 | 조건 | 바꾸는 양 |
| --- | --- | --- |
| 늘림 | 지각이 2번 이상이고, 약속한 지각 수 Σ(1 − 정시 확률)보다 1번 이상 많다 | 지각 분 중앙값(1~5분) |
| 줄임 | 지각이 없고, 모든 아침이 계획한 도착(일정 시작 − 버퍼)보다 먼저 왔다 | 2분 |
| 그대로 | 그 밖. 모델이 약속대로 맞고 있으면 버퍼가 할 일이 없다 | |

범위는 4~15분이다. 늘릴 때 크게, 줄일 때 작게 움직인다. 늦는 쪽이 일찍 오는 쪽보다
비싸다. 모델이 계속 맞으면 버퍼는 천천히 하한 쪽으로 내려가고 정시율은 사용자가 고른 τ 에
가까워진다. 버퍼는 모델이 못 보는 위험만 맡는다.

## 무엇을 세는가

- **직전 조정 뒤에 결정된 아침만** 센다. 조정 전 아침을 다시 세면 같은 지각으로 밤마다
  버퍼를 또 늘린다. 그래서 한 번 바꾸면 새 버퍼로 아침이 3번 쌓일 때까지 그대로다.
- 그중 최근 10번. 3번 미만이면 그대로다(처음에는 기본값 10분). 3번은 적지만 늘리려면
  그중 2번이 약속보다 더 늦어야 하고, 줄이려면 3번 모두 버퍼를 쓰지 않아야 한다.
- 지각·여유는 주간 리포트와 같은 정의다(`reports.services.collect_outcomes`, 일정 시작 −
  실제 도착, GPS 판정이 먼저). 리포트가 "지각 3번" 이라고 하는데 버퍼가 다른 수를 세면
  조정 이유를 설명할 수 없다.
- 버퍼·정시 확률은 결정 시점 계획(`history.decision_revisions`)에서 읽는다. 알람이 울린 뒤
  다시 계산된 값은 사용자가 따른 계획이 아니다. 이력이 없는 오래된 일정은 지금 계획을 쓴다.
  정시 확률이 없는 계획(변동성을 모름)은 τ 를 약속으로 본다.

## 이동 보정과 겹치지 않는가

이동 보정(`RouteCorrection`)이 따라오는 동안에는 같은 지각이 보정에도 버퍼에도 들어갈 수
있다. 그래서 한 번의 지각으로는 늘리지 않고(2번 이상, 약속보다 1번 이상 많을 때만), 늘린
뒤에는 새 버퍼로 3번이 쌓일 때까지 다시 늘리지 않는다. 보정이 따라잡아 버퍼를 쓰지 않는
아침이 이어지면 다시 줄어든다.

밤 학습(`train_models`)이 조정하고, 바로 뒤의 재계산(`planning.refresh`)이 앞으로 7일
알람에 반영한다.
"""

from __future__ import annotations

import statistics
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta

from django.contrib.auth import get_user_model
from django.utils import timezone

from . import history
from .models import AlarmPlan, BufferAdjustment

# 조정 기록이 없을 때의 버퍼. 문 앞에서 실제 출발까지의 여유(back-spec 4.5
# door_buffer_minutes)로 시작한 값이다.
DEFAULT_MINUTES = 10
# 하한. 도착 판정은 목적지 100m 안이라 자리에 앉기까지 몇 분이 더 든다. 0까지 내리면
# "매번 조금 일찍 왔다" 는 아침들이 일정 시작에 딱 맞춰 도착하게 된다(사용자 결정 10-28).
MIN_MINUTES = 4
MAX_MINUTES = 15

# 한 번에 늘리는 최대 폭과 줄이는 폭.
MAX_STEP_UP = 5
STEP_DOWN = 2

# 볼 아침 수와 판단에 필요한 최소 수.
RECENT_MORNINGS = 10
MIN_MORNINGS = 3

# 늘리는 조건. 지각이 이 수 이상이고, 약속한 지각 수보다 이만큼 이상 많아야 한다.
MIN_LATE = 2
EXCESS_LATE = 1.0

# 이보다 오래된 아침은 보지 않는다. 리포트의 기본 범위와 같다.
LOOKBACK_DAYS = 90

SOURCE_FIXED = "fixed"
SOURCE_LEARNED = "learned"

# 판단 결과. 배치 로그에 그대로 나간다(공개 로그라 사용자 정보는 넣지 않는다).
INCREASED = "늘림"
DECREASED = "줄임"
KEEP_FEW = "그대로(아침 부족)"
KEEP_ON_TRACK = "그대로(약속과 맞음)"
KEEP_AT_LIMIT = "그대로(상·하한)"


@dataclass(frozen=True)
class Buffer:
    """지금 버퍼. `since` 는 마지막 조정 시각이고, 조정한 적이 없으면 None."""

    minutes: int
    source: str
    since: datetime | None


@dataclass(frozen=True)
class Morning:
    """지난 아침 하나. 분 단위."""

    #: 일정 시작 − 실제 도착. 음수면 지각
    slack: float
    #: 그 아침 계획의 버퍼
    buffer: int
    #: 그 아침 계획이 약속한 정시 확률(0~1)
    promise: float


@dataclass(frozen=True)
class Decision:
    previous: int
    minutes: int
    outcome: str
    sample_count: int
    late_count: int = 0
    expected_late: float = 0.0
    mean_slack: float | None = None
    tightest_slack: float | None = None
    median_late: float | None = None

    @property
    def changed(self) -> bool:
        return self.minutes != self.previous


def current(user_id: int) -> Buffer:
    """사용자의 지금 버퍼."""
    row = (
        BufferAdjustment.objects.filter(user_id=user_id)
        .order_by("-created_at", "-id")
        .values_list("minutes", "created_at")
        .first()
    )
    if row is None:
        return Buffer(DEFAULT_MINUTES, SOURCE_FIXED, None)
    return Buffer(row[0], SOURCE_LEARNED, row[1])


def recent_mornings(user, since: datetime | None) -> list[Morning]:
    """`since` 뒤에 결정된 지난 아침 중 도착 기록이 있는 최근 것들. 최근 것부터."""
    from apps.reports.services import collect_outcomes

    start = timezone.now() - timedelta(days=LOOKBACK_DAYS)
    if since is not None:
        start = max(start, since)
    outcomes = [o for o in collect_outcomes(user, since=start) if o.slack_minutes is not None]
    revisions = history.decision_revisions(o.event.pk for o in outcomes)

    mornings: list[Morning] = []
    for outcome in sorted(outcomes, key=lambda o: o.event.start_at, reverse=True):
        decided = revisions.get(outcome.event.pk) or getattr(outcome.event, "alarm_plan", None)
        if decided is None or decided.status != AlarmPlan.Status.OK:
            continue
        if decided.buffer_minutes is None:
            continue
        if since is not None and decided.computed_at < since:
            continue
        if decided.on_time_probability is not None:
            promise = decided.on_time_probability / 100
        elif decided.tau_used is not None:
            promise = decided.tau_used
        else:
            continue
        mornings.append(Morning(outcome.slack_minutes, decided.buffer_minutes, promise))
        if len(mornings) == RECENT_MORNINGS:
            break
    return mornings


def decide(buffer: Buffer, mornings: list[Morning]) -> Decision:
    """지금 버퍼와 그 버퍼로 보낸 아침들로 새 버퍼를 정한다. 저장하지 않는다."""
    from apps.reports.services import half_up, late_minutes

    previous = buffer.minutes
    if len(mornings) < MIN_MORNINGS:
        return Decision(previous, previous, KEEP_FEW, len(mornings))

    slacks = [m.slack for m in mornings]
    late = [late_minutes(m.slack) for m in mornings if m.slack < 0]
    expected = sum(1 - m.promise for m in mornings)
    stats = {
        "sample_count": len(mornings),
        "late_count": len(late),
        "expected_late": round(expected, 2),
        "mean_slack": round(statistics.fmean(slacks), 1),
        "tightest_slack": min(slacks),
        "median_late": statistics.median(late) if late else None,
    }

    if len(late) >= MIN_LATE and len(late) >= expected + EXCESS_LATE:
        step = min(MAX_STEP_UP, max(1, half_up(statistics.median(late))))
        minutes = min(MAX_MINUTES, previous + step)
        outcome = INCREASED if minutes != previous else KEEP_AT_LIMIT
        return Decision(previous, minutes, outcome, **stats)

    if not late and all(m.slack >= m.buffer for m in mornings):
        minutes = max(MIN_MINUTES, previous - STEP_DOWN)
        outcome = DECREASED if minutes != previous else KEEP_AT_LIMIT
        return Decision(previous, minutes, outcome, **stats)

    return Decision(previous, previous, KEEP_ON_TRACK, **stats)


def evaluate(user) -> Decision:
    buffer = current(user.pk)
    return decide(buffer, recent_mornings(user, buffer.since))


def adjust_all(*, dry_run: bool = False) -> Counter:
    """도착 기록이 있는 모든 사용자의 버퍼를 다시 정한다. 결과별 사용자 수를 돌려준다."""
    from apps.observations.models import TripObservation

    since = timezone.now() - timedelta(days=LOOKBACK_DAYS)
    user_ids = set(
        TripObservation.objects.filter(
            kind=TripObservation.Kind.ARRIVE, observed_at__gte=since
        ).values_list("user_id", flat=True)
    )
    outcomes: Counter = Counter()
    for user in get_user_model().objects.filter(pk__in=user_ids).order_by("pk"):
        decision = evaluate(user)
        outcomes[decision.outcome] += 1
        if decision.changed and not dry_run:
            record(user, decision)
    return outcomes


def record(user, decision: Decision) -> BufferAdjustment:
    reason = (
        BufferAdjustment.Reason.LATE_MORE_THAN_PROMISED
        if decision.minutes > decision.previous
        else BufferAdjustment.Reason.BUFFER_UNUSED
    )
    return BufferAdjustment.objects.create(
        user=user,
        previous_minutes=decision.previous,
        minutes=decision.minutes,
        reason=reason,
        sample_count=decision.sample_count,
        late_count=decision.late_count,
        expected_late=decision.expected_late,
        mean_slack_minutes=decision.mean_slack,
        tightest_slack_minutes=decision.tightest_slack,
        median_late_minutes=decision.median_late,
    )


def reason_text(adjustment: BufferAdjustment) -> str:
    """리포트에 쓰는 조정 이유. 리포트 요약과 같은 '~음' 체다."""
    from apps.reports.services import half_up

    change = f"버퍼를 {adjustment.previous_minutes}분에서 {adjustment.minutes}분으로"
    if adjustment.reason == BufferAdjustment.Reason.LATE_MORE_THAN_PROMISED:
        return (
            f"최근 {adjustment.sample_count}번 중 {adjustment.late_count}번 늦었음. "
            f"알람이 약속한 정시 확률로는 {adjustment.expected_late:.1f}번 정도였음. "
            f"늦은 시간의 중앙값 {half_up(adjustment.median_late_minutes or 0)}분을 보고 "
            f"{change} 늘림."
        )
    return (
        f"최근 {adjustment.sample_count}번 모두 계획한 도착 시각보다 먼저 도착했음"
        f"(가장 빠듯했을 때도 {half_up(adjustment.tightest_slack_minutes)}분 일찍 도착). "
        f"{change} 줄임."
    )

