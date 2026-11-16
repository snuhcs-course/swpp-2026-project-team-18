package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.testing.fromJson
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 알람 결정 화면 머리글의 "며칠 뒤".
 *
 * **음수가 이 파일의 이유다.** 원래 `else -> "${days}일 뒤"` 하나로 두어 이틀 전
 * 일정이 "-2일 뒤" 로 나왔다. 실기기에서 지난 일정을 열어 보고 발견했다.
 *
 * 날짜 차이만 보므로 아침 · 저녁을 말하지 않는다. 예전의 "내일 아침" 은 내일 저녁 일정에도 붙었다.
 * (Partly AI-generated (Claude))
 */
class RelativeDayLabelTest {

    @Test
    fun `앞날은 며칠 뒤로 적는다`() {
        assertEquals("오늘", relativeDayLabel(0))
        assertEquals("내일", relativeDayLabel(1))
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

    // AI-generated (Claude)
    @Test
    fun `내일 저녁 일정과 오늘 밤 울리는 내일 0시 30분 일정에 아침이라고 하지 않는다`() {
        // Given 오늘은 11월 8일이다. 내일 19:00 일정(알람 17:30)과 내일 00:30 일정(알람 오늘 23:20)
        val zone = ZoneId.of("Asia/Seoul")
        val now = OffsetDateTime.parse("2026-11-08T09:00:00+09:00")
        val evening = event(start = "2026-11-09T19:00:00+09:00", alarmAt = "2026-11-09T17:30:00+09:00")
        val pastMidnight = event(start = "2026-11-09T00:30:00+09:00", alarmAt = "2026-11-08T23:20:00+09:00")

        // When 알람 결정 화면 머리글을 만든다
        val labels = listOf(evening, pastMidnight).map { it.toPlanView(zone, now = now)!!.whenLabel }

        // Then 예전에는 둘 다 "내일 아침" 이었다
        assertEquals(listOf("내일", "내일"), labels)
    }

    private fun event(start: String, alarmAt: String): EventDto = fromJson(
        """{"id":1,"title":"약속","start_at":"$start","source":"manual",
            "place":{"id":1,"name":"카페","lat":37.6,"lng":127.1},
            "alarm_enabled":true,"alarm_on":true,
            "alarm_plan":{"status":"ok","alarm_at":"$alarmAt","prep_minutes":30,"travel_minutes":40}}"""
    )
}
