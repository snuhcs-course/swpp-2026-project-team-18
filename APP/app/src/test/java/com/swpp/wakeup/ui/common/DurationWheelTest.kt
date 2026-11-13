// AI-generated (Claude)
package com.swpp.wakeup.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** 준비 시간 휠(iter4 ⑮-a)의 열 값. 5분~4시간 밖으로는 굴러가지 않는다. */
class DurationWheelTest {

    private val range = 5..240

    @Test
    fun `시간 열은 0시간부터 4시간까지다`() {
        assertEquals(0..4, DurationWheelMath.hours(range))
    }

    @Test
    fun `0시간이면 분은 5부터, 4시간이면 0분만`() {
        assertEquals(5..59, DurationWheelMath.minutesFor(0, range))
        assertEquals(0..59, DurationWheelMath.minutesFor(1, range))
        assertEquals(0..59, DurationWheelMath.minutesFor(3, range))
        assertEquals(0..0, DurationWheelMath.minutesFor(4, range))
    }

    @Test
    fun `시간을 바꿔 분이 범위를 벗어나면 가까운 끝으로 붙인다`() {
        assertEquals(65, DurationWheelMath.combine(1, 5, range))
        assertEquals(5, DurationWheelMath.combine(0, 3, range)) // 1시간 3분에서 0시간으로
        assertEquals(240, DurationWheelMath.combine(4, 35, range)) // 3시간 35분에서 4시간으로
        assertEquals(35, DurationWheelMath.combine(0, 35, range))
        assertEquals(240, DurationWheelMath.combine(9, 0, range))
    }

    @Test
    fun `안내 한 줄은 범위를 사람 말로 쓴다`() {
        assertEquals("5분부터 4시간까지, 1분 단위로 정할 수 있어요", DurationWheelMath.guide(range))
    }
}
