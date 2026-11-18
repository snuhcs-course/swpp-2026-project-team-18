// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 일정 시작과 같거나 그 뒤에 울리는 휠 시각은 저장하지 않는다(11-18 사용자) */
class AlarmAfterStartTest {

    private val nine = 9 * 60
    private val presets = mapOf("safe" to 7 * 60 + 27, "normal" to 7 * 60 + 32, "gamble" to 7 * 60 + 41)

    @Test
    fun `시작과 같은 분 · 1분 늦음 · 30분 늦음은 막는다`() {
        assertEquals(UserAlarmRule.Save.AfterStart, UserAlarmRule.choose(nine, presets, supported = true, startMinutes = nine))
        assertEquals(UserAlarmRule.Save.AfterStart, UserAlarmRule.choose(nine + 1, presets, supported = true, startMinutes = nine))
        assertEquals(UserAlarmRule.Save.AfterStart, UserAlarmRule.choose(nine + 30, presets, supported = true, startMinutes = nine))
        // 서버가 아직 직접 시각을 받지 않아도 이유는 "시작 뒤" 가 먼저다
        assertEquals(UserAlarmRule.Save.AfterStart, UserAlarmRule.choose(nine + 30, presets, supported = false, startMinutes = nine))
    }

    @Test
    fun `시작 1분 전은 맞출 수 있다`() {
        assertEquals(UserAlarmRule.Save.Custom(nine - 1), UserAlarmRule.choose(nine - 1, presets, supported = true, startMinutes = nine))
    }

    @Test
    fun `12시간 경계 - 안쪽은 시작 뒤, 바깥은 전날 밤`() {
        assertTrue(UserAlarmRule.isAfterStart(nine + 11 * 60 + 59, nine))
        assertFalse("21:00 은 전날 밤 21:00", UserAlarmRule.isAfterStart(nine + 12 * 60, nine))
        assertEquals(UserAlarmRule.Save.Custom(23 * 60), UserAlarmRule.choose(23 * 60, presets, supported = true, startMinutes = nine))
        // 자정 넘어 시작하는 일정: 00:30 일정, 23:50 알람은 전날 밤이라 된다 · 00:40 은 막는다
        assertFalse(UserAlarmRule.isAfterStart(23 * 60 + 50, 30))
        assertTrue(UserAlarmRule.isAfterStart(40, 30))
    }

    @Test
    fun `세 칸은 서버가 정한 시각이라 막지 않는다 · 시작을 모르면 지금처럼`() {
        val late = mapOf("normal" to nine + 10)
        assertEquals(UserAlarmRule.Save.Preset("normal"), UserAlarmRule.choose(nine + 10, late, supported = true, startMinutes = nine))
        assertEquals(UserAlarmRule.Save.Custom(nine + 30), UserAlarmRule.choose(nine + 30, presets, supported = true, startMinutes = null))
    }

    @Test
    fun `막을 때 문구는 이유와 할 일`() {
        assertEquals("일정이 시작한 뒤에 울리는 시각이라 맞출 수 없어요. 더 이른 시각을 골라 주세요", UserAlarmRule.AFTER_START_NOTE)
    }
}
