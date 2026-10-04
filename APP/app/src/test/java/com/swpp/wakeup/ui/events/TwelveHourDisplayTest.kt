package com.swpp.wakeup.ui.events

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 일정 추가의 시작 시각은 12시간제로 보여주고 내부 값은 0~23 시로 둔다.
 *
 * 경계값이 틀리기 쉽다: 오전 12시는 0시, 오후 12시는 12시다.
 */
class TwelveHourDisplayTest {

    @Test
    fun `자정은 오전 12시`() {
        assertEquals(12, to12Hour(0))
        assertEquals("오전", meridiemLabel(0))
    }

    @Test
    fun `정오는 오후 12시`() {
        assertEquals(12, to12Hour(12))
        assertEquals("오후", meridiemLabel(12))
    }

    @Test
    fun `오전 시각`() {
        assertEquals(9, to12Hour(9))
        assertEquals(11, to12Hour(11))
        assertEquals("오전", meridiemLabel(11))
    }

    @Test
    fun `오전 오후 전환은 시를 그대로 두고 12시간을 옮긴다`() {
        assertEquals(21, withMeridiem(9, pm = true))
        assertEquals(9, withMeridiem(21, pm = false))
        assertEquals(12, withMeridiem(0, pm = true))   // 오전 12시 → 오후 12시
        assertEquals(0, withMeridiem(12, pm = false))  // 오후 12시 → 오전 12시
        assertEquals(9, withMeridiem(9, pm = false))   // 이미 오전이면 그대로
    }

    @Test
    fun `오후 시각`() {
        assertEquals(1, to12Hour(13))
        assertEquals(11, to12Hour(23))
        assertEquals("오후", meridiemLabel(23))
    }
}
