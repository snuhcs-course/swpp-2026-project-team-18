package com.swpp.wakeup.domain.model

/**
 * 주간 리포트의 화면용 표현.
 *
 * ## 이 화면은 앱을 변호하지 않는다
 *
 * 목적은 **앱이 틀렸는지 사용자에게 보여주는 것**이다. 그래서 여기 있는 값은
 * 전부 "좋게 보이도록" 가공하지 않는다.
 *
 * - [unobservedCount] 를 감추지 않는다. 결과를 모르는 아침이 많으면 정시율
 *   자체가 적은 표본에 기댄 것이다.
 * - [tightestSlackLabel] 을 평균과 함께 보여준다. 정시였어도 여유가 1분이면
 *   운이 좋았던 것이다.
 * - 표본이 부족하면 [calibration] 이 판정을 내리지 않는다.
 */
data class WeeklyReportView(
    /** "9월 8일 ~ 9월 14일" */
    val rangeLabel: String,

    val eventCount: Int,
    val arrivedCount: Int,
    val unobservedCount: Int,
    val onTimeCount: Int,
    val lateCount: Int,

    /** "정시 도착 86%" — 관측이 없으면 null */
    val onTimeLabel: String?,
    /** 0.0~1.0. 막대 길이에 쓴다 */
    val onTimeFraction: Float?,

    /** "중간 여유 12분 전 도착" */
    val medianSlackLabel: String?,
    /** "가장 아슬아슬했던 아침 1분 전" — 지각했으면 "N분 늦음" */
    val tightestSlackLabel: String?,

    val weekdays: List<WeekdayLoad>,
    val causes: List<LateCauseLine>,
    val calibration: CalibrationView,
) {
    val hasArrivals: Boolean get() = arrivedCount > 0

    /**
     * 관측 공백이 결론을 흔들 만큼 큰가.
     *
     * 절반 이상의 아침을 측정하지 못했으면 정시율을 앞세우지 않는 편이 맞다.
     */
    val observationGapMatters: Boolean
        get() = eventCount > 0 && unobservedCount * 2 >= eventCount
}

/** 요일별 부하. "화요일마다 늦는다" 같은 패턴이 보여야 행동이 바뀐다. */
data class WeekdayLoad(
    /** "월" */
    val label: String,
    val total: Int,
    val late: Int,
) {
    /** 지각 비율. 일정이 없으면 null — 0 으로 그리면 "완벽했다" 로 보인다 */
    val lateFraction: Float?
        get() = if (total == 0) null else late.toFloat() / total
}

/**
 * 지각 한 건의 원인.
 *
 * [detail] 은 측정된 값만 적는다. 측정하지 못한 요인을 0 으로 적으면 "그건
 * 괜찮았다" 로 읽혀 사용자가 엉뚱한 곳을 고친다.
 */
data class LateCauseLine(
    val eventId: Long,
    /** "12분 늦음" */
    val lateLabel: String,
    /** "이동이 12분 더 걸림" / "원인을 측정하지 못함" */
    val headline: String,
    /** "준비 +2분 · 출발 +0분 · 이동 측정 안 됨" */
    val detail: String,
    /** 측정 실패가 원인 판정을 막았는가. 화면이 색으로 구분한다 */
    val unknown: Boolean,
)

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

    companion object {
        const val WIRE_INSUFFICIENT = "insufficient"
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
)
