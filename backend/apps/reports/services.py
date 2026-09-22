"""주간 리포트와 캘리브레이션. back-spec.md 5.8, front-spec F11.

## 이 모듈의 목적은 **앱이 틀렸는지 사용자에게 보여주는 것**이다

앱은 "정시 도착 확률 92%" 라고 말한다. 그 말이 맞는지는 아무도 확인해 주지
않는다. 캘리브레이션은 "92% 라고 말한 아침들 중 실제로 정시 도착한 비율" 을
계산한다. 92라고 했는데 60%만 정시였다면 모델이 과신하고 있는 것이고, 그 사실이
화면에 보여야 사용자가 τ 를 올리거나 여유를 더 둘 수 있다.

이걸 숨기면 앱은 그럴싸해 보이지만 신뢰할 근거가 없다.

## 새 모델을 만들지 않는다

명세 4.4 는 `ArrivalObservation` 을 포함한 관측 5개 표를 두지만, 앱은
`TripObservation`(출발·도착 쌍)과 `BlockObservation` 만 올린다. 빈 표를 만들면
"정본이 무엇인지" 가 모호해지므로 **기존 관측에서 파생한다.**

    정시 도착      arrive 관측 시각 <= 일정 시작 시각
    여유(분)       일정 시작 − 실제 도착   (음수면 지각)
    실제 이동(분)  arrive − depart
    실제 준비 초과 실제 출발 − 계획 출발마감(depart_by)

## 지각 원인 분해에서 무엇을 말할 수 있고 무엇은 말할 수 없는가

    travel_over   실제 이동 − 계획 이동      출발·도착 관측이 둘 다 있을 때
    depart_late   실제 출발 − 계획 출발마감   출발 관측이 있을 때
    prep_over     실제 준비 합 − 계획 준비    그 아침의 블록 관측이 있을 때

**측정하지 못한 원인을 0으로 두지 않는다.** 0으로 두면 "준비는 제때 했는데
이동이 늦었다" 로 읽히는데, 사실은 준비를 측정하지 못한 것이다. 각 값은 측정
가능했을 때만 채우고, 주원인은 **측정된 것들 중에서만** 고른다. 어떤 것이
측정되지 않았는지도 함께 내린다.

`depart_late` 와 `prep_over` 는 겹친다 — 준비가 늦으면 출발도 늦는다. 그래서
블록 관측이 있으면 `prep_over` 를 쓰고, 없으면 `depart_late` 로 "집에서 늦게
나섰다" 까지만 말한다. 둘을 더하지 않는다.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from django.db.models import Prefetch
from django.utils import timezone

from apps.events.models import Event
from apps.observations.models import TripObservation
from apps.routines.models import BlockObservation

# 캘리브레이션 버킷 폭. back-spec 5.8 이 0.05 로 정했다.
BUCKET_WIDTH = 0.05

# τ 의 유효 범위. 프로필·태그 모두 이 안이다.
TAU_MIN = 0.50
TAU_MAX = 1.00

# 버킷을 "의미 있다" 고 말하기 위한 최소 표본.
#
# 표본 1건으로 "실제 정시율 0%" 를 보여주면 사용자는 앱이 완전히 틀렸다고
# 읽는다. 한 번 지각한 것과 계통 오차는 다르다. 아래 수 미만은 값을 내리되
# `reliable=False` 로 표시해 화면이 흐리게 그릴 수 있게 한다.
MIN_BUCKET_SAMPLES = 3

# 리포트가 다루는 과거 범위. 이보다 오래된 관측은 루틴이 달라져 있다.
DEFAULT_LOOKBACK_DAYS = 90


@dataclass
class CalibrationBucket:
    """τ 한 구간의 예측 대비 실제."""

    lower: float
    upper: float
    #: 이 구간에 속한 지난 일정 수
    total: int
    #: 그중 정시 도착한 수
    on_time: int

    @property
    def center(self) -> float:
        return round((self.lower + self.upper) / 2, 4)

    @property
    def actual_rate(self) -> float | None:
        """실제 정시 비율. 표본이 없으면 None — 0.0 이 아니다."""
        if self.total == 0:
            return None
        return round(self.on_time / self.total, 4)

    @property
    def reliable(self) -> bool:
        return self.total >= MIN_BUCKET_SAMPLES

    @property
    def gap(self) -> float | None:
        """실제 − 예측. 음수면 과신(약속보다 덜 정시)."""
        actual = self.actual_rate
        if actual is None:
            return None
        return round(actual - self.center, 4)

    def as_dict(self) -> dict:
        return {
            "lower": round(self.lower, 4),
            "upper": round(self.upper, 4),
            "center": self.center,
            "total": self.total,
            "on_time": self.on_time,
            "actual_rate": self.actual_rate,
            "gap": self.gap,
            "reliable": self.reliable,
        }


@dataclass
class LatenessCause:
    """지각 한 건의 원인 분해.

    측정하지 못한 값은 **None 이다.** 0 으로 두면 "그 요인은 문제가 없었다" 로
    읽히는데, 사실은 재료가 없어 모른다는 뜻이다.
    """

    event_id: int
    #: 얼마나 늦었나(분). 양수다
    late_minutes: int
    prep_over: float | None = None
    travel_over: float | None = None
    depart_late: float | None = None

    @property
    def measured(self) -> dict[str, float]:
        return {
            name: value
            for name, value in (
                ("prep_over", self.prep_over),
                ("travel_over", self.travel_over),
                ("depart_late", self.depart_late),
            )
            if value is not None
        }

    @property
    def primary(self) -> str | None:
        """가장 큰 초과 요인. **측정된 것 중에서만** 고른다.

        초과가 하나도 없으면 None 이다. 그게 무슨 뜻인지는 [label] 이 가른다 —
        "측정을 못 했다" 와 "계획이 짧았다" 는 완전히 다른 결론이다.
        """
        positives = {k: v for k, v in self.measured.items() if v > 0}
        if not positives:
            return None
        return max(positives, key=lambda k: positives[k])

    @property
    def unmeasured(self) -> list[str]:
        return [
            name
            for name, value in (
                ("prep_over", self.prep_over),
                ("travel_over", self.travel_over),
                ("depart_late", self.depart_late),
            )
            if value is None
        ]

    @property
    def label(self) -> str:
        """화면에 쓸 원인 이름.

        ## 왜 `primary` 만으로는 안 되는가

        초과 요인이 없을 때 그것을 그냥 "계획이 짧았다" 로 부르면 **거짓말이
        된다.** 출발 관측이 없어서 아무것도 재지 못한 아침도 같은 결론이 되기
        때문이다. 사용자는 앱이 계획을 잘못 세웠다고 읽고, 실제로는 추적이
        동작하지 않은 것이다.

        그래서 셋으로 나눈다.

            <요인 이름>      측정된 초과가 있다
            unknown         측정하지 못한 요인이 있어 단정할 수 없다
            plan_too_tight  전부 측정했고 초과가 없었다 → 계획에 여유가 없었다
        """
        found = self.primary
        if found is not None:
            return found
        if self.unmeasured:
            return "unknown"
        return "plan_too_tight"

    def as_dict(self) -> dict:
        return {
            "event": self.event_id,
            "late_minutes": self.late_minutes,
            "prep_over": self.prep_over,
            "travel_over": self.travel_over,
            "depart_late": self.depart_late,
            "primary": self.primary,
            "label": self.label,
            "unmeasured": self.unmeasured,
        }


@dataclass
class Outcome:
    """지난 일정 하나의 결과. 캘리브레이션과 주간 패턴이 공유한다."""

    event: Event
    tau: float | None
    #: 일정 시작 − 실제 도착 (분). 양수면 여유, 음수면 지각
    slack_minutes: float | None
    depart_at: datetime | None
    arrive_at: datetime | None

    @property
    def has_arrival(self) -> bool:
        return self.arrive_at is not None

    @property
    def on_time(self) -> bool | None:
        if self.slack_minutes is None:
            return None
        return self.slack_minutes >= 0


# ---------------------------------------------------------------------------
# 수집
# ---------------------------------------------------------------------------


def _observations_by_kind(event: Event) -> dict[str, TripObservation]:
    """일정 하나의 출발·도착 관측.

    같은 종류가 여러 건이면 **가장 이른 것**을 쓴다. 출발은 처음 집을 나선
    순간이고 도착은 처음 목적지에 닿은 순간이다. 마지막 것을 쓰면 잠깐 돌아온
    경우에 이동 시간이 부풀려진다.
    """
    found: dict[str, TripObservation] = {}
    for obs in sorted(event.trip_observations.all(), key=lambda o: o.observed_at):
        found.setdefault(obs.kind, obs)
    return found


def collect_outcomes(
    user,
    *,
    since: datetime | None = None,
    until: datetime | None = None,
) -> list[Outcome]:
    """지난 일정의 결과를 모은다.

    **아직 오지 않은 일정은 제외한다.** 결과가 없는 일정을 "지각 아님" 으로
    세면 정시율이 부풀려진다.

    도착 관측이 없는 일정도 목록에 담는다. 캘리브레이션은 제외하지만, "관측이
    없어서 셀 수 없는 아침이 몇 번" 을 보여주는 것이 정직하다.
    """
    now = timezone.now()
    start = since or (now - timedelta(days=DEFAULT_LOOKBACK_DAYS))
    end = min(until or now, now)

    events = (
        Event.objects.filter(user=user, start_at__gte=start, start_at__lte=end)
        .select_related("alarm_plan", "tag", "user__profile")
        .prefetch_related(
            Prefetch(
                "trip_observations",
                queryset=TripObservation.objects.order_by("observed_at"),
            )
        )
        .order_by("start_at")
    )

    outcomes: list[Outcome] = []
    for event in events:
        plan = getattr(event, "alarm_plan", None)
        obs = _observations_by_kind(event)
        arrive = obs.get(TripObservation.Kind.ARRIVE)
        depart = obs.get(TripObservation.Kind.DEPART)

        slack = None
        if arrive is not None:
            slack = round((event.start_at - arrive.observed_at).total_seconds() / 60, 1)

        outcomes.append(
            Outcome(
                event=event,
                # τ 는 계획이 실제로 쓴 값이다. 태그·프로필에서 다시 유도하면
                # 그 사이 설정이 바뀐 경우에 과거를 잘못 채점한다.
                tau=getattr(plan, "tau_used", None),
                slack_minutes=slack,
                depart_at=depart.observed_at if depart else None,
                arrive_at=arrive.observed_at if arrive else None,
            )
        )
    return outcomes


# ---------------------------------------------------------------------------
# 캘리브레이션
# ---------------------------------------------------------------------------


def bucket_for(tau: float) -> tuple[float, float]:
    """τ 가 속한 0.05 구간. 상단 경계는 구간에 포함하지 않는다.

    ## 왜 정수로 계산하는가

    `int((tau - 0.50) / 0.05)` 로 하면 **0.95 가 0.90 버킷에 들어간다.**
    `0.95 - 0.50` 이 이진수로 0.45 보다 미세하게 작게 표현되어 나눗셈 결과가
    8.999999999999998 이 되고 버림에서 8 이 된다. 테스트가 잡아냈다.

    그래서 천분위 정수로 바꿔 계산한다. 버림 전에 아주 작은 값을 더해 표현
    오차만 흡수하고, 반올림이 아니라 **버림**을 써서 "상단 경계 제외" 규칙을
    지킨다 — 반올림하면 0.94999 가 0.95 버킷으로 올라간다.
    """
    clamped = min(max(tau, TAU_MIN), TAU_MAX)
    milli = int(clamped * 1000 + 1e-6)

    lower_milli = int(TAU_MIN * 1000)
    width_milli = int(BUCKET_WIDTH * 1000)
    max_index = (int(TAU_MAX * 1000) - lower_milli) // width_milli - 1

    index = min((milli - lower_milli) // width_milli, max_index)
    lower = (lower_milli + index * width_milli) / 1000
    return lower, (lower_milli + (index + 1) * width_milli) / 1000


def calibration(user, *, since: datetime | None = None) -> dict:
    """τ 버킷별 예측 대비 실제 정시율.

    이상적이면 각 버킷의 중앙값과 실제 비율이 y=x 위에 놓인다. 실제 비율이
    아래에 있으면 **과신**이다 — 약속한 만큼 정시가 아니다.

    빈 버킷은 내리지 않는다. 표본 없는 점을 0 으로 그리면 그래프가 바닥에
    붙어 모델이 완전히 틀린 것처럼 보인다.
    """
    outcomes = collect_outcomes(user, since=since)
    scored = [o for o in outcomes if o.tau is not None and o.on_time is not None]

    buckets: dict[tuple[float, float], CalibrationBucket] = {}
    for outcome in scored:
        key = bucket_for(outcome.tau)
        bucket = buckets.get(key)
        if bucket is None:
            bucket = CalibrationBucket(lower=key[0], upper=key[1], total=0, on_time=0)
            buckets[key] = bucket
        bucket.total += 1
        if outcome.on_time:
            bucket.on_time += 1

    ordered = [buckets[key] for key in sorted(buckets)]
    reliable = [b for b in ordered if b.reliable]

    # 전체 편차. 표본이 있는 버킷만 쓰고, 신뢰 가능한 버킷이 없으면 None 이다.
    gaps = [b.gap for b in reliable if b.gap is not None]
    mean_gap = round(statistics.fmean(gaps), 4) if gaps else None

    return {
        "buckets": [b.as_dict() for b in ordered],
        "scored_count": len(scored),
        # 채점할 수 없었던 지난 일정 수. 도착 관측이 없거나 계획이 없던 경우다.
        "unscored_count": len(outcomes) - len(scored),
        "reliable_bucket_count": len(reliable),
        "min_samples_for_reliable": MIN_BUCKET_SAMPLES,
        "mean_gap": mean_gap,
        # 전체 정시율. 버킷과 달리 τ 를 가리지 않는다
        "on_time_rate": (
            round(sum(1 for o in scored if o.on_time) / len(scored), 4) if scored else None
        ),
        "verdict": _verdict(mean_gap, len(reliable)),
    }


def _verdict(mean_gap: float | None, reliable_buckets: int) -> str:
    """한 줄 판정. 화면이 그대로 쓴다.

    표본이 부족하면 **판정하지 않는다.** "잘 맞음" 을 표본 2건으로 말하면
    사용자가 그 말을 믿고 여유를 줄인다.
    """
    if mean_gap is None or reliable_buckets == 0:
        return "insufficient"
    if mean_gap < -0.10:
        return "overconfident"
    if mean_gap > 0.10:
        return "conservative"
    return "calibrated"


# ---------------------------------------------------------------------------
# 지각 원인
# ---------------------------------------------------------------------------


def lateness_causes(user, *, since: datetime | None = None) -> list[LatenessCause]:
    """지각한 일정마다 원인을 분해한다.

    측정하지 못한 요인은 None 으로 남긴다. 0 으로 채우면 "그 요인은 괜찮았다"
    로 읽혀 사용자가 엉뚱한 곳을 고친다.
    """
    outcomes = [
        o
        for o in collect_outcomes(user, since=since)
        if o.on_time is False and o.slack_minutes is not None
    ]
    if not outcomes:
        return []

    # 그 아침의 블록 관측. 일정별로 묶어 둔다.
    block_minutes: dict[int, float] = {}
    rows = BlockObservation.objects.filter(
        user=user, event_id__in=[o.event.pk for o in outcomes]
    ).values_list("event_id", "duration_minutes", "was_parallel")
    for event_id, duration, was_parallel in rows:
        # 병렬 블록은 계획에서도 합으로 더하지 않는다. 실제에서도 빼야 비교가
        # 같은 기준이 된다.
        if was_parallel:
            continue
        block_minutes[event_id] = block_minutes.get(event_id, 0.0) + float(duration)

    causes: list[LatenessCause] = []
    for outcome in outcomes:
        plan = getattr(outcome.event, "alarm_plan", None)
        cause = LatenessCause(
            event_id=outcome.event.pk,
            late_minutes=int(round(-outcome.slack_minutes)),
        )

        if plan is not None:
            if (
                outcome.depart_at is not None
                and outcome.arrive_at is not None
                and plan.travel_minutes is not None
            ):
                actual = (outcome.arrive_at - outcome.depart_at).total_seconds() / 60
                cause.travel_over = round(actual - plan.travel_minutes, 1)

            if outcome.depart_at is not None and plan.depart_by is not None:
                cause.depart_late = round(
                    (outcome.depart_at - plan.depart_by).total_seconds() / 60, 1
                )

            observed_prep = block_minutes.get(outcome.event.pk)
            if observed_prep is not None and plan.prep_minutes is not None:
                cause.prep_over = round(observed_prep - plan.prep_minutes, 1)

        causes.append(cause)

    return causes


# ---------------------------------------------------------------------------
# 주간 리포트
# ---------------------------------------------------------------------------


def week_bounds(anchor: date | None = None) -> tuple[datetime, datetime]:
    """월요일 00:00 부터 일요일 24:00 까지. 기기 시간대가 아니라 서버 기준이다.

    [anchor] 가 속한 주를 돌려준다. None 이면 **지난 주**다 — 이번 주는 아직
    진행 중이라 리포트가 매 시간 달라지고, 사용자가 "이게 최종인가" 를 알 수 없다.
    """
    today = anchor or (timezone.localdate() - timedelta(days=7))
    monday = today - timedelta(days=today.weekday())
    start = timezone.make_aware(
        datetime.combine(monday, datetime.min.time()),
        timezone.get_current_timezone(),
    )
    return start, start + timedelta(days=7)


def weekly(user, *, anchor: date | None = None) -> dict:
    """주간 리포트.

    저장하지 않고 요청마다 계산한다. 한 주의 일정은 수십 건이라 비용이 작고,
    저장하면 "언제 만들어진 값인가" 와 "계획이 바뀌면 다시 만드나" 라는 문제가
    생긴다. 배치 생성은 사용자가 수천 명이 된 뒤에 필요한 최적화다.
    """
    start, end = week_bounds(anchor)
    outcomes = collect_outcomes(user, since=start, until=end)

    arrived = [o for o in outcomes if o.on_time is not None]
    on_time = [o for o in arrived if o.on_time]
    late = [o for o in arrived if not o.on_time]

    slacks = [o.slack_minutes for o in arrived if o.slack_minutes is not None]

    # 요일별 지각. 월=0. "화요일마다 늦는다" 같은 패턴이 보여야 행동이 바뀐다.
    by_weekday: list[dict] = []
    for index in range(7):
        day_outcomes = [
            o for o in arrived if timezone.localtime(o.event.start_at).weekday() == index
        ]
        day_late = [o for o in day_outcomes if not o.on_time]
        by_weekday.append(
            {
                "weekday": index,
                "total": len(day_outcomes),
                "late": len(day_late),
            }
        )

    causes = [
        c
        for c in lateness_causes(user, since=start)
        if any(o.event.pk == c.event_id for o in late)
    ]

    # 원인별 건수. `unknown` 을 따로 센다 — 측정 실패를 "계획이 짧았다" 로
    # 합치면 사용자가 앱의 계획을 의심하게 되는데, 실제로는 추적이 동작하지
    # 않은 것이다.
    primary_counts: dict[str, int] = {}
    for cause in causes:
        primary_counts[cause.label] = primary_counts.get(cause.label, 0) + 1

    return {
        "week_start": start.date().isoformat(),
        "week_end": (end - timedelta(days=1)).date().isoformat(),
        "event_count": len(outcomes),
        "arrived_count": len(arrived),
        # 결과를 알 수 없는 아침 수. 숨기면 정시율이 실제보다 좋아 보인다.
        "unobserved_count": len(outcomes) - len(arrived),
        "on_time_count": len(on_time),
        "late_count": len(late),
        "on_time_rate": (
            round(len(on_time) / len(arrived), 4) if arrived else None
        ),
        "median_slack_minutes": (
            round(statistics.median(slacks), 1) if slacks else None
        ),
        # 가장 아슬아슬했던 아침. 정시였어도 여유가 1분이면 운이 좋았던 것이다.
        "tightest_slack_minutes": min(slacks) if slacks else None,
        "by_weekday": by_weekday,
        "late_causes": [c.as_dict() for c in causes],
        "primary_counts": primary_counts,
        "calibration": calibration(user, since=start),
    }
