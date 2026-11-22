package com.swpp.wakeup.reminder

// Partly AI-generated (Claude)

import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.testing.FakeReportsApi
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 일요일 주간 리포트 알림. 401 이면 보내지 않고, 그 밖의 실패는 "확인해 보세요" 로 보낸다.
 * 끄면 다음 알림을 걸지 않는다.
 */
class WeeklyReportReminderFetchTest {

    private val today = LocalDate.of(2026, 11, 22)
    private var askedWeek: String? = null

    private fun api(respond: suspend () -> Response<WeeklyReportDto>) = object : FakeReportsApi() {
        override suspend fun weekly(week: String?): Response<WeeklyReportDto> {
            askedWeek = week
            return respond()
        }
    }

    private fun fetch(ready: Boolean = true, respond: suspend () -> Response<WeeklyReportDto>) = runBlocking {
        fetchWeeklyReport(today, ready) { api(respond) }
    }

    @Test
    fun `준비 전이면 서버를 부르지 않고 실패다`() {
        assertEquals(WeeklyFetch.Failed, fetch(ready = false) { error("부르면 안 된다") })
        assertNull(askedWeek)
    }

    @Test
    fun `오늘이 든 주를 묻고 본문을 화면 값으로 바꾼다`() {
        val result = fetch { ok(fromJson<WeeklyReportDto>("{}")) }

        assertTrue(result is WeeklyFetch.Ok)
        assertEquals("2026-11-22", askedWeek)
    }

    @Test
    fun `401 은 로그인이 풀린 것이라 따로 가른다`() {
        assertEquals(WeeklyFetch.Unauthorized, fetch { httpError(401) })
    }

    @Test
    fun `그 밖의 실패 응답과 네트워크 오류는 실패다`() {
        assertEquals(WeeklyFetch.Failed, fetch { httpError(500) })
        assertEquals(WeeklyFetch.Failed, fetch { Response.success(null) })
        assertEquals(WeeklyFetch.Failed, fetch { throw IOException("offline") })
        assertEquals(WeeklyFetch.Failed, fetch { throw IllegalStateException("boom") })
    }

    @Test
    fun `취소는 삼키지 않는다`() {
        try {
            fetch { throw CancellationException("stop") }
            fail("취소가 삼켜졌다")
        } catch (e: CancellationException) {
            // 기대한 대로
        }
    }

    @Test
    fun `로그인이 풀렸으면 알림을 보내지 않는다`() {
        assertNull(reminderMessageFor(WeeklyFetch.Unauthorized))
    }

    @Test
    fun `받지 못했어도 확인해 보라는 알림은 보낸다`() {
        assertEquals("이번 주 리포트를 확인해 보세요", reminderMessageFor(WeeklyFetch.Failed)?.text)
    }

    @Test
    fun `받은 리포트는 그 내용으로 문구를 정한다`() {
        val ok = fetch { ok(fromJson<WeeklyReportDto>("{}")) } as WeeklyFetch.Ok
        assertEquals(WeeklyReportReminderLogic.message(ok.view), reminderMessageFor(ok))
    }

    // --- 켜고 끄기 ------------------------------------------------------------

    private val prefs = InMemoryPrefs()
    private val context = prefsContext(prefs)

    @Test
    fun `기본은 켜져 있다`() {
        assertTrue(WeeklyReportReminder.isEnabled(context))
    }

    @Test
    fun `끄고 켜면 저장된다`() {
        WeeklyReportReminder.setEnabled(context, false)
        assertFalse(WeeklyReportReminder.isEnabled(context))

        WeeklyReportReminder.setEnabled(context, true)
        assertTrue(WeeklyReportReminder.isEnabled(context))
    }

    @Test
    fun `마지막으로 알린 주를 기억하고 깨진 값은 없는 것으로 본다`() {
        val store = WeeklyReportReminderStore(context)
        assertNull(store.lastNotifiedWeek)

        store.lastNotifiedWeek = LocalDate.of(2026, 11, 16)
        assertEquals(LocalDate.of(2026, 11, 16), WeeklyReportReminderStore(context).lastNotifiedWeek)

        prefs.edit().putString("last_notified_week", "not-a-date").apply()
        assertNull(store.lastNotifiedWeek)

        store.lastNotifiedWeek = null
        assertNull(store.lastNotifiedWeek)
    }

    @Test
    fun `예약은 AlarmManager 가 없어도 죽지 않는다`() {
        val now = ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"))
        WeeklyReportReminder.scheduleNext(context, now)
        WeeklyReportReminder.setEnabled(context, false)
        WeeklyReportReminder.scheduleNext(context, now)
        WeeklyReportReminder.ensureChannel(context)
    }
}
