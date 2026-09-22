"""분포 표현과 합성. front-spec.md 4.3 / back-spec.md 6절.

## 왜 분포인가

제품이 파는 것은 알람 시각이 아니라 **도착 확신도**다. "07:40에 일어나라"가
아니라 "이 시각에 일어나면 90% 확률로 늦지 않는다"를 말해야 한다. 그러려면
준비 시간과 이동 시간을 점추정치가 아니라 분포로 들고 있어야 한다.

τ(타우)는 사용자가 고르는 확신도다. τ=0.90이면 준비+이동 소요의 90번째
백분위를 쓴다. τ를 올리면 알람이 빨라지고(잠을 잃고) 지각이 줄어든다.
이 교환비를 사용자가 직접 만지는 것이 이 앱의 핵심 개념이다.

## numpy 를 쓰지 않는 이유

`requirements/dev.txt` 에 "numpy 는 P2 에서 필요"라고 적어 두었으나 넣지 않았다.

1. 정규분포끼리의 합성은 **해석적으로 정확히** 풀린다. 몬테카를로가 필요 없다.
   N(μ₁,σ₁²) + N(μ₂,σ₂²) = N(μ₁+μ₂, σ₁²+σ₂²) — 독립 가정 하에.
2. 다봉형(Mixture)이 섞일 때만 수치해가 필요한데, 분위수는 CDF 이분법으로
   구한다. 몬테카를로보다 **표본 오차가 없다.** 같은 입력에 항상 같은 값이
   나오므로 테스트가 흔들리지 않는다.
3. 무료 Render 인스턴스에서 빌드 시간과 메모리를 아낀다. 의존성이 하나 줄면
   공급망 위험도 그만큼 줄어든다.

`statistics.NormalDist` 로 정규분포의 CDF·역CDF를 정확히 얻는다(stdlib).

## 음수 시간 처리

준비·이동 시간은 음수가 될 수 없다. 정규분포는 꼬리가 음수로 열려 있어
엄밀히는 절단정규분포(truncated normal)를 써야 한다.

**절단하지 않고 결과만 0에서 자른다.** 근거는 우리가 쓰는 구간이다. τ는
0.5~0.999 범위이므로(back-spec 4.1 `default_tau` 제약) 항상 분포의 위쪽
꼬리를 본다. 음수 꼬리의 질량은 분위수에 영향이 없다. 절단정규분포로
바꾸면 해석적 합성이 깨지고 얻는 정확도는 0이다.

몬테카를로 표본은 0에서 자른다 — 표본은 평균 근처에도 놓이므로 음수가
섞일 수 있고, 그대로 두면 합성 결과의 왼쪽 꼬리가 물리적으로 불가능한
값을 갖는다.
"""

from __future__ import annotations

import math
import random
from abc import ABC, abstractmethod
from dataclasses import dataclass
from statistics import NormalDist

# 분위수를 이분법으로 구할 때의 허용 오차(분). 0.01분 = 0.6초.
# 알람은 분 단위로 표시되므로 이보다 정밀할 필요가 없다.
_QUANTILE_TOLERANCE_MIN = 0.01

# 이분법 최대 반복. 구간을 절반씩 줄이므로 60회면 어떤 현실적 범위에서도
# 허용 오차에 도달한다. 무한 루프를 막는 상한이다.
_MAX_BISECT_STEPS = 60

# Mixture 분위수의 탐색 구간을 잡을 때 쓰는 극단 분위. 0 과 1 을 쓰면
# 정규분포의 역CDF 가 무한으로 발산한다.
_BRACKET_LOW = 1e-9
_BRACKET_HIGH = 1 - 1e-9


class Distribution(ABC):
    """소요 시간(분)의 확률분포.

    구현체는 `quantile` 과 `cdf` 를 **서로 정합하게** 제공해야 한다.
    `cdf(quantile(t)) ≈ t` 가 성립하지 않으면 알람 시각과 표시 확률이
    어긋난다 — 사용자에게 "90% 확신"이라고 말하면서 실제로는 다른 값을
    쓰는 상태가 된다. 이 정합성은 테스트로 고정한다.
    """

    @abstractmethod
    def quantile(self, tau: float) -> float:
        """누적확률 `tau` 에 해당하는 소요 시간(분). 0 미만이면 0 으로 자른다."""

    @abstractmethod
    def cdf(self, minutes: float) -> float:
        """`minutes` 이내에 끝날 확률."""

    @abstractmethod
    def sample(self, rng: random.Random) -> float:
        """표본 하나(분). 0 미만이면 0 으로 자른다."""

    @property
    @abstractmethod
    def mean(self) -> float:
        """기댓값(분)."""

    def __post_init_validate__(self) -> None:  # pragma: no cover - 훅
        pass


def _check_tau(tau: float) -> float:
    """분위수 인자를 검증한다.

    0 이나 1 을 넣으면 정규분포의 역CDF 가 발산한다. 호출부의 실수를
    조용히 통과시키면 알람 시각이 터무니없는 값이 되므로 여기서 막는다.
    """
    if not (0.0 < tau < 1.0):
        raise ValueError(f"tau 는 0 과 1 사이여야 한다: {tau!r}")
    return tau


@dataclass(frozen=True)
class Normal(Distribution):
    """정규분포. 관측이 충분하고 단봉일 때 쓴다.

    `sd_min` 이 0 이면 점추정치와 같다. 관측이 없어 변동성을 모를 때
    0 을 넣으면 `quantile` 이 τ 와 무관하게 평균을 돌려준다 — 그게 정직한
    동작이다. 모르는 변동성을 임의로 채우지 않는다.
    """

    mean_min: float
    sd_min: float = 0.0

    def __post_init__(self):
        if self.sd_min < 0:
            raise ValueError(f"표준편차는 음수일 수 없다: {self.sd_min!r}")
        if not math.isfinite(self.mean_min) or not math.isfinite(self.sd_min):
            raise ValueError("평균·표준편차는 유한해야 한다")

    @property
    def mean(self) -> float:
        return self.mean_min

    def quantile(self, tau: float) -> float:
        _check_tau(tau)
        if self.sd_min == 0:
            return max(0.0, self.mean_min)
        return max(0.0, NormalDist(self.mean_min, self.sd_min).inv_cdf(tau))

    def cdf(self, minutes: float) -> float:
        if self.sd_min == 0:
            # 점추정치. 경계에서 0.5 를 주는 것은 계단함수의 관례다.
            if minutes > self.mean_min:
                return 1.0
            if minutes < self.mean_min:
                return 0.0
            return 0.5
        return NormalDist(self.mean_min, self.sd_min).cdf(minutes)

    def sample(self, rng: random.Random) -> float:
        if self.sd_min == 0:
            return max(0.0, self.mean_min)
        return max(0.0, rng.gauss(self.mean_min, self.sd_min))


@dataclass(frozen=True)
class Empirical(Distribution):
    """관측 표본 그대로의 분포. 합성 결과를 담는 데도 쓴다.

    표본을 정렬해 두고 선형보간으로 분위수를 구한다. 관측 분포가 어떤
    모양이든(비대칭·다봉) 그대로 따라간다 — 정규분포로 억지로 맞추면
    "대중교통은 놓치면 배차간격만큼 점프한다" 같은 실제 패턴이 사라진다.
    """

    samples_min: tuple[float, ...]

    def __post_init__(self):
        if not self.samples_min:
            raise ValueError("표본이 비어 있다")
        # frozen dataclass 라 __setattr__ 를 우회한다. 정렬을 생성 시점에
        # 한 번만 하려는 것이다 — quantile 이 호출마다 정렬하면 O(n log n)
        # 이 반복된다.
        object.__setattr__(
            self, "samples_min", tuple(sorted(max(0.0, s) for s in self.samples_min))
        )

    @property
    def mean(self) -> float:
        return sum(self.samples_min) / len(self.samples_min)

    def quantile(self, tau: float) -> float:
        _check_tau(tau)
        xs = self.samples_min
        n = len(xs)
        if n == 1:
            return xs[0]
        # numpy 의 기본(linear) 방식과 같은 위치 계산이다. 외부 도구로
        # 교차 검증할 때 값이 맞아야 하므로 관례를 따른다.
        pos = tau * (n - 1)
        lo = math.floor(pos)
        hi = min(lo + 1, n - 1)
        frac = pos - lo
        return xs[lo] + (xs[hi] - xs[lo]) * frac

    def cdf(self, minutes: float) -> float:
        xs = self.samples_min
        n = len(xs)
        # quantile 의 역함수가 되도록 같은 격자에서 보간한다. 단순히
        # "minutes 이하 표본 비율" 로 계산하면 cdf(quantile(t)) != t 가 되어
        # 알람 시각과 표시 확률이 어긋난다.
        if minutes <= xs[0]:
            return 0.0
        if minutes >= xs[-1]:
            return 1.0
        # xs[lo] <= minutes < xs[lo+1] 인 lo 를 찾는다.
        lo, hi = 0, n - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if xs[mid] <= minutes:
                lo = mid
            else:
                hi = mid
        span = xs[lo + 1] - xs[lo]
        frac = 0.0 if span == 0 else (minutes - xs[lo]) / span
        return (lo + frac) / (n - 1)

    def sample(self, rng: random.Random) -> float:
        return rng.choice(self.samples_min)


@dataclass(frozen=True)
class Mixture(Distribution):
    """가중 혼합분포. 대중교통 대기시간이 다봉형이라 필요하다.

    배차를 잡으면 대기 2분, 놓치면 배차간격만큼 점프한다. 정규분포 하나로는
    이 두 봉우리가 표현되지 않고, 평균만 쓰면 "놓쳤을 때"가 사라져 알람이
    낙관적으로 계산된다.

    가중치는 합이 1 이 되도록 정규화한다. 호출부가 확률을 정확히 맞추도록
    강요하면 실수가 조용한 버그로 남는다.
    """

    components: tuple[tuple[float, Distribution], ...]

    def __post_init__(self):
        if not self.components:
            raise ValueError("구성요소가 비어 있다")
        total = sum(w for w, _ in self.components)
        if total <= 0:
            raise ValueError(f"가중치 합이 0 이하다: {total!r}")
        if any(w < 0 for w, _ in self.components):
            raise ValueError("가중치는 음수일 수 없다")
        object.__setattr__(
            self,
            "components",
            tuple((w / total, d) for w, d in self.components),
        )

    @property
    def mean(self) -> float:
        return sum(w * d.mean for w, d in self.components)

    def cdf(self, minutes: float) -> float:
        return sum(w * d.cdf(minutes) for w, d in self.components)

    def quantile(self, tau: float) -> float:
        """CDF 이분법. 몬테카를로를 쓰지 않아 표본 오차가 없다."""
        _check_tau(tau)
        lo = min(d.quantile(_BRACKET_LOW) for _, d in self.components)
        hi = max(d.quantile(_BRACKET_HIGH) for _, d in self.components)
        lo = max(0.0, lo)
        if hi <= lo:
            return lo
        for _ in range(_MAX_BISECT_STEPS):
            if hi - lo <= _QUANTILE_TOLERANCE_MIN:
                break
            mid = (lo + hi) / 2
            if self.cdf(mid) < tau:
                lo = mid
            else:
                hi = mid
        return max(0.0, (lo + hi) / 2)

    def sample(self, rng: random.Random) -> float:
        r = rng.random()
        acc = 0.0
        for w, d in self.components:
            acc += w
            if r <= acc:
                return d.sample(rng)
        return self.components[-1][1].sample(rng)


# ---------------------------------------------------------------------------
# 합성
# ---------------------------------------------------------------------------

# 몬테카를로 표본 수. front-spec 4.3 이 2000 으로 정했다. 분위수의 표준오차는
# 대략 sqrt(τ(1-τ)/n)/pdf 수준이라, τ=0.9 에서 2000 표본이면 1 분 미만이다.
DEFAULT_CONVOLVE_SAMPLES = 2000


def convolve(
    a: Distribution,
    b: Distribution,
    samples: int = DEFAULT_CONVOLVE_SAMPLES,
    rng: random.Random | None = None,
) -> Distribution:
    """두 소요 시간을 더한 분포.

    **독립을 가정한다.** 준비 시간과 이동 시간이 서로 영향을 주지 않는다는
    뜻이다. 완전히 맞는 가정은 아니다 — 늦게 일어난 날은 서둘러서 준비가
    짧아지고, 그 대신 출발이 늦어 이동이 혼잡 시간에 걸린다. 그 상관은
    `slack_coef`(back-spec 6.1 S3)가 준비 시간 쪽에서 흡수하도록 설계됐다.
    여기서 상관을 모형화하면 같은 효과를 두 번 세게 된다.

    정규분포끼리면 해석해를 쓴다. 표본 오차가 없고 빠르다.
    그 외에는 몬테카를로로 `Empirical` 을 만든다.
    """
    if isinstance(a, Normal) and isinstance(b, Normal):
        return Normal(
            mean_min=a.mean_min + b.mean_min,
            sd_min=math.hypot(a.sd_min, b.sd_min),
        )

    if samples < 2:
        raise ValueError(f"표본 수는 2 이상이어야 한다: {samples!r}")

    # 시드를 고정한 rng 를 주면 결과가 재현된다. 테스트와 "같은 입력에 같은
    # 알람" 을 위해 호출부가 시드를 넘길 수 있게 열어 둔다.
    r = rng if rng is not None else random.Random(0)
    return Empirical(tuple(a.sample(r) + b.sample(r) for _ in range(samples)))


def shift(dist: Distribution, minutes: float) -> Distribution:
    """분포를 상수만큼 평행이동한다.

    안전 버퍼처럼 확정된 시간을 더할 때 쓴다. 상수는 분산을 바꾸지 않으므로
    합성이 아니라 이동이다 — `convolve` 에 sd=0 정규분포를 넘기면 몬테카를로를
    타면서 표본 오차만 얻는다.
    """
    if isinstance(dist, Normal):
        return Normal(dist.mean_min + minutes, dist.sd_min)
    if isinstance(dist, Empirical):
        return Empirical(tuple(s + minutes for s in dist.samples_min))
    if isinstance(dist, Mixture):
        return Mixture(tuple((w, shift(d, minutes)) for w, d in dist.components))
    raise TypeError(f"알 수 없는 분포 형식: {type(dist).__name__}")


def max_of(a: Distribution, b: Distribution,
           samples: int = DEFAULT_CONVOLVE_SAMPLES,
           rng: random.Random | None = None) -> Distribution:
    """두 소요 시간 중 **긴 쪽**의 분포.

    병렬 블록에 쓴다. 세탁기를 돌리면서 샤워를 하면 총 시간은 합이 아니라
    둘 중 긴 쪽이다. max 는 해석해가 없어 몬테카를로로 구한다.
    """
    if samples < 2:
        raise ValueError(f"표본 수는 2 이상이어야 한다: {samples!r}")
    r = rng if rng is not None else random.Random(0)
    return Empirical(tuple(max(a.sample(r), b.sample(r)) for _ in range(samples)))


def blend(
    personal: Distribution,
    global_: Distribution,
    n_observations: int,
    k: int = 5,
) -> Distribution:
    """개인 추정과 전역 추정을 관측 수로 섞는다(shrinkage).

    back-spec 6.1 S2:

        θ = w · 개인 + (1−w) · 전역,   w = n / (n + k),   k = 5

    관측 5회에서 개인:전역이 50:50 이 된다. 관측 2회짜리 개인 평균을 그대로
    믿으면 우연히 빨랐던 이틀이 알람을 낙관적으로 만든다. 반대로 관측이
    30회 쌓이면 전역값은 거의 사라진다.

    두 분포가 모두 정규분포면 모수를 직접 섞는다. 분산도 같은 가중으로
    섞되 **표준편차가 아니라 분산을 섞는다** — 표준편차를 선형으로 섞으면
    분산이 과소평가된다.
    """
    if n_observations < 0:
        raise ValueError(f"관측 수는 음수일 수 없다: {n_observations!r}")
    if k <= 0:
        raise ValueError(f"k 는 양수여야 한다: {k!r}")

    w = n_observations / (n_observations + k)

    if isinstance(personal, Normal) and isinstance(global_, Normal):
        mean = w * personal.mean_min + (1 - w) * global_.mean_min
        var = w * personal.sd_min**2 + (1 - w) * global_.sd_min**2
        return Normal(mean, math.sqrt(var))

    # 일반형은 혼합분포로 둔다. 모수를 섞을 수 없는 조합(경험분포 등)에서
    # 의미가 보존되는 유일한 방법이다.
    if w <= 0:
        return global_
    if w >= 1:
        return personal
    return Mixture(((w, personal), (1 - w, global_)))


def bayesian_update(
    prior_mean: float,
    prior_sd: float,
    observations: list[float] | tuple[float, ...],
    observation_sd: float,
) -> Normal:
    """정규-정규 공액사전으로 평균을 갱신한다. back-spec 6.1 S1.

        μ_n = (μ₀/σ₀² + Σxᵢ/σ²) / (1/σ₀² + n/σ²)
        σ_n² = 1 / (1/σ₀² + n/σ²)

    관측 1~14건 구간에서 쓴다. 표본평균을 그대로 쓰면 관측 1건에 평균이
    통째로 끌려간다. 사전분포가 그 흔들림을 잡아 준다.

    반환하는 `Normal` 의 `sd_min` 은 **평균의 불확실성**이 아니라 예측
    분포의 표준편차다 — 알람 계산에 필요한 것은 "다음 한 번이 얼마나
    걸릴지"이므로, 관측 분산과 평균 불확실성을 함께 넣는다.
    """
    if prior_sd <= 0:
        raise ValueError(f"사전 표준편차는 양수여야 한다: {prior_sd!r}")
    if observation_sd <= 0:
        raise ValueError(f"관측 표준편차는 양수여야 한다: {observation_sd!r}")

    n = len(observations)
    if n == 0:
        return Normal(prior_mean, prior_sd)

    prior_precision = 1.0 / (prior_sd**2)
    obs_precision = n / (observation_sd**2)

    post_mean = (
        prior_mean * prior_precision + sum(observations) / (observation_sd**2)
    ) / (prior_precision + obs_precision)
    post_var_of_mean = 1.0 / (prior_precision + obs_precision)

    # 예측 분산 = 평균의 불확실성 + 관측 자체의 분산.
    predictive_sd = math.sqrt(post_var_of_mean + observation_sd**2)
    return Normal(post_mean, predictive_sd)
