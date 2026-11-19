// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 울렸고 아직 끝나지 않은 아침(11-17 "죽과이": 울리자 다음 알람이 맨 위로 올라갔고, 추적이 강제 종료로 끊긴 뒤 다시
 * 이어지지 않았다).
 */
class TripResumeTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L

    /** 11-17 10:00 죽과이, 알람 8:13 */
    private val start = ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val alarm = start - 107 * minute

    private fun event(id: Long, startAt: Long, alarmAt: Long?, skipped: Boolean = false, alarmOn: Boolean = true) =
        UpcomingEvent(
            id = id, startTime = "10:00", dayLabel = "화", title = "일정 $id", placeAndRoute = "서울대",
            alarmAt = alarmAt?.let { "8:13" }, onTimeProbability = 90, tag = null, planStatus = "ok",
            planStatusLabel = null, startAtEpochSecond = startAt / 1000,
            startDate = java.time.Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate(),
            alarmOn = alarmOn, alarmAtEpochSecond = alarmAt?.div(1000), skipped = skipped,
        )

    private val jukgwai = event(1, start, alarm)
    private val tomorrow = event(2, start + 24 * 60 * minute, alarm + 24 * 60 * minute)

    private fun view(at: Long, arrived: Set<Long> = emptySet(), vararg events: UpcomingEvent = arrayOf(jukgwai, tomorrow)) =
        HomeList.view(events.toList(), at, arrived, emptyMap(), zone)

    @Test
    fun `울리기 전에는 진행 중이 없고 그 일정이 다음 알람이다`() {
        val v = view(alarm - minute)
        assertNull(v.ongoing)
        assertEquals(1L, v.nextAlarm?.id)
    }

    @Test
    fun `울린 직후에는 그 일정이 진행 중이고 다음 알람은 내일 것이다`() {
        val v = view(alarm + minute)
        assertEquals(1L, v.ongoing?.id)
        assertEquals(2L, v.nextAlarm?.id)
    }

    @Test
    fun `출발 뒤 일정 시작을 넘겨도 도착 전이면 추적 마감까지 진행 중이다`() {
        assertEquals(1L, view(start + 30 * minute).ongoing?.id)
        assertNull("추적 마감(시작 + 1시간)이 지나면 끝", view(start + HomeList.OPEN_TRIP_MILLIS).ongoing)
    }

    @Test
    fun `도착했으면 진행 중이 아니다`() {
        assertNull(view(alarm + 60 * minute, arrived = setOf(1L)).ongoing)
    }

    @Test
    fun `안 감이거나 알람을 끈 일정은 진행 중이 아니다`() {
        assertNull(view(alarm + minute, emptySet(), event(1, start, alarm, skipped = true)).ongoing)
        assertNull(view(alarm + minute, emptySet(), event(1, start, alarm, alarmOn = false)).ongoing)
    }

    @Test
    fun `다음 날에는 어제 일정이 끝나 내일 일정만 남는다`() {
        val v = view(start + 20 * 60 * minute)
        assertNull(v.ongoing)
        assertEquals(2L, v.nextAlarm?.id)
    }

    @Test
    fun `단계는 이 일정을 추적 중일 때만 준비 중 · 이동 중이고 아니면 기록 멈춤이다`() {
        assertEquals(TripResume.Stage.PREPARING, TripResume.stage(1, trackingEventId = 1, inTransit = false))
        assertEquals(TripResume.Stage.IN_TRANSIT, TripResume.stage(1, trackingEventId = 1, inTransit = true))
        assertEquals(TripResume.Stage.NOT_TRACKING, TripResume.stage(1, trackingEventId = null, inTransit = false))
        assertEquals(TripResume.Stage.NOT_TRACKING, TripResume.stage(1, trackingEventId = 2, inTransit = true))
    }

    @Test
    fun `다음 할 일 한 줄`() {
        assertEquals("오전 9:18까지 출발해요", TripResume.nextLine(TripResume.Stage.PREPARING, "오전 9:18", null, "오전 10:00"))
        assertEquals("오전 9:45 도착 예정이에요", TripResume.nextLine(TripResume.Stage.IN_TRANSIT, null, "오전 9:45", "오전 10:00"))
        assertEquals(
            "출발과 도착을 기록하려면 다시 시작해 주세요",
            TripResume.nextLine(TripResume.Stage.NOT_TRACKING, "오전 9:18", null, "오전 10:00"),
        )
    }

    private fun schedule(id: Long = 1) = AlarmSchedule(
        eventId = id, alarmAtMillis = alarm, departByMillis = alarm + 65 * minute, arriveAtMillis = alarm + 92 * minute,
        startAtMillis = start, alarmLabel = "8:13", meridiem = "AM", eventLine = "", placeName = null, arrivalLine = null,
    )

    @Test
    fun `강제 종료로 끊긴 추적은 앱을 열 때 잇는다`() {
        assertEquals(1L, TripResume.toResume(schedule(), trackingEventId = null, arrived = emptySet(), nowMillis = alarm + 70 * minute)?.eventId)
    }

    @Test
    fun `이미 추적 중 · 도착 · 마감 뒤 · 알람 전에는 잇지 않는다`() {
        val s = schedule()
        assertNull(TripResume.toResume(s, trackingEventId = 1, arrived = emptySet(), nowMillis = alarm + minute))
        assertNull(TripResume.toResume(s, trackingEventId = null, arrived = setOf(1L), nowMillis = alarm + minute))
        assertNull(TripResume.toResume(s, trackingEventId = null, arrived = emptySet(), nowMillis = s.trackingDeadlineMillis))
        assertNull(TripResume.toResume(s, trackingEventId = null, arrived = emptySet(), nowMillis = alarm - minute))
        assertNull("정상으로 끝난 추적은 사본이 없다", TripResume.toResume(null, null, emptySet(), alarm + minute))
    }
}
