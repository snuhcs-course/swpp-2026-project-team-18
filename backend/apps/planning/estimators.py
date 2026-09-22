"""준비 시간·이동 시간 분포 추정. front-spec.md 4.2 의 3~4단계.

## 확신도를 말할 수 있는 조건

이 파일의 가장 중요한 결정이다. **변동성을 모르면 확률을 말하지 않는다.**

| 성분 | 변동성의 출처 | 없을 때 |
| --- | --- | --- |
| 준비 | 사용자가 신고한 블록 범위(min~max) + 블록 관측 | sd=0 (점추정) |
| 이동 | `RouteCorrection` (관측된 예측 대비 실제) | sd=0 (점추정) |

`sd=0` 이면 τ 를 올려도 값이 변하지 않는다. 그게 정직한 동작이다.

**확률은 두 성분 모두 변동성이 있을 때만 계산한다.** 준비만 변동성이 있는
상태에서 확률을 내면 이동의 불확실성을 0 으로 치므로 **확신도를 과대
보고한다.** "92% 정시 도착" 이 실제로는 "이동이 예측대로라면 92%" 인데
그 조건이 화면에 없다. 그래서 둘 다 갖출 때까지 null 을 유지한다.

## 블록 범위를 분포로 바꾸는 규칙

사용자가 "샤워 12~18분" 이라고 답하면 그것은 **사용자가 신고한 변동성**이다.
없는 값을 만들어내는 것이 아니다. 범위를 약 95% 구간으로 보고

    mean = (min + max) / 2
    sd   = (max - min) / 4

로 둔다. 정규분포의 ±2σ 가 95% 를 덮으므로 범위 폭이 4σ 다. 관측이 쌓이면
`blend` 가 실측 쪽으로 끌어당긴다.

범위가 한 점이면(min == max) sd=0 이다. 사용자가 "정확히 10분" 이라고
말한 것이므로 변동성을 붙이지 않는다.

## 임계경로

병렬 블록은 합이 아니라 max 로 들어간다. 세탁기를 돌리며 샤워하면 총
시간은 둘 중 긴 쪽이다. 선행 블록이 있는 병렬 블록은 그 시점부터 시작하므로
`선행까지의 누적 + 자기 소요` 가 끝나는 시각이다.

    총 준비 = max( 직렬 블록의 합,  각 병렬 블록의 완료 시점 )
"""

from __future__ import annotations

import logging
import random
from dataclasses import dataclass, field
from datetime import datetime

from django.db.models import QuerySet

from apps.routines.models import RoutineBlock

from . import distributions as dist

logger = logging.getLogger(__name__)

# 사용자가 신고한 범위를 몇 σ 로 볼지. 4 는 범위를 95% 구간으로 보는 것이다.
RANGE_TO_SD_DIVISOR = 4.0

# 블록 관측이 이만큼 쌓이면 개인 실측이 신고 범위를 대체하기 시작한다.
# back-spec 6.1 S2 의 k.
BLOCK_SHRINKAGE_K = 5

# 몬테카를로 시드. 같은 입력이 같은 알람을 내도록 고정한다. 알람 시각이
# 재계산마다 1~2분씩 흔들리면 사용자가 신뢰를 잃는다.
MONTE_CARLO_SEED = 20260915

# 이동 보정 표본이 이만큼 모이면 경로 전용 값을 주로 믿는다.
ROUTE_SHRINKAGE_K = 5


@dataclass
class PrepEstimate:
    """준비 시간 추정 결과."""

    distribution: dist.Distribution
    # 계산에 쓴 블록 수. 0 이면 온보딩 값으로 떨어진 것이다.
    block_count: int = 0
    # 변동성의 근거. 화면이 "실측" / "신고값" / "고정값" 을 구분해 표시한다.
    source: str = "fixed"
    # 블록별 내역. 근거 카드와 "무엇을 버릴까" 판단에 쓴다.
    breakdown: list[dict] = field(default_factory=list)

    @property
    def has_variance(self) -> bool:
        return self.distribution.quantile(0.99) > self.distribution.quantile(0.5) + 1e-9


@dataclass
class TravelEstimate:
    """이동 시간 추정 결과."""

    distribution: dist.Distribution
    # 카카오가 준 원래 점추정치(분). 보정 전 값이다.
    raw_minutes: float = 0.0
    factor: float = 1.0
    source: str = "kakao"
    sample_count: int = 0

    @property
    def has_variance(self) -> bool:
        return self.distribution.quantile(0.99) > self.distribution.quantile(0.5) + 1e-9


# ---------------------------------------------------------------------------
# 준비 시간
# ---------------------------------------------------------------------------


def block_distribution(
    block: RoutineBlock,
    observation_mean: float | None = None,
    observation_sd: float | None = None,
    observation_count: int = 0,
) -> dist.Distribution:
    """블록 하나의 소요 시간 분포.

    신고 범위를 사전값으로 두고, 관측이 있으면 shrinkage 로 섞는다.
    관측 5회에서 개인:신고 = 50:50 이 된다(back-spec 6.1 S2).
    """
    lo = float(block.default_min_minutes)
    hi = float(block.default_max_minutes)
    if hi < lo:
        # DB 제약이 막지만 방어한다. 뒤집힌 범위로 음수 sd 를 만들면
        # Normal 이 예외를 던져 알람 계산 전체가 실패한다.
        logger.warning("블록 %s 의 범위가 뒤집혔다: %s~%s", block.pk, lo, hi)
        lo, hi = hi, lo

    declared = dist.Normal(
        mean_min=(lo + hi) / 2.0,
        sd_min=(hi - lo) / RANGE_TO_SD_DIVISOR,
    )

    if observation_count <= 0 or observation_mean is None:
        return declared

    # 관측 표준편차가 없거나 표본이 1건이면 신고 범위의 폭을 쓴다.
    # 표본 1건으로 sd 를 계산하면 0 이 나오고, 그러면 "한 번 재봤으니
    # 변동성이 없다" 는 잘못된 결론이 된다.
    obs_sd = observation_sd if (observation_sd and observation_count >= 2) else declared.sd_min
    observed = dist.Normal(mean_min=float(observation_mean), sd_min=float(obs_sd))
    return dist.blend(observed, declared, observation_count, k=BLOCK_SHRINKAGE_K)


def _selected_blocks(event, blocks: QuerySet[RoutineBlock] | None = None) -> list[RoutineBlock]:
    """이 일정에 포함되는 블록 목록.

    `EventBlockSelection` 에 행이 있으면 그 값을, 없으면
    `included_by_default` 를 따른다.
    """
    if blocks is None:
        blocks = RoutineBlock.objects.filter(user_id=event.user_id).order_by("order", "id")
    blocks = list(blocks)
    if not blocks:
        return []

    overrides = {
        sel.block_id: sel.checked for sel in event.block_selections.all()
    }
    return [b for b in blocks if overrides.get(b.pk, b.included_by_default)]


def _parallel_finish(
    block: RoutineBlock,
    own: dist.Distribution,
    serial_prefix: dict[int, dist.Distribution],
    rng: random.Random,
) -> dist.Distribution:
    """병렬 블록의 완료 시점 분포.

    선행 블록이 **직렬 사슬 안에 있으면** 그 시점 뒤에 시작한다. 그렇지
    않으면(선행이 없거나, 선행도 병렬이거나, 이 일정에서 제외됐으면) 아침
    시작과 동시에 시작한다고 본다.

    병렬→병렬 사슬은 따라가지 않는다. 편집기에서 만들 수 없는 조합이고,
    만들어져도 이 근사는 완료 시점을 **과소평가하지 않는다** — 직렬 누적이
    시작 시점의 하한이므로 알람이 늦어지는 방향으로 틀리지 않는다.
    """
    pre_id = block.precondition_id
    if pre_id is not None and pre_id in serial_prefix:
        return dist.convolve(serial_prefix[pre_id], own, rng=rng)
    return own


def estimate_prep(
    event,
    profile,
    fallback_minutes: int,
    observations: dict[int, tuple[float, float, int]] | None = None,
) -> PrepEstimate:
    """준비 시간 분포를 만든다.

    `observations` 는 `{block_id: (평균, 표준편차, 표본수)}` 다. 호출부가
    한 번에 조회해 넘긴다 — 블록마다 쿼리를 날리면 N+1 이 된다.
    """
    obs = observations or {}
    blocks = _selected_blocks(event)

    if not blocks:
        # 블록이 없으면 온보딩 값 하나뿐이다. 값 하나에서 변동성을 만들 수
        # 없으므로 sd=0 이다. τ 를 올려도 준비 시간이 변하지 않는다.
        minutes = profile.onboarding_prep_min or fallback_minutes
        return PrepEstimate(
            distribution=dist.Normal(float(minutes), 0.0),
            block_count=0,
            source="onboarding" if profile.onboarding_prep_min else "fixed",
            breakdown=[],
        )

    rng = random.Random(MONTE_CARLO_SEED)

    dists: dict[int, dist.Distribution] = {}
    breakdown: list[dict] = []
    has_observed = False
    for b in blocks:
        mean, sd, n = obs.get(b.pk, (None, None, 0))
        if n > 0:
            has_observed = True
        d = block_distribution(b, mean, sd, n)
        dists[b.pk] = d
        breakdown.append(
            {
                "block_id": b.pk,
                "name": b.name,
                "minutes": round(d.mean, 1),
                "declared_min": b.default_min_minutes,
                "declared_max": b.default_max_minutes,
                "parallelizable": b.parallelizable,
                "drop_cost": b.drop_cost,
                "observation_count": n,
                "source": "observed" if n > 0 else "declared",
            }
        )

    serial = [b for b in blocks if not b.parallelizable]
    parallel = [b for b in blocks if b.parallelizable]

    # 직렬 누적. serial_prefix[block_id] = 이 블록까지 끝난 시점의 분포.
    serial_total: dist.Distribution = dist.Normal(0.0, 0.0)
    serial_prefix: dict[int, dist.Distribution] = {}
    for b in serial:
        serial_total = dist.convolve(serial_total, dists[b.pk], rng=rng)
        serial_prefix[b.pk] = serial_total

    total = serial_total
    for b in parallel:
        finish = _parallel_finish(b, dists[b.pk], serial_prefix, rng)
        total = dist.max_of(total, finish, rng=rng)

    # 블록 범위가 전부 한 점이면 sd=0 이다. 그건 사용자가 변동성이 없다고
    # 말한 것이므로 그대로 둔다.
    declared_variance = any(
        b.default_max_minutes > b.default_min_minutes for b in blocks
    )
    if has_observed:
        source = "observed"
    elif declared_variance:
        source = "declared_range"
    else:
        source = "declared_point"

    return PrepEstimate(
        distribution=total,
        block_count=len(blocks),
        source=source,
        breakdown=breakdown,
    )


# ---------------------------------------------------------------------------
# 이동 시간
# ---------------------------------------------------------------------------


def route_signature(route_key: str) -> str:
    """경로 key 에서 보정 조회용 시그니처를 만든다.

    `route_key` 는 `transit:2호선>5513` 처럼 수단과 노선을 담는다. 시간
    버킷은 들어 있지 않으므로 그대로 쓴다.
    """
    return (route_key or "").strip()[:120]


def _lookup_correction(signature: str, mode: str, when: datetime):
    """가장 구체적인 보정부터 찾아 (경로별, 전역) 쌍으로 돌려준다."""
    from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection

    hour = when.hour if when else -1
    weekday = (
        RouteCorrection.WeekdayType.WEEKEND
        if when and when.weekday() >= 5
        else RouteCorrection.WeekdayType.WEEKDAY
    )

    def pick(sig: str):
        qs = RouteCorrection.objects.filter(route_signature=sig)
        # 시간대·요일이 맞는 것 → 요일만 맞는 것 → 아무거나. 구체적인 순서다.
        for flt in (
            {"mode": mode, "hour_bucket": hour, "weekday_type": weekday},
            {"mode": mode, "hour_bucket": -1, "weekday_type": weekday},
            {"mode": mode, "hour_bucket": -1, "weekday_type": RouteCorrection.WeekdayType.ANY},
            {"hour_bucket": -1, "weekday_type": RouteCorrection.WeekdayType.ANY},
        ):
            found = qs.filter(**flt).first()
            if found is not None:
                return found
        return None

    specific = pick(signature) if signature else None
    global_ = pick(GLOBAL_SIGNATURE)
    return specific, global_


def estimate_travel(
    minutes: float,
    route_key: str,
    mode: str,
    depart_at: datetime | None,
) -> TravelEstimate:
    """이동 시간 분포를 만든다.

    카카오 점추정치에 관측된 보정을 적용한다. 보정이 없으면 sd=0 으로 두어
    τ 가 이동 시간을 움직이지 않게 한다 — 변동성을 모르는데 움직이면 거짓이다.
    """
    raw = float(minutes)
    signature = route_signature(route_key)
    specific, global_ = _lookup_correction(signature, mode or "", depart_at)

    if specific is None and global_ is None:
        return TravelEstimate(
            distribution=dist.Normal(raw, 0.0),
            raw_minutes=raw,
            factor=1.0,
            source="kakao",
            sample_count=0,
        )

    # 경로 전용이 있으면 전역과 shrinkage 로 섞는다. 표본 3건짜리 경로 값을
    # 그대로 믿으면 우연히 막혔던 하루가 알람을 크게 앞당긴다.
    if specific is not None and global_ is not None:
        n = specific.sample_count
        w = n / (n + ROUTE_SHRINKAGE_K)
        factor = w * specific.factor + (1 - w) * global_.factor
        # 표준편차는 분산으로 섞는다.
        var = w * specific.sd_minutes**2 + (1 - w) * global_.sd_minutes**2
        sd = var**0.5
        count = n + global_.sample_count
        source = "observed_route"
    elif specific is not None:
        factor, sd, count = specific.factor, specific.sd_minutes, specific.sample_count
        source = "observed_route"
    else:
        factor, sd, count = global_.factor, global_.sd_minutes, global_.sample_count
        source = "observed_global"

    return TravelEstimate(
        distribution=dist.Normal(raw * factor, sd),
        raw_minutes=raw,
        factor=factor,
        source=source,
        sample_count=count,
    )


# ---------------------------------------------------------------------------
# 합성
# ---------------------------------------------------------------------------


@dataclass
class AlarmMath:
    """알람 계산 결과. 분 단위 값과 확률을 담는다."""

    prep_minutes: int
    travel_minutes: int
    buffer_minutes: int
    total_minutes: int
    tau_used: float
    # 둘 다 변동성이 있을 때만 채운다. 없으면 None.
    on_time_probability: int | None
    prep_source: str
    travel_source: str
    confidence_basis: str
    prep_breakdown: list[dict]


def compute_alarm_math(
    prep: PrepEstimate,
    travel: TravelEstimate,
    buffer_minutes: int,
    tau: float,
) -> AlarmMath:
    """준비·이동 분포를 합성해 알람에 필요한 분 수와 확률을 낸다.

    합성한 분포의 τ 분위수를 쓴다. **준비의 τ 분위수와 이동의 τ 분위수를
    따로 구해 더하면 안 된다** — 그러면 둘이 동시에 최악인 경우를 가정하게
    되어 실제보다 훨씬 이른 알람이 나온다. 예를 들어 τ=0.9 두 개를 더하면
    결합 확신도는 0.9 가 아니라 0.99 에 가깝다.

    확률은 `P(준비 + 이동 <= 확보한 시간)` 이다. 확보한 시간은
    τ 분위수 + 버퍼이므로 확률은 τ 보다 크다 — 버퍼가 확신도를 더 사준다.
    """
    rng = random.Random(MONTE_CARLO_SEED)
    total_dist = dist.convolve(prep.distribution, travel.distribution, rng=rng)

    q = total_dist.quantile(tau)
    budget = q + buffer_minutes

    both_vary = prep.has_variance and travel.has_variance
    if both_vary:
        probability = int(round(total_dist.cdf(budget) * 100))
        probability = max(0, min(100, probability))
        basis = "observed"
    else:
        probability = None
        if not prep.has_variance and not travel.has_variance:
            basis = "point_estimate"
        elif not travel.has_variance:
            basis = "travel_variance_unknown"
        else:
            basis = "prep_variance_unknown"

    # 분 단위로 쪼개 표시한다. 합성 분위수를 준비·이동 비율로 나눈다 —
    # 합성 후의 τ 분위수는 각 성분의 τ 분위수 합이 아니므로, 내역은 비율로
    # 안분해야 합계가 맞는다.
    prep_mean = prep.distribution.mean
    travel_mean = travel.distribution.mean
    denom = prep_mean + travel_mean
    if denom > 0:
        prep_part = q * (prep_mean / denom)
        travel_part = q * (travel_mean / denom)
    else:
        prep_part = travel_part = q / 2

    prep_min = int(round(prep_part))
    travel_min = int(round(travel_part))
    # 반올림 오차를 이동 쪽에 흡수시켜 합계가 정확히 맞게 한다.
    total_min = int(round(q)) + buffer_minutes
    travel_min = max(0, total_min - buffer_minutes - prep_min)

    return AlarmMath(
        prep_minutes=prep_min,
        travel_minutes=travel_min,
        buffer_minutes=buffer_minutes,
        total_minutes=total_min,
        tau_used=tau,
        on_time_probability=probability,
        prep_source=prep.source,
        travel_source=travel.source,
        confidence_basis=basis,
        prep_breakdown=prep.breakdown,
    )
