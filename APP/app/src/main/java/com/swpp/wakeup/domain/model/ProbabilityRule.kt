// AI-generated (Claude)
package com.swpp.wakeup.domain.model

/** 정시 도착 확률 한 값의 색 구분. 앱 전체가 이 네 가지만 쓴다 */
enum class ProbabilityTone { GOOD, WARN, BAD, UNKNOWN }

/**
 * 정시 도착 확률을 어떤 색으로 보여 줄지 정하는 앱 공통 규칙(iter4 H2-3).
 *
 * 목표치(τ) 이상이면 초록, [LATE_RISK_PERCENT] 이상이면 주황, 그 아래면 빨강이다.
 *
 * **[LATE_RISK_PERCENT] 아래를 먼저 본다.** τ 가 0.65 인 "빠듯하게" 를 고른 사용자에게
 * 66% 를 초록으로 보여 주면, 같은 화면이 "지각할 수 있어요" 라고 적으면서 색은 안심하라고
 * 말하게 된다. 목표를 낮춰 잡았다고 해서 지각 위험이 사라지지는 않는다.
 */
object ProbabilityRule {
    /** 이 아래면 빨강 · "지각할 수 있어요" */
    const val LATE_RISK_PERCENT = 70
    const val LATE_WARNING = "지각할 수 있어요"
    /** τ 를 모를 때의 목표치. [ConfidenceView.meets] 와 같은 값이다 */
    const val DEFAULT_GOAL_PERCENT = 90

    /** τ(0~1)를 목표 확률(%)로. 범위 밖이거나 없으면 [DEFAULT_GOAL_PERCENT] */
    fun goalPercent(tau: Double?): Int =
        tau?.takeIf { it in 0.0..1.0 }?.let { (it * 100).toInt() } ?: DEFAULT_GOAL_PERCENT

    fun tone(percent: Int?, tau: Double? = null): ProbabilityTone = when {
        percent == null -> ProbabilityTone.UNKNOWN
        percent < LATE_RISK_PERCENT -> ProbabilityTone.BAD
        percent >= goalPercent(tau) -> ProbabilityTone.GOOD
        else -> ProbabilityTone.WARN
    }

    /** 빨강 구간이면 경고 문구, 아니면 null */
    fun warning(percent: Int?): String? =
        LATE_WARNING.takeIf { percent != null && percent < LATE_RISK_PERCENT }
}
