package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.swpp.wakeup.domain.model.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * 울리지 않고 지나간 알람의 판별.
 *
 * 10-27 실기기 사례를 기준으로 둔다 — 08:25 알람, 09:25 출발, 10:00 일정. 강제
 * 중지가 알람을 지웠고 사용자는 08:28 에 앱을 열었다. 그때 "안 울렸다" 가 떠야
 * 하고, 정상으로 울린 알람에는 뜨면 안 된다.
 */
class MissedAlarmsTest {

    private fun at(hhmm: String): Long =
        OffsetDateTime.parse("2026-10-27T$hhmm:00+09:00").toInstant().toEpochMilli()

    private fun schedule(
        eventId: Long = 130,
        alarm: String = "08:25",
        depart: String? = "09:25",
        start: String = "10:00",
    ) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at(alarm),
        departByMillis = depart?.let(::at),
        arriveAtMillis = null,
        startAtMillis = at(start),
        alarmLabel = alarm,
        meridiem = "AM",
        eventLine = "$start 수업",
        placeName = null,
        arrivalLine = null,
    )

    @Test
    fun `울린 기록 없이 지난 알람은 출발 전이면 놓친 것이다`() {
        assertTrue(MissedAlarms.isMissed(schedule(), emptySet(), at("08:28")))
    }

    @Test
    fun `울린 알람은 놓친 것이 아니다`() {
        val handled = setOf(MissedAlarms.key(schedule()))
        assertFalse(MissedAlarms.isMissed(schedule(), handled, at("08:28")))
    }

    @Test
    fun `발화가 배달되는 동안은 놓쳤다고 하지 않는다`() {
        // 08:25 알람을 08:26 에 아직 못 받았을 수 있다. 그 사이에 홈을 열어도
        // 곧 울릴 알람에 "안 울렸다" 를 띄우면 안 된다.
        val justAfter = at("08:25") + MissedAlarms.GRACE_MILLIS - 1
        assertFalse(MissedAlarms.isMissed(schedule(), emptySet(), justAfter))
    }

    @Test
    fun `출발 시각이 지나면 알리지 않는다`() {
        assertFalse(MissedAlarms.isMissed(schedule(), emptySet(), at("09:25")))
    }

    @Test
    fun `출발 시각이 없으면 일정 시작까지 알린다`() {
        val noDepart = schedule(depart = null)
        assertTrue(MissedAlarms.isMissed(noDepart, emptySet(), at("09:50")))
        assertFalse(MissedAlarms.isMissed(noDepart, emptySet(), at("10:00")))
    }

    @Test
    fun `미룬 알람은 시각이 달라 앞 알람의 표시로 덮이지 않는다`() {
        val first = schedule(alarm = "08:25")
        val snoozed = schedule(alarm = "08:30")
        val handled = setOf(MissedAlarms.key(first))
        assertTrue(MissedAlarms.isMissed(snoozed, handled, at("08:40")))
    }

    @Test
    fun `앞으로 울릴 알람이 다시 걸린 일정은 고르지 않는다`() {
        val result = MissedAlarms.select(
            candidates = listOf(schedule()),
            handled = emptySet(),
            upcomingEventIds = setOf(130L),
            nowMillis = at("08:28"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `같은 일정은 가장 늦은 알람 하나만 고르고 알람 시각 순이다`() {
        val result = MissedAlarms.select(
            candidates = listOf(
                schedule(eventId = 2, alarm = "08:10", depart = "09:30"),
                schedule(eventId = 130, alarm = "08:25"),
                schedule(eventId = 130, alarm = "08:20"),
            ),
            handled = emptySet(),
            upcomingEventIds = emptySet(),
            nowMillis = at("08:28"),
        )
        assertEquals(listOf(2L, 130L), result.map { it.eventId })
        assertEquals(at("08:25"), result.last().alarmAtMillis)
    }

    @Test
    fun `하루 지난 처리 표시는 버리고 모양이 다른 값도 버린다`() {
        val old = "1@${at("08:25") - MissedAlarms.HANDLED_TTL_MILLIS}"
        val recent = MissedAlarms.key(schedule())
        val pruned = MissedAlarms.pruneHandled(setOf(old, recent, "garbage"), at("08:28"))
        assertEquals(setOf(recent), pruned)
    }
}
