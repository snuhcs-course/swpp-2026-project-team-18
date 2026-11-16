// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.google.gson.Gson
import com.swpp.wakeup.data.local.withDiskDefaults
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 디스크에 저장된 알람 사본의 표시 라벨([AlarmSchedule.withClockLabel]).
 *
 * 라벨을 12시간제로 바꾸기 전 버전은 사본에 24시간제 "17:50" 을 저장했다. 그 사본은 다음 동기화나 미루기가 다시 쓸
 * 때까지 디스크에 남는다(재부팅 · 앱 업데이트 뒤 복원, 동기화가 지키는 미룬 알람, 놓친 알람). 그 전에 울리면 알람
 * 화면과 알림이 "17:50 PM" 이었다. 사본을 읽는 곳(`ScheduledAlarmStore`)은 라벨을 알람 시각에서 다시 만든다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 업데이트 전에 저장된 오후 · 자정 무렵 알람이 계획을 받을 때와 같은 12시간제로 읽힌다.
 * - 미뤄 둔 사본은 다시 울릴 시각으로 읽히고, 기록에 쓰는 계획의 알람 시각은 그대로다.
 * - 이미 맞는 사본은 바뀌지 않는다.
 */
class AlarmScheduleLabelTest {

    private val gson = Gson()
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun millis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** 업데이트 전 버전이 사본에 적던 JSON. 라벨은 24시간제 "H:mm" 이다 */
    private fun storedBeforeUpdate(alarmAt: String, label: String, meridiem: String, extra: String = ""): String =
        """
        {
          "eventId": 7,
          "alarmAtMillis": ${millis(alarmAt)},
          "startAtMillis": ${millis(alarmAt) + 70 * 60_000L},
          "alarmLabel": "$label",
          "meridiem": "$meridiem",
          "eventLine": "19:00 약속"$extra
        }
        """.trimIndent()

    /** `ScheduledAlarmStore` 가 사본을 읽는 순서 그대로다 */
    private fun readBack(json: String, zone: ZoneId = seoul): AlarmSchedule =
        gson.fromJson(json, AlarmSchedule::class.java).withDiskDefaults().withClockLabel(zone)

    @Test
    fun `업데이트 전에 저장된 오후 알람 사본도 12시간제 숫자로 읽힌다`() {
        // Given 업데이트 전 버전이 한국 시간 17:50 알람을 "17:50" 으로 저장했다
        val json = storedBeforeUpdate(alarmAt = "2026-11-09T17:50:00+09:00", label = "17:50", meridiem = "PM")

        // When 동기화 전에 그 사본을 읽는다(재부팅 · 앱 업데이트 뒤 복원, 울리는 순간)
        val schedule = readBack(json)

        // Then 알람 화면과 알림이 "17:50 PM" 이 아니라 "5:50 PM" 을 보인다
        assertEquals("5:50" to "PM", schedule.alarmLabel to schedule.meridiem)
    }

    @Test
    fun `자정 무렵과 정오 무렵 알람도 계획을 받을 때와 같은 12시간제로 읽힌다`() {
        // Given 업데이트 전 버전이 24시간제로 저장한 00:30 · 12:05 · 07:40 알람
        val cases = listOf(
            Triple("2026-11-09T00:30:00+09:00", "0:30", "12:30" to "AM"),
            Triple("2026-11-09T12:05:00+09:00", "12:05", "12:05" to "PM"),
            Triple("2026-11-09T07:40:00+09:00", "7:40", "7:40" to "AM"),
        )

        cases.forEach { (alarmAt, storedLabel, expected) ->
            // When 사본을 읽는다(AM/PM 은 예전에도 맞게 적혔다)
            val schedule = readBack(storedBeforeUpdate(alarmAt, storedLabel, meridiem = expected.second))

            // Then 예전 "0:30 AM" 도 "12:30 AM" 이 된다
            assertEquals(alarmAt, expected, schedule.alarmLabel to schedule.meridiem)
        }
    }

    @Test
    fun `업데이트 전에 미뤄 둔 사본은 다시 울릴 시각으로 읽히고 계획의 알람 시각은 그대로다`() {
        // Given 17:50 알람을 5분 미뤄 17:55 에 다시 울릴 사본. 예전 미루기는 라벨을 "17:55" 로 적었다
        val planned = millis("2026-11-09T17:50:00+09:00")
        val json = storedBeforeUpdate(
            alarmAt = "2026-11-09T17:55:00+09:00",
            label = "17:55",
            meridiem = "PM",
            extra = """, "firstAlarmAtMillis": $planned""",
        )

        // When 사본을 읽는다(동기화가 미룬 알람을 지킬 때도 이 값을 그대로 옮긴다)
        val schedule = readBack(json)

        // Then 다시 울릴 때 "5:55 PM" 이고, 서버에 보내는 alarm_at 은 처음 계획한 17:50 이다
        assertEquals("5:55" to "PM", schedule.alarmLabel to schedule.meridiem)
        assertEquals(planned, schedule.plannedAlarmAtMillis)
    }

    @Test
    fun `이미 12시간제로 저장된 사본은 그대로 읽힌다`() {
        // Given 지금 버전이 계획을 받아 저장한 17:50 알람
        val now = AlarmSchedule(
            eventId = 7,
            alarmAtMillis = millis("2026-11-09T17:50:00+09:00"),
            departByMillis = millis("2026-11-09T18:20:00+09:00"),
            arriveAtMillis = millis("2026-11-09T18:55:00+09:00"),
            startAtMillis = millis("2026-11-09T19:00:00+09:00"),
            alarmLabel = "5:50",
            meridiem = "PM",
            eventLine = "19:00 약속",
            placeName = "카페",
            arrivalLine = "18:55 도착 예정",
            prepMinutes = 30,
            prepBlocks = listOf(ScheduledBlock(1, "샤워", 12.0)),
        )

        // When 디스크에 썼다가 읽는다
        val schedule = readBack(gson.toJson(now))

        // Then 라벨도 다른 값도 그대로다
        assertEquals(now, schedule)
    }
}
