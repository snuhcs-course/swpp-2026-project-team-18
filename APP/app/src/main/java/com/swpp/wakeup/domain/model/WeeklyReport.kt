package com.swpp.wakeup.domain.model

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor

// Partly AI-generated (Claude)
/**
 * 주간 리포트(Figma ⑧)의 화면용 표현. 상세 리포트(⑨)도 여기서 그날을 찾는다.
 *
 * ## 이 화면은 앱을 변호하지 않는다
 *
 * 목적은 **앱이 틀렸는지 사용자에게 보여주는 것**이다. 그래서 여기 있는 값은
 * 전부 "좋게 보이도록" 가공하지 않는다.
 *
 * - [unobservedCount] 를 감추지 않는다. 결과를 모르는 아침이 많으면 정시율
 *   자체가 적은 표본에 기댄 것이다.
 * - 가장 아슬아슬했던 날([slackSubLabel])을 중간값과 함께 보여준다. 정시였어도
 *   여유가 1분이면 운이 좋았던 것이다.
 * - 측정하지 못한 값은 0 이 아니라 "측정 안 됨"·"기록 없음" 이다.
 *
 * 문구는 전부 [com.swpp.wakeup.data.repository.toView] 가 만든다(JVM 테스트 대상).
 * 화면은 [Tone] 을 색으로 바꾸기만 한다.
 */
data class WeeklyReportView(
    /** "10월 5일 ~ 10월 11일". 날짜를 못 읽으면 "—" */
    val rangeLabel: String,
    /** 그 주 월요일. 요일 칸과 ⑨ 의 기준이다. 못 읽으면 null */
    val weekStart: LocalDate?,
    /** "›" 를 켤 수 있는가. 서버 `has_next_week`, 구버전이면 true */
    val canGoNext: Boolean,
    /** 진행 중인 주인가. 요약 카드에 "진행 중" 칩을 단다 */
    val inProgress: Boolean,

    val eventCount: Int,
    val arrivedCount: Int,
    val unobservedCount: Int,
    val onTimeCount: Int,
    val lateCount: Int,

    /** 서버가 쓴 주간 요약. 구버전 서버·빈 문장이면 null */
    val summary: ReportSummary?,

    /** 왼쪽 타일 큰 값 "5/6". 도착 기록이 없으면 "—" */
    val onTimeCountLabel: String,
    val onTimeTone: Tone,
    /** "정시 도착 83%" / "정시 도착 · 기록 없음" */
    val onTimeLabel: String,
    /** "목표 90% · 지난주 3/5". 있는 조각만, 없으면 null */
    val onTimeSubLabel: String?,

    /** 오른쪽 타일 큰 값 "9분 일찍" / "3분 늦게" / "정각" / "—" */
    val slackValueLabel: String,
    val slackTone: Tone,
    /** "평소 도착 (중간값)" / "평소 도착 · 기록 없음" */
    val slackLabel: String,
    /** "가장 늦은 날 10분 늦음" / "가장 아슬아슬 3분 전" */
    val slackSubLabel: String?,

    /** 측정 공백 안내. [observationGapMatters] 일 때만 */
    val gapNotice: GapNotice?,

    /** 요일 7칸. 구버전 서버(outcomes 없음)·주 시작을 못 읽으면 빈 목록 */
    val days: List<ReportDay>,
    /** "기록 6/7건" */
    val daysCoverageLabel: String,
    /** 지각한 일정, 시작 순 */
    val lateDays: List<LateDayLine>,
    /** 지각 중 원인을 나누지 못한 것이 있는가. 캡션을 단다 */
    val hasUnknownCause: Boolean,
    /** 준비·출발·이동 평균. 구버전 서버면 빈 목록 */
    val deviations: List<DeviationLine>,
    /** 기록 반영 문장. 계획이 없던 주·구버전 서버면 null */
    val learningLabel: String?,
) {
    val hasArrivals: Boolean get() = arrivedCount > 0

    /**
     * 관측 공백이 결론을 흔들 만큼 큰가.
     *
     * 절반 이상의 아침을 측정하지 못했으면 정시율을 앞세우지 않는 편이 맞다.
     */
    val observationGapMatters: Boolean
        get() = eventCount > 0 && unobservedCount * 2 >= eventCount

    /**
     * 카드별로 보이는 조건. 한곳에 모아 둔다(서버 값만 본다).
     *
     * 타일은 일정이 있으면 보인다. 요약이 없는 구버전 서버에서는 일정이 0 이어도
     * 보여서 화면이 비지 않게 한다("—" · "기록 없음").
     */
    val showTiles: Boolean get() = eventCount > 0 || summary == null
    val showDays: Boolean get() = eventCount > 0 && days.isNotEmpty()
    val showLateDays: Boolean get() = lateDays.isNotEmpty()
    val showDeviations: Boolean get() = eventCount > 0 && deviations.isNotEmpty()
}

/** 서버 요약 한 단락. [isAi] 면 "AI 요약 · 집계 수치만 사용함" 을 단다. */
data class ReportSummary(val text: String, val isAi: Boolean)

/** 화면 색의 뜻. GOOD 초록, WARN 주황, BAD 빨강, NEUTRAL 기본 글자, UNKNOWN 흐린 글자. */
enum class Tone { GOOD, WARN, BAD, NEUTRAL, UNKNOWN }

/** 측정 공백 안내 카드. */
data class GapNotice(val title: String, val body: String, val caption: String?)

enum class DayStatus { NONE, UNOBSERVED, ON_TIME, LATE }

/**
 * 요일 칸 하나.
 *
 * 대표값은 그날 도착 기록 중 **가장 작은 여유**다(지각이 있으면 그 지각). 도착
 * 기록이 하나도 없으면 시작이 가장 이른 일정이 대표다.
 */
data class ReportDay(
    val date: LocalDate,
    /** "월" */
    val label: String,
    val status: DayStatus,
    /** 막대 길이에 쓰는 분. 정시는 양수, 지각은 음수(최소 1분). 기록이 없으면 null */
    val slackMinutes: Int?,
    /** "+12" / "−10" / "0" / "—" / "" */
    val valueLabel: String,
    /** 낭독용. "화요일, 10분 늦게 도착" */
    val description: String,
    /** 그날 일정, 시작 순 */
    val outcomes: List<MorningOutcome>,
) {
    /** 일정이 있는 칸만 누를 수 있다. */
    val clickable: Boolean get() = outcomes.isNotEmpty()

    val representative: MorningOutcome?
        get() = outcomes.filter { it.slackMinutes != null }.minByOrNull { it.slackMinutes!! }
            ?: outcomes.firstOrNull()
}

enum class OutcomeResult { ON_TIME, LATE, UNOBSERVED }

/** 지난 일정 하나. ⑨ 상세 리포트가 그대로 그린다. */
data class MorningOutcome(
    /** 서버 일정 id. 없으면 칩·지각 줄에서 빠진다 */
    val eventId: Long?,
    /** "09:00 자료구조" (제목 8자 넘으면 8자 + "…") */
    val chipLabel: String,
    val result: OutcomeResult,
    /** "10분 지각" / "9분 전 도착" / "정각 도착" / "도착 기록 없음" */
    val headline: String,
    /** 기록 없음일 때 그 이유 한 줄 */
    val headlineNote: String?,
    /** "09:00 자료구조 · 알람 7:40" */
    val contextLine: String,
    /** "앱이 본 정시 확률 90%" / "적용 τ 0.90". 둘 다 없으면 null */
    val confidenceLine: String?,
    /** 준비(해당될 때만)·출발·이동 */
    val rows: List<PlanActualRow>,
    /** 도착. 계획도 실제도 없으면 null */
    val arrival: PlanActualRow?,
    /** 지각일 때만 */
    val cause: CauseExplanation?,
    /** 이 일정의 τ 구간 아래 끝. 예측 검증 표에서 강조한다 */
    val tauBucketLower: Double?,
    /** 서버 여유(분). 대표 일정을 고를 때 쓴다 */
    val slackMinutes: Double?,
    /** 이 주 지각이 이 일정 하나뿐인가 */
    val isOnlyLateOfWeek: Boolean,
)

enum class RowKind { DURATION, TIME }

/**
 * 계획 대비 실제 한 줄.
 *
 * [RowKind.DURATION] 은 막대 두 줄(계획·실제)을, [RowKind.TIME] 은 "계획 8:08 → 실제
 * 8:15" 를 그린다. 막대 길이는 그 줄의 max(계획, 실제) 기준이다.
 */
data class PlanActualRow(
    /** "준비" / "출발" / "이동" / "도착" */
    val label: String,
    val kind: RowKind,
    /** "계획 28분" / "계획 8:08" / "계획 없음" */
    val plannedLabel: String,
    /** "실제 34분" / "실제 8:15". 측정하지 못했으면 null → "실제 측정 안 됨" */
    val actualLabel: String?,
    /** "+6분" / "−3분" / "±0분" / "측정 안 됨". 도착 줄은 빈 문자열 */
    val deltaLabel: String,
    val deltaTone: Tone,
    /** 실제 막대·글자 색. 늘어남 BAD, 그 밖 GOOD */
    val actualTone: Tone,
    val plannedFraction: Float?,
    val actualFraction: Float?,
)

/** 지각 원인 카드. 제목 → 설명 → 할 일("→ "). */
data class CauseExplanation(val title: String, val body: String, val action: String?)

/** ⑧ "지각한 날" 한 줄. */
data class LateDayLine(
    val date: LocalDate,
    val eventId: Long,
    /** "화 10월 6일" */
    val dateLabel: String,
    /** "10분 늦음" */
    val lateLabel: String,
    /** "이동이 13분 더 걸렸음" / "원인 측정 못 함" */
    val causeTitle: String,
    /** 낭독용. "화요일 10월 6일, 10분 늦음, 이동이 13분 더 걸렸음" */
    val description: String,
)

/** ⑧ "계획 대비 실제 · 이번 주 평균" 한 줄. */
data class DeviationLine(val label: String, val valueLabel: String, val tone: Tone)

/**
 * 캘리브레이션 표시.
 *
 * [verdictLabel] 이 이 화면의 결론이다. **표본이 부족하면 판정하지 않는다** —
 * "잘 맞음" 을 표본 2건으로 말하면 사용자가 그 말을 믿고 여유를 줄인다.
 */
data class CalibrationView(
    val points: List<CalibrationPoint>,
    val scoredCount: Int,
    val unscoredCount: Int,
    /** "잘 맞음" / "과신 중" / "지나치게 이름" / "표본 부족" */
    val verdictLabel: String,
    /** 결론을 풀어 쓴 한 줄 */
    val verdictNote: String,
    /** 사용자가 할 일. 없으면 null */
    val action: String?,
    val verdict: String,
) {
    val hasData: Boolean get() = points.isNotEmpty()

    val isTrustworthy: Boolean get() = verdict != WIRE_INSUFFICIENT

    // Partly AI-generated (Claude)
    /** "채점 36건 · 채점 못 함 5건". 채점 못 한 것이 없으면 앞 조각만 */
    val countsLabel: String
        get() = if (unscoredCount > 0) "채점 ${scoredCount}건 · 채점 못 함 ${unscoredCount}건"
        else "채점 ${scoredCount}건"

    companion object {
        const val WIRE_INSUFFICIENT = "insufficient"
        const val WIRE_OVERCONFIDENT = "overconfident"
    }
}

/**
 * 캘리브레이션 점 하나.
 *
 * [promised] 는 앱이 약속한 확률, [actual] 은 실제 정시율이다. 둘이 같으면
 * 완벽히 맞는 것이다.
 */
data class CalibrationPoint(
    /** "90~95%" */
    val rangeLabel: String,
    /** 0.0~1.0 */
    val promised: Float,
    /** 0.0~1.0. 표본이 없으면 null */
    val actual: Float?,
    val total: Int,
    /** 표본이 충분한가. 부족하면 흐리게 그린다 */
    val reliable: Boolean,
    /** "4번 중 2번 정시" */
    val countLabel: String,
    /** 실제가 약속보다 낮은가. 과신 구간이다 */
    val overconfident: Boolean,
    /** 구간 아래 끝(0.90). ⑨ 가 이 일정의 구간을 강조할 때 쓴다 (Partly AI-generated (Claude)) */
    val lower: Double? = null,
) {
    // Partly AI-generated (Claude)
    /** "실제 89% · 27번" — 표본이 부족하면 " · 표본 부족" 을 붙인다 */
    val actualLabel: String
        get() {
            val actualPart = actual?.let { "실제 ${halfUp(it.toDouble() * 100)}%" } ?: "실제 —"
            return "$actualPart · ${total}번" + if (reliable) "" else " · 표본 부족"
        }

    // Partly AI-generated (Claude)
    /** 이 구간이 [tauLower] 를 아래 끝으로 갖는가. 서버가 준 아래 끝끼리 비교한다 */
    fun matches(tauLower: Double?): Boolean =
        tauLower != null && lower != null && abs(lower - tauLower) <= 1e-6
}

// Partly AI-generated (Claude)
/**
 * 서버 `services.half_up` 과 같은 반올림. 절댓값을 0.5 올림한 뒤 부호를 붙인다
 * (8.5 → 9, −8.5 → −9). 분·%·%p 를 만드는 곳은 모두 이것을 쓴다 — 서버 요약 문장과
 * 화면 숫자가 1 씩 어긋나지 않게.
 *
 * 소수 6자리에서 먼저 정리해 이진 표현 오차(22.499999999999996)가 경계를 넘지 않게 한다.
 */
internal fun halfUp(value: Double): Int {
    val magnitude = floor(Math.round(abs(value) * 1_000_000) / 1_000_000.0 + 0.5).toInt()
    return if (value < 0) -magnitude else magnitude
}
