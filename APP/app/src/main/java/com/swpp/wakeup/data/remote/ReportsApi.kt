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

data class WeeklyReportDto(
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("week_end") val weekEnd: String,
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
    @SerializedName("by_weekday") val byWeekday: List<WeekdayRowDto> = emptyList(),
    @SerializedName("late_causes") val lateCauses: List<LateCauseDto> = emptyList(),
    /** 원인 라벨 → 건수. 키는 [LateCauseDto.label] 과 같다 */
    @SerializedName("primary_counts") val primaryCounts: Map<String, Int> = emptyMap(),
    val calibration: CalibrationDto? = null,
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
    val buckets: List<CalibrationBucketDto> = emptyList(),
    @SerializedName("scored_count") val scoredCount: Int = 0,
    @SerializedName("unscored_count") val unscoredCount: Int = 0,
    @SerializedName("reliable_bucket_count") val reliableBucketCount: Int = 0,
    @SerializedName("min_samples_for_reliable") val minSamples: Int = 3,
    /** 실제 − 예측의 평균. 음수면 과신. 표본이 부족하면 null */
    @SerializedName("mean_gap") val meanGap: Double? = null,
    @SerializedName("on_time_rate") val onTimeRate: Double? = null,
    /** `calibrated` / `overconfident` / `conservative` / `insufficient` */
    val verdict: String = VERDICT_INSUFFICIENT,
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
