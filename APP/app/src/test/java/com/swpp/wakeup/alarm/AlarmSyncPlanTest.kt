// Partly AI-generated (Claude)
package com.swpp.wakeup.alarm

import com.swpp.wakeup.domain.model.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSyncPlanTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val horizon = 7L * 24 * 60 * minute

    private fun schedule(eventId: Long, at: Long, label: String = "7:40") = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = at + 60 * minute,
        alarmLabel = label,
        meridiem = "AM",
        eventLine = "09:00 일정 $eventId",
        placeName = null,
        arrivalLine = null,
    )

    private fun plan(before: List<AlarmSchedule>, server: List<AlarmSchedule>) =
        AlarmSyncPlan.upcoming(before, server, now, horizon, max = 20)

    @Test
    fun `서버 시각이 지났어도 미룬 알람은 미룬 시각으로 남는다`() {
        val server = schedule(1, now - 2 * minute, label = "7:38")
        val snoozed = schedule(1, now + 3 * minute, label = "7:43")

        val result = plan(listOf(snoozed), listOf(server))

        assertEquals(listOf(now + 3 * minute), result.map { it.alarmAtMillis })
        assertEquals("7:43", result.single().alarmLabel)
    }

    @Test
    fun `미룬 알람을 지켜도 처음 알람 시각을 함께 지킨다`() {
        val server = schedule(1, now - 2 * minute, label = "7:38")
        val snoozed = schedule(1, now + 3 * minute, label = "7:43")
            .copy(firstAlarmAtMillis = now - 2 * minute)

        val result = plan(listOf(snoozed), listOf(server))

        assertEquals(now + 3 * minute, result.single().alarmAtMillis)
        assertEquals(now - 2 * minute, result.single().firstAlarmAtMillis)
    }

    @Test
    fun `서버가 시각을 옮겨 서버 것이 이기면 처음 알람 시각은 비어 있다`() {
        val snoozed = schedule(1, now + 3 * minute).copy(firstAlarmAtMillis = now - 2 * minute)
        val moved = schedule(1, now + 30 * minute)

        assertEquals(null, plan(listOf(snoozed), listOf(moved)).single().firstAlarmAtMillis)
    }

    @Test
    fun `서버 목록에서 빠진 일정의 미룬 알람은 버린다`() {
        val snoozed = schedule(1, now + 3 * minute)

        assertEquals(emptyList<AlarmSchedule>(), plan(listOf(snoozed), emptyList()))
    }

    @Test
    fun `서버가 시각을 앞으로 옮겼으면 서버 시각이 이긴다`() {
        val snoozed = schedule(1, now + 3 * minute)
        val moved = schedule(1, now + 30 * minute)

        assertEquals(listOf(now + 30 * minute), plan(listOf(snoozed), listOf(moved)).map { it.alarmAtMillis })
    }

    @Test
    fun `지난 알람은 등록하지 않고 앞으로의 알람은 시각 순으로 등록한다`() {
        val past = schedule(1, now - minute)
        val later = schedule(2, now + 90 * minute)
        val sooner = schedule(3, now + 10 * minute)
        val tooFar = schedule(4, now + horizon + minute)

        assertEquals(listOf(3L, 2L), plan(emptyList(), listOf(past, later, sooner, tooFar)).map { it.eventId })
    }
}
