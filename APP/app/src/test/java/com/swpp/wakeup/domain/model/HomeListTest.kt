// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeListTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L

    /** 2026-11-02 08:00 KST */
    private val now = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        startAt: Long,
        alarmAt: Long? = startAt - 90 * minute,
        alarmOn: Boolean = true,
        skipped: Boolean = false,
    ): UpcomingEvent {
        val date = java.time.Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()
        return UpcomingEvent(
            id = id,
            startTime = "09:00",
            dayLabel = "월",
            title = "일정 $id",
            placeAndRoute = "서울대",
            alarmAt = alarmAt?.let { "7:30" },
            onTimeProbability = 90,
            tag = null,
            planStatus = "ok",
            planStatusLabel = null,
            startAtEpochSecond = startAt / 1000,
            startDate = date,
            alarmOn = alarmOn,
            alarmAtEpochSecond = alarmAt?.div(1000),
            skipped = skipped,
        )
    }

    private fun device(eventId: Long, at: Long, label: String) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = at + 60 * minute,
        alarmLabel = label,
        meridiem = "AM",
        eventLine = "",
        placeName = null,
        arrivalLine = null,
    )

    private fun view(
        vararg events: UpcomingEvent,
        arrived: Set<Long> = emptySet(),
        device: Map<Long, AlarmSchedule> = emptyMap(),
        at: Long = now,
    ) = HomeList.view(events.toList(), at, arrived, device, zone)

    @Test
    fun `시작한 일정은 도착 전이면 한 시간 동안 남고 그 뒤에 빠진다`() {
        val started = event(1, startAt = now - 30 * minute)

        assertTrue(HomeList.isVisible(started, now, emptySet()))
        assertTrue(HomeList.isVisible(started, now + 29 * minute, emptySet()))
        assertFalse("시작 + 60분이면 빠진다", HomeList.isVisible(started, now + 30 * minute, emptySet()))
    }

    @Test
    fun `시작 뒤 도착한 일정은 바로 빠지고 시작 전 도착은 시작까지 남는다`() {
        val started = event(1, startAt = now - 10 * minute)
        val soon = event(2, startAt = now + 10 * minute)

        assertFalse(HomeList.isVisible(started, now, setOf(1L)))
        assertTrue(HomeList.isVisible(soon, now, setOf(2L)))
        assertFalse(HomeList.isVisible(soon, now + 10 * minute, setOf(2L)))
    }

    @Test
    fun `울린 알람은 다음 알람이 아니고 그다음 알람이 카드에 온다`() {
        val rung = event(1, startAt = now + 30 * minute, alarmAt = now - 5 * minute)
        val later = event(2, startAt = now + 5 * 60 * minute)

        val v = view(rung, later)

        assertEquals(2L, v.nextAlarm?.id)
        assertEquals("울린 일정도 목록에는 남는다", 2, v.totalCount)
    }

    @Test
    fun `미룬 알람은 미룬 시각으로 다음 알람에 남는다`() {
        val snoozed = event(1, startAt = now + 30 * minute, alarmAt = now - 2 * minute)
        val later = event(2, startAt = now + 5 * 60 * minute)

        val v = view(snoozed, later, device = mapOf(1L to device(1, now + 3 * minute, "8:03")))

        assertEquals(1L, v.nextAlarm?.id)
        assertEquals("8:03", v.nextAlarm?.alarmAt)
        assertEquals("미룬 알람이 울리는 순간 다시 그린다", now + 3 * minute, v.nextChangeMillis)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `미룬 알람은 카드의 큰 시각과 남은 시간도 미룬 시각으로 바꾼다`() {
        val snoozed = event(1, startAt = now + 6 * 60 * minute, alarmAt = now - 2 * minute)
        // 13:03 으로 미룸(device 라벨은 24시간제)
        val at = now + 5 * 60 * minute + 3 * minute

        val next = view(snoozed, device = mapOf(1L to device(1, at, "13:03"))).nextAlarm

        assertEquals("1:03", next?.alarmClock)
        assertEquals("PM", next?.alarmMeridiem)
        assertEquals(at, next?.alarmAtMillis)
    }

    @Test
    fun `안 감 일정과 꺼진 알람은 다음 알람이 아니다`() {
        val skipped = event(1, startAt = now + 60 * minute, skipped = true)
        val off = event(2, startAt = now + 70 * minute, alarmOn = false)

        assertNull(view(skipped, off).nextAlarm)
    }

    @Test
    fun `다시 거를 시각은 가장 이른 경계이고 기다림은 5분을 넘지 않는다`() {
        val started = event(1, startAt = now - 50 * minute, alarmAt = null)

        val v = view(started)

        assertEquals(now + 10 * minute, v.nextChangeMillis)
        assertEquals(5 * minute + 500, HomeList.recheckDelayMillis(v.nextChangeMillis, now))
        assertEquals(500L, HomeList.recheckDelayMillis(now - minute, now))
    }

    @Test
    fun `안 감으로 표시는 오늘까지의 도착하지 않은 일정에만 나온다`() {
        val today = event(1, startAt = now + 60 * minute)
        val arrived = event(2, startAt = now + 30 * minute)
        val tomorrow = event(3, startAt = now + 26 * 60 * minute)
        val skipped = event(4, startAt = now + 90 * minute, skipped = true)

        val v = view(today, arrived, tomorrow, skipped, arrived = setOf(2L))

        assertEquals(setOf(1L), v.markable)
    }

    @Test
    fun `묶음 이름은 오늘 내일 그리고 날짜다`() {
        val v = view(
            event(1, startAt = now + 60 * minute),
            event(2, startAt = now + 25 * 60 * minute),
            event(3, startAt = now + 49 * 60 * minute),
        )

        assertEquals(listOf("오늘", "내일", "11월 4일 수"), v.sections.map { it.label })
        assertEquals(LocalDate.of(2026, 11, 2), v.today)
    }

    @Test
    fun `서버에는 지금보다 5시간 전부터 요청한다`() {
        assertEquals("2026-11-01T18:00:00Z", HomeList.serverFrom(now))
    }

    @Test
    fun `화면 복귀 새로고침은 1분에 한 번이고 다른 요청 중에는 하지 않는다`() {
        assertTrue(HomeRefresh.shouldRefreshOnResume(10_000, null, inFlight = false, mutating = false))
        assertFalse(HomeRefresh.shouldRefreshOnResume(70_000, 20_000, inFlight = false, mutating = false))
        assertTrue(HomeRefresh.shouldRefreshOnResume(80_000, 20_000, inFlight = false, mutating = false))
        assertFalse(HomeRefresh.shouldRefreshOnResume(80_000, 20_000, inFlight = true, mutating = false))
        assertFalse(HomeRefresh.shouldRefreshOnResume(80_000, 20_000, inFlight = false, mutating = true))
    }
}
