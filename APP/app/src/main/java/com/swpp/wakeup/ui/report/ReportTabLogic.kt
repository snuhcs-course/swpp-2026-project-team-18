// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.report

import com.swpp.wakeup.domain.model.DayStatus
import com.swpp.wakeup.domain.model.LateRow
import com.swpp.wakeup.domain.model.ProbabilityCompare
import com.swpp.wakeup.domain.model.WeeklyReportView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 리포트 탭(iter4 H2-2 · H2-1, Figma `546:1943`)의 문구. 숫자보다 결론을 먼저 문장으로 말한다.
 *
 * 값은 전부 서버 주간 리포트(`WeeklyReportView`)에서 온다. 여기서는 말만 고른다 — 판정(정시 ·
 * 지각 · 캘리브레이션 · 버퍼 조정)은 서버 몫이다. 안드로이드 의존이 없어 JVM 테스트로 문구를
 * 고정한다(`ReportTabLogicTest`).
 */
internal object ReportTabLogic {

    private val MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일")
    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")

    /** 서버 판정 값(`calibration.verdict`) */
    const val VERDICT_CALIBRATED = "calibrated"
    const val VERDICT_OVERCONFIDENT = "overconfident"
    const val VERDICT_CONSERVATIVE = "conservative"
    const val VERDICT_INSUFFICIENT = "insufficient"

    /** 주 선택 가운데 글자. 같은 달이면 "9월 14일 ~ 20일", 달이 바뀌면 "9월 28일 ~ 10월 4일" */
    fun range(view: WeeklyReportView): String {
        val start = view.weekStart ?: return view.rangeLabel
        val end = start.plusDays(6)
        val tail = if (end.monthValue == start.monthValue) "${end.dayOfMonth}일" else end.format(MONTH_DAY)
        return "${start.format(MONTH_DAY)} ~ $tail"
    }

    /** "이번 주" / "지난주" / "이 주". [today] 가 든 주(월~일)가 이번 주다 */
    fun weekWord(weekStart: LocalDate?, today: LocalDate): String {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (weekStart) {
            thisMonday -> "이번 주"
            thisMonday.minusWeeks(1) -> "지난주"
            else -> "이 주"
        }
    }

    /**
     * 첫 카드 머리. [highlight] 는 초록으로 칠한다.
     * "이번 주 5번 중 " + "4번" + "\n제시간에 도착했어요"
     */
    data class Headline(val lead: String, val highlight: String?, val tail: String) {
        val text: String get() = lead + highlight.orEmpty() + tail
    }

    fun headline(view: WeeklyReportView, today: LocalDate): Headline {
        val word = weekWord(view.weekStart, today)
        return when {
            view.arrivedCount > 0 -> Headline(
                lead = "$word ${view.arrivedCount}번 중 ",
                highlight = "${view.onTimeCount}번",
                tail = "\n제시간에 도착했어요",
            )
            view.eventCount > 0 && word == "이번 주" -> Headline("이번 주 도착 기록이\n아직 없어요", null, "")
            view.eventCount > 0 -> Headline("${word}에는 도착 기록이\n없어요", null, "")
            word == "이번 주" -> Headline("이번 주는 아직\n다녀온 일정이 없어요", null, "")
            else -> Headline("${word}에는 일정이 없었어요", null, "")
        }
    }

    /**
     * 머리 아래 작은 글씨. 결과를 모르는 아침 수를 감추지 않는다(⑧ 와 같은 원칙) — 숨기면 "5번 중
     * 4번" 이 실제보다 좋아 보인다.
     */
    fun captions(view: WeeklyReportView): List<String> = listOfNotNull(
        view.unobservedCount.takeIf { it > 0 && view.arrivedCount > 0 }
            ?.let { "도착 기록이 없는 일정 ${it}개는 빼고 셌어요" },
        "위치 권한을 켜 두고 알람을 끈 뒤 집을 나서면 도착이 저절로 기록돼요"
            .takeIf { view.arrivedCount == 0 && view.eventCount > 0 },
        view.skippedCount.takeIf { it > 0 }?.let { "안 감으로 표시한 일정 ${it}개는 빼고 셌어요" },
    )

    enum class DotKind { ON_TIME, LATE, UNOBSERVED, TODAY, EMPTY }

    /** 요일 점 하나. [label] 은 오늘이면 "오늘" */
    data class Dot(
        val date: LocalDate,
        val label: String,
        val kind: DotKind,
        val isToday: Boolean,
        /** 그날 일정이 있으면 ⑨ 로 간다 */
        val clickable: Boolean,
        val eventId: Long?,
        /** 낭독용 "화요일, 늦은 일정 있음" */
        val description: String,
    )

    /**
     * 요일 점 일곱 개(Figma `days`). 점은 하루에 하나라서 그날 여러 번 다녔으면 한 번이라도 늦었을 때
     * 빨강이다. 일정이 없거나 아직 오지 않은 날은 회색 테두리, 오늘이 아직 비어 있으면 밝은 테두리다.
     */
    fun dots(view: WeeklyReportView, today: LocalDate): List<Dot> {
        val start = view.weekStart ?: return emptyList()
        val byDate = view.days.associateBy { it.date }
        return (0..6).map { offset ->
            val date = start.plusDays(offset.toLong())
            val day = byDate[date]
            val isToday = date == today
            val kind = when (day?.status) {
                DayStatus.ON_TIME -> DotKind.ON_TIME
                DayStatus.LATE -> DotKind.LATE
                DayStatus.UNOBSERVED -> DotKind.UNOBSERVED
                else -> if (isToday) DotKind.TODAY else DotKind.EMPTY
            }
            val name = WEEKDAYS[date.dayOfWeek.value - 1]
            val state = when (kind) {
                DotKind.ON_TIME -> "제시간에 도착"
                DotKind.LATE -> "늦은 일정 있음"
                DotKind.UNOBSERVED -> "도착 기록 없음"
                DotKind.TODAY, DotKind.EMPTY -> "기록 없음"
            }
            Dot(
                date = date,
                label = if (isToday) "오늘" else name,
                kind = kind,
                isToday = isToday,
                clickable = day?.clickable == true,
                eventId = day?.representative?.eventId,
                description = (if (isToday) "오늘 " else "") + "${name}요일, $state",
            )
        }
    }

    /** "화요일 6분 늦었어요" */
    fun lateTitle(row: LateRow): String {
        val day = WEEKDAYS[row.date.dayOfWeek.value - 1] + "요일"
        return row.lateMinutes?.let { "$day ${it}분 늦었어요" } ?: "${day}에 늦었어요"
    }

    /** "9:00 자료구조 및 알고리즘" */
    fun lateSub(row: LateRow): String = listOfNotNull(row.startClock, row.title).joinToString(" ")

    /**
     * 막대를 그리는가. 서버가 그 주 판정을 보류했으면(표본 부족) 그리지 않는다 — 2번 중 1번을
     * "실제 50%" 로 보이면 앱이 크게 틀린 것처럼 읽힌다. 구버전 서버(판정 없음)는 그린다.
     */
    fun showBars(compare: ProbabilityCompare): Boolean =
        compare.verdict != VERDICT_INSUFFICIENT && compare.promised != null

    /**
     * 판정 한 줄(굵게). 서버 판정의 말 바꾸기(Figma 캡션 `546:2097`).
     * 표본 부족 → "기록이 아직 적어요", 잘 맞음 → "거의 맞았어요", 과신 → "조금 낙관적이었어요",
     * 지나치게 이름 → "필요보다 일찍 깨웠어요". 판정이 없으면 null.
     */
    fun verdictTitle(compare: ProbabilityCompare): String? = when (compare.verdict) {
        VERDICT_INSUFFICIENT -> "기록이 아직 적어요."
        VERDICT_CALIBRATED -> "거의 맞았어요."
        VERDICT_OVERCONFIDENT -> "조금 낙관적이었어요."
        VERDICT_CONSERVATIVE -> "필요보다 일찍 깨웠어요."
        else -> null
    }

    /**
     * 판정 아래 줄(보통 굵기). 표본 부족이면 "N번 더 다니면 보여 드릴게요"(N = 최소 횟수 − 지금 횟수).
     * 그 주에 서버가 여유 시간을 실제로 바꿨을 때만 알람 이야기를 덧붙인다 — 늘렸으면 "더 일찍",
     * 줄였으면 "조금 늦게". 진행 중인 주는 앞으로의 일, 지난 주는 한 일로 쓴다.
     */
    fun verdictBody(compare: ProbabilityCompare, bufferChangeMinutes: Int, inProgress: Boolean): List<String> =
        listOfNotNull(
            "${compare.moreTrips}번 더 다니면 보여 드릴게요".takeIf { compare.verdict == VERDICT_INSUFFICIENT },
            bufferLine(bufferChangeMinutes, inProgress),
        )

    fun bufferLine(bufferChangeMinutes: Int, inProgress: Boolean): String? = when {
        bufferChangeMinutes > 0 && inProgress -> "다음 주엔 알람을 조금 더 일찍 맞출게요"
        bufferChangeMinutes > 0 -> "이 주부터 알람을 조금 더 일찍 맞췄어요"
        bufferChangeMinutes < 0 && inProgress -> "다음 주엔 알람을 조금 늦게 맞출게요"
        bufferChangeMinutes < 0 -> "이 주부터 알람을 조금 늦게 맞췄어요"
        else -> null
    }

    /** 막대 길이(0~1). 확률은 %, 준비 시간은 둘 중 긴 쪽의 1.4배를 끝으로 둔다(Figma 28분 · 31분) */
    fun percentFraction(percent: Int): Float = (percent.coerceIn(0, 100) / 100f)

    fun minutesFraction(minutes: Int, other: Int): Float {
        val end = maxOf(minutes, other, 1) * 1.4f
        return (minutes.coerceAtLeast(0) / end).coerceIn(0f, 1f)
    }

    /** 실제가 예상보다 나쁜가. 실제 막대 · 글자를 주황으로 칠한다(Figma) */
    fun probabilityWorse(compare: ProbabilityCompare): Boolean =
        compare.promised != null && compare.actual < compare.promised

    fun prepLonger(planned: Int, actual: Int): Boolean = actual > planned

    const val PREP_NOTE = "다음 알람부터 내 기록으로 맞춰요"
}
