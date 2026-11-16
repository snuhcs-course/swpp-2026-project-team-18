// AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.testing.fromJson
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AM/PM 옆에 놓이는 알람 시각은 12시간제 숫자다. AM/PM 없이 보이는 시각만 24시간제다.
 *
 * 예전에는 알람 결정 · 알람 등록(알람 화면 · 알림 · 놓친 알람 안내) · 미루기가 24시간제 "H:mm" 숫자에 AM/PM 을
 * 붙여 오후 알람이 "17:50 PM" 이었다. 같은 알람이 홈 행과 지각 위험 바꾸기 선택지에서는 "5:50 PM" 이었다.
 * 기존 테스트가 모두 오전 알람이라 드러나지 않았다.
 */
class MeridiemLabelTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val profile = fromJson<ProfileDto>("""{"has_home":true,"home_lat":37.48,"home_lng":126.95}""")

    /** 남은 시간 문구가 돌릴 때마다 바뀌지 않게 시계를 고정한다. 11월 8일 아침이다 */
    private val now = OffsetDateTime.parse("2026-11-08T09:00:00+09:00")

    /** [alarmAt] 에 울리는 켜진 일정. 시작은 알람 70분 뒤다 */
    private fun event(alarmAt: String): EventDto {
        val start = OffsetDateTime.parse(alarmAt).plusMinutes(70)
        return fromJson(
            """{"id":1,"title":"약속","start_at":"$start","source":"manual",
                "place":{"id":1,"name":"카페","lat":37.6,"lng":127.1},
                "alarm_enabled":true,"alarm_on":true,
                "alarm_plan":{"status":"ok","alarm_at":"$alarmAt","prep_minutes":30,"travel_minutes":40}}"""
        )
    }

    @Test
    fun `알람 결정 화면의 오후 알람은 12시간제 숫자에 PM 을 붙인다`() {
        // Given 한국 시간 17:50 에 울리는 알람
        val dto = event(alarmAt = "2026-11-09T08:50:00Z")

        // When 알람 결정 화면 값을 만든다
        val plan = dto.toPlanView(zone, now = now)!!

        // Then 예전에는 "17:50" 과 "PM" 이라 "17:50 PM" 으로 보였다
        assertEquals("5:50", plan.alarmAt)
        assertEquals("PM", plan.meridiem)
    }

    @Test
    fun `알람 등록 라벨과 결정 화면은 오전 오후 정오 자정을 같은 12시간제로 적는다`() {
        // Given 한국 시간 17:50 · 07:40 · 12:05 · 00:30 에 울리는 알람
        val cases = listOf(
            "2026-11-09T08:50:00Z" to ("5:50" to "PM"),
            "2026-11-08T22:40:00Z" to ("7:40" to "AM"),
            "2026-11-09T03:05:00Z" to ("12:05" to "PM"),
            "2026-11-08T15:30:00Z" to ("12:30" to "AM"),
        )

        cases.forEach { (alarmAt, expected) ->
            // When 알람 화면 · 알림이 쓰는 등록 값과 알람 결정 화면 값을 만든다
            val schedule = event(alarmAt).toSchedule(zone, profile)!!
            val plan = event(alarmAt).toPlanView(zone, now = now)!!

            // Then 둘 다 같은 숫자와 AM/PM 이다
            assertEquals(alarmAt, expected, schedule.alarmLabel to schedule.meridiem)
            assertEquals(alarmAt, expected, plan.alarmAt to plan.meridiem)
        }
    }

    @Test
    fun `미룬 알람의 라벨은 계획을 받을 때와 같은 12시간제다`() {
        // Given 17:50 · 07:40 에 울리는 알람을 받아 등록했다
        val evening = event(alarmAt = "2026-11-09T08:50:00Z").toSchedule(zone, profile)!!
        val morning = event(alarmAt = "2026-11-08T22:40:00Z").toSchedule(zone, profile)!!

        // When 각각 3분 미룬다
        val eveningSnoozed = evening.snoozedTo(evening.alarmAtMillis + 3 * MINUTE, zone)
        val morningSnoozed = morning.snoozedTo(morning.alarmAtMillis + 3 * MINUTE, zone)

        // Then 예전에는 오후에 미루면 "17:53" 과 "PM" 이었다. 계획의 알람 시각은 기록용으로 남는다
        assertEquals("5:53" to "PM", eveningSnoozed.alarmLabel to eveningSnoozed.meridiem)
        assertEquals("7:43" to "AM", morningSnoozed.alarmLabel to morningSnoozed.meridiem)
        assertEquals(evening.alarmAtMillis, eveningSnoozed.firstAlarmAtMillis)
    }

    @Test
    fun `AM PM 을 붙이지 않는 다음 알람 카드와 도착 예정은 24시간제 그대로다`() {
        // Given 17:50 에 울리고 19:00 에 시작하는 일정. 도착 예정은 18:55 다
        val dto = fromJson<EventDto>(
            """{"id":1,"title":"약속","start_at":"2026-11-09T10:00:00Z","source":"manual",
                "place":{"id":1,"name":"카페","lat":37.6,"lng":127.1},
                "alarm_enabled":true,"alarm_on":true,
                "alarm_plan":{"status":"ok","alarm_at":"2026-11-09T08:50:00Z","arrive_at":"2026-11-09T09:55:00Z",
                    "prep_minutes":30,"travel_minutes":40}}"""
        )

        // When 홈과 알람 결정 화면 값을 만든다
        val upcoming = dto.toUpcoming(zone)!!
        val plan = dto.toPlanView(zone, now = now)!!

        // Then 다음 알람 카드는 "17:50" 하나로, 홈 행은 "5:50" 과 "PM" 으로 보인다
        assertEquals("17:50", upcoming.alarmAt)
        assertEquals("5:50" to "PM", upcoming.alarmClock to upcoming.alarmMeridiem)
        assertEquals("18:55", plan.arrivalAt)
        assertEquals("18:55 도착 예정", plan.arrivalLine)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
