// AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.ui.graphics.Color
import com.swpp.wakeup.domain.model.ProbabilityRule
import com.swpp.wakeup.domain.model.ProbabilityTone
import com.swpp.wakeup.ui.theme.JitColor

/** [ProbabilityRule] 의 구분을 앱 색으로. 확률을 그리는 곳은 모두 이 함수를 쓴다 */
fun probabilityColor(tone: ProbabilityTone): Color = when (tone) {
    ProbabilityTone.GOOD -> JitColor.Green
    ProbabilityTone.WARN -> JitColor.Amber
    ProbabilityTone.BAD -> JitColor.Red
    ProbabilityTone.UNKNOWN -> JitColor.TextSecondary
}

/** 확률(%)과 목표치(τ)로 바로 색을 고른다 */
fun probabilityColor(percent: Int?, tau: Double? = null): Color =
    probabilityColor(ProbabilityRule.tone(percent, tau))
