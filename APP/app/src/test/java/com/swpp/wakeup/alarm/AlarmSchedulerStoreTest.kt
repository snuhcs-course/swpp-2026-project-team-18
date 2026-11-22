package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.swpp.wakeup.data.remote.AlarmLogInput
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.prefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AlarmScheduler] 가 디스크 사본을 어떻게 맞추는지.
 *
 * JVM 에서는 `AlarmManager` 를 얻을 수 없어(스텁이 null 을 준다) 시스템 등록은 건너뛴다.
 * 여기서 보는 것은 **재부팅 뒤에 다시 걸 목록**이 맞는지다 — 사본이 틀리면 등록도 틀린다.
 */
class AlarmSchedulerStoreTest {

    private val prefs = InMemoryPrefs()
    private val context = prefsContext(prefs)
    private val scheduler = AlarmScheduler(context)
    private val store = ScheduledAlarmStore(context)

    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private fun schedule(
        eventId: Long,
        alarmAt: Long,
        departBy: Long? = alarmAt + hour,
        proposed: Long? = null,
        answerBy: Long? = null,
    ) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = alarmAt,
        departByMillis = departBy,
        arriveAtMillis = null,
        startAtMillis = alarmAt + 2 * hour,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "수업 $eventId",
        placeName = null,
        arrivalLine = null,
        proposedAlarmAtMillis = proposed,
        proposalAnswerByMillis = answerBy,
    )

    @Test
    fun `동기화는 앞으로의 알람만 사본에 남긴다`() {
        scheduler.sync(listOf(schedule(1, now + hour), schedule(2, now - hour), schedule(3, now + 2 * hour)))

        assertEquals(listOf(1L, 3L), store.all().map { it.eventId })
        assertEquals(listOf(1L, 3L), scheduler.registered().map { it.eventId })
    }

    @Test
    fun `동기화는 새로 생긴 등록을 기록 큐에 적는다`() {
        scheduler.sync(listOf(schedule(1, now + hour)))
        scheduler.sync(listOf(schedule(1, now + hour)))

        val logs = AlarmLogQueue(context).pending()
        assertEquals(1, logs.size)
        assertEquals(AlarmLogInput.KIND_SCHEDULED, logs.single().kind)
    }

    @Test
    fun `서버 목록에서 빠진 알람은 사본에서도 빠진다`() {
        scheduler.sync(listOf(schedule(1, now + hour), schedule(2, now + 2 * hour)))
        scheduler.sync(listOf(schedule(2, now + 2 * hour)))

        assertEquals(listOf(2L), store.all().map { it.eventId })
    }

    @Test
    fun `미루기는 새 시각으로 사본을 고치고 계획 시각은 남긴다`() {
        val original = schedule(1, now + hour)
        scheduler.sync(listOf(original))

        scheduler.snooze(original, 5)

        val snoozed = store.find(1)!!
        assertTrue(snoozed.alarmAtMillis in (now + 4 * 60_000)..(System.currentTimeMillis() + 5 * 60_000))
        assertEquals(original.alarmAtMillis, snoozed.plannedAlarmAtMillis)
        assertEquals(alarmClockLabel(snoozed.alarmAtMillis), snoozed.alarmLabel to snoozed.meridiem)
    }

    @Test
    fun `복원은 지난 알람을 버리고 남은 것의 수를 돌려준다`() {
        store.replaceAll(listOf(schedule(1, now + hour), schedule(2, now - 10 * hour, departBy = now - 9 * hour)))

        assertEquals(1, scheduler.restoreAll())
        assertEquals(listOf(1L), store.all().map { it.eventId })
    }

    @Test
    fun `앱 시작 재등록은 사본을 고치지 않는다`() {
        store.replaceAll(listOf(schedule(1, now + hour), schedule(2, now - 60_000)))

        assertEquals(1, scheduler.rearm())
        assertEquals(2, store.all().size)
    }

    @Test
    fun `출발 전에 지나간 알람은 놓친 알람이다`() {
        store.replaceAll(listOf(schedule(1, now - 10 * 60_000, departBy = now + hour)))

        val missed = scheduler.missed()

        assertEquals(listOf(1L), missed.map { it.eventId })
    }

    @Test
    fun `놓친 알람을 처리하면 다시 띄우지 않는다`() {
        val s = schedule(1, now - 10 * 60_000, departBy = now + hour)
        store.replaceAll(listOf(s))

        scheduler.acknowledgeMissed(s)

        assertTrue(scheduler.missed().isEmpty())
    }

    @Test
    fun `복원이 버린 놓친 알람은 사본으로 옮겨 둔다`() {
        val s = schedule(1, now - 10 * 60_000, departBy = now + hour)
        store.replaceAll(listOf(s))

        scheduler.restoreAll()

        assertTrue(store.all().isEmpty())
        assertEquals(listOf(1L), store.missedCopies().map { it.eventId })
        assertEquals(listOf(1L), scheduler.missed().map { it.eventId })
    }

    @Test
    fun `cancel 은 그 일정만 사본에서 지운다`() {
        scheduler.sync(listOf(schedule(1, now + hour), schedule(2, now + 2 * hour)))

        scheduler.cancel(1)

        assertEquals(listOf(2L), store.all().map { it.eventId })
    }

    @Test
    fun `안 감 표시는 놓친 알람 사본도 처리한다`() {
        val s = schedule(1, now - 10 * 60_000, departBy = now + hour)
        store.replaceMissed(listOf(s))

        scheduler.cancelEvent(1)

        assertTrue(store.missedCopies().isEmpty())
        assertTrue(store.handledKeys().contains(MissedAlarms.key(s)))
    }

    @Test
    fun `전체 해제는 해제한 건수를 돌려주고 사본을 비운다`() {
        scheduler.sync(listOf(schedule(1, now + hour), schedule(2, now + 2 * hour)))

        assertEquals(2, scheduler.cancelAll())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `제안 마감 - 제안이 더 이르면 그 시각으로 다시 건다`() {
        val alarmAt = now + 2 * hour
        val proposed = now + hour
        store.replaceAll(listOf(schedule(1, alarmAt, proposed = proposed, answerBy = now + 30 * 60_000)))

        val moved = scheduler.settleProposal(1, nowMillis = now)

        assertNotNull(moved)
        assertEquals(proposed, store.find(1)!!.alarmAtMillis)
        assertNull(store.find(1)!!.proposedAlarmAtMillis)
        assertEquals(alarmClockLabel(proposed), moved!!.alarmLabel to moved.meridiem)
    }

    @Test
    fun `제안 마감 - 제안이 더 늦으면 지금 시각을 지키고 제안만 지운다`() {
        val alarmAt = now + hour
        store.replaceAll(listOf(schedule(1, alarmAt, proposed = now + 2 * hour, answerBy = now + 30 * 60_000)))

        assertNull(scheduler.settleProposal(1, nowMillis = now))
        assertEquals(alarmAt, store.find(1)!!.alarmAtMillis)
        assertNull(store.find(1)!!.proposedAlarmAtMillis)
    }

    @Test
    fun `제안 마감 - 사본이나 제안이 없으면 아무것도 하지 않는다`() {
        assertNull(scheduler.settleProposal(99, nowMillis = now))
        store.replaceAll(listOf(schedule(1, now + hour)))
        assertNull(scheduler.settleProposal(1, nowMillis = now))
    }
}
