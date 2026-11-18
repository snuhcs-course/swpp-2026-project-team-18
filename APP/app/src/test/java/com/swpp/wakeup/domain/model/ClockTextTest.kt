// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 앱의 시각 표기는 "오전 8:13" / "오후 5:50" 한 가지다(11-18 표기 통일) */
class ClockTextTest {

    @Test
    fun `자정 · 정오 · 한 자리 분`() {
        assertEquals("오전 12:00", ClockText.label(0, 0))
        assertEquals("오전 12:05", ClockText.label(0, 5))
        assertEquals("오후 12:00", ClockText.label(12, 0))
        assertEquals("오전 8:05", ClockText.label(8, 5))
        assertEquals("오후 5:50", ClockText.label(17, 50))
        assertEquals("오후 11:59", ClockText.label(23, 59))
        assertEquals("오전 11:59", ClockText.label(LocalTime.of(11, 59)))
    }

    @Test
    fun `하루의 분 · 밀리초 · 시간대`() {
        assertEquals("오전 7:40", ClockText.label(7 * 60 + 40))
        assertEquals("오후 11:30", ClockText.label(-30))
        assertEquals("오전 12:10", ClockText.label(24 * 60 + 10))
        val seoul = ZoneId.of("Asia/Seoul")
        val at = ZonedDateTime.of(2030, 1, 1, 17, 50, 0, 0, seoul)
        assertEquals("오후 5:50", ClockText.label(at))
        assertEquals("오후 5:50", ClockText.label(at.toInstant().toEpochMilli(), seoul))
    }

    @Test
    fun `AM PM 코드와 예전 24시간제 저장값도 같은 표기로`() {
        assertEquals("오후 5:50", ClockText.label("5:50", "PM"))
        assertEquals("예전 기기 알람(H:mm + PM)", "오후 5:50", ClockText.label("17:50", "PM"))
        assertEquals("오전 12:30", ClockText.label("12:30", "AM"))
        assertEquals("오전 12:30", ClockText.label("0:30", "AM"))
        assertEquals("오후 12:10", ClockText.label("12:10", "PM"))
        assertEquals("오전 9:00", ClockText.label("09:00", null))
        assertEquals("오후 2:00", ClockText.label("14:00", null))
        assertEquals("오후" to "5:50", ClockText.split("17:50", "PM"))
        assertNull(ClockText.label("어제", "AM"))
        assertNull(ClockText.label("25:00", null))
        assertNull(ClockText.label(null, "AM"))
    }

    @Test
    fun `오전 오후 낱말`() {
        assertEquals("오전", ClockText.meridiemOf("AM"))
        assertEquals("오후", ClockText.meridiemOf("pm"))
        assertNull(ClockText.meridiemOf(""))
        assertEquals("오후 12:00 - 12:30", "${ClockText.label(12, 0)} - ${ClockText.clock(12, 30)}")
    }
}
