// AI-generated (Claude)
package com.swpp.wakeup.ui.common

import com.swpp.wakeup.ui.common.TimeWheelMath.centeredValue
import com.swpp.wakeup.ui.common.TimeWheelMath.firstRowFor
import com.swpp.wakeup.ui.common.TimeWheelMath.rowCount
import com.swpp.wakeup.ui.common.TimeWheelMath.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시각 휠(iter4 H2-5)의 계산. 0~23 시 ↔ 오전/오후 · 1~12 시, 분 간격, 줄 번호 ↔ 값. */
class TimeWheelTest {

    @Test
    fun `0시와 12시는 12로 보인다`() {
        assertEquals(12, TimeWheelMath.hour12(0))
        assertEquals(12, TimeWheelMath.hour12(12))
        assertEquals(1, TimeWheelMath.hour12(13))
        assertEquals(11, TimeWheelMath.hour12(23))
        assertEquals(9, TimeWheelMath.hour12(9))
    }

    @Test
    fun `오전 12시는 0시이고 오후 12시는 12시다`() {
        assertEquals(0, TimeWheelMath.hour24(pm = false, hour12 = 12))
        assertEquals(12, TimeWheelMath.hour24(pm = true, hour12 = 12))
        assertEquals(13, TimeWheelMath.hour24(pm = true, hour12 = 1))
        assertEquals(9, TimeWheelMath.hour24(pm = false, hour12 = 9))
        for (h in 0..23) {
            assertEquals(h, TimeWheelMath.hour24(TimeWheelMath.isPm(h), TimeWheelMath.hour12(h)))
        }
    }

    @Test
    fun `분 열은 간격대로 두 자리로 쓴다`() {
        val one = TimeWheelMath.minutes(1)
        assertEquals(60, one.size)
        assertEquals("00", one.first())
        assertEquals("59", one.last())
        assertEquals(listOf("00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55"), TimeWheelMath.minutes(5))
    }

    @Test
    fun `간격에 맞지 않는 분은 앞 칸으로 내린다`() {
        assertEquals(40, TimeWheelMath.snapMinute(43, 5))
        assertEquals(55, TimeWheelMath.snapMinute(59, 5))
        assertEquals(43, TimeWheelMath.snapMinute(43, 1))
        assertEquals(59, TimeWheelMath.snapMinute(75, 1))
    }

    @Test
    fun `큰 글자와 안내 한 줄`() {
        assertEquals("오전" to "9:00", TimeWheelMath.readout(9, 0))
        assertEquals("오전" to "12:05", TimeWheelMath.readout(0, 5))
        assertEquals("오후" to "12:30", TimeWheelMath.readout(12, 30))
        assertEquals("오후" to "9:02", TimeWheelMath.readout(21, 2))
        assertEquals("위아래로 밀어서 골라요 · 5분 단위", TimeWheelMath.guide(5))
    }

    @Test
    fun `끝없이 도는 열은 가운데쯤에서 시작하고 값이 돌아온다`() {
        val size = 12
        assertEquals(12_000, rowCount(size, loop = true))
        val first = firstRowFor(8, size, loop = true) // "9"
        assertEquals(8, valueAt(first + TimeWheelMath.CENTER_ROW, size, loop = true))
        assertEquals(8, centeredValue(first + TimeWheelMath.CENTER_ROW, size, loop = true))
        // 12 다음은 1
        assertEquals(0, valueAt(first + TimeWheelMath.CENTER_ROW + 4, size, loop = true))
        // 손으로 끝까지 갈 수 없을 만큼 위아래 여유가 있다
        assertTrue(first > 1_000 && rowCount(size, true) - first > 1_000)
    }

    @Test
    fun `끝이 있는 열은 위아래 빈 줄 둘씩이고 가운데 값을 끝 값으로 붙잡는다`() {
        val size = 2
        assertEquals(6, rowCount(size, loop = false))
        assertNull(valueAt(0, size, loop = false))
        assertNull(valueAt(1, size, loop = false))
        assertEquals(0, valueAt(2, size, loop = false))
        assertEquals(1, valueAt(3, size, loop = false))
        assertNull(valueAt(4, size, loop = false))
        assertEquals(0, firstRowFor(0, size, loop = false))
        assertEquals(1, firstRowFor(1, size, loop = false))
        assertEquals(0, centeredValue(1, size, loop = false))
        assertEquals(1, centeredValue(3, size, loop = false))
        assertEquals(1, centeredValue(5, size, loop = false))
    }
}
