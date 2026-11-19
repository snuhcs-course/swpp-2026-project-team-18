// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 시작 시각 뒤로 알람을 고르면 정시 도착 확률은 0 이다(11-18 사용자: 9:00 일정에 9:30 알람이 "90%" 로 보였다).
 * 예전에는 시작보다 늦은 알람을 늘 "전날 밤" 으로 보고 하루를 더해, 23시간 반이 남은 것으로 셌다.
 */
class LateAlarmProbabilityTest {

    private val nine = 9 * 60

    @Test
    fun `시작 뒤 12시간 안쪽의 알람은 남은 시간이 음수다`() {
        assertEquals(-30, AlarmTimeChoice.availableMinutes(9 * 60 + 30, nine))
        assertEquals(-1, AlarmTimeChoice.availableMinutes(9 * 60 + 1, nine))
        assertEquals(0, AlarmTimeChoice.availableMinutes(nine, nine))
        assertEquals(-(11 * 60 + 59), AlarmTimeChoice.availableMinutes(20 * 60 + 59, nine))
    }

    @Test
    fun `그보다 늦으면 전날 밤 알람이다`() {
        assertEquals(12 * 60, AlarmTimeChoice.availableMinutes(21 * 60, nine))
        assertEquals(10 * 60, AlarmTimeChoice.availableMinutes(23 * 60, nine))
        // 자정 넘어 시작하는 일정: 00:30 일정, 23:50 알람 = 40분
        assertEquals(40, AlarmTimeChoice.availableMinutes(23 * 60 + 50, 30))
    }

    @Test
    fun `남은 시간이 0 이하면 분포와 상관없이 0 퍼센트`() {
        assertEquals(0, AlarmTimeChoice.shownProbability(nine, null, null, cellProbability = 90, startMinutes = nine))
        assertEquals(0, AlarmTimeChoice.shownProbability(nine + 30, nine + 30, 90, cellProbability = 90, startMinutes = nine))
        assertEquals("0%", ProbabilityRule.displayLabel(0))
        assertEquals(ProbabilityRule.LATE_WARNING, ProbabilityRule.warning(0))
    }

    @Test
    fun `시작 전 시각은 서버가 준 값만 보이고 없으면 비운다`() {
        assertNull(AlarmTimeChoice.shownProbability(nine - 30, null, null, cellProbability = null, startMinutes = nine))
        assertEquals(82, AlarmTimeChoice.shownProbability(nine - 30, null, null, cellProbability = 82, startMinutes = nine))
    }

    @Test
    fun `보이는 숫자는 낮은 값도 높은 값도 그대로다`() {
        assertEquals(0, ProbabilityRule.displayPercent(0))
        assertEquals(25, ProbabilityRule.displayPercent(25))
        assertEquals(98, ProbabilityRule.displayPercent(98))
    }
}
