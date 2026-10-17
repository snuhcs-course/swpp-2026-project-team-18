// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.report

import com.swpp.wakeup.data.remote.ActualDto
import com.swpp.wakeup.data.remote.CalibrationBucketDto
import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.DeviationStatDto
import com.swpp.wakeup.data.remote.LearningDto
import com.swpp.wakeup.data.remote.OutcomeDeviationDto
import com.swpp.wakeup.data.remote.OutcomeDto
import com.swpp.wakeup.data.remote.PlannedDto
import com.swpp.wakeup.data.remote.PreviousWeekDto
import com.swpp.wakeup.data.remote.WeeklyDeviationDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.toView
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 프리뷰용 응답. 설계 문서의 예시 주(10월 5일 ~ 11일: 일정 7 · 도착 기록 6 · 정시 5 ·
 * 화요일 10분 지각, 지난주 3/5)를 서버 모양 그대로 만들고 **실제 매핑**을 거친다.
 * 화면이 쓰는 문구가 프리뷰와 실행에서 갈라지지 않게 하려는 것이다.
 *
 * 실제 사용자 데이터가 아니다. 앱 실행 중에는 참조하지 않는다.
 */
internal object ReportPreviewData {

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    private fun utc(date: String, time: String): String =
        LocalDateTime.parse("${date}T$time").atZone(SEOUL).toInstant().toString()

    private fun outcome(
        id: Long,
        date: String,
        start: String,
        title: String,
        result: String,
        slack: Double?,
        plan: List<String>,
        minutes: List<Double>,
        actual: List<String?>,
        actualMinutes: List<Double?>,
        deviation: List<Double?>,
        probability: Int? = 90,
        cause: String? = null,
    ) = OutcomeDto(
        event = id,
        title = title,
        startAt = utc(date, start),
        date = date,
        result = result,
        slackMinutes = slack,
        onTimeProbability = probability,
        tau = 0.9,
        tauBucketLower = 0.9,
        prepApplies = true,
        alarmOn = true,
        planned = PlannedDto(
            alarmAt = utc(date, plan[0]),
            departBy = utc(date, plan[1]),
            arriveAt = utc(date, plan[2]),
            prepMinutes = minutes[0],
            travelMinutes = minutes[1],
            bufferMinutes = minutes[2],
        ),
        actual = ActualDto(
            departAt = actual[0]?.let { utc(date, it) },
            arriveAt = actual[1]?.let { utc(date, it) },
            prepMinutes = actualMinutes[0],
            travelMinutes = actualMinutes[1],
        ),
        deviation = OutcomeDeviationDto(
            prepOver = deviation[0],
            departLate = deviation[1],
            travelOver = deviation[2],
        ),
        cause = cause,
        unmeasured = emptyList(),
    )

    private val exampleOutcomes = listOf(
        outcome(
            101, "2026-10-05", "09:00", "자료구조", OutcomeDto.RESULT_ON_TIME, 12.0,
            listOf("07:40", "08:08", "08:50"), listOf(28.0, 42.0, 10.0),
            listOf("08:06", "08:48"), listOf(27.0, 42.0), listOf(-1.0, -2.0, 0.0),
        ),
        outcome(
            102, "2026-10-05", "15:00", "동아리 회의", OutcomeDto.RESULT_ON_TIME, 15.0,
            listOf("13:40", "14:08", "14:50"), listOf(28.0, 42.0, 10.0),
            listOf("14:05", "14:45"), listOf(null, 40.0), listOf(null, -3.0, -2.0),
        ),
        outcome(
            412, "2026-10-06", "09:00", "자료구조", OutcomeDto.RESULT_LATE, -10.0,
            listOf("07:40", "08:08", "08:50"), listOf(28.0, 42.0, 10.0),
            listOf("08:15", "09:10"), listOf(34.0, 55.0), listOf(6.0, 7.0, 13.0),
            cause = "travel_over",
        ),
        outcome(
            103, "2026-10-07", "10:00", "실험", OutcomeDto.RESULT_ON_TIME, 3.0,
            listOf("08:35", "09:15", "09:50"), listOf(40.0, 35.0, 10.0),
            listOf("09:20", "09:57"), listOf(null, 37.0), listOf(null, 5.0, 2.0),
        ),
        outcome(
            104, "2026-10-08", "09:00", "자료구조", OutcomeDto.RESULT_ON_TIME, 9.0,
            listOf("07:42", "08:10", "08:50"), listOf(28.0, 40.0, 10.0),
            listOf("08:12", "08:51"), listOf(30.0, 39.0), listOf(2.0, 2.0, -1.0),
            probability = 91,
        ),
        outcome(
            105, "2026-10-08", "13:00", "알고리즘", OutcomeDto.RESULT_ON_TIME, 9.0,
            listOf("11:40", "12:08", "12:50"), listOf(28.0, 42.0, 10.0),
            listOf("12:11", "12:51"), listOf(null, 40.0), listOf(null, 3.0, -2.0),
        ),
        outcome(
            106, "2026-10-09", "10:00", "실험", OutcomeDto.RESULT_UNOBSERVED, null,
            listOf("08:35", "09:15", "09:50"), listOf(40.0, 35.0, 10.0),
            listOf(null, null), listOf(null, null), listOf(null, null, null),
            probability = null,
        ),
    )

    val exampleWeek: WeeklyReportView = WeeklyReportDto(
        weekStart = "2026-10-05",
        weekEnd = "2026-10-11",
        eventCount = 7,
        arrivedCount = 6,
        unobservedCount = 1,
        onTimeCount = 5,
        lateCount = 1,
        onTimeRate = 0.8333,
        medianSlackMinutes = 9.0,
        tightestSlackMinutes = -10.0,
        weekComplete = true,
        hasNextWeek = true,
        targetRate = 0.9,
        targetMet = false,
        previousWeek = PreviousWeekDto(
            weekStart = "2026-09-28", eventCount = 5, arrivedCount = 5, onTimeCount = 3,
            onTimeRate = 0.6, onTimeRateChangePp = 23,
        ),
        deviation = WeeklyDeviationDto(
            prepOver = DeviationStatDto(2, 4.0, 1.0),
            departLate = DeviationStatDto(5, 3.0, 2.0),
            travelOver = DeviationStatDto(5, -1.0, 7.0),
        ),
        learning = LearningDto(planCount = 7, prepFromRecords = 2, travelFromRecords = 3),
        outcomes = exampleOutcomes,
        summary = "이 주 일정 7건 중 도착을 기록한 6번에서 5번 정시 도착해 정시율 83%로 목표 90%에 " +
            "못 미쳤음. 지각 1번은 이동이 계획보다 오래 걸린 것 때문이었고 화요일에 10분 늦었음. " +
            "지난주 60%보다 23%p 올랐음. 이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 " +
            "보거나 몇 분 일찍 나서 볼 것.",
        summarySource = WeeklyReportDto.SOURCE_LLM,
    ).toView(SEOUL)

    /** 일정은 있었지만 도착을 하나도 기록하지 못한 주(⑧-a). */
    val unrecordedWeek: WeeklyReportView = WeeklyReportDto(
        weekStart = "2026-09-28",
        weekEnd = "2026-10-04",
        eventCount = 3,
        arrivedCount = 0,
        unobservedCount = 3,
        weekComplete = true,
        hasNextWeek = true,
        deviation = WeeklyDeviationDto(DeviationStatDto(), DeviationStatDto(), DeviationStatDto()),
        learning = LearningDto(planCount = 3),
        outcomes = listOf("2026-09-28", "2026-09-30", "2026-10-02").mapIndexed { index, date ->
            outcome(
                200L + index, date, "10:00", "실험", OutcomeDto.RESULT_UNOBSERVED, null,
                listOf("08:35", "09:15", "09:50"), listOf(40.0, 35.0, 10.0),
                listOf(null, null), listOf(null, null), listOf(null, null, null),
                probability = null,
            )
        },
        summary = "이 주 일정 3건 모두 도착 기록이 없어 정시 여부를 채점하지 못했음. " +
            "위치 권한을 켜 두고 알람을 해제한 뒤 이동하면 출발·도착이 자동으로 기록됨.",
        summarySource = WeeklyReportDto.SOURCE_TEMPLATE,
    ).toView(SEOUL)

    val longTerm: CalibrationView = CalibrationDto(
        buckets = listOf(
            CalibrationBucketDto(0.85, 0.90, 0.875, 7, 6, 0.8571, -0.0179, true),
            CalibrationBucketDto(0.90, 0.95, 0.925, 27, 24, 0.8889, -0.0361, true),
            CalibrationBucketDto(0.95, 1.0, 0.975, 2, 2, 1.0, 0.025, false),
        ),
        scoredCount = 36,
        unscoredCount = 5,
        reliableBucketCount = 2,
        meanGap = -0.027,
        onTimeRate = 0.8889,
        verdict = CalibrationDto.VERDICT_CALIBRATED,
    ).toView()

    val thinLongTerm: CalibrationView = CalibrationDto(
        scoredCount = 0,
        unscoredCount = 3,
        verdict = CalibrationDto.VERDICT_INSUFFICIENT,
    ).toView()
}
