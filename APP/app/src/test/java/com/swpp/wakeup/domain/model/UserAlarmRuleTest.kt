// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.data.remote.isUserAlarm
import com.swpp.wakeup.data.remote.ringAt
import com.swpp.wakeup.data.remote.ringProbability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 휠로 고른 시각 저장(iter4 H2-3 · BE-H3b): 어느 길로 갈지 · 서버 버전 잠금 · 보낼 시각 */
class UserAlarmRuleTest {

    private val presets = mapOf("safe" to 7 * 60 + 20, "normal" to 7 * 60 + 40, "gamble" to 8 * 60 + 5)
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `세 칸과 같은 시각이면 버전과 상관없이 그 칸으로`() {
        assertEquals(UserAlarmRule.Save.Preset("normal"), UserAlarmRule.choose(7 * 60 + 40, presets, supported = false))
        assertEquals(UserAlarmRule.Save.Preset("normal"), UserAlarmRule.choose(7 * 60 + 40, presets, supported = true))
    }

    @Test
    fun `세 칸 밖이면 0_21_0 부터 직접 정한 시각으로 아니면 잠근다`() {
        assertEquals(UserAlarmRule.Save.Custom(7 * 60 + 50), UserAlarmRule.choose(7 * 60 + 50, presets, supported = true))
        assertEquals(UserAlarmRule.Save.Locked, UserAlarmRule.choose(7 * 60 + 50, presets, supported = false))
        assertTrue(UserAlarmRule.supported("0.21.0"))
        assertTrue(UserAlarmRule.supported("0.22.1"))
        assertFalse(UserAlarmRule.supported("0.20.0"))
        assertFalse("모르면 부르지 않는다", UserAlarmRule.supported(null))
    }

    @Test
    fun `알람 날짜는 시작보다 앞선 가장 가까운 그 시각`() {
        val start = ZonedDateTime.of(2026, 11, 17, 9, 0, 0, 0, seoul)
        assertEquals(ZonedDateTime.of(2026, 11, 17, 7, 50, 0, 0, seoul), UserAlarmRule.alarmDateTime(7 * 60 + 50, start))
        // 전날 밤 알람
        assertEquals(ZonedDateTime.of(2026, 11, 16, 23, 30, 0, 0, seoul), UserAlarmRule.alarmDateTime(23 * 60 + 30, start))
        // 시작과 같은 시각은 하루 전(서버가 범위 밖이라고 알려 준다)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, seoul), UserAlarmRule.alarmDateTime(9 * 60, start))
        // 자정 넘어 시작하는 일정
        val late = ZonedDateTime.of(2026, 11, 17, 0, 30, 0, 0, seoul)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 23, 0, 0, 0, seoul), UserAlarmRule.alarmDateTime(23 * 60, late))
    }

    @Test
    fun `보낼 시각에는 오프셋을 붙이고 초를 버린다`() {
        val at = ZonedDateTime.of(2026, 11, 17, 8, 10, 42, 0, seoul)
        assertEquals("2026-11-17T08:10:00+09:00", UserAlarmRule.wire(at))
    }

    @Test
    fun `기기 알람과 화면은 울릴 시각을 쓴다`() {
        val gson = Gson()
        val user = gson.fromJson(
            """{"status":"ok","alarm_at":"2026-11-17T07:54:00+09:00","on_time_probability":97,
               "effective_alarm_at":"2026-11-17T08:10:00+09:00","effective_on_time_probability":46,
               "alarm_source":"user"}""",
            AlarmPlanDto::class.java,
        )
        assertEquals("2026-11-17T08:10:00+09:00", user.ringAt)
        assertEquals(46, user.ringProbability)
        assertTrue(user.isUserAlarm)

        // 구버전 서버(새 필드 없음)는 지금처럼 alarm_at
        val old = gson.fromJson(
            """{"status":"ok","alarm_at":"2026-11-17T07:54:00+09:00","on_time_probability":97}""",
            AlarmPlanDto::class.java,
        )
        assertEquals("2026-11-17T07:54:00+09:00", old.ringAt)
        assertEquals(97, old.ringProbability)
        assertFalse(old.isUserAlarm)
    }
}
