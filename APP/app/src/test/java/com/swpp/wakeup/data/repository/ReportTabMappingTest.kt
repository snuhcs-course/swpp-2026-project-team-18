// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.ActualDto
import com.swpp.wakeup.data.remote.BufferAdjustmentDto
import com.swpp.wakeup.data.remote.CalibrationBucketDto
import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.OutcomeDto
import com.swpp.wakeup.data.remote.PlannedDto
import com.swpp.wakeup.data.remote.WeeklyBufferDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 주간 리포트 응답 → 리포트 탭(iter4 H2-2, Figma `546:1943`)이 더 쓰는 값.
 * 늦은 일정 줄 · 정시 도착 확률 · 준비 시간 · 버퍼 조정 합. 예시는 Figma 의 9월 14일 ~ 20일 주다.
 */
class ReportTabMappingTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun outcome(
        id: Long,
        date: String,
        startUtc: String,
        result: String,
        slack: Double? = null,
        probability: Int? = null,
        plannedPrep: Double? = null,
        actualPrep: Double? = null,
        prepApplies: Boolean? = true,
        title: String? = "자료구조 및 알고리즘",
    ) = OutcomeDto(
        event = id,
        title = title,
        startAt = startUtc,
        date = date,
        result = result,
        slackMinutes = slack,
        onTimeProbability = probability,
        prepApplies = prepApplies,
        planned = PlannedDto(prepMinutes = plannedPrep),
        actual = ActualDto(prepMinutes = actualPrep),
    )

    private fun week(
        outcomes: List<OutcomeDto>,
        arrived: Int = outcomes.count { it.result != OutcomeDto.RESULT_UNOBSERVED },
        onTimeRate: Double? = 0.8,
        targetRate: Double? = 0.9,
        calibration: CalibrationDto? = null,
        buffer: WeeklyBufferDto? = null,
        skipped: Int = 0,
    ) = WeeklyReportDto(
        weekStart = "2026-09-14",
        weekEnd = "2026-09-20",
        eventCount = outcomes.size,
        arrivedCount = arrived,
        onTimeRate = onTimeRate,
        targetRate = targetRate,
        calibration = calibration,
        buffer = buffer,
        skippedCount = skipped,
        outcomes = outcomes,
        weekComplete = false,
        hasNextWeek = false,
    ).toView(seoul)

    private fun bucket(total: Int) = CalibrationBucketDto(lower = 0.9, upper = 0.95, center = 0.925, total = total)

    @Test
    fun `늦은 일정 줄은 날짜 늦은 분 시작 시각 제목`() {
        val view = week(
            listOf(
                outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 8.0),
                // 화요일 9:00(서울) 수업에 5.6분 늦음 → 서버 규칙대로 6분
                outcome(2, "2026-09-15", "2026-09-15T00:00:00Z", "late", slack = -5.6),
                outcome(3, "2026-09-16", "2026-09-16T05:30:00Z", "late", slack = -0.2, title = "  "),
            ),
        )

        assertEquals(2, view.lateRows.size)
        val tuesday = view.lateRows[0]
        assertEquals(LocalDate.of(2026, 9, 15), tuesday.date)
        assertEquals(2L, tuesday.eventId)
        assertEquals(6, tuesday.lateMinutes)
        assertEquals("9:00", tuesday.startClock)
        assertEquals("자료구조 및 알고리즘", tuesday.title)

        val wednesday = view.lateRows[1]
        assertEquals("늦었으면 최소 1분", 1, wednesday.lateMinutes)
        assertEquals("14:30", wednesday.startClock)
        assertEquals("제목이 비면 '일정'", "일정", wednesday.title)
    }

    @Test
    fun `예상은 도착한 일정에서 앱이 말한 확률의 평균, 실제는 서버 정시율`() {
        val view = week(
            listOf(
                outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 8.0, probability = 90),
                outcome(2, "2026-09-15", "2026-09-15T00:00:00Z", "late", slack = -6.0, probability = 88),
                outcome(3, "2026-09-16", "2026-09-16T00:00:00Z", "on_time", slack = 3.0, probability = 86),
                // 도착 기록이 없는 일정은 예상에도 넣지 않는다
                outcome(4, "2026-09-16", "2026-09-16T05:00:00Z", "unobserved", probability = 40),
            ),
            onTimeRate = 0.8,
            calibration = CalibrationDto(buckets = listOf(bucket(5)), verdict = "overconfident"),
        )

        val compare = view.probability!!
        assertEquals(88, compare.promised)
        assertEquals(80, compare.actual)
        assertEquals("overconfident", compare.verdict)
        assertEquals("판정이 났으면 남은 횟수는 의미 없지만 1 이상", 1, compare.moreTrips)
    }

    @Test
    fun `확률이 없으면 목표 평균, 표본이 부족하면 판정까지 남은 횟수`() {
        val outcomes = listOf(
            outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 8.0),
            outcome(2, "2026-09-15", "2026-09-15T00:00:00Z", "on_time", slack = 4.0),
        )
        val thin = week(
            outcomes,
            onTimeRate = 1.0,
            targetRate = 0.9,
            calibration = CalibrationDto(
                buckets = listOf(bucket(1), bucket(2)),
                minSamples = 3,
                verdict = "insufficient",
            ),
        ).probability!!
        assertEquals(90, thin.promised)
        assertEquals(100, thin.actual)
        assertEquals("가장 큰 구간 2번 → 1번 더", 1, thin.moreTrips)

        val empty = week(outcomes, calibration = CalibrationDto(buckets = emptyList(), verdict = "insufficient"))
        assertEquals(3, empty.probability!!.moreTrips)

        assertNull("구버전 서버(캘리브레이션 없음)는 판정이 없다", week(outcomes).probability!!.verdict)
    }

    @Test
    fun `도착 기록이 없으면 확률 카드가 없다`() {
        val view = week(
            listOf(outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "unobserved")),
            onTimeRate = null,
        )
        assertNull(view.probability)
    }

    @Test
    fun `준비 시간은 계획과 실제를 둘 다 잰 아침만 평균한다`() {
        val view = week(
            listOf(
                outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 8.0, plannedPrep = 28.0, actualPrep = 31.0),
                outcome(2, "2026-09-15", "2026-09-15T00:00:00Z", "late", slack = -6.0, plannedPrep = 27.0, actualPrep = 30.6),
                outcome(3, "2026-09-16", "2026-09-16T00:00:00Z", "on_time", slack = 3.0, plannedPrep = 30.0, actualPrep = null),
                // 집에서 나서지 않는 일정은 준비가 없다
                outcome(
                    4, "2026-09-16", "2026-09-16T05:00:00Z", "on_time",
                    slack = 3.0, plannedPrep = 10.0, actualPrep = 50.0, prepApplies = false,
                ),
            ),
        )

        val prep = view.prep!!
        assertEquals("(28 + 27) / 2 = 27.5 → 28", 28, prep.plannedMinutes)
        assertEquals("(31 + 30.6) / 2 = 30.8 → 31", 31, prep.actualMinutes)
        assertEquals(2, prep.count)

        assertNull(week(listOf(outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 1.0))).prep)
    }

    @Test
    fun `버퍼 조정은 바뀐 분의 합이고 안 감 수를 그대로 둔다`() {
        val outcomes = listOf(outcome(1, "2026-09-14", "2026-09-14T00:00:00Z", "on_time", slack = 8.0))
        val raised = week(
            outcomes,
            buffer = WeeklyBufferDto(
                adjustments = listOf(
                    BufferAdjustmentDto(previousMinutes = 8, minutes = 10),
                    BufferAdjustmentDto(previousMinutes = 10, minutes = 13),
                    BufferAdjustmentDto(previousMinutes = null, minutes = 20),
                ),
            ),
            skipped = 2,
        )
        assertEquals(5, raised.bufferChangeMinutes)
        assertEquals(2, raised.skippedCount)

        val lowered = week(outcomes, buffer = WeeklyBufferDto(adjustments = listOf(BufferAdjustmentDto(previousMinutes = 10, minutes = 8))))
        assertEquals(-2, lowered.bufferChangeMinutes)
        assertEquals(0, week(outcomes).bufferChangeMinutes)
    }
}
