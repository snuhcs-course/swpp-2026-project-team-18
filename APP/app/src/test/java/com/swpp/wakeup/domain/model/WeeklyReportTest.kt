package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 주간 리포트 화면 규칙.
 *
 * 이 화면의 목적은 **앱이 틀렸는지 보여주는 것**이다. 그래서 가장 위험한 실패는
 * "숫자가 좋게 나오는 것" 이다. 아래 테스트는 전부 그걸 막는다.
 */
// Partly AI-generated (Claude)
class ReportDayTest {

    private fun morning(id: Long, result: OutcomeResult, slack: Double?) = MorningOutcome(
        eventId = id,
        chipLabel = "09:00 수업",
        result = result,
        headline = "",
        headlineNote = null,
        contextLine = "",
        confidenceLine = null,
        rows = emptyList(),
        arrival = null,
        cause = null,
        tauBucketLower = null,
        slackMinutes = slack,
        isOnlyLateOfWeek = false,
    )

    private fun day(vararg outcomes: MorningOutcome) = ReportDay(
        date = LocalDate.of(2026, 10, 5),
        label = "월",
        status = DayStatus.ON_TIME,
        slackMinutes = null,
        valueLabel = "",
        description = "",
        outcomes = outcomes.toList(),
    )

    @Test
    fun `대표는 가장 작은 여유다`() {
        val day = day(
            morning(1, OutcomeResult.ON_TIME, 15.0),
            morning(2, OutcomeResult.ON_TIME, 12.0),
        )
        assertEquals(2L, day.representative?.eventId)
    }

    @Test
    fun `지각이 있으면 지각이 대표다`() {
        // 정시의 여유는 0 이상이고 지각은 음수라, 가장 작은 여유가 곧 지각이다.
        val day = day(
            morning(1, OutcomeResult.ON_TIME, 2.0),
            morning(2, OutcomeResult.LATE, -3.0),
        )
        assertEquals(2L, day.representative?.eventId)
    }

    @Test
    fun `도착 기록이 없으면 가장 이른 일정이 대표다`() {
        val day = day(
            morning(7, OutcomeResult.UNOBSERVED, null),
            morning(8, OutcomeResult.UNOBSERVED, null),
        )
        assertEquals(7L, day.representative?.eventId)
        // 기록이 하나라도 있으면 그쪽이 먼저다.
        val mixed = day(morning(7, OutcomeResult.UNOBSERVED, null), morning(8, OutcomeResult.ON_TIME, 4.0))
        assertEquals(8L, mixed.representative?.eventId)
    }

    @Test
    fun `일정이 없는 날은 누를 수 없다`() {
        val empty = day()
        assertFalse(empty.clickable)
        assertNull(empty.representative)
        assertTrue(day(morning(1, OutcomeResult.UNOBSERVED, null)).clickable)
    }
}

class CalibrationViewTest {

    private fun view(
        verdict: String,
        points: List<CalibrationPoint> = emptyList(),
        scored: Int = 0,
        unscored: Int = 0,
    ) = CalibrationView(
        points = points,
        scoredCount = scored,
        unscoredCount = unscored,
        verdictLabel = "라벨",
        verdictNote = "설명",
        action = null,
        verdict = verdict,
    )

    @Test
    fun `표본 부족은 신뢰할 수 없는 판정이다`() {
        // "잘 맞음" 을 표본 2건으로 말하면 사용자가 그 말을 믿고 여유를 줄인다.
        assertFalse(view("insufficient").isTrustworthy)
        assertTrue(view("calibrated").isTrustworthy)
        assertTrue(view("overconfident").isTrustworthy)
        assertTrue(view("conservative").isTrustworthy)
    }

    @Test
    fun `점이 없으면 그래프를 그리지 않는다`() {
        assertFalse(view("insufficient").hasData)
        assertTrue(
            view(
                "calibrated",
                listOf(point(promised = 0.9f, actual = 0.9f)),
            ).hasData
        )
    }

    // Partly AI-generated (Claude)
    @Test
    fun `채점 건수 줄은 채점 못 한 것이 있을 때만 둘째 조각을 붙인다`() {
        assertEquals("채점 36건 · 채점 못 함 5건", view("calibrated", scored = 36, unscored = 5).countsLabel)
        assertEquals("채점 4건", view("insufficient", scored = 4).countsLabel)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `실제 정시율 표기와 구간 강조`() {
        val reliable = point(promised = 0.925f, actual = 0.625f).copy(lower = 0.9)
        assertEquals("실제 63% · 4번", reliable.actualLabel)
        assertTrue(reliable.matches(0.9))
        assertTrue(reliable.matches(0.9000001))
        assertFalse(reliable.matches(0.95))
        assertFalse(reliable.matches(null))
        assertFalse("구간을 모르면 강조하지 않는다", reliable.copy(lower = null).matches(0.9))

        val thin = reliable.copy(reliable = false, actual = null)
        assertEquals("실제 — · 4번 · 표본 부족", thin.actualLabel)
    }

    private fun point(promised: Float, actual: Float?) = CalibrationPoint(
        rangeLabel = "90~95%",
        promised = promised,
        actual = actual,
        total = 4,
        reliable = true,
        countLabel = "4번 중 4번 정시",
        overconfident = actual != null && actual < promised,
    )
}

// Partly AI-generated (Claude)
class WeeklyReportViewTest {

    private fun report(
        eventCount: Int,
        arrivedCount: Int,
        unobservedCount: Int,
        summary: ReportSummary? = null,
        days: List<ReportDay> = emptyList(),
        deviations: List<DeviationLine> = emptyList(),
    ) = WeeklyReportView(
        rangeLabel = "10월 5일 ~ 10월 11일",
        weekStart = LocalDate.of(2026, 10, 5),
        canGoNext = true,
        inProgress = false,
        eventCount = eventCount,
        arrivedCount = arrivedCount,
        unobservedCount = unobservedCount,
        onTimeCount = arrivedCount,
        lateCount = 0,
        summary = summary,
        onTimeCountLabel = "—",
        onTimeTone = Tone.NEUTRAL,
        onTimeLabel = "정시 도착 · 기록 없음",
        onTimeSubLabel = null,
        slackValueLabel = "—",
        slackTone = Tone.NEUTRAL,
        slackLabel = "평소 도착 · 기록 없음",
        slackSubLabel = null,
        gapNotice = null,
        days = days,
        daysCoverageLabel = "기록 $arrivedCount/${eventCount}건",
        lateDays = emptyList(),
        hasUnknownCause = false,
        deviations = deviations,
        learningLabel = null,
    )

    @Test
    fun `도착이 없으면 비율을 앞세우지 않는다`() {
        assertFalse(report(eventCount = 3, arrivedCount = 0, unobservedCount = 3).hasArrivals)
        assertTrue(report(eventCount = 3, arrivedCount = 2, unobservedCount = 1).hasArrivals)
    }

    @Test
    fun `관측 공백이 절반을 넘으면 경고한다`() {
        // 절반 이상을 측정하지 못했으면 정시율은 적은 표본에 기댄 값이다.
        // 그 사실을 밝히지 않으면 사용자가 비율을 과신한다.
        assertTrue(
            report(eventCount = 4, arrivedCount = 2, unobservedCount = 2).observationGapMatters
        )
        assertTrue(
            report(eventCount = 5, arrivedCount = 2, unobservedCount = 3).observationGapMatters
        )
        assertFalse(
            report(eventCount = 5, arrivedCount = 4, unobservedCount = 1).observationGapMatters
        )
    }

    @Test
    fun `일정이 없는 주는 공백 경고를 띄우지 않는다`() {
        // 0 건인데 "측정하지 못한 아침 0건" 을 띄우면 고장으로 읽힌다.
        assertFalse(
            report(eventCount = 0, arrivedCount = 0, unobservedCount = 0).observationGapMatters
        )
    }

    @Test
    fun `일정이 없는 주는 요약이 말하고 타일을 숨긴다`() {
        val summary = ReportSummary("이 주에는 지난 일정이 없음.", isAi = false)
        assertFalse(report(0, 0, 0, summary = summary).showTiles)
        // 구버전 서버는 요약이 없다. 그때 타일까지 숨기면 화면이 빈다.
        assertTrue(report(0, 0, 0).showTiles)
        assertTrue(report(2, 1, 1, summary = summary).showTiles)
    }

    @Test
    fun `요일 칸과 계획 대비 실제는 일정이 있고 값이 있을 때만`() {
        val days = (0..6).map {
            ReportDay(LocalDate.of(2026, 10, 5 + it), "월", DayStatus.NONE, null, "", "", emptyList())
        }
        val line = DeviationLine("준비", "측정 안 됨", Tone.UNKNOWN)
        assertFalse(report(0, 0, 0, days = days, deviations = listOf(line)).showDays)
        assertFalse(report(0, 0, 0, days = days, deviations = listOf(line)).showDeviations)
        assertTrue(report(1, 0, 1, days = days, deviations = listOf(line)).showDays)
        assertTrue(report(1, 0, 1, days = days, deviations = listOf(line)).showDeviations)
        assertFalse("구버전 서버", report(1, 0, 1).showDays)
    }
}
