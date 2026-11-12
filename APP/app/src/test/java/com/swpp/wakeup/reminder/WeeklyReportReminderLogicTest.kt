// Partly AI-generated (Claude)
package com.swpp.wakeup.reminder

import com.swpp.wakeup.data.remote.OutcomeDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.toView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 일요일 낮 12시 주간 리포트 알림(iter4 H2-2)의 시각 · 문구 */
class WeeklyReportReminderLogicTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 2026-11-09(월) ~ 11-15(일) */
    private val monday = LocalDate.of(2026, 11, 9)
    private val sunday = LocalDate.of(2026, 11, 15)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = seoul) =
        ZonedDateTime.of(LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute), zone)

    @Test
    fun `일요일 12시 전이면 그 주 일요일 12시`() {
        for (offset in 0L..5L) {
            val day = monday.plusDays(offset)
            assertEquals("$day", at(sunday, 12), WeeklyReportReminderLogic.nextTrigger(at(day, 9, 30), null))
        }
        assertEquals("일요일 11:59", at(sunday, 12), WeeklyReportReminderLogic.nextTrigger(at(sunday, 11, 59), null))
        assertEquals(DayOfWeek.SUNDAY, WeeklyReportReminderLogic.nextTrigger(at(monday, 0), null).dayOfWeek)
    }

    @Test
    fun `일요일 12시부터 18시 전까지 아직 안 띄웠으면 지금, 띄웠으면 다음 주`() {
        val noon = at(sunday, 12)
        assertEquals("정각", noon, WeeklyReportReminderLogic.nextTrigger(noon, null))
        val later = at(sunday, 12, 1)
        assertEquals("앱을 12:01 에 열어 다시 걸어도 오늘 것을 놓치지 않는다", later, WeeklyReportReminderLogic.nextTrigger(later, null))
        assertEquals("지난주 것만 띄웠으면 지금", later, WeeklyReportReminderLogic.nextTrigger(later, monday.minusWeeks(1)))

        assertEquals("이번 주를 이미 처리했으면 다음 주", at(sunday.plusWeeks(1), 12), WeeklyReportReminderLogic.nextTrigger(later, monday))
        assertEquals("18시가 지나면 다음 주", at(sunday.plusWeeks(1), 12), WeeklyReportReminderLogic.nextTrigger(at(sunday, 18), null))
    }

    @Test
    fun `띄워도 되는 때는 일요일 12시부터 18시 전까지 한 번`() {
        assertTrue(WeeklyReportReminderLogic.shouldNotify(at(sunday, 12), null))
        assertTrue(WeeklyReportReminderLogic.shouldNotify(at(sunday, 17, 59), monday.minusWeeks(1)))
        assertFalse("알람 창보다 일찍 깼다", WeeklyReportReminderLogic.shouldNotify(at(sunday, 11, 59), null))
        assertFalse("18시를 넘기면 그 주는 건너뛴다", WeeklyReportReminderLogic.shouldNotify(at(sunday, 18), null))
        assertFalse("월요일에 '이번 주' 라고 보내면 틀린다", WeeklyReportReminderLogic.shouldNotify(at(sunday.plusDays(1), 12), null))
        assertFalse("같은 주에 두 번 띄우지 않는다", WeeklyReportReminderLogic.shouldNotify(at(sunday, 13), monday))
        assertFalse(WeeklyReportReminderLogic.shouldNotify(at(LocalDate.of(2026, 11, 14), 12), null))
    }

    @Test
    fun `시간대가 바뀌면 그 시간대의 벽시계 12시`() {
        val newYork = ZoneId.of("America/New_York")
        val next = WeeklyReportReminderLogic.nextTrigger(at(monday, 9, zone = newYork), null)
        assertEquals(newYork, next.zone)
        assertEquals(at(sunday, 12, zone = newYork), next)
        assertFalse("서울 12시와 다른 순간", next.toInstant() == at(sunday, 12).toInstant())

        // 미국 서머타임이 끝나는 날(2026-11-01 일요일 새벽 2시)을 건너도 벽시계 12:00
        val beforeDst = LocalDate.of(2026, 10, 25)
        val afterDst = WeeklyReportReminderLogic.nextTrigger(at(beforeDst, 13, zone = newYork), beforeDst.minusDays(6))
        assertEquals(at(LocalDate.of(2026, 11, 1), 12, zone = newYork), afterDst)
        assertEquals(12, afterDst.hour)
    }

    private fun report(outcomes: List<OutcomeDto>) = WeeklyReportDto(
        weekStart = monday.toString(),
        weekEnd = sunday.toString(),
        eventCount = outcomes.size,
        arrivedCount = outcomes.count { it.result != OutcomeDto.RESULT_UNOBSERVED },
        onTimeCount = outcomes.count { it.result == OutcomeDto.RESULT_ON_TIME },
        outcomes = outcomes,
    ).toView(seoul)

    private fun outcome(id: Long, result: String) = OutcomeDto(
        event = id,
        title = "수업",
        startAt = "2026-11-10T00:00:00Z",
        date = "2026-11-10",
        result = result,
        slackMinutes = when (result) {
            "on_time" -> 5.0
            "late" -> -3.0
            else -> null
        },
    )

    @Test
    fun `알림 문구`() {
        val week = report(
            listOf(
                outcome(1, "on_time"), outcome(2, "late"), outcome(3, "on_time"),
                outcome(4, "on_time"), outcome(5, "on_time"),
            ),
        )
        assertEquals(
            WeeklyReportReminderLogic.Message("이번 주 리포트가 나왔어요", "5번 중 4번 제시간에 도착했어요 · 눌러서 자세히 보기"),
            WeeklyReportReminderLogic.message(week),
        )
        assertEquals(
            "이번 주 도착 기록이 아직 없어요 · 눌러서 확인하기",
            WeeklyReportReminderLogic.message(report(listOf(outcome(1, "unobserved"))))?.text,
        )
        assertNull("다녀온 일정이 없으면 보내지 않는다", WeeklyReportReminderLogic.message(report(emptyList())))
        assertEquals("받지 못했으면 숫자 없이", "이번 주 리포트를 확인해 보세요", WeeklyReportReminderLogic.message(null)?.text)
    }
}
