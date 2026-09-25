package com.swpp.wakeup.domain.model

/**
 * 목적지까지 남은 시간(분).
 *
 * [ArrivalOutlook] 은 "늦었는가" 만 판정한다. 얼마나 남았는지를 재는 것은 이쪽
 * 일이다. 둘을 나눠 두면 속도 추정을 바꿀 때 판정 규칙을 건드리지 않는다.
 *
 * ## 계획 속도만으로 낼 때
 *
 * 아직 떠나지 않았거나 관측이 없으면 남은 구간을 **계획 속도로** 간다고 본다.
 * 그러면 늦는 원인이 "이미 흘려보낸 시간" 으로 잡힌다 — 출발이 26분 늦었으면
 * 26분 늦는다.
 *
 * ## 관측 속도를 섞을 때
 *
 * 관측 속도만 쓰면 **지하철을 기다리는 8분 동안 도착 예정이 무한이 된다**(이동
 * 거리가 늘지 않아 속도가 0 에 수렴한다). 계획 속도만 쓰면 실제로 느리게 가고
 * 있는데도 남은 구간은 계획대로 간다고 본다.
 *
 * 그래서 축소 추정으로 섞는다. 표본이 쌓일수록 관측 쪽 가중을 올린다.
 *
 * ```
 * w = 경과 분 ÷ (경과 분 + PACE_SHRINKAGE_MINUTES)
 * 유효 속도 = (1 − w) × 계획 속도 + w × 관측 속도
 * ```
 *
 * 10분 정지면 `w ≈ 0.67` 이라 유효 속도가 계획의 약 3분의 1 로 떨어진다. "많이
 * 늦는다" 고 말하지만 발산하지 않는다.
 */
object TripEta {

    /**
     * 관측 속도의 가중이 절반이 되는 경과 시간(분).
     *
     * 서버가 이동 시간 보정에 쓰는 `estimators.ROUTE_SHRINKAGE_K = 5` 와 같은
     * 값이다. 같은 종류의 판단에 다른 숫자를 쓰면 둘 중 하나가 틀린 것이다.
     */
    const val PACE_SHRINKAGE_MINUTES = 5.0

    /**
     * 계획 속도로만 낸 남은 분. 관측이 없을 때 쓴다.
     *
     * @param travelMinutes 계획 이동 시간. 없으면 null
     * @param ratio 경로 진행률 0~1. 아직 안 떠났으면 0
     */
    fun plannedRemainingMinutes(travelMinutes: Int?, ratio: Float): Double? {
        if (travelMinutes == null || travelMinutes < 0) return null
        return travelMinutes * (1.0 - ratio.coerceIn(0f, 1f))
    }

    /**
     * 관측 속도를 섞어 낸 남은 분.
     *
     * 표본이 모자라면(이동 시간이 0 이하) 계획 속도로 떨어진다. 이동을 시작한
     * 직후에는 좌표 하나로 낸 속도가 심하게 튀는데, 축소 추정의 `w` 가 그때
     * 거의 0 이라 자동으로 계획 속도가 된다.
     *
     * @param travelMinutes 계획 이동 시간
     * @param totalM 경로 전체 길이(m)
     * @param traveledM 경로를 따라 이동한 거리(m)
     * @param movingMinutes 이동을 시작한 뒤 경과한 분
     */
    fun remainingMinutes(
        travelMinutes: Int?,
        totalM: Int,
        traveledM: Int,
        movingMinutes: Double,
    ): Double? {
        val planned = plannedRemainingMinutes(travelMinutes, ratioOf(traveledM, totalM))
            ?: return null
        if (travelMinutes == null || travelMinutes <= 0 || totalM <= 0) return planned
        if (movingMinutes <= 0.0 || !movingMinutes.isFinite()) return planned

        val remainingM = (totalM - traveledM).coerceAtLeast(0)
        if (remainingM == 0) return 0.0

        // m/분. 계획은 경로 전체를 계획 시간에 간다고 본 값이다.
        val plannedPace = totalM.toDouble() / travelMinutes
        val observedPace = traveledM.toDouble() / movingMinutes

        val w = movingMinutes / (movingMinutes + PACE_SHRINKAGE_MINUTES)
        val effectivePace = (1.0 - w) * plannedPace + w * observedPace
        // 완전히 멈춰 있으면 유효 속도가 0 에 가까워진다. 0 으로 나누지 않는다.
        if (effectivePace <= 0.0) return null

        return remainingM / effectivePace
    }

    /** 진행률. 분모가 0 이면 0 이다 — 나눌 것이 없으면 아직 아무것도 안 갔다 */
    private fun ratioOf(traveledM: Int, totalM: Int): Float =
        if (totalM <= 0) 0f else (traveledM.toFloat() / totalM).coerceIn(0f, 1f)
}
