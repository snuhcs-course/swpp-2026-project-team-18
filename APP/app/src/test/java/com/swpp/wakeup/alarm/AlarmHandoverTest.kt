package com.swpp.wakeup.alarm

// AI-generated (Claude)

import com.swpp.wakeup.domain.model.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/**
 * 알람 화면이 떠 있는 동안 들어온 알람으로 화면을 넘길지(STAT-7).
 *
 * 화면은 앞 알람을 그리는데 해제·미루기는 새 알람에 적히던 문제다. 넘길 때는 앞 알람을 응답 없이
 * 끝났다고 한 번만 적고, 같은 알람이 다시 들어온 것(전체화면 알림과 직접 시작, 알림 누름)은 그대로 둔다.
 */
class AlarmHandoverTest {

    private fun at(hhmm: String): Long =
        OffsetDateTime.parse("2026-11-09T$hhmm:00+09:00").toInstant().toEpochMilli()

    private fun schedule(eventId: Long, alarm: String) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at(alarm),
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = at("10:00"),
        alarmLabel = alarm,
        meridiem = "AM",
        eventLine = "10:00 수업 $eventId",
        placeName = null,
        arrivalLine = null,
    )

    private val first = schedule(eventId = 1, alarm = "07:00")
    private val second = schedule(eventId = 2, alarm = "07:02")

    /** 수신기가 울렸다고 표시한 알람. 둘 다 울렸다 */
    private val rung = setOf(MissedAlarms.key(first), MissedAlarms.key(second))

    @Test
    fun `다른 일정의 알람이 울리면 화면이 그 알람으로 넘어가고 앞 알람은 응답 없이 끝났다고 적는다`() {
        // Given 07:00 알람이 울리는 중
        // When 07:02 에 다른 일정의 알람이 같은 화면으로 들어온다
        val handover = AlarmHandover.of(first, incoming = second, silencedKeys = emptySet(), rungKeys = rung)

        // Then 화면·해제·미루기가 07:02 알람을 맡고, 07:00 알람은 응답 없이 끝났다고 남긴다
        assertEquals(second, handover?.next)
        assertEquals(first, handover?.unanswered)
    }

    @Test
    fun `소리를 자동으로 멈추며 이미 적은 알람은 넘길 때 다시 적지 않는다`() {
        // Given 07:00 알람이 5분 동안 응답이 없어 소리가 멈췄고 그때 이미 적었다
        val silenced = setOf(MissedAlarms.key(first))

        // When 다른 일정의 알람이 들어온다
        val handover = AlarmHandover.of(first, incoming = second, silencedKeys = silenced, rungKeys = rung)

        // Then 넘어가되 07:00 알람을 두 번 적지 않는다
        assertEquals(second, handover?.next)
        assertNull(handover?.unanswered)
    }

    @Test
    fun `미뤘다가 다시 울린 알람도 다른 알람이 떠 있으면 그 화면을 넘겨받는다`() {
        // Given 07:00 알람을 미뤘고, 그 사이 울린 07:02 알람의 화면이 떠 있다
        val snoozed = first.snoozedTo(atMillis = at("07:05"), label = "7:05", meridiem = "AM")

        // When 07:05 에 미룬 알람이 다시 울린다
        val handover = AlarmHandover.of(
            second,
            incoming = snoozed,
            silencedKeys = emptySet(),
            rungKeys = rung + MissedAlarms.key(snoozed),
        )

        // Then 화면이 미룬 알람으로 넘어가고 07:02 알람은 응답 없이 끝났다고 남긴다
        assertEquals(snoozed, handover?.next)
        assertEquals(second, handover?.unanswered)
    }

    @Test
    fun `같은 알람이 다시 들어오면 넘기지 않는다`() {
        // Given 07:00 알람이 떠 있고 소리가 이미 멈췄다
        val silenced = setOf(MissedAlarms.key(first))

        // When 전체화면 알림과 직접 시작이 한 번 더 오거나, 사용자가 알림을 누른다
        val handover = AlarmHandover.of(first, incoming = first, silencedKeys = silenced, rungKeys = rung)

        // Then 그대로 둔다. 넘기면 멈춘 소리가 다시 울린다
        assertNull(handover)
    }

    @Test
    fun `넘겨준 알람의 인텐트가 늦게 와도 그 알람으로 되돌아가지 않는다`() {
        // Given 거의 같은 순간에 두 알람이 울려 07:00 알람에서 07:02 알람으로 이미 넘어갔다
        val silenced = setOf(MissedAlarms.key(first))

        // When 07:00 알람의 전체화면 알림이 뒤늦게 들어온다
        val handover = AlarmHandover.of(second, incoming = first, silencedKeys = silenced, rungKeys = rung)

        // Then 마지막에 울린 07:02 알람을 지킨다
        assertNull(handover)
    }

    @Test
    fun `사본에서 빠진 일정의 인텐트는 지금 알람을 지킨다`() {
        // Given 07:00 알람이 떠 있다
        // When 동기화가 지운 일정의 인텐트가 들어와 사본에서 찾지 못한다
        val handover = AlarmHandover.of(first, incoming = null, silencedKeys = emptySet(), rungKeys = rung)

        // Then 넘기지 않는다
        assertNull(handover)
    }

    @Test
    fun `같은 일정이 다른 시각으로 다시 울리면 넘어간다`() {
        // Given 07:00 알람이 떠 있고 소리가 멈췄다. 그사이 동기화가 같은 일정을 07:20 으로 다시 걸었다
        val moved = schedule(eventId = 1, alarm = "07:20")
        val silenced = setOf(MissedAlarms.key(first))

        // When 07:20 에 그 알람이 울려 같은 화면으로 들어온다
        val handover = AlarmHandover.of(
            first,
            incoming = moved,
            silencedKeys = silenced,
            rungKeys = rung + MissedAlarms.key(moved),
        )

        // Then 07:20 알람으로 넘어간다. 07:00 알람은 이미 적었다
        assertEquals(moved, handover?.next)
        assertNull(handover?.unanswered)
    }

    @Test
    fun `같은 일정의 시각이 뒤로 옮겨졌을 뿐 아직 울리지 않았으면 넘기지 않는다`() {
        // Given 07:00 알람이 떠 있는데 동기화가 사본의 시각을 07:30 으로 옮겼다
        val later = schedule(eventId = 1, alarm = "07:30")

        // When 사용자가 알림을 눌러 같은 일정의 인텐트가 들어온다
        val handover = AlarmHandover.of(first, incoming = later, silencedKeys = emptySet(), rungKeys = rung)

        // Then 실제로 울린 07:00 알람을 지킨다. 아직 오지 않은 시각을 띄우지 않는다
        assertNull(handover)
    }
}
