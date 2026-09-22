"""분포 엔진 단위 테스트.

기대값은 **손으로 계산해 박아 둔다.** 구현으로 기대값을 만들면 구현이 틀렸을
때 테스트도 같이 틀려서 아무것도 못 잡는다.

가장 중요한 성질은 `cdf(quantile(tau)) == tau` 다. 이게 깨지면 알람 시각과
화면에 표시되는 확률이 서로 다른 분포를 보는 상태가 된다 — 사용자에게
"90% 확신" 이라고 말하면서 실제로는 다른 값으로 알람을 맞추는 것이다.
DB 도 네트워크도 쓰지 않는 순수 함수 테스트다.
"""

import math
import random

import pytest

from apps.planning.distributions import (
    Empirical,
    Mixture,
    Normal,
    bayesian_update,
    blend,
    convolve,
    max_of,
    shift,
)

# 표준정규 분위수. scipy 없이 검증하려고 알려진 값을 박아 둔다.
Z_90 = 1.2815515655446004
Z_95 = 1.6448536269514722
Z_50 = 0.0


class TestNormal:
    def test_quantile_uses_known_z_values(self):
        d = Normal(mean_min=20.0, sd_min=5.0)
        assert d.quantile(0.9) == pytest.approx(20 + 5 * Z_90, abs=1e-9)
        assert d.quantile(0.95) == pytest.approx(20 + 5 * Z_95, abs=1e-9)
        assert d.quantile(0.5) == pytest.approx(20 + 5 * Z_50, abs=1e-9)

    def test_cdf_inverts_quantile(self):
        d = Normal(38.0, 9.0)
        for tau in (0.5, 0.75, 0.9, 0.99, 0.999):
            assert d.cdf(d.quantile(tau)) == pytest.approx(tau, abs=1e-9)

    def test_zero_sd_is_a_point_estimate(self):
        """관측이 없어 변동성을 모를 때의 동작. τ 를 올려도 값이 안 변한다.

        이게 정직한 동작이다. 모르는 분산을 임의로 채우면 τ 슬라이더가
        움직이는 것처럼 보이지만 근거가 없다.
        """
        d = Normal(30.0, 0.0)
        assert d.quantile(0.5) == 30.0
        assert d.quantile(0.99) == 30.0
        assert d.mean == 30.0

    def test_zero_sd_cdf_is_a_step(self):
        d = Normal(30.0, 0.0)
        assert d.cdf(29.9) == 0.0
        assert d.cdf(30.0) == 0.5
        assert d.cdf(30.1) == 1.0

    def test_negative_quantile_is_clamped_to_zero(self):
        """소요 시간은 음수가 될 수 없다."""
        d = Normal(2.0, 10.0)
        assert d.quantile(0.01) == 0.0

    def test_rejects_negative_sd(self):
        with pytest.raises(ValueError, match="표준편차"):
            Normal(10.0, -1.0)

    @pytest.mark.parametrize("bad", [0.0, 1.0, -0.1, 1.5])
    def test_rejects_tau_outside_open_unit_interval(self, bad):
        """0 이나 1 은 역CDF 가 발산한다. 조용히 통과시키면 알람이 터진다."""
        with pytest.raises(ValueError, match="tau"):
            Normal(10.0, 2.0).quantile(bad)

    def test_sample_is_never_negative(self):
        rng = random.Random(1234)
        d = Normal(1.0, 20.0)
        assert all(d.sample(rng) >= 0 for _ in range(500))


class TestEmpirical:
    def test_quantile_matches_linear_interpolation_convention(self):
        """numpy 기본(linear) 과 같은 위치 계산인지 확인한다.

        표본 [10,20,30,40,50], tau=0.25 → pos = 0.25*4 = 1.0 → 정확히 20.
        tau=0.375 → pos = 1.5 → 20 과 30 의 중간 = 25.
        """
        d = Empirical((10.0, 20.0, 30.0, 40.0, 50.0))
        assert d.quantile(0.25) == pytest.approx(20.0)
        assert d.quantile(0.375) == pytest.approx(25.0)
        assert d.quantile(0.5) == pytest.approx(30.0)

    def test_sorts_input(self):
        d = Empirical((50.0, 10.0, 30.0))
        assert d.samples_min == (10.0, 30.0, 50.0)

    def test_clamps_negative_samples(self):
        d = Empirical((-5.0, 10.0))
        assert d.samples_min == (0.0, 10.0)

    def test_cdf_inverts_quantile(self):
        """이 성질이 깨지면 알람 시각과 표시 확률이 어긋난다."""
        rng = random.Random(7)
        d = Empirical(tuple(rng.gauss(40, 8) for _ in range(400)))
        for tau in (0.1, 0.25, 0.5, 0.75, 0.9, 0.99):
            assert d.cdf(d.quantile(tau)) == pytest.approx(tau, abs=1e-9)

    def test_cdf_saturates_outside_the_sample_range(self):
        d = Empirical((10.0, 20.0, 30.0))
        assert d.cdf(5.0) == 0.0
        assert d.cdf(100.0) == 1.0

    def test_mean(self):
        assert Empirical((10.0, 20.0, 30.0)).mean == pytest.approx(20.0)

    def test_rejects_empty(self):
        with pytest.raises(ValueError, match="표본"):
            Empirical(())

    def test_single_sample(self):
        d = Empirical((42.0,))
        assert d.quantile(0.5) == 42.0
        assert d.quantile(0.99) == 42.0


class TestMixture:
    def test_normalizes_weights(self):
        m = Mixture(((3.0, Normal(10.0, 1.0)), (1.0, Normal(20.0, 1.0))))
        assert [w for w, _ in m.components] == pytest.approx([0.75, 0.25])

    def test_mean_is_the_weighted_mean(self):
        m = Mixture(((0.75, Normal(10.0, 1.0)), (0.25, Normal(20.0, 1.0))))
        assert m.mean == pytest.approx(0.75 * 10 + 0.25 * 20)

    def test_cdf_is_the_weighted_cdf(self):
        a, b = Normal(10.0, 2.0), Normal(30.0, 3.0)
        m = Mixture(((0.6, a), (0.4, b)))
        for x in (5.0, 10.0, 20.0, 30.0, 40.0):
            assert m.cdf(x) == pytest.approx(0.6 * a.cdf(x) + 0.4 * b.cdf(x))

    def test_cdf_inverts_quantile(self):
        m = Mixture(((0.7, Normal(12.0, 2.0)), (0.3, Normal(25.0, 3.0))))
        for tau in (0.5, 0.75, 0.9, 0.99):
            # 이분법 허용 오차가 0.01분이라 cdf 로 되돌리면 그만큼 오차가 있다.
            assert m.cdf(m.quantile(tau)) == pytest.approx(tau, abs=2e-3)

    def test_bimodal_high_quantile_reaches_the_far_mode(self):
        """대중교통 대기시간의 실제 모양.

        배차를 잡으면 2분, 놓치면 12분. 놓칠 확률 30%.
        τ=0.9 는 "놓쳤을 때" 를 포함해야 한다. 평균(5분)만 쓰면 알람이
        낙관적으로 계산되고 그게 지각으로 나타난다.
        """
        wait = Mixture(((0.7, Normal(2.0, 0.5)), (0.3, Normal(12.0, 1.0))))
        assert wait.mean == pytest.approx(0.7 * 2 + 0.3 * 12)  # 5.0
        assert wait.quantile(0.9) > 11.0
        assert wait.quantile(0.5) < 3.0

    def test_rejects_empty_and_bad_weights(self):
        with pytest.raises(ValueError, match="구성요소"):
            Mixture(())
        with pytest.raises(ValueError, match="가중치"):
            Mixture(((0.0, Normal(1.0, 1.0)),))
        with pytest.raises(ValueError, match="가중치"):
            Mixture(((-1.0, Normal(1.0, 1.0)), (2.0, Normal(2.0, 1.0))))


class TestConvolve:
    def test_normal_plus_normal_is_analytic_and_exact(self):
        """표본 오차가 없어야 한다. 몬테카를로를 타면 이 등식이 깨진다."""
        prep = Normal(30.0, 6.0)
        travel = Normal(20.0, 8.0)
        total = convolve(prep, travel)
        assert isinstance(total, Normal)
        assert total.mean_min == pytest.approx(50.0)
        # sqrt(36 + 64) = 10 정확히
        assert total.sd_min == pytest.approx(10.0)

    def test_variances_add_not_standard_deviations(self):
        """표준편차를 더하면 14, 분산을 더하면 10 이다. 흔한 실수를 고정한다."""
        total = convolve(Normal(0.0, 6.0), Normal(0.0, 8.0))
        assert total.sd_min == pytest.approx(10.0)
        assert total.sd_min != pytest.approx(14.0)

    def test_mixture_path_returns_empirical_with_matching_mean(self):
        prep = Normal(30.0, 5.0)
        wait = Mixture(((0.7, Normal(2.0, 0.5)), (0.3, Normal(12.0, 1.0))))
        total = convolve(prep, wait, samples=20000, rng=random.Random(42))
        assert isinstance(total, Empirical)
        # 30 + 5.0 = 35. 표본 20000 이면 평균 오차는 0.1분 수준이다.
        assert total.mean == pytest.approx(35.0, abs=0.2)

    def test_is_reproducible_with_a_seeded_rng(self):
        """같은 입력에 같은 알람이 나와야 한다."""
        a = Normal(30.0, 5.0)
        b = Mixture(((0.5, Normal(2.0, 0.5)), (0.5, Normal(12.0, 1.0))))
        first = convolve(a, b, samples=500, rng=random.Random(9)).quantile(0.9)
        second = convolve(a, b, samples=500, rng=random.Random(9)).quantile(0.9)
        assert first == second

    def test_rejects_too_few_samples(self):
        with pytest.raises(ValueError, match="표본 수"):
            convolve(
                Mixture(((1.0, Normal(1.0, 1.0)),)),
                Normal(1.0, 1.0),
                samples=1,
            )


class TestShift:
    def test_moves_mean_and_keeps_sd(self):
        """상수를 더하는 것은 분산을 바꾸지 않는다."""
        d = shift(Normal(40.0, 9.0), 10.0)
        assert isinstance(d, Normal)
        assert d.mean_min == pytest.approx(50.0)
        assert d.sd_min == pytest.approx(9.0)

    def test_empirical(self):
        d = shift(Empirical((10.0, 20.0)), 5.0)
        assert d.samples_min == (15.0, 25.0)

    def test_mixture(self):
        m = shift(Mixture(((0.5, Normal(2.0, 1.0)), (0.5, Normal(12.0, 1.0)))), 3.0)
        assert m.mean == pytest.approx(10.0)

    def test_rejects_unknown_type(self):
        class Weird(Normal):
            pass

        # Normal 의 서브클래스는 isinstance 로 잡히므로 통과한다. 완전히
        # 다른 타입만 거부한다는 사실을 문서화하는 테스트다.
        assert isinstance(shift(Weird(1.0, 1.0), 1.0), Normal)


class TestMaxOf:
    def test_result_is_at_least_each_component(self):
        """병렬 블록: 세탁기를 돌리며 샤워하면 총 시간은 긴 쪽이다."""
        shower = Normal(14.0, 2.0)
        laundry = Normal(8.0, 1.0)
        both = max_of(shower, laundry, samples=20000, rng=random.Random(3))
        # max 의 평균은 각 평균보다 크거나 같다.
        assert both.mean >= shower.mean - 0.2
        assert both.mean >= laundry.mean
        # 그리고 합보다는 작다 — 합으로 계산하면 22분이 된다.
        assert both.mean < shower.mean + laundry.mean

    def test_identical_distributions_give_larger_mean_than_one(self):
        d = Normal(10.0, 3.0)
        m = max_of(d, d, samples=20000, rng=random.Random(5))
        # E[max(X,Y)] = μ + σ/√π ≈ 10 + 3*0.5642 = 11.69
        assert m.mean == pytest.approx(10.0 + 3.0 / math.sqrt(math.pi), abs=0.15)


class TestBlend:
    def test_fifty_fifty_at_n_equals_k(self):
        """back-spec 6.1: 관측 5회에서 개인:전역 = 50:50."""
        personal = Normal(10.0, 2.0)
        global_ = Normal(20.0, 4.0)
        out = blend(personal, global_, n_observations=5, k=5)
        assert isinstance(out, Normal)
        assert out.mean_min == pytest.approx(15.0)

    def test_blends_variance_not_standard_deviation(self):
        """표준편차를 선형으로 섞으면 분산이 과소평가된다.

        w=0.5, sd 2 와 4 → 분산 4 와 16 → 섞은 분산 10 → sd 3.162.
        표준편차를 섞으면 3.0 이 나온다. 작은 차이지만 τ=0.99 에서
        알람이 몇 분 늦어진다.
        """
        out = blend(Normal(0.0, 2.0), Normal(0.0, 4.0), n_observations=5, k=5)
        assert out.sd_min == pytest.approx(math.sqrt(10.0))
        assert out.sd_min != pytest.approx(3.0)

    def test_no_observations_returns_global(self):
        personal = Normal(10.0, 2.0)
        global_ = Normal(20.0, 4.0)
        out = blend(personal, global_, n_observations=0)
        assert out.mean_min == pytest.approx(20.0)
        assert out.sd_min == pytest.approx(4.0)

    def test_many_observations_approaches_personal(self):
        out = blend(Normal(10.0, 2.0), Normal(20.0, 4.0), n_observations=95, k=5)
        assert out.mean_min == pytest.approx(10.5)  # w = 0.95

    def test_non_normal_falls_back_to_a_mixture(self):
        personal = Empirical((10.0, 10.0, 10.0))
        global_ = Normal(20.0, 4.0)
        out = blend(personal, global_, n_observations=5, k=5)
        assert isinstance(out, Mixture)
        assert out.mean == pytest.approx(15.0)

    def test_rejects_bad_arguments(self):
        with pytest.raises(ValueError, match="관측 수"):
            blend(Normal(1.0, 1.0), Normal(1.0, 1.0), n_observations=-1)
        with pytest.raises(ValueError, match="k"):
            blend(Normal(1.0, 1.0), Normal(1.0, 1.0), n_observations=1, k=0)


class TestBayesianUpdate:
    def test_no_observations_returns_the_prior(self):
        out = bayesian_update(30.0, 10.0, [], observation_sd=10.0)
        assert out.mean_min == pytest.approx(30.0)
        assert out.sd_min == pytest.approx(10.0)

    def test_single_observation_hand_computed(self):
        """손으로 계산한 값.

        사전 N(30, 10²), 관측 [40], 관측 sd 10.
          prior_precision = 1/100 = 0.01
          obs_precision   = 1/100 = 0.01
          post_mean = (30*0.01 + 40/100) / 0.02 = 0.7/0.02 = 35
          post_var_of_mean = 1/0.02 = 50
          predictive_sd = sqrt(50 + 100) = sqrt(150)
        """
        out = bayesian_update(30.0, 10.0, [40.0], observation_sd=10.0)
        assert out.mean_min == pytest.approx(35.0)
        assert out.sd_min == pytest.approx(math.sqrt(150.0))

    def test_one_observation_does_not_drag_the_mean_all_the_way(self):
        """표본평균을 그대로 쓰면 40 이 된다. 사전분포가 흔들림을 잡는다."""
        out = bayesian_update(30.0, 10.0, [40.0], observation_sd=10.0)
        assert 30.0 < out.mean_min < 40.0

    def test_many_observations_approach_the_sample_mean(self):
        obs = [45.0] * 50
        out = bayesian_update(30.0, 10.0, obs, observation_sd=10.0)
        assert out.mean_min == pytest.approx(45.0, abs=0.5)

    def test_tight_prior_resists_observations(self):
        loose = bayesian_update(30.0, 20.0, [50.0], observation_sd=10.0)
        tight = bayesian_update(30.0, 1.0, [50.0], observation_sd=10.0)
        assert tight.mean_min < loose.mean_min

    def test_predictive_sd_exceeds_observation_sd(self):
        """예측 분산은 관측 분산보다 항상 크다 — 평균도 모르기 때문이다."""
        out = bayesian_update(30.0, 10.0, [40.0] * 3, observation_sd=8.0)
        assert out.sd_min > 8.0

    def test_rejects_nonpositive_sds(self):
        with pytest.raises(ValueError, match="사전 표준편차"):
            bayesian_update(30.0, 0.0, [1.0], observation_sd=10.0)
        with pytest.raises(ValueError, match="관측 표준편차"):
            bayesian_update(30.0, 10.0, [1.0], observation_sd=0.0)
