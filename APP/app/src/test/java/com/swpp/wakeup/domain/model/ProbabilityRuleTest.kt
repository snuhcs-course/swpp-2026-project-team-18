// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 확률 색 규칙(iter4 H2-3). 앱 어디서나 같은 확률이 같은 색이어야 한다 */
class ProbabilityRuleTest {

    @Test
    fun `목표치 이상이면 초록`() {
        assertEquals(ProbabilityTone.GOOD, ProbabilityRule.tone(90, 0.90))
        assertEquals(ProbabilityTone.GOOD, ProbabilityRule.tone(97, 0.90))
        assertEquals(ProbabilityTone.GOOD, ProbabilityRule.tone(90, null))
    }

    @Test
    fun `목표치에 못 미쳐도 70 이상이면 주황`() {
        assertEquals(ProbabilityTone.WARN, ProbabilityRule.tone(89, 0.90))
        assertEquals(ProbabilityTone.WARN, ProbabilityRule.tone(70, 0.90))
    }

    @Test
    fun `70 미만은 목표치를 넘겨도 빨강`() {
        assertEquals(ProbabilityTone.BAD, ProbabilityRule.tone(69, 0.90))
        // 빠듯하게(τ 0.65)를 고른 사용자에게도 66% 는 지각 위험이다.
        assertEquals(ProbabilityTone.BAD, ProbabilityRule.tone(66, 0.65))
    }

    @Test
    fun `확률이 없으면 색을 정하지 않는다`() {
        assertEquals(ProbabilityTone.UNKNOWN, ProbabilityRule.tone(null, 0.90))
        assertNull(ProbabilityRule.warning(null))
    }

    @Test
    fun `목표치는 타우에서 온다`() {
        assertEquals(97, ProbabilityRule.goalPercent(0.97))
        assertEquals(90, ProbabilityRule.goalPercent(null))
        assertEquals(90, ProbabilityRule.goalPercent(1.5))
    }

    @Test
    fun `70 미만에만 경고 문구를 붙인다`() {
        assertEquals("지각할 수 있어요", ProbabilityRule.warning(65))
        assertNull(ProbabilityRule.warning(70))
    }
}
