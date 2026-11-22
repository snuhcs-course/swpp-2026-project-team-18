package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.prefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알람 디스크 사본. 재부팅 뒤 [BootReceiver] 가 서버 없이 이것만 보고 다시 건다.
 * Context 는 SharedPreferences 만 돌려주는 가짜([prefsContext])다.
 */
class ScheduledAlarmStoreTest {

    private val prefs = InMemoryPrefs()
    private val store = ScheduledAlarmStore(prefsContext(prefs))

    private fun schedule(eventId: Long, alarmAt: Long) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = alarmAt,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = alarmAt + 3_600_000,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "수업 $eventId",
        placeName = null,
        arrivalLine = null,
    )

    @Test
    fun `비어 있으면 빈 목록이다`() {
        assertTrue(store.all().isEmpty())
        assertTrue(store.handledKeys().isEmpty())
        assertTrue(store.missedCopies().isEmpty())
    }

    @Test
    fun `저장한 알람을 시각 오름차순으로 돌려준다`() {
        store.replaceAll(listOf(schedule(2, 2_000), schedule(1, 1_000)))

        assertEquals(listOf(1L, 2L), store.all().map { it.eventId })
    }

    @Test
    fun `새 인스턴스도 같은 사본을 읽는다`() {
        store.replaceAll(listOf(schedule(1, 1_000)))

        assertEquals(1, ScheduledAlarmStore(prefsContext(prefs)).all().size)
    }

    @Test
    fun `깨진 JSON 은 버리고 빈 목록을 돌려준다`() {
        prefs.edit().putString("schedules", "{not json").apply()

        assertTrue(store.all().isEmpty())
        assertNull(prefs.getString("schedules", null))
    }

    @Test
    fun `upsert 는 같은 일정을 갈아 끼우고 없던 일정은 더한다`() {
        store.replaceAll(listOf(schedule(1, 1_000), schedule(2, 2_000)))

        store.upsert(schedule(1, 3_000))
        store.upsert(schedule(9, 500))

        assertEquals(listOf(9L to 500L, 2L to 2_000L, 1L to 3_000L), store.all().map { it.eventId to it.alarmAtMillis })
    }

    @Test
    fun `find 와 remove 는 일정 id 로 찾는다`() {
        store.replaceAll(listOf(schedule(1, 1_000), schedule(2, 2_000)))

        assertEquals(2_000L, store.find(2)?.alarmAtMillis)
        store.remove(2)
        assertNull(store.find(2))
        assertEquals(listOf(1L), store.all().map { it.eventId })
    }

    @Test
    fun `wipe 는 사본을 통째로 비운다`() {
        store.replaceAll(listOf(schedule(1, 1_000)))
        store.replaceMissed(listOf(schedule(2, 2_000)))

        store.wipe()

        assertTrue(store.all().isEmpty())
        assertTrue(store.missedCopies().isEmpty())
    }

    @Test
    fun `처리한 알람은 키로 남는다`() {
        val s = schedule(1, 1_000)

        store.markHandled(s, nowMillis = 1_000)

        assertEquals(setOf(MissedAlarms.key(s)), store.handledKeys())
    }

    @Test
    fun `놓친 알람 사본을 쓰고 비울 수 있다`() {
        store.replaceMissed(listOf(schedule(2, 2_000), schedule(1, 1_000)))
        assertEquals(listOf(1L, 2L), store.missedCopies().map { it.eventId })

        store.replaceMissed(emptyList())
        assertTrue(store.missedCopies().isEmpty())
        assertNull(prefs.getString("missed", null))
    }

    @Test
    fun `깨진 놓친 알람 사본은 버린다`() {
        prefs.edit().putString("missed", "[{]").apply()

        assertTrue(store.missedCopies().isEmpty())
        assertNull(prefs.getString("missed", null))
    }

    @Test
    fun `다른 계정의 사본은 읽지 않고 비운다`() {
        prefs.edit().putString("email", "a@snu.ac.kr").apply()
        store.replaceAll(listOf(schedule(1, 1_000)))
        store.markHandled(schedule(1, 1_000), nowMillis = 1_000)

        prefs.edit().putString("email", "b@snu.ac.kr").apply()

        assertTrue(store.all().isEmpty())
        prefs.edit().putString("email", "b@snu.ac.kr").apply()
        assertTrue(store.handledKeys().isEmpty())
        assertTrue(store.missedCopies().isEmpty())
    }
}
