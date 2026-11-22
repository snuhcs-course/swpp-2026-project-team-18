package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.swpp.wakeup.domain.model.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * [AlarmScheduler] 에서 떼어 낸 값 판단. PendingIntent 의 requestCode 가 용도끼리 겹치면
 * 한쪽을 해제할 때 다른 알람이 지워지고, 라벨이 시각과 어긋나면 미룬 알람이 원래 시각으로 보인다.
 */
class AlarmSchedulerLogicTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun schedule(
        eventId: Long = 7,
        proposed: Long? = null,
        answerBy: Long? = null,
    ) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at("2026-11-23T07:00:00+09:00"),
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = at("2026-11-23T09:00:00+09:00"),
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "09:00 수업",
        placeName = null,
        arrivalLine = null,
        proposedAlarmAtMillis = proposed,
        proposalAnswerByMillis = answerBy,
    )

    @Test
    fun `발화 requestCode 는 일정 id 그대로다`() {
        assertEquals(130, AlarmRequestCodes.fire(130L))
    }

    @Test
    fun `표시와 마감 requestCode 는 발화 구간과 겹치지 않는다`() {
        val id = 130L
        val codes = setOf(AlarmRequestCodes.fire(id), AlarmRequestCodes.show(id), AlarmRequestCodes.deadline(id))
        assertEquals(3, codes.size)
        assertEquals(1_000_130, AlarmRequestCodes.show(id))
        assertEquals(4_000_130, AlarmRequestCodes.deadline(id))
    }

    @Test
    fun `일정마다 data URI 가 달라 PendingIntent 가 구별된다`() {
        assertEquals("jit://alarm/5", AlarmRequestCodes.fireUri(5))
        assertEquals("jit://proposal/5", AlarmRequestCodes.deadlineUri(5))
        assertNotEquals(AlarmRequestCodes.fireUri(5), AlarmRequestCodes.fireUri(6))
    }

    @Test
    fun `오전 시각은 12시간제 라벨과 AM 이다`() {
        assertEquals("7:05" to "AM", alarmClockLabel(at("2026-11-23T07:05:00+09:00"), seoul))
    }

    @Test
    fun `정오와 오후는 PM 이고 12시간제로 적는다`() {
        assertEquals("12:00" to "PM", alarmClockLabel(at("2026-11-23T12:00:00+09:00"), seoul))
        assertEquals("1:30" to "PM", alarmClockLabel(at("2026-11-23T13:30:00+09:00"), seoul))
    }

    @Test
    fun `자정 직후는 12시 AM 이다`() {
        assertEquals("12:10" to "AM", alarmClockLabel(at("2026-11-23T00:10:00+09:00"), seoul))
    }

    @Test
    fun `라벨은 기기 시간대 기준이다`() {
        val instant = at("2026-11-23T07:00:00+09:00")
        assertEquals("10:00" to "PM", alarmClockLabel(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `미루기는 지금부터 분 단위로 뒤로 민다`() {
        assertEquals(1_000L + 5 * 60_000L, snoozeAtMillis(1_000L, 5))
        assertEquals(1_000L, snoozeAtMillis(1_000L, 0))
    }

    @Test
    fun `답을 기다리는 제안만 마감 작업을 건다`() {
        val now = at("2026-11-23T06:00:00+09:00")
        val waiting = schedule(eventId = 1, proposed = now + 1, answerBy = now + 60_000)
        val noProposal = schedule(eventId = 2, proposed = null, answerBy = now + 60_000)
        val noDeadline = schedule(eventId = 3, proposed = now + 1, answerBy = null)
        val expired = schedule(eventId = 4, proposed = now + 1, answerBy = now)

        val armed = proposalDeadlinesToArm(listOf(waiting, noProposal, noDeadline, expired), now)

        assertEquals(listOf(waiting to now + 60_000), armed)
    }

    @Test
    fun `걸 제안이 없으면 비어 있다`() {
        assertTrue(proposalDeadlinesToArm(emptyList(), 0L).isEmpty())
    }
}
