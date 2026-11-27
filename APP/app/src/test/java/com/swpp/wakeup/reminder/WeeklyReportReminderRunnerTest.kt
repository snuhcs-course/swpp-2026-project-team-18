package com.swpp.wakeup.reminder

import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.prefsContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeeklyReportReminderRunnerTest {
    private val prefs = InMemoryPrefs()
    private val store = WeeklyReportReminderStore(prefsContext(prefs))
    private val sunday = ZonedDateTime.of(2026, 11, 22, 12, 1, 0, 0, ZoneId.of("Asia/Seoul"))
    private var account: String? = "first@example.test"
    private val shown = mutableListOf<Pair<WeeklyReportReminderLogic.Message, LocalDate>>()
    private var scheduled = 0
    private var fetchCount = 0

    private fun run(
        now: ZonedDateTime = sunday,
        fetch: suspend (LocalDate) -> WeeklyFetch = { WeeklyFetch.Failed },
    ) = runBlocking {
        runWeeklyReminder(
            now, store, { account },
            fetch = { day -> fetchCount++; fetch(day) },
            show = { message, week -> shown += message to week },
            reschedule = { scheduled++ },
        )
    }

    @Test fun `disabled or outside window never queries but always reschedules`() {
        store.enabled = false
        run()
        store.enabled = true
        run(sunday.minusHours(1))
        run(sunday.plusHours(6))
        run(sunday.plusDays(1))
        assertEquals(0, fetchCount)
        assertEquals(4, scheduled)
        assertTrue(shown.isEmpty())
        assertNull(store.lastNotifiedWeek)
    }

    @Test fun `logged out week is marked before returning to avoid wake loop`() {
        account = null
        run()
        assertEquals(LocalDate.of(2026, 11, 16), store.lastNotifiedWeek)
        account = "first@example.test"
        run()
        assertEquals(0, fetchCount)
        assertEquals(2, scheduled)
        assertTrue(shown.isEmpty())
    }

    @Test fun `unauthorized response sends no notification and is not retried this week`() {
        run { WeeklyFetch.Unauthorized }
        run { error("Already processed") }
        assertEquals(1, fetchCount)
        assertEquals(2, scheduled)
        assertTrue(shown.isEmpty())
    }

    @Test fun `failed fetch sends generic reminder for correct week after marking`() {
        run { day ->
            assertEquals(sunday.toLocalDate(), day)
            assertEquals(LocalDate.of(2026, 11, 16), store.lastNotifiedWeek)
            WeeklyFetch.Failed
        }
        assertEquals(LocalDate.of(2026, 11, 16), shown.single().second)
        assertEquals("이번 주 리포트를 확인해 보세요", shown.single().first.text)
        assertEquals(1, scheduled)
    }

    @Test fun `logout during report request suppresses old account notification`() {
        run { account = null; WeeklyFetch.Failed }
        assertTrue(shown.isEmpty())
        assertEquals(1, scheduled)
    }

    @Test fun `account switch during request suppresses old account notification`() {
        run { account = "second@example.test"; WeeklyFetch.Failed }
        assertTrue(shown.isEmpty())
        assertEquals(1, scheduled)
    }

    @Test fun `cancelled fetch propagates but still schedules next week`() {
        try {
            run { throw CancellationException("Worker stopped") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, scheduled)
            assertTrue(shown.isEmpty())
            assertEquals(LocalDate.of(2026, 11, 16), store.lastNotifiedWeek)
        }
    }
}
