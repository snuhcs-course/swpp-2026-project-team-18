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

수치는 저장하지 않는다. 저장하는 것은 LLM 이 쓴 요약 문장뿐이고 그건
`summary.py`·`models.WeeklySummary` 의 몫이다. 이 모듈은 순수 계산이다.

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

집에서 출발하지 않는 일정(`prep_source == not_from_home`)은 준비 단계가 **해당되지
않는다.** 그 일정의 `prep_over` 는 None 이지만 "측정 못 함" 으로 세지 않는다.

사용자가 "안 감" 으로 표시한 일정(`Event.skipped_at`)은 아예 세지 않는다. 일정 수·
측정 못 함·정시율·캘리브레이션 어디에도 들어가지 않고 `skipped_count` 로만 나간다.
"""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from datetime import timezone as dt_timezone

from django.core.exceptions import ObjectDoesNotExist
from django.db.models import Prefetch
from django.utils import timezone

from apps.events.alarms import alarm_states
from apps.events.models import Event
from apps.observations import selection
from apps.observations.models import TripObservation
from apps.planning import buffers
from apps.planning.estimators import PREP_SOURCE_NOT_FROM_HOME
from apps.planning.models import AlarmPlan, BufferAdjustment
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

# Partly AI-generated (Claude)
# 주간 정시율로 "목표 달성"·"지난주 대비" 를 말하려면 필요한 도착 기록 수.
# 버킷 신뢰 기준과 같은 수를 쓴다 — 같은 3건을 두고 캘리브레이션은 보류하는데
# 주간 판정은 내리면 같은 화면의 두 카드가 서로 다른 말을 한다. 요약 템플릿의
# "기록 적음" 문장과 LLM 허용 기준도 이 값이다(summary.py).
THIN_ARRIVALS = MIN_BUCKET_SAMPLES

# 일정 하나의 결과. 앱 `OutcomeDto.result` 와 같은 값이다.
RESULT_ON_TIME = "on_time"
RESULT_LATE = "late"
RESULT_UNOBSERVED = "unobserved"

# 계획이 실제 기록을 썼는지 판별하는 출처 값(planning.estimators).
# 준비는 사용자 본인의 블록 관측이 있을 때만 "observed" 이고, 이동은 보정이
# 적용되면 "<경로 출처>+observed_route|observed_global" 이다(planning/services.py).
PREP_SOURCE_OBSERVED = "observed"
TRAVEL_SOURCE_OBSERVED_MARK = "+observed"


# Partly AI-generated (Claude)
def half_up(value: float) -> int:
    """반올림. 절댓값을 0.5 올림한 뒤 부호를 붙인다(8.5 → 9, −8.5 → −9).

    파이썬 `round()` 는 은행가 반올림이라 `round(62.5) == 62` 다. 앱의
    `roundToInt()` 는 63 이라, 같은 값을 두고 요약 문장과 화면 타일이 1 씩
    어긋난다. 분·%·%p 를 만드는 곳은 모두 이 함수를 쓴다.

    소수 6자리에서 먼저 정리한다. `(0.6 - 0.375) * 100` 이 22.499999999999996 이
    되는 것처럼 이진 표현 오차가 경계값을 반대쪽으로 넘기기 때문이다.
    """
    magnitude = math.floor(round(abs(value), 6) + 0.5)
    return int(math.copysign(magnitude, value)) if magnitude else 0


# Partly AI-generated (Claude)
def late_minutes(slack: float) -> int:
    """지각 분(양수). 늦었으면 **최소 1분**이다.

    여유 −0.4분을 그냥 반올림하면 0 이 되어 "0분 늦음" 이 나온다. 서버는 그
    아침을 지각으로 세므로 화면·요약도 지각으로 말해야 한다. 지각 줄·타일·요일
    값·상세 화면·요약이 모두 같은 값을 쓴다(앱 `ReportMapping.lateMinutes`).
    """
    return max(1, half_up(-slack))


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


# Partly AI-generated (Claude)
@dataclass
class LatenessCause:
    """지각 한 건의 원인 분해.

    측정하지 못한 값은 **None 이다.** 0 으로 두면 "그 요인은 문제가 없었다" 로
    읽히는데, 사실은 재료가 없어 모른다는 뜻이다.
    """

    event_id: int
    #: 얼마나 늦었나(분). 양수이고 최소 1이다([late_minutes])
    late_minutes: int
    prep_over: float | None = None
    travel_over: float | None = None
    depart_late: float | None = None
    #: 준비 단계가 있는 일정인가. False 면 `prep_over` 가 None 이어도 "측정 못 함"
    #: 이 아니다 — 집에서 출발하지 않는 일정은 준비가 **해당되지 않는다.** 이것을
    #: 측정 못 함으로 세면 전부 잰 지각도 `unknown` 이 되고, 화면이 숨긴 준비를
    #: "측정 안 됨: 준비" 로 적게 된다.
    prep_applies: bool = True

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
        pairs = [("prep_over", self.prep_over)] if self.prep_applies else []
        pairs += [("travel_over", self.travel_over), ("depart_late", self.depart_late)]
        return [name for name, value in pairs if value is None]

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

    GPS 판정이 먼저다. 직접 누른 판정은 GPS 가 없을 때만 쓴다(`selection`).
    """
    observations = list(event.trip_observations.all())
    found: dict[str, TripObservation] = {}
    for kind in TripObservation.Kind.values:
        obs = selection.representative(observations, kind, event)
        if obs is not None:
            found[kind] = obs
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

    **"안 감" 으로 표시한 일정은 뺀다**(`Event.skipped_at`). 안 간 아침은 측정
    실패가 아니고, 그날 관측은 실제 등굣길이 아니다. 버퍼 조정(`planning.buffers`)도
    이 함수를 쓰므로 같이 빠진다. 주간 리포트는 따로 센다(`skipped_count`).
    """
    now = timezone.now()
    start = since or (now - timedelta(days=DEFAULT_LOOKBACK_DAYS))
    end = min(until or now, now)

    events = (
        Event.objects.filter(
            user=user, start_at__gte=start, start_at__lte=end, skipped_at__isnull=True,
        )
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


# Partly AI-generated (Claude)
def _week_outcomes(user, start: datetime, end: datetime) -> list[Outcome]:
    """`[start, end)` 의 지난 일정. **끝을 포함하지 않는다.**

    `collect_outcomes` 는 끝을 포함한다(`start_at__lte`). 주 경계에 그대로 쓰면
    다음 주 월요일 00:00 에 시작하는 일정이 두 주에 다 들어간다. 1µs 앞에서 끊는다.
    """
    return collect_outcomes(user, since=start, until=end - timedelta(microseconds=1))


# Partly AI-generated (Claude)
def _ok_plan(event: Event) -> AlarmPlan | None:
    """계산이 끝난 계획. 없거나 OK 가 아니면 None — 그런 계획은 값이 전부 null 이다."""
    plan = getattr(event, "alarm_plan", None)
    if plan is None or plan.status != AlarmPlan.Status.OK:
        return None
    return plan


# Partly AI-generated (Claude)
def _prep_applies(event: Event) -> bool | None:
    """준비 단계가 있는 일정인가. 계획이 없으면 모른다(None)."""
    plan = _ok_plan(event)
    if plan is None:
        return None
    return plan.prep_source != PREP_SOURCE_NOT_FROM_HOME


# Partly AI-generated (Claude)
def _block_minutes(user, event_ids: list[int]) -> dict[int, float]:
    """일정별 블록 관측 합(분). 병렬 블록은 뺀다.

    병렬 블록은 계획에서도 합으로 더하지 않는다. 실제에서도 빼야 비교가 같은
    기준이 된다. 관측이 없는 일정은 키가 없다 — 0 이 아니다.
    """
    if not event_ids:
        return {}
    totals: dict[int, float] = {}
    rows = BlockObservation.objects.filter(user=user, event_id__in=event_ids).values_list(
        "event_id", "duration_minutes", "was_parallel"
    )
    for event_id, duration, was_parallel in rows:
        if was_parallel:
            continue
        totals[event_id] = totals.get(event_id, 0.0) + float(duration)
    return totals


# Partly AI-generated (Claude)
def _measure(outcome: Outcome, block_minutes: dict[int, float]) -> dict[str, float | None]:
    """계획 대비 실제 세 값. 잴 수 없으면 None 이다(0 이 아니다).

    정의는 모듈 머리말의 표 그대로다. 준비가 해당되지 않는 일정
    (`not_from_home`)은 블록 관측이 있어도 준비를 재지 않는다.
    """
    values: dict[str, float | None] = {
        "prep_over": None,
        "travel_over": None,
        "depart_late": None,
    }
    plan = _ok_plan(outcome.event)
    if plan is None:
        return values

    if (
        outcome.depart_at is not None
        and outcome.arrive_at is not None
        and plan.travel_minutes is not None
    ):
        actual = (outcome.arrive_at - outcome.depart_at).total_seconds() / 60
        values["travel_over"] = round(actual - plan.travel_minutes, 1)

    if outcome.depart_at is not None and plan.depart_by is not None:
        values["depart_late"] = round(
            (outcome.depart_at - plan.depart_by).total_seconds() / 60, 1
        )

    observed_prep = block_minutes.get(outcome.event.pk)
    if (
        plan.prep_source != PREP_SOURCE_NOT_FROM_HOME
        and observed_prep is not None
        and plan.prep_minutes is not None
    ):
        values["prep_over"] = round(observed_prep - plan.prep_minutes, 1)

    return values


# Partly AI-generated (Claude)
def _cause(outcome: Outcome, measured: dict[str, float | None]) -> LatenessCause:
    return LatenessCause(
        event_id=outcome.event.pk,
        late_minutes=late_minutes(outcome.slack_minutes),
        prep_applies=_prep_applies(outcome.event) is not False,
        **measured,
    )


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
    return calibration_from(collect_outcomes(user, since=since))


# Partly AI-generated (Claude)
def calibration_from(outcomes: list[Outcome]) -> dict:
    """이미 모은 결과로 캘리브레이션을 계산한다.

    주간 리포트는 **그 주의 일정만** 넘긴다. 예전에는 `calibration(user,
    since=주 시작)` 을 불러 끝이 "지금" 이 됐고, 과거 주를 보면 그 뒤 주의
    일정까지 그 주 캘리브레이션에 섞였다.
    """
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


# Partly AI-generated (Claude)
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

    blocks = _block_minutes(user, [o.event.pk for o in outcomes])
    return [_cause(outcome, _measure(outcome, blocks)) for outcome in outcomes]


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


# Partly AI-generated (Claude)
def weekly(user, *, anchor: date | None = None) -> dict:
    """주간 리포트.

    저장하지 않고 요청마다 계산한다. 한 주의 일정은 수십 건이라 비용이 작고,
    저장하면 "언제 만들어진 값인가" 와 "계획이 바뀌면 다시 만드나" 라는 문제가
    생긴다. 배치 생성은 사용자가 수천 명이 된 뒤에 필요한 최적화다.

    한 번 모아 나눠 쓴다 — 그 주 결과·블록 관측·알람 상태를 한 번씩 읽고 원인
    분해·캘리브레이션·일정별 행이 같은 값을 쓴다. 줄글 요약은 여기서 만들지 않고
    뷰가 붙인다(`summary.summary_for`). 그래서 이 함수의 테스트는 LLM 과 무관하다.

    **좌표·장소·주소는 내리지 않는다**(N19). 일정 제목은 본인 일정이라 내린다.
    """
    start, end = week_bounds(anchor)
    outcomes = _week_outcomes(user, start, end)
    blocks = _block_minutes(user, [o.event.pk for o in outcomes])
    alarms = _alarm_flags(user) if outcomes else {}
    measured = {o.event.pk: _measure(o, blocks) for o in outcomes}

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

    causes = {o.event.pk: _cause(o, measured[o.event.pk]) for o in late}

    # 원인별 건수. `unknown` 을 따로 센다 — 측정 실패를 "계획이 짧았다" 로
    # 합치면 사용자가 앱의 계획을 의심하게 되는데, 실제로는 추적이 동작하지
    # 않은 것이다.
    primary_counts: dict[str, int] = {}
    for cause in causes.values():
        primary_counts[cause.label] = primary_counts.get(cause.label, 0) + 1

    on_time_rate = round(len(on_time) / len(arrived), 4) if arrived else None

    # 목표 = 도착 기록이 있는 일정들이 계획에 쓴 τ 의 평균. 판정은 반올림한 %
    # 끼리 한다 — 그래야 "정시율 92%로 목표 92%에 못 미쳤음" 이 나오지 않는다.
    taus = [o.tau for o in arrived if o.tau is not None]
    target_rate = round(statistics.fmean(taus), 4) if taus else None
    target_met = None
    if on_time_rate is not None and target_rate is not None and len(arrived) >= THIN_ARRIVALS:
        target_met = half_up(on_time_rate * 100) >= half_up(target_rate * 100)

    # 끝난 주인가. 다음 주 월요일 00:00 이 지났는가와 **같은 값**이다 — 화면이
    # 쓰는 곳이 달라(요약의 "진행 중" 표시, 주 전환 "›") 이름을 나눠 둔다.
    complete = timezone.now() >= end

    return {
        "week_start": start.date().isoformat(),
        "week_end": (end - timedelta(days=1)).date().isoformat(),
        "event_count": len(outcomes),
        "arrived_count": len(arrived),
        # 결과를 알 수 없는 아침 수. 숨기면 정시율이 실제보다 좋아 보인다.
        "unobserved_count": len(outcomes) - len(arrived),
        # "안 감" 으로 표시한 지난 일정 수. 위 모든 수에서 빠져 있다.
        "skipped_count": _skipped_count(user, start, end),
        "on_time_count": len(on_time),
        "late_count": len(late),
        "on_time_rate": on_time_rate,
        "median_slack_minutes": (
            round(statistics.median(slacks), 1) if slacks else None
        ),
        # 가장 아슬아슬했던 아침. 정시였어도 여유가 1분이면 운이 좋았던 것이다.
        "tightest_slack_minutes": min(slacks) if slacks else None,
        "by_weekday": by_weekday,
        "late_causes": [causes[o.event.pk].as_dict() for o in late],
        "primary_counts": primary_counts,
        # 그 주 일정만으로 계산한다(`calibration_from` 주석).
        "calibration": calibration_from(outcomes),
        "week_complete": complete,
        "has_next_week": complete,
        "target_rate": target_rate,
        "target_met": target_met,
        "previous_week": _previous_week(user, start, on_time_rate, len(arrived)),
        "deviation": _deviation_stats(list(measured.values())),
        "learning": _learning(outcomes),
        "buffer": _buffer(user, start, end),
        "outcomes": [
            _outcome_row(o, measured[o.event.pk], causes.get(o.event.pk), alarms, blocks)
            for o in outcomes
        ],
    }


# Partly AI-generated (Claude)
def _skipped_count(user, start: datetime, end: datetime) -> int:
    """`[start, end)` 의 지난 일정 중 "안 감" 으로 표시한 수. 기준은 `collect_outcomes` 와 같다."""
    return Event.objects.filter(
        user=user,
        start_at__gte=start,
        start_at__lt=end,
        start_at__lte=timezone.now(),
        skipped_at__isnull=False,
    ).count()


# Partly AI-generated (Claude)
def _alarm_flags(user) -> dict[int, bool] | None:
    """일정 id → 알람이 울리는 일정인가. `events.alarms.alarm_states` 를 읽기만 한다.

    출발·도착 기록은 알람을 해제할 때만 시작된다(앱 `AlarmActivity`). 알람이 없던
    일정은 원래 기록될 수 없으므로 리포트가 그것을 "측정 실패" 로 말하면 안 된다.

    **지금 설정 기준이다.** 지난 일정의 켬/끔을 나중에 바꿨으면 바뀐 값이 나온다.
    알람 상호작용 기록이 따로 없어서 이것이 가장 가까운 근사다.

    판정할 수 없으면(프로필이 없는 오래된 계정) None — 모두 "꺼짐" 으로 보면 측정
    공백을 알람 탓으로 잘못 돌린다.
    """
    try:
        states = alarm_states(user)
    except ObjectDoesNotExist:
        return None
    return {event_id: on for event_id, (on, _first) in states.items()}


# Partly AI-generated (Claude)
def _previous_week(
    user, start: datetime, this_rate: float | None, this_arrived: int
) -> dict | None:
    """앞 주의 정시 집계. 앞 주에 지난 일정이 없으면 None.

    `on_time_rate_change_pp` 는 두 주 모두 도착 기록이 [THIN_ARRIVALS] 건 이상일
    때만 낸다. 1건짜리 주와 비교한 "%p 올랐음" 은 우연을 추세로 보이게 한다.
    """
    prev_start, prev_end = week_bounds(timezone.localtime(start).date() - timedelta(days=7))
    outcomes = _week_outcomes(user, prev_start, prev_end)
    if not outcomes:
        return None

    arrived = [o for o in outcomes if o.on_time is not None]
    on_time = sum(1 for o in arrived if o.on_time)
    rate = round(on_time / len(arrived), 4) if arrived else None

    change = None
    if (
        rate is not None
        and this_rate is not None
        and len(arrived) >= THIN_ARRIVALS
        and this_arrived >= THIN_ARRIVALS
    ):
        change = half_up((this_rate - rate) * 100)

    return {
        "week_start": prev_start.date().isoformat(),
        "event_count": len(outcomes),
        "arrived_count": len(arrived),
        "on_time_count": on_time,
        "on_time_rate": rate,
        "on_time_rate_change_pp": change,
    }


# Partly AI-generated (Claude)
def _deviation_stats(rows: list[dict[str, float | None]]) -> dict:
    """계획 대비 실제의 주간 평균·표준편차. 그 주 **모든** 일정 중 잰 것만 쓴다.

    표본이 없으면 평균도 None 이다. 표준편차는 2건부터 낸다(모표준편차) — 1건의
    편차 0 은 "흔들림이 없다" 가 아니라 "모른다" 다.
    """
    out = {}
    for key in ("prep_over", "depart_late", "travel_over"):
        values = [row[key] for row in rows if row[key] is not None]
        out[key] = {
            "count": len(values),
            "mean_minutes": round(statistics.fmean(values), 1) if values else None,
            "sd_minutes": round(statistics.pstdev(values), 1) if len(values) >= 2 else None,
        }
    return out


# Partly AI-generated (Claude)
def _learning(outcomes: list[Outcome]) -> dict:
    """이 주 계획이 실제 기록을 썼는지 센다.

    `prediction.ModelArtifact` 는 알람 계산이 읽지 않는다. 아티팩트를 "학습 반영"
    으로 보이면 사실이 아니어서, 대신 **각 계획이 실제로 쓴 출처**를 센다.
    이동 보정(`RouteCorrection`)은 모든 사용자의 기록으로 만든다 — 그래서 앱은
    이동 쪽을 "내 기록" 이 아니라 "이동 기록 보정" 이라고 쓴다.
    """
    plans = [plan for plan in (_ok_plan(o.event) for o in outcomes) if plan is not None]
    return {
        "plan_count": len(plans),
        "prep_from_records": sum(1 for p in plans if p.prep_source == PREP_SOURCE_OBSERVED),
        "travel_from_records": sum(
            1 for p in plans if TRAVEL_SOURCE_OBSERVED_MARK in (p.travel_source or "")
        ),
    }


# Partly AI-generated (Claude)
def _buffer(user, start: datetime, end: datetime) -> dict:
    """지금 버퍼와 이 주에 한 버퍼 조정(`planning.buffers`). 조정마다 이유 문장을 붙인다.

    `current_*` 는 이 주가 아니라 **지금** 값이다. 지난 주를 열어도 지금 버퍼가 나온다.
    """
    now = buffers.current(user.pk)
    adjustments = BufferAdjustment.objects.filter(
        user=user, created_at__gte=start, created_at__lt=end
    ).order_by("created_at", "id")
    return {
        "current_minutes": now.minutes,
        "current_source": now.source,
        "adjustments": [
            {
                "adjusted_at": _iso(row.created_at),
                "previous_minutes": row.previous_minutes,
                "minutes": row.minutes,
                "reason": row.reason,
                "reason_text": buffers.reason_text(row),
                "sample_count": row.sample_count,
                "late_count": row.late_count,
                "expected_late": row.expected_late,
                "mean_slack_minutes": row.mean_slack_minutes,
                "tightest_slack_minutes": row.tightest_slack_minutes,
            }
            for row in adjustments
        ],
    }


# Partly AI-generated (Claude)
def _iso(value: datetime | None) -> str | None:
    """UTC ISO 8601, 끝은 `Z`. 다른 API 와 같이 로컬 변환은 앱 몫이다."""
    if value is None:
        return None
    return value.astimezone(dt_timezone.utc).isoformat().replace("+00:00", "Z")


# Partly AI-generated (Claude)
def _outcome_row(
    outcome: Outcome,
    measured: dict[str, float | None],
    cause: LatenessCause | None,
    alarms: dict[int, bool] | None,
    blocks: dict[int, float],
) -> dict:
    """지난 일정 하나의 계획 대비 실제. 앱 ⑨ 상세 리포트가 그대로 그린다.

    정시·지각·원인·τ 구간은 **서버가 판정한다.** 앱은 받은 값을 표시만 한다.
    계획이 없거나 OK 가 아니면 `planned` 객체는 두고 값만 전부 null 이다.
    """
    event = outcome.event
    plan = _ok_plan(event)
    local = timezone.localtime(event.start_at)

    if outcome.on_time is None:
        result = RESULT_UNOBSERVED
    elif outcome.on_time:
        result = RESULT_ON_TIME
    else:
        result = RESULT_LATE

    travel_actual = None
    if outcome.depart_at is not None and outcome.arrive_at is not None:
        travel_actual = round((outcome.arrive_at - outcome.depart_at).total_seconds() / 60, 1)
    observed_prep = blocks.get(event.pk)

    return {
        "event": event.pk,
        "title": event.title,
        "start_at": _iso(event.start_at),
        # 날짜·요일은 서버 시간대(Asia/Seoul) 기준이다. 주 경계와 같은 기준이어야
        # 앱이 이 일정을 맞는 요일 칸에 넣는다.
        "date": local.date().isoformat(),
        "weekday": local.weekday(),
        "result": result,
        "slack_minutes": outcome.slack_minutes,
        "on_time_probability": plan.on_time_probability if plan else None,
        "tau": outcome.tau,
        "tau_bucket_lower": bucket_for(outcome.tau)[0] if outcome.tau is not None else None,
        "prep_applies": _prep_applies(event),
        # 알람 판정에 빠진 일정(계획 실패·명시적 끔)은 울리지 않은 것이다.
        "alarm_on": None if alarms is None else alarms.get(event.pk, False),
        "planned": {
            "alarm_at": _iso(plan.alarm_at) if plan else None,
            "depart_by": _iso(plan.depart_by) if plan else None,
            "arrive_at": _iso(plan.arrive_at) if plan else None,
            "prep_minutes": plan.prep_minutes if plan else None,
            "travel_minutes": plan.travel_minutes if plan else None,
            "buffer_minutes": plan.buffer_minutes if plan else None,
        },
        "actual": {
            "depart_at": _iso(outcome.depart_at),
            "arrive_at": _iso(outcome.arrive_at),
            "prep_minutes": round(observed_prep, 1) if observed_prep is not None else None,
            "travel_minutes": travel_actual,
        },
        "deviation": dict(measured),
        "cause": cause.label if cause is not None else None,
        "unmeasured": cause.unmeasured if cause is not None else [],
    }
