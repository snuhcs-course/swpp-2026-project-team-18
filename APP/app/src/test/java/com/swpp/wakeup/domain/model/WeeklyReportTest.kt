package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주간 리포트 화면 규칙.
 *
 * 이 화면의 목적은 **앱이 틀렸는지 보여주는 것**이다. 그래서 가장 위험한 실패는
 * "숫자가 좋게 나오는 것" 이다. 아래 테스트는 전부 그걸 막는다.
 */
class WeekdayLoadTest {

    @Test
    fun `일정이 없는 요일은 비율이 없다`() {
        // 0 으로 그리면 "그 요일은 완벽했다" 로 보인다. 실제로는 일정이 없었다.
        assertNull(WeekdayLoad("금", total = 0, late = 0).lateFraction)
    }

    @Test
    fun `지각 비율을 센다`() {
        assertEquals(1.0f, WeekdayLoad("화", total = 2, late = 2).lateFraction)
        assertEquals(0.5f, WeekdayLoad("수", total = 2, late = 1).lateFraction)
        assertEquals(0.0f, WeekdayLoad("목", total = 3, late = 0).lateFraction)
    }
}

class CalibrationViewTest {

    private fun view(verdict: String, points: List<CalibrationPoint> = emptyList()) =
        CalibrationView(
            points = points,
            scoredCount = 0,
            unscoredCount = 0,
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

class WeeklyReportViewTest {

    private fun report(
        eventCount: Int,
        arrivedCount: Int,
        unobservedCount: Int,
    ) = WeeklyReportView(
        rangeLabel = "9월 8일 ~ 9월 14일",
        eventCount = eventCount,
        arrivedCount = arrivedCount,
        unobservedCount = unobservedCount,
        onTimeCount = arrivedCount,
        lateCount = 0,
        onTimeLabel = null,
        onTimeFraction = null,
        medianSlackLabel = null,
        tightestSlackLabel = null,
        weekdays = emptyList(),
        causes = emptyList(),
        calibration = CalibrationView(
            points = emptyList(),
            scoredCount = 0,
            unscoredCount = 0,
            verdictLabel = "표본 부족",
            verdictNote = "",
            action = null,
            verdict = CalibrationView.WIRE_INSUFFICIENT,
        ),
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
}
