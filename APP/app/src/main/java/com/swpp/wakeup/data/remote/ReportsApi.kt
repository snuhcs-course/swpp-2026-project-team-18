package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 주간 리포트·캘리브레이션 API. 서버 `apps/reports` 와 짝이다.
 *
 * ## 이 화면의 목적은 **앱이 틀렸는지 보여주는 것**이다
 *
 * 앱은 "정시 도착 확률 92%" 라고 말한다. 그 말이 맞는지는 아무도 확인해 주지
 * 않는다. 캘리브레이션은 "92% 라고 말한 아침들 중 실제로 정시였던 비율" 이다.
 * 92라고 했는데 60%만 정시였다면 과신이고, 그 사실이 보여야 사용자가 지각
 * 위험을 낮추거나 여유를 더 둔다.
 */
interface ReportsApi {

    /**
     * 주간 리포트.
     *
     * [week] 는 `YYYY-MM-DD`. 그 날짜가 속한 주를 뜻한다. 생략하면 **지난 주**다 —
     * 이번 주는 진행 중이라 값이 매 시간 달라지고 최종인지 알 수 없다.
     */
    @GET("api/reports/weekly")
    suspend fun weekly(@Query("week") week: String? = null): Response<WeeklyReportDto>

    /** 전체 기간(기본 90일) 캘리브레이션. 주간보다 표본이 많아 추세가 보인다. */
    @GET("api/reports/calibration")
    suspend fun calibration(): Response<CalibrationDto>
}

// Partly AI-generated (Claude)
/**
 * 주간 리포트 응답.
 *
 * ## 모든 필드에 기본값을 둔다
 *
 * 기본값 없는 필드가 하나라도 있으면 Kotlin 은 무인자 생성자를 만들지 않고, 그러면
 * Gson 은 생성자를 건너뛰어 **키가 빠진 non-null 필드를 null 로 채운다**
 * (`NetworkDtoNullSafetyTest` 머리말). 그래서 문자열·목록·객체·소수는 nullable 이고
 * 이름이 개수인 것만 `Int = 0` 이다. 구버전 서버(0.10.0)는 Phase B 키를 주지 않는다 —
 * 그때 화면은 요약·요일·지각한 날·계획 대비 실제를 숨긴다.
 */
data class WeeklyReportDto(
    @SerializedName("week_start") val weekStart: String? = null,
    @SerializedName("week_end") val weekEnd: String? = null,
    @SerializedName("event_count") val eventCount: Int = 0,
    @SerializedName("arrived_count") val arrivedCount: Int = 0,
    /**
     * 결과를 알 수 없는 아침 수.
     *
     * **숨기면 정시율이 실제보다 좋아 보인다.** 도착 관측이 없는 아침을 정시로
     * 세지 않으므로, 이 값이 크면 정시율 자체가 적은 표본에 기댄 것이다.
     */
    @SerializedName("unobserved_count") val unobservedCount: Int = 0,
    @SerializedName("on_time_count") val onTimeCount: Int = 0,
    @SerializedName("late_count") val lateCount: Int = 0,
    /** 0.0~1.0. 도착 관측이 없으면 null */
    @SerializedName("on_time_rate") val onTimeRate: Double? = null,
    @SerializedName("median_slack_minutes") val medianSlackMinutes: Double? = null,
    /**
     * 가장 아슬아슬했던 아침의 여유(분).
     *
     * 정시였어도 여유가 1분이면 운이 좋았던 것이다. 평균만 보여주면 그 사실이
     * 묻힌다.
     */
    @SerializedName("tightest_slack_minutes") val tightestSlackMinutes: Double? = null,
    @SerializedName("by_weekday") val byWeekday: List<WeekdayRowDto>? = null,
    @SerializedName("late_causes") val lateCauses: List<LateCauseDto>? = null,
    /** 원인 라벨 → 건수. 키는 [LateCauseDto.label] 과 같다 */
    @SerializedName("primary_counts") val primaryCounts: Map<String, Int>? = null,
    /** 그 주 일정만으로 계산한 캘리브레이션. 화면은 90일 쪽을 쓴다(한 주는 늘 표본 부족) */
    val calibration: CalibrationDto? = null,
    /** 그 주 일요일 24:00 이 지났는가. false 면 "진행 중" */
    @SerializedName("week_complete") val weekComplete: Boolean? = null,
    /** 다음 주로 갈 수 있는가(서버 시간대 기준). 없으면(구버전) 갈 수 있다고 본다 */
    @SerializedName("has_next_week") val hasNextWeek: Boolean? = null,
    /** 0.0~1.0. 도착 기록이 있는 일정들이 쓴 τ 의 평균 */
    @SerializedName("target_rate") val targetRate: Double? = null,
    /** 목표 달성. 도착 기록이 3건 미만이면 null — 판정하지 않는다 */
    @SerializedName("target_met") val targetMet: Boolean? = null,
    @SerializedName("previous_week") val previousWeek: PreviousWeekDto? = null,
    val deviation: WeeklyDeviationDto? = null,
    val learning: LearningDto? = null,
    /** 그 주 지난 일정 하나씩, 시작 순 */
    val outcomes: List<OutcomeDto>? = null,
    /** 서버가 쓴 주간 요약 문장(3~5문장, 400자 이하) */
    val summary: String? = null,
    /** `llm` / `template`. 모르는 값이면 템플릿으로 본다 */
    @SerializedName("summary_source") val summarySource: String? = null,
) {
    companion object {
        const val SOURCE_LLM = "llm"
        const val SOURCE_TEMPLATE = "template"
    }
}

// Partly AI-generated (Claude)
/** 앞 주 정시 집계. 앞 주에 지난 일정이 없으면 응답에서 null 이다. */
data class PreviousWeekDto(
    @SerializedName("week_start") val weekStart: String? = null,
    @SerializedName("event_count") val eventCount: Int = 0,
    @SerializedName("arrived_count") val arrivedCount: Int = 0,
    @SerializedName("on_time_count") val onTimeCount: Int = 0,
    @SerializedName("on_time_rate") val onTimeRate: Double? = null,
    /** 정시율 차이(%p, 반올림). 두 주 모두 도착 기록 3건 이상일 때만 */
    @SerializedName("on_time_rate_change_pp") val onTimeRateChangePp: Int? = null,
)

// Partly AI-generated (Claude)
/** 계획 대비 실제 한 단계의 주간 통계. 잰 일정이 없으면 평균도 null 이다(0 이 아니다). */
data class DeviationStatDto(
    val count: Int = 0,
    @SerializedName("mean_minutes") val meanMinutes: Double? = null,
    @SerializedName("sd_minutes") val sdMinutes: Double? = null,
)

// Partly AI-generated (Claude)
data class WeeklyDeviationDto(
    @SerializedName("prep_over") val prepOver: DeviationStatDto? = null,
    @SerializedName("depart_late") val departLate: DeviationStatDto? = null,
    @SerializedName("travel_over") val travelOver: DeviationStatDto? = null,
)

// Partly AI-generated (Claude)
/**
 * 이 주 계획이 실제 기록을 썼는가.
 *
 * 준비는 내 블록 관측, 이동은 모든 사용자의 이동 기록으로 만든 보정이다. 그래서
 * 화면은 이동 쪽을 "내 기록" 이라고 쓰지 않는다.
 */
data class LearningDto(
    @SerializedName("plan_count") val planCount: Int = 0,
    @SerializedName("prep_from_records") val prepFromRecords: Int = 0,
    @SerializedName("travel_from_records") val travelFromRecords: Int = 0,
)

// Partly AI-generated (Claude)
/**
 * 지난 일정 하나의 계획 대비 실제. ⑨ 상세 리포트가 그린다.
 *
 * 개수가 아닌 수는 **전부 nullable** 이다. `Int = 0` 으로 두면 Gson 이 JSON null 을
 * 원시형에 넣지 않고 0 을 남겨 "정시 확률 0%"·"계획 0분" 이 된다.
 *
 * 앱은 요일 칸 위치를 [date] 로만 정한다. [weekday] 는 서버 집계용이라 읽지 않는다.
 */
data class OutcomeDto(
    val event: Long? = null,
    val title: String? = null,
    @SerializedName("start_at") val startAt: String? = null,
    /** 서버 시간대(Asia/Seoul) 날짜 `YYYY-MM-DD` */
    val date: String? = null,
    val weekday: Int? = null,
    /** [RESULT_ON_TIME] / [RESULT_LATE] / [RESULT_UNOBSERVED] */
    val result: String? = null,
    /** 일정 시작 − 실제 도착(분). 음수면 지각. 도착 기록이 없으면 null */
    @SerializedName("slack_minutes") val slackMinutes: Double? = null,
    @SerializedName("on_time_probability") val onTimeProbability: Int? = null,
    val tau: Double? = null,
    /** 이 일정의 τ 가 속한 0.05 구간의 아래 끝. 서버가 계산한다 */
    @SerializedName("tau_bucket_lower") val tauBucketLower: Double? = null,
    /** 준비 단계가 있는 일정인가. 집에서 출발하지 않으면 false, 계획이 없으면 null */
    @SerializedName("prep_applies") val prepApplies: Boolean? = null,
    /** 알람이 울리는 일정인가. 출발·도착은 알람을 해제할 때만 기록된다 */
    @SerializedName("alarm_on") val alarmOn: Boolean? = null,
    val planned: PlannedDto? = null,
    val actual: ActualDto? = null,
    val deviation: OutcomeDeviationDto? = null,
    /** 지각일 때 원인 라벨([LateCauseDto.label] 과 같은 값). 아니면 null */
    val cause: String? = null,
    /** 측정하지 못한 요인 이름들. 지각일 때만 채워진다 */
    val unmeasured: List<String>? = null,
) {
    companion object {
        const val RESULT_ON_TIME = "on_time"
        const val RESULT_LATE = "late"
        const val RESULT_UNOBSERVED = "unobserved"
    }
}

// Partly AI-generated (Claude)
/** 계획 값. 계획이 없거나 계산이 실패한 일정은 전부 null 이다. 시각은 UTC ISO 8601. */
data class PlannedDto(
    @SerializedName("alarm_at") val alarmAt: String? = null,
    @SerializedName("depart_by") val departBy: String? = null,
    /** 도착 예정 = 일정 시작 − 버퍼 */
    @SerializedName("arrive_at") val arriveAt: String? = null,
    @SerializedName("prep_minutes") val prepMinutes: Double? = null,
    @SerializedName("travel_minutes") val travelMinutes: Double? = null,
    @SerializedName("buffer_minutes") val bufferMinutes: Double? = null,
)

// Partly AI-generated (Claude)
/** 실제 값. 측정하지 못한 것은 null 이다. */
data class ActualDto(
    @SerializedName("depart_at") val departAt: String? = null,
    @SerializedName("arrive_at") val arriveAt: String? = null,
    /** 그 아침 블록 기록의 합(병렬 제외) */
    @SerializedName("prep_minutes") val prepMinutes: Double? = null,
    /** 출발·도착이 둘 다 있을 때만 */
    @SerializedName("travel_minutes") val travelMinutes: Double? = null,
)

// Partly AI-generated (Claude)
/** 실제 − 계획(분). 잴 수 없으면 null — **0 이 아니다**. */
data class OutcomeDeviationDto(
    @SerializedName("prep_over") val prepOver: Double? = null,
    @SerializedName("depart_late") val departLate: Double? = null,
    @SerializedName("travel_over") val travelOver: Double? = null,
)

data class WeekdayRowDto(
    /** 0=월 … 6=일 */
    val weekday: Int,
    val total: Int = 0,
    val late: Int = 0,
)

data class LateCauseDto(
    val event: Long,
    @SerializedName("late_minutes") val lateMinutes: Int = 0,
    /** 측정하지 못했으면 null. **0 이 아니다** */
    @SerializedName("prep_over") val prepOver: Double? = null,
    @SerializedName("travel_over") val travelOver: Double? = null,
    @SerializedName("depart_late") val departLate: Double? = null,
    /** 가장 큰 초과 요인. 초과가 없으면 null */
    val primary: String? = null,
    /**
     * 화면에 쓸 원인 이름.
     *
     * `prep_over`/`travel_over`/`depart_late` 중 하나이거나,
     * `unknown`(측정 못 함) 또는 `plan_too_tight`(전부 측정했는데 초과 없음)다.
     * 이 둘을 섞으면 추적이 안 된 아침을 "앱 계산이 틀렸다" 로 읽게 된다.
     */
    val label: String = LABEL_UNKNOWN,
    /** 측정하지 못한 요인 이름들 */
    val unmeasured: List<String> = emptyList(),
) {
    companion object {
        const val LABEL_PREP = "prep_over"
        const val LABEL_TRAVEL = "travel_over"
        const val LABEL_DEPART = "depart_late"
        const val LABEL_UNKNOWN = "unknown"
        const val LABEL_PLAN_TIGHT = "plan_too_tight"
    }
}

data class CalibrationDto(
    // Partly AI-generated (Claude): 목록·판정은 nullable — JSON null 이 와도 매핑이 터지지 않게
    val buckets: List<CalibrationBucketDto>? = null,
    @SerializedName("scored_count") val scoredCount: Int = 0,
    @SerializedName("unscored_count") val unscoredCount: Int = 0,
    @SerializedName("reliable_bucket_count") val reliableBucketCount: Int = 0,
    @SerializedName("min_samples_for_reliable") val minSamples: Int = 3,
    /** 실제 − 예측의 평균. 음수면 과신. 표본이 부족하면 null */
    @SerializedName("mean_gap") val meanGap: Double? = null,
    @SerializedName("on_time_rate") val onTimeRate: Double? = null,
    /** `calibrated` / `overconfident` / `conservative` / `insufficient` */
    val verdict: String? = VERDICT_INSUFFICIENT,
) {
    companion object {
        const val VERDICT_CALIBRATED = "calibrated"
        const val VERDICT_OVERCONFIDENT = "overconfident"
        const val VERDICT_CONSERVATIVE = "conservative"
        const val VERDICT_INSUFFICIENT = "insufficient"
    }
}

data class CalibrationBucketDto(
    val lower: Double,
    val upper: Double,
    /** 구간 중앙값. 이것이 "앱이 약속한 확률" 이다 */
    val center: Double,
    val total: Int = 0,
    @SerializedName("on_time") val onTime: Int = 0,
    /** 실제 정시 비율. 표본이 없으면 null — 0.0 이 아니다 */
    @SerializedName("actual_rate") val actualRate: Double? = null,
    /** 실제 − 예측. 음수면 과신 */
    val gap: Double? = null,
    /** 표본이 충분한가. 부족하면 화면이 흐리게 그린다 */
    val reliable: Boolean = false,
)
