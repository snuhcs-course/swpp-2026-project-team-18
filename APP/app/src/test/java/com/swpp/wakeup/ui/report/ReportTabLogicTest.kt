// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.report

import com.swpp.wakeup.data.remote.OutcomeDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.toView
import com.swpp.wakeup.domain.model.LateRow
import com.swpp.wakeup.domain.model.ProbabilityCompare
import com.swpp.wakeup.domain.model.WeeklyReportView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 리포트 탭(iter4 H2-2 · H2-1, Figma `546:1943`)의 문구 */
class ReportTabLogicTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val thursday = LocalDate.of(2026, 9, 17)

    private fun outcome(id: Long, date: String, startUtc: String, result: String, slack: Double? = null) =
        OutcomeDto(event = id, title = "수업", startAt = startUtc, date = date, result = result, slackMinutes = slack)

    private fun view(
        start: String = "2026-09-14",
        end: String = "2026-09-20",
        outcomes: List<OutcomeDto> = emptyList(),
        arrived: Int = outcomes.count { it.result != OutcomeDto.RESULT_UNOBSERVED },
        onTime: Int = outcomes.count { it.result == OutcomeDto.RESULT_ON_TIME },
        skipped: Int = 0,
    ): WeeklyReportView = WeeklyReportDto(
        weekStart = start,
        weekEnd = end,
        eventCount = outcomes.size,
        arrivedCount = arrived,
        unobservedCount = outcomes.size - arrived,
        onTimeCount = onTime,
        lateCount = arrived - onTime,
        onTimeRate = if (arrived > 0) onTime.toDouble() / arrived else null,
        skippedCount = skipped,
        outcomes = outcomes,
    ).toView(seoul)

    /** Figma 예시: 월 정시 · 화 지각(+ 정시) · 수 정시 두 번, 오늘(목)은 아직 */
    private val figmaWeek = listOf(
        outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", 8.0),
        outcome(2, "2026-09-15", "2026-09-15T00:00:00Z", "late", -6.0),
        outcome(3, "2026-09-15", "2026-09-15T05:00:00Z", "on_time", 4.0),
        outcome(4, "2026-09-16", "2026-09-16T00:00:00Z", "on_time", 2.0),
        outcome(5, "2026-09-16", "2026-09-16T05:00:00Z", "on_time", 9.0),
    )

    @Test
    fun `주 선택 글자는 같은 달이면 끝 날짜만`() {
        assertEquals("9월 14일 ~ 20일", ReportTabLogic.range(view()))
        assertEquals("9월 28일 ~ 10월 4일", ReportTabLogic.range(view(start = "2026-09-28", end = "2026-10-04")))
        assertEquals("날짜를 못 읽으면 매핑 글자", "—", ReportTabLogic.range(view(start = "x", end = "y")))
    }

    @Test
    fun `이번 주 지난주 이 주`() {
        val monday = LocalDate.of(2026, 9, 14)
        assertEquals("이번 주", ReportTabLogic.weekWord(monday, thursday))
        assertEquals("일요일도 이번 주", "이번 주", ReportTabLogic.weekWord(monday, LocalDate.of(2026, 9, 20)))
        assertEquals("지난주", ReportTabLogic.weekWord(monday.minusWeeks(1), thursday))
        assertEquals("이 주", ReportTabLogic.weekWord(monday.minusWeeks(2), thursday))
    }

    @Test
    fun `머리는 몇 번 중 몇 번 제시간에 도착했는지`() {
        val headline = ReportTabLogic.headline(view(outcomes = figmaWeek), thursday)
        assertEquals("이번 주 5번 중 4번\n제시간에 도착했어요", headline.text)
        assertEquals("4번", headline.highlight)

        val lastWeek = ReportTabLogic.headline(view(outcomes = figmaWeek), LocalDate.of(2026, 9, 24))
        assertEquals("지난주 5번 중 4번\n제시간에 도착했어요", lastWeek.text)
    }

    @Test
    fun `도착 기록이나 일정이 없을 때 머리`() {
        val unobserved = view(outcomes = listOf(outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "unobserved")))
        assertEquals("이번 주 도착 기록이\n아직 없어요", ReportTabLogic.headline(unobserved, thursday).text)
        assertNull(ReportTabLogic.headline(unobserved, thursday).highlight)
        assertEquals("지난주에는 도착 기록이\n없어요", ReportTabLogic.headline(unobserved, LocalDate.of(2026, 9, 22)).text)
        assertEquals(
            listOf("위치 권한을 켜 두고 알람을 끈 뒤 집을 나서면 도착이 저절로 기록돼요"),
            ReportTabLogic.captions(unobserved),
        )

        assertEquals("이번 주는 아직\n다녀온 일정이 없어요", ReportTabLogic.headline(view(), thursday).text)
        assertEquals("지난주에는 일정이 없었어요", ReportTabLogic.headline(view(), LocalDate.of(2026, 9, 21)).text)
    }

    @Test
    fun `결과를 모르는 아침과 안 간 일정은 작은 글씨로 밝힌다`() {
        val outcomes = figmaWeek + outcome(6, "2026-09-16", "2026-09-16T08:00:00Z", "unobserved")
        assertEquals(
            listOf("도착 기록이 없는 일정 1개는 빼고 셌어요", "안 감으로 표시한 일정 2개는 빼고 셌어요"),
            ReportTabLogic.captions(view(outcomes = outcomes, skipped = 2)),
        )
        assertTrue(ReportTabLogic.captions(view(outcomes = figmaWeek)).isEmpty())
    }

    @Test
    fun `요일 점은 하루에 하나, 한 번이라도 늦으면 빨강, 오늘은 오늘`() {
        val dots = ReportTabLogic.dots(view(outcomes = figmaWeek), thursday)
        assertEquals(listOf("월", "화", "수", "오늘", "금", "토", "일"), dots.map { it.label })
        assertEquals(
            listOf(
                ReportTabLogic.DotKind.ON_TIME,
                ReportTabLogic.DotKind.LATE,
                ReportTabLogic.DotKind.ON_TIME,
                ReportTabLogic.DotKind.TODAY,
                ReportTabLogic.DotKind.EMPTY,
                ReportTabLogic.DotKind.EMPTY,
                ReportTabLogic.DotKind.EMPTY,
            ),
            dots.map { it.kind },
        )
        assertTrue("일정이 있는 날은 ⑨ 로 간다", dots[1].clickable)
        assertEquals("늦은 일정이 그날 대표", 2L, dots[1].eventId)
        assertFalse(dots[3].clickable)
        assertEquals("화요일, 늦은 일정 있음", dots[1].description)
        assertEquals("오늘 목요일, 기록 없음", dots[3].description)

        val unobserved = ReportTabLogic.dots(
            view(outcomes = listOf(outcome(1, "2026-09-17", "2026-09-17T00:00:00Z", "unobserved"))),
            thursday,
        )
        assertEquals("오늘이어도 결과가 있으면 결과 색", ReportTabLogic.DotKind.UNOBSERVED, unobserved[3].kind)
        assertTrue("주를 모르면 점이 없다", ReportTabLogic.dots(view(start = "x", end = "y"), thursday).isEmpty())
    }

    @Test
    fun `늦은 일정 줄 문구`() {
        val row = LateRow(LocalDate.of(2026, 9, 15), 2L, 6, "9:00", "자료구조 및 알고리즘")
        assertEquals("화요일 6분 늦었어요", ReportTabLogic.lateTitle(row))
        assertEquals("9:00 자료구조 및 알고리즘", ReportTabLogic.lateSub(row))
        val unknown = row.copy(lateMinutes = null, startClock = null)
        assertEquals("화요일에 늦었어요", ReportTabLogic.lateTitle(unknown))
        assertEquals("자료구조 및 알고리즘", ReportTabLogic.lateSub(unknown))
    }

    @Test
    fun `판정 말 바꾸기와 표본 부족`() {
        fun compare(verdict: String?, more: Int = 1) = ProbabilityCompare(88, 80, verdict, more)

        val thin = compare("insufficient", more = 2)
        assertEquals("기록이 아직 적어요.", ReportTabLogic.verdictTitle(thin))
        assertEquals(listOf("2번 더 다니면 보여 드릴게요"), ReportTabLogic.verdictBody(thin, 0, inProgress = true))
        assertFalse("표본이 부족하면 막대를 그리지 않는다", ReportTabLogic.showBars(thin))

        assertEquals("거의 맞았어요.", ReportTabLogic.verdictTitle(compare("calibrated")))
        assertEquals("조금 낙관적이었어요.", ReportTabLogic.verdictTitle(compare("overconfident")))
        assertEquals("필요보다 일찍 깨웠어요.", ReportTabLogic.verdictTitle(compare("conservative")))
        assertNull(ReportTabLogic.verdictTitle(compare(null)))
        assertTrue(ReportTabLogic.showBars(compare("overconfident")))
        assertTrue("구버전 서버도 막대는 그린다", ReportTabLogic.showBars(compare(null)))
        assertFalse(ReportTabLogic.showBars(ProbabilityCompare(null, 80, "calibrated", 1)))
        assertTrue(ReportTabLogic.probabilityWorse(compare("overconfident")))
    }

    @Test
    fun `알람 이야기는 서버가 그 주 여유 시간을 실제로 바꿨을 때만`() {
        val over = ProbabilityCompare(88, 80, "overconfident", 1)
        assertEquals(listOf("다음 주엔 알람을 조금 더 일찍 맞출게요"), ReportTabLogic.verdictBody(over, 3, inProgress = true))
        assertTrue("바꾸지 않았으면 쓰지 않는다", ReportTabLogic.verdictBody(over, 0, inProgress = true).isEmpty())
        assertEquals("이 주부터 알람을 조금 더 일찍 맞췄어요", ReportTabLogic.bufferLine(3, inProgress = false))
        assertEquals("다음 주엔 알람을 조금 늦게 맞출게요", ReportTabLogic.bufferLine(-2, inProgress = true))
        assertEquals("이 주부터 알람을 조금 늦게 맞췄어요", ReportTabLogic.bufferLine(-2, inProgress = false))
    }

    @Test
    fun `막대 길이`() {
        assertEquals(0.88f, ReportTabLogic.percentFraction(88), 1e-6f)
        assertEquals(1f, ReportTabLogic.percentFraction(120), 1e-6f)
        // Figma 준비 시간: 28분 · 31분 → 214dp 중 138 · 153
        assertEquals(138f / 214f, ReportTabLogic.minutesFraction(28, 31), 0.01f)
        assertEquals(153f / 214f, ReportTabLogic.minutesFraction(31, 28), 0.01f)
        assertTrue(ReportTabLogic.prepLonger(28, 31))
        assertFalse(ReportTabLogic.prepLonger(31, 28))
    }
}
