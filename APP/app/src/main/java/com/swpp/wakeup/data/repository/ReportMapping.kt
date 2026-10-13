// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.DeviationStatDto
import com.swpp.wakeup.data.remote.LateCauseDto
import com.swpp.wakeup.data.remote.OutcomeDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.domain.model.CalibrationPoint
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.CauseExplanation
import com.swpp.wakeup.domain.model.DayStatus
import com.swpp.wakeup.domain.model.DeviationLine
import com.swpp.wakeup.domain.model.GapNotice
import com.swpp.wakeup.domain.model.LateDayLine
import com.swpp.wakeup.domain.model.MorningOutcome
import com.swpp.wakeup.domain.model.OutcomeResult
import com.swpp.wakeup.domain.model.PlanActualRow
import com.swpp.wakeup.domain.model.ReportDay
import com.swpp.wakeup.domain.model.ReportSummary
import com.swpp.wakeup.domain.model.RowKind
import com.swpp.wakeup.domain.model.Tone
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.domain.model.halfUp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * 리포트 응답 → 화면 값. 안드로이드 의존이 없어 JVM 테스트로 문구까지 고정한다
 * (`ReportMappingTest`).
 *
 * 규칙
 * - **지어낸 숫자 금지.** 화면 숫자는 서버 값이거나 서버 값의 표시용 집계(최솟값·개수)다.
 *   정시·지각·원인·목표 판정과 τ 구간은 서버가 한다.
 * - 측정하지 못한 값은 0 이 아니라 "측정 안 됨"·"기록 없음" 이다.
 * - 분·% 반올림은 서버와 같다([halfUp]). 지각은 최소 1분이다([lateMinutes]).
 * - 시각은 기기 시간대 `H:mm`, 일정 시작만 `HH:mm`. 음수 부호는 U+2212 "−".
 * - 요일 칸은 `date` 로만 정한다. 주 밖의 날짜·못 읽는 날짜는 뺀다.
 */

private val RANGE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")
private val START_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm")
private val WEEKDAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/** 음수 부호. 하이픈(-)은 숫자 옆에서 짧아 보여 놓치기 쉽다. */
internal const val MINUS = "\u2212"

/** 칩에 넣는 일정 제목의 최대 길이. 넘으면 자르고 "…" 를 붙인다. */
private const val CHIP_TITLE_MAX = 8

internal fun percent(value: Double?): Int? = value?.let { halfUp(it.coerceIn(0.0, 1.0) * 100) }

/** 지각 분(양수). 서버 `late_minutes` 와 같다 — 늦었으면 최소 1분이다. */
internal fun lateMinutes(slack: Double): Int = maxOf(1, halfUp(-slack))

/** 여유를 화면용 정수 분으로. 정시는 반올림, 지각은 음수이고 최소 1분이다. */
internal fun slackDisplay(slack: Double): Int = if (slack < 0) -lateMinutes(slack) else halfUp(slack)

/** "+6분" / "−3분" / "±0분" */
internal fun signedMinutes(minutes: Int): String = when {
    minutes > 0 -> "+${minutes}분"
    minutes < 0 -> "$MINUS${-minutes}분"
    else -> "±0분"
}

/** τ 표기. 둘째 자리까지는 늘 쓰고 셋째 자리는 있을 때만(0.9 → "0.90", 0.999 → "0.999"). */
internal fun tauLabel(tau: Double): String {
    val thousandths = Math.round(tau * 1000)
    val whole = thousandths / 1000
    val fraction = (thousandths % 1000).toString().padStart(3, '0')
    return "$whole." + if (fraction.endsWith("0")) fraction.dropLast(1) else fraction
}

private fun parseDate(raw: String?): LocalDate? =
    raw?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

private fun parseInstant(raw: String?): Instant? =
    raw?.let { runCatching { Instant.parse(it.trim()) }.getOrNull() }

private fun clock(raw: String?, zone: ZoneId): String? =
    parseInstant(raw)?.atZone(zone)?.format(TIME_FORMAT)

private fun weekdayName(date: LocalDate): String = WEEKDAY_NAMES[date.dayOfWeek.value - 1]

// ---------------------------------------------------------------------------
// ⑧ 주간 리포트
// ---------------------------------------------------------------------------

internal fun WeeklyReportDto.toView(zone: ZoneId = ZoneId.systemDefault()): WeeklyReportView {
    val start = parseDate(weekStart)
    val end = parseDate(weekEnd)
    val range = if (start != null && end != null) {
        "${start.format(RANGE_FORMAT)} ~ ${end.format(RANGE_FORMAT)}"
    } else {
        "—"
    }

    // 주 안에 놓을 수 있는 일정만. 날짜를 못 읽거나 주 밖이면 칸에도 지각 줄에도 없다.
    val placed = if (start == null) {
        emptyList()
    } else {
        outcomes.orEmpty().mapNotNull { dto ->
            val date = parseDate(dto.date) ?: return@mapNotNull null
            val offset = ChronoUnit.DAYS.between(start, date)
            if (offset !in 0..6) return@mapNotNull null
            Placed(date, dto, dto.toMorning(zone, lateCount))
        }
    }

    val hasArrivals = arrivedCount > 0
    val median = medianSlackMinutes?.takeIf { hasArrivals }
    val lateLines = placed.mapNotNull { it.toLateLine() }

    return WeeklyReportView(
        rangeLabel = range,
        weekStart = start,
        canGoNext = hasNextWeek ?: true,
        inProgress = weekComplete == false,
        eventCount = eventCount,
        arrivedCount = arrivedCount,
        unobservedCount = unobservedCount,
        onTimeCount = onTimeCount,
        lateCount = lateCount,
        summary = summary?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { ReportSummary(it, isAi = summarySource == WeeklyReportDto.SOURCE_LLM) },
        onTimeCountLabel = if (hasArrivals) "$onTimeCount/$arrivedCount" else "—",
        onTimeTone = when {
            !hasArrivals -> Tone.NEUTRAL
            targetMet == true -> Tone.GOOD
            targetMet == false -> Tone.WARN
            else -> Tone.NEUTRAL
        },
        onTimeLabel = when {
            !hasArrivals -> "정시 도착 · 기록 없음"
            else -> percent(onTimeRate)?.let { "정시 도착 $it%" } ?: "정시 도착"
        },
        onTimeSubLabel = if (hasArrivals) onTimeContext() else null,
        slackValueLabel = median?.let { medianLabel(halfUp(it)).first } ?: "—",
        slackTone = median?.let { medianLabel(halfUp(it)).second } ?: Tone.NEUTRAL,
        slackLabel = if (median == null) "평소 도착 · 기록 없음" else "평소 도착 (중간값)",
        slackSubLabel = tightestSlackMinutes?.takeIf { median != null }?.let(::tightestLabel),
        gapNotice = gapNotice(),
        days = if (start == null || outcomes == null) emptyList() else buildDays(start, placed),
        daysCoverageLabel = "기록 $arrivedCount/${eventCount}건",
        lateDays = lateLines,
        hasUnknownCause = lateLines.any { it.causeTitle == CAUSE_UNKNOWN_SHORT },
        deviations = deviation?.let {
            listOf(
                DeviationLine("준비", durationAverage(it.prepOver), averageTone(it.prepOver)),
                DeviationLine("출발", departAverage(it.departLate), averageTone(it.departLate)),
                DeviationLine("이동", durationAverage(it.travelOver), averageTone(it.travelOver)),
            )
        }.orEmpty(),
        learningLabel = learningLabel(),
    )
}

private class Placed(val date: LocalDate, val dto: OutcomeDto, val morning: MorningOutcome)

/** "목표 90% · 지난주 3/5". 있는 조각만. 지난주는 도착 기록이 있을 때만 */
private fun WeeklyReportDto.onTimeContext(): String? {
    val parts = listOfNotNull(
        percent(targetRate)?.let { "목표 $it%" },
        previousWeek?.takeIf { it.arrivedCount > 0 }
            ?.let { "지난주 ${it.onTimeCount}/${it.arrivedCount}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun medianLabel(minutes: Int): Pair<String, Tone> = when {
    minutes >= 1 -> "${minutes}분 일찍" to Tone.GOOD
    minutes <= -1 -> "${-minutes}분 늦게" to Tone.BAD
    else -> "정각" to Tone.NEUTRAL
}

private fun tightestLabel(slack: Double): String {
    if (slack < 0) return "가장 늦은 날 ${lateMinutes(slack)}분 늦음"
    val minutes = halfUp(slack)
    return if (minutes == 0) "가장 아슬아슬 정각 도착" else "가장 아슬아슬 ${minutes}분 전"
}

/**
 * 측정 공백 안내.
 *
 * 출발·도착은 **알람을 해제할 때만** 기록된다. 알람이 없던 일정이 비어 있는 것은
 * 측정 실패가 아니므로 따로 센다. 구버전 서버는 알람 여부가 없어 전부 측정 못 함으로 센다.
 */
private fun WeeklyReportDto.gapNotice(): GapNotice? {
    if (eventCount <= 0 || unobservedCount * 2 < eventCount) return null
    val withoutAlarm = outcomes.orEmpty().count { it.isUnobserved() && it.alarmOn == false }
    val measuredGap = (unobservedCount - withoutAlarm).coerceAtLeast(0)
    val share = if (arrivedCount > 0) ". 위 비율은 ${arrivedCount}건만 반영한 값임" else ""
    return if (measuredGap > 0) {
        GapNotice(
            title = "측정하지 못한 아침 ${measuredGap}건",
            body = "도착을 기록하지 못한 아침은 정시로 세지 않음$share",
            caption = withoutAlarm.takeIf { it > 0 }
                ?.let { "알람이 울리지 않은 일정 ${it}건은 출발·도착을 기록하지 않음" },
        )
    } else {
        GapNotice(
            title = "알람이 울리지 않은 일정 ${withoutAlarm}건",
            body = "알람이 울리지 않은 일정은 출발·도착을 기록하지 않아 정시로 세지 않음$share",
            caption = null,
        )
    }
}

private fun OutcomeDto.isUnobserved(): Boolean =
    result != OutcomeDto.RESULT_ON_TIME && result != OutcomeDto.RESULT_LATE

private fun buildDays(start: LocalDate, placed: List<Placed>): List<ReportDay> =
    (0..6).map { offset ->
        val date = start.plusDays(offset.toLong())
        val mornings = placed.filter { it.date == date }.map { it.morning }
        val status = when {
            mornings.isEmpty() -> DayStatus.NONE
            mornings.any { it.result == OutcomeResult.LATE } -> DayStatus.LATE
            mornings.any { it.result == OutcomeResult.ON_TIME } -> DayStatus.ON_TIME
            else -> DayStatus.UNOBSERVED
        }
        val draft = ReportDay(date, weekdayName(date), status, null, "", "", mornings)
        val minutes = draft.representative?.slackMinutes
            ?.takeIf { status == DayStatus.ON_TIME || status == DayStatus.LATE }
            ?.let(::slackDisplay)
        val longName = "${weekdayName(date)}요일"
        draft.copy(
            slackMinutes = minutes,
            valueLabel = when {
                status == DayStatus.NONE -> ""
                minutes == null -> "—"
                minutes > 0 -> "+$minutes"
                minutes < 0 -> "$MINUS${-minutes}"
                else -> "0"
            },
            description = longName + ", " + when {
                status == DayStatus.NONE -> "일정 없음"
                minutes == null -> "도착 기록 없음"
                minutes > 0 -> "${minutes}분 일찍 도착"
                minutes < 0 -> "${-minutes}분 늦게 도착"
                else -> "정각 도착"
            },
        )
    }

private fun Placed.toLateLine(): LateDayLine? {
    if (morning.result != OutcomeResult.LATE) return null
    val id = dto.event ?: return null
    val dateText = date.format(RANGE_FORMAT)
    val late = dto.slackMinutes?.let { "${lateMinutes(it)}분 늦음" } ?: "늦음"
    val causeTitle = if (dto.hasKnownCause()) morning.cause?.title ?: CAUSE_UNKNOWN_SHORT
    else CAUSE_UNKNOWN_SHORT
    return LateDayLine(
        date = date,
        eventId = id,
        dateLabel = "${weekdayName(date)} $dateText",
        lateLabel = late,
        causeTitle = causeTitle,
        description = "${weekdayName(date)}요일 $dateText, $late, $causeTitle",
    )
}

/** ⑧ 지각한 날 줄의 "원인을 못 나눔" 표기. 카드 아래 캡션이 이 말을 풀어 쓴다. */
internal const val CAUSE_UNKNOWN_SHORT = "원인 측정 못 함"

private val KNOWN_CAUSES = setOf(
    LateCauseDto.LABEL_PREP,
    LateCauseDto.LABEL_TRAVEL,
    LateCauseDto.LABEL_DEPART,
    LateCauseDto.LABEL_PLAN_TIGHT,
)

private fun OutcomeDto.hasKnownCause(): Boolean = cause in KNOWN_CAUSES

private fun averageTone(stat: DeviationStatDto?): Tone {
    val mean = stat?.meanMinutes?.takeIf { stat.count > 0 } ?: return Tone.UNKNOWN
    val minutes = halfUp(mean)
    return when {
        minutes >= 1 -> Tone.WARN
        minutes <= -1 -> Tone.GOOD
        else -> Tone.NEUTRAL
    }
}

/** 준비·이동. "평균 4분 더 걸림 · 2회" / "평균 1분 덜 걸림 · 5회" / "계획과 비슷함 · 3회" */
private fun durationAverage(stat: DeviationStatDto?): String {
    val mean = stat?.meanMinutes?.takeIf { stat.count > 0 } ?: return "측정 안 됨"
    val minutes = halfUp(mean)
    return when {
        minutes >= 1 -> "평균 ${minutes}분 더 걸림 · ${stat.count}회"
        minutes <= -1 -> "평균 ${-minutes}분 덜 걸림 · ${stat.count}회"
        else -> "계획과 비슷함 · ${stat.count}회"
    }
}

/** 출발. "평균 3분 늦게 나섬 · 5회" / "평균 2분 일찍 나섬 · 4회" / "계획대로 나섬 · 3회" */
private fun departAverage(stat: DeviationStatDto?): String {
    val mean = stat?.meanMinutes?.takeIf { stat.count > 0 } ?: return "측정 안 됨"
    val minutes = halfUp(mean)
    return when {
        minutes >= 1 -> "평균 ${minutes}분 늦게 나섬 · ${stat.count}회"
        minutes <= -1 -> "평균 ${-minutes}분 일찍 나섬 · ${stat.count}회"
        else -> "계획대로 나섬 · ${stat.count}회"
    }
}

/**
 * 기록 반영. 계획이 실제로 쓴 출처를 센 것이다(서버 `learning`).
 *
 * 이동 보정은 모든 사용자의 기록으로 만든다 — "내 기록" 이라고 쓰지 않는다.
 */
private fun WeeklyReportDto.learningLabel(): String? {
    val counts = learning?.takeIf { it.planCount > 0 } ?: return null
    val parts = listOfNotNull(
        counts.prepFromRecords.takeIf { it > 0 }?.let { "${it}건은 내 준비 기록으로" },
        counts.travelFromRecords.takeIf { it > 0 }?.let { "${it}건은 이동 기록 보정으로" },
    )
    return if (parts.isEmpty()) {
        "이번 주 계획은 준비·이동 기록 없이 입력한 준비 시간과 경로 예상 시간으로 계산됨"
    } else {
        "이번 주 일정 ${counts.planCount}건의 계획 중 ${parts.joinToString(", ")} 계산함"
    }
}

// ---------------------------------------------------------------------------
// ⑨ 상세 리포트
// ---------------------------------------------------------------------------

internal fun OutcomeDto.toMorning(zone: ZoneId, weekLateCount: Int): MorningOutcome {
    val outcome = when (result) {
        OutcomeDto.RESULT_ON_TIME -> OutcomeResult.ON_TIME
        OutcomeDto.RESULT_LATE -> OutcomeResult.LATE
        // 모르는 값은 기록 없음으로 본다. 정시로 세면 정시율을 부풀린다.
        else -> OutcomeResult.UNOBSERVED
    }
    val startLabel = parseInstant(startAt)?.atZone(zone)?.format(START_FORMAT)
    val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: "일정"
    val shortName = if (name.length > CHIP_TITLE_MAX) {
        name.take(CHIP_TITLE_MAX).trimEnd() + "…"
    } else {
        name
    }
    val heading = listOfNotNull(startLabel, name).joinToString(" ")

    val alarmPart = when (alarmOn) {
        false -> "알람 꺼짐"
        else -> clock(planned?.alarmAt, zone)?.let { "알람 $it" }
    }

    val rows = buildList {
        if (prepApplies == true) {
            add(
                durationRow("준비", planned?.prepMinutes, actual?.prepMinutes, deviation?.prepOver)
            )
        }
        add(timeRow("출발", planned?.departBy, actual?.departAt, deviation?.departLate, zone))
        add(durationRow("이동", planned?.travelMinutes, actual?.travelMinutes, deviation?.travelOver))
    }

    return MorningOutcome(
        eventId = event,
        chipLabel = listOfNotNull(startLabel, shortName).joinToString(" "),
        result = outcome,
        headline = when (outcome) {
            OutcomeResult.LATE -> slackMinutes?.let { "${lateMinutes(it)}분 지각" } ?: "지각"
            OutcomeResult.ON_TIME -> slackMinutes?.let {
                val minutes = halfUp(it)
                if (minutes == 0) "정각 도착" else "${minutes}분 전 도착"
            } ?: "정시 도착"
            OutcomeResult.UNOBSERVED -> "도착 기록 없음"
        },
        headlineNote = when {
            outcome != OutcomeResult.UNOBSERVED -> null
            alarmOn == false -> "알람이 울리지 않은 일정이라 출발·도착을 기록하지 않았음"
            else -> "도착을 기록하지 못해 채점하지 않았음"
        },
        contextLine = listOfNotNull(heading, alarmPart).joinToString(" · "),
        confidenceLine = onTimeProbability?.let { "앱이 본 정시 확률 $it%" }
            ?: tau?.let { "적용 τ ${tauLabel(it)}" },
        rows = rows,
        arrival = arrivalRow(planned?.arriveAt, actual?.arriveAt, outcome, zone),
        cause = if (outcome == OutcomeResult.LATE) causeExplanation() else null,
        tauBucketLower = tauBucketLower,
        slackMinutes = slackMinutes,
        isOnlyLateOfWeek = outcome == OutcomeResult.LATE && weekLateCount == 1,
    )
}

private fun deltaTone(minutes: Int?): Tone = when {
    minutes == null -> Tone.UNKNOWN
    minutes > 0 -> Tone.BAD
    minutes < 0 -> Tone.GOOD
    // "±0분" 은 흐린 글자로 쓴다. 칭찬도 경고도 아니다.
    else -> Tone.UNKNOWN
}

private fun durationRow(
    label: String,
    planned: Double?,
    actual: Double?,
    delta: Double?,
): PlanActualRow {
    val minutes = delta?.let(::halfUp)
    val longest = maxOf(planned ?: 0.0, actual ?: 0.0)
    fun fraction(value: Double?): Float? =
        value?.let { if (longest > 0) (it / longest).toFloat().coerceIn(0f, 1f) else 0f }
    return PlanActualRow(
        label = label,
        kind = RowKind.DURATION,
        plannedLabel = planned?.let { "계획 ${halfUp(it)}분" } ?: "계획 없음",
        actualLabel = actual?.let { "실제 ${halfUp(it)}분" },
        deltaLabel = minutes?.let(::signedMinutes) ?: "측정 안 됨",
        deltaTone = deltaTone(minutes),
        actualTone = if ((delta ?: 0.0) > 0) Tone.BAD else Tone.GOOD,
        plannedFraction = fraction(planned),
        actualFraction = fraction(actual),
    )
}

private fun timeRow(
    label: String,
    planned: String?,
    actual: String?,
    delta: Double?,
    zone: ZoneId,
): PlanActualRow {
    val minutes = delta?.let(::halfUp)
    return PlanActualRow(
        label = label,
        kind = RowKind.TIME,
        plannedLabel = clock(planned, zone)?.let { "계획 $it" } ?: "계획 없음",
        actualLabel = clock(actual, zone)?.let { "실제 $it" },
        deltaLabel = minutes?.let(::signedMinutes) ?: "측정 안 됨",
        deltaTone = deltaTone(minutes),
        actualTone = if ((delta ?: 0.0) > 0) Tone.BAD else Tone.GOOD,
        plannedFraction = null,
        actualFraction = null,
    )
}

/** 도착. 차이는 쓰지 않는다(결과 카드가 말한다). 실제 색은 지각 빨강, 그 밖 초록. */
private fun arrivalRow(
    planned: String?,
    actual: String?,
    outcome: OutcomeResult,
    zone: ZoneId,
): PlanActualRow? {
    val plannedClock = clock(planned, zone)
    val actualClock = clock(actual, zone)
    if (plannedClock == null && actualClock == null) return null
    return PlanActualRow(
        label = "도착",
        kind = RowKind.TIME,
        plannedLabel = plannedClock?.let { "계획 $it" } ?: "계획 없음",
        actualLabel = actualClock?.let { "실제 $it" },
        deltaLabel = "",
        deltaTone = Tone.UNKNOWN,
        actualTone = if (outcome == OutcomeResult.LATE) Tone.BAD else Tone.GOOD,
        plannedFraction = null,
        actualFraction = null,
    )
}

/**
 * 원인 제목의 분. 같은 화면 차이 줄과 같은 반올림([halfUp])이다. 반올림해 1분이 안
 * 되거나 값이 없으면 숫자를 뺀다("이동이 더 걸렸음") — 줄이 "±0분" 인데 제목이 "1분" 이면
 * 한 화면이 같은 값을 두 가지로 말한다. (Partly AI-generated (Claude))
 */
private fun overrun(value: Double?): String =
    value?.let(::halfUp)?.takeIf { it >= 1 }?.let { "${it}분 " }.orEmpty()

private val STAGE_NAMES = mapOf(
    LateCauseDto.LABEL_PREP to "준비",
    LateCauseDto.LABEL_DEPART to "출발",
    LateCauseDto.LABEL_TRAVEL to "이동",
)

/**
 * 지각 원인 카드. 원인 판정은 서버(`cause`)가 하고 앱은 문장만 고른다.
 *
 * 원인을 나누지 못한 지각의 안내는 **실제 기록 방식**에 맞춘다. 지각이 기록됐다면
 * 위치 권한은 켜져 있었다 — 그래서 "위치 권한을 켜라" 고 하지 않는다.
 */
private fun OutcomeDto.causeExplanation(): CauseExplanation {
    val prepCounts = prepApplies != false
    val stages = if (prepCounts) "준비·출발·이동" else "출발·이동"
    val biggest = "$stages 중 계획보다 가장 크게 늘어난 단계임"
    val missing = unmeasured.orEmpty()
    return when (cause) {
        LateCauseDto.LABEL_TRAVEL -> CauseExplanation(
            title = "이동이 ${overrun(deviation?.travelOver)}더 걸렸음",
            body = biggest,
            action = "다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것",
        )

        LateCauseDto.LABEL_DEPART -> CauseExplanation(
            title = if (prepCounts) "집에서 ${overrun(deviation?.departLate)}늦게 나섰음"
            else "출발이 ${overrun(deviation?.departLate)}늦었음",
            body = if (LateCauseDto.LABEL_PREP in missing) {
                "$biggest. 준비 항목을 기록하면 준비와 출발 중 어디서 늦었는지 나눠 볼 수 있음"
            } else {
                biggest
            },
            action = "알람이 정한 출발 시각을 지켜 볼 것",
        )

        LateCauseDto.LABEL_PREP -> CauseExplanation(
            title = "준비가 ${overrun(deviation?.prepOver)}더 걸렸음",
            body = biggest,
            action = "오래 걸린 준비 항목의 시간 범위를 루틴에서 늘려 두면 알람이 그만큼 앞당겨짐",
        )

        LateCauseDto.LABEL_PLAN_TIGHT -> CauseExplanation(
            title = "계획대로 움직였는데 늦었음",
            body = "${stages}이 모두 계획 안이었음. 계획의 여유가 부족했음",
            action = if (prepCounts) "평소 준비 시간을 넉넉히 잡으면 알람이 앞당겨짐"
            else "몇 분 일찍 나서 볼 것",
        )

        else -> unknownCause(missing)
    }
}

private fun OutcomeDto.unknownCause(missing: List<String>): CauseExplanation {
    val title = "원인을 나누지 못했음"
    val departMissing = LateCauseDto.LABEL_DEPART in missing
    return when {
        planned?.departBy == null -> CauseExplanation(
            title,
            "그날은 알람 계획이 없어 계획과 비교하지 못했음",
            "집 주소와 일정 장소를 정해 두면 계획이 계산됨",
        )

        prepApplies == false && departMissing -> CauseExplanation(
            title,
            "출발지가 집이 아닌 일정이라 출발 시각을 기록하지 않았음",
            null,
        )

        departMissing -> CauseExplanation(
            title,
            "집을 나선 시각이 기록되지 않아 출발·이동을 나눠 보지 못했음",
            "집 안에서 알람을 해제하면 나서는 시각까지 기록됨",
        )

        missing == listOf(LateCauseDto.LABEL_PREP) -> CauseExplanation(
            title,
            "측정 안 됨: 준비",
            "준비 항목을 기록하면 준비 시간도 나눠 볼 수 있음",
        )

        else -> CauseExplanation(
            title,
            missing.mapNotNull { STAGE_NAMES[it] }.takeIf { it.isNotEmpty() }
                ?.joinToString(" · ", prefix = "측정 안 됨: ")
                ?: "기록이 부족해 단계를 나눠 보지 못했음",
            null,
        )
    }
}

// ---------------------------------------------------------------------------
// 예측 검증(캘리브레이션)
// ---------------------------------------------------------------------------

internal fun CalibrationDto.toView(): CalibrationView {
    val points = buckets.orEmpty().map { bucket ->
        val promisedPercent = percent(bucket.center) ?: 0
        val actualPercent = percent(bucket.actualRate)
        CalibrationPoint(
            rangeLabel = "${percent(bucket.lower)}~${percent(bucket.upper)}%",
            promised = bucket.center.toFloat().coerceIn(0f, 1f),
            actual = bucket.actualRate?.toFloat()?.coerceIn(0f, 1f),
            total = bucket.total,
            reliable = bucket.reliable,
            countLabel = "${bucket.total}번 중 ${bucket.onTime}번 정시",
            // 신뢰할 수 없는 버킷은 과신으로 낙인찍지 않는다. 한 번 지각한 것과
            // 계통 오차는 다르다.
            overconfident = bucket.reliable &&
                actualPercent != null && actualPercent < promisedPercent,
            lower = bucket.lower,
        )
    }

    val (verdictLabel, verdictNote, action) = when (verdict) {
        CalibrationDto.VERDICT_CALIBRATED -> Triple(
            "잘 맞음",
            "앱이 말한 확률과 실제 정시율이 거의 같음",
            null,
        )

        CalibrationDto.VERDICT_OVERCONFIDENT -> Triple(
            "과신 중",
            "앱이 약속한 것보다 실제 정시율이 낮음. 알람이 늦게 잡히고 있음",
            "지각 위험을 낮추면 알람이 앞당겨짐",
        )

        CalibrationDto.VERDICT_CONSERVATIVE -> Triple(
            "지나치게 이름",
            "실제로는 거의 늘 정시임. 알람이 필요 이상으로 이름",
            "지각 위험을 높이면 더 잘 수 있음",
        )

        else -> Triple(
            "표본 부족",
            "판정하려면 같은 구간에서 ${minSamples}번 이상의 결과가 필요함",
            "아침에 이동 기록이 쌓이면 자동으로 채워짐",
        )
    }

    return CalibrationView(
        points = points,
        scoredCount = scoredCount,
        unscoredCount = unscoredCount,
        verdictLabel = verdictLabel,
        verdictNote = verdictNote,
        action = action,
        verdict = verdict ?: CalibrationDto.VERDICT_INSUFFICIENT,
    )
}

