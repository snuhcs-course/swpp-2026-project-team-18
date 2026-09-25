package com.swpp.wakeup.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 알람 결정 화면 머리글의 "며칠 뒤".
 *
 * **음수가 이 파일의 이유다.** 원래 `else -> "${days}일 뒤"` 하나로 두어 이틀 전
 * 일정이 "-2일 뒤" 로 나왔다. 실기기에서 지난 일정을 열어 보고 발견했다.
 */
class RelativeDayLabelTest {

    @Test
    fun `앞날은 며칠 뒤로 적는다`() {
        assertEquals("오늘", relativeDayLabel(0))
        assertEquals("내일 아침", relativeDayLabel(1))
        assertEquals("2일 뒤", relativeDayLabel(2))
        assertEquals("30일 뒤", relativeDayLabel(30))
    }

    @Test
    fun `지난날은 며칠 전으로 적는다`() {
        assertEquals("어제", relativeDayLabel(-1))
        assertEquals("2일 전", relativeDayLabel(-2))
        assertEquals("30일 전", relativeDayLabel(-30))
    }

    @Test
    fun `어떤 값에도 음수 기호가 새지 않는다`() {
        for (d in -400L..400L) {
            assertFalse("days=$d 에서 '$d' 가 그대로 나왔다", relativeDayLabel(d).contains("-"))
        }
    }
}
