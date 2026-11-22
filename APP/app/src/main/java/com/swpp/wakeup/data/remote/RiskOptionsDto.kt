package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName

/** Server-modeled risk choices; absent uncertainty remains absent. */
data class RiskOptionsDto(
    val options: List<RiskOptionDto> = emptyList(),
    @SerializedName("selected_key") val selectedKey: String? = null,
    /** "추천" 을 붙일 칸(BE-H3e). 일정 종류(없으면 프로필) 기본 τ 와 같은 칸. 같은 칸이 없거나 구버전이면 null */
    @SerializedName("recommended_key") val recommendedKey: String? = null,
    @SerializedName("auto_adjusted") val autoAdjusted: Boolean = false,
    @SerializedName("auto_reason") val autoReason: String? = null,
    val distribution: RiskDistributionDto? = null,
    @SerializedName("unavailable_reason") val unavailableReason: String? = null,
    /** 휠 시각의 확률 표(BE-H3c, 서버 0.22.0). 구버전 서버 · 확률을 낼 수 없으면 null (Partly AI-generated (Claude)) */
    @SerializedName("probability_table") val probabilityTable: ProbabilityTableDto? = null,
)

/**
 * `whole[i]` = 일정 시작 i 분 전에 일어날 때의 정시 도착 확률(0~99). `i > max_minutes` 면 마지막 칸, `i ≤ 0` 이면 0
 * (docs/iter4/server-alarm-control.md 3.7절). (Partly AI-generated (Claude))
 */
data class ProbabilityTableDto(
    val unit: String? = null,
    val whole: List<Int>? = null,
    @SerializedName("max_minutes") val maxMinutes: Int? = null,
)

data class RiskOptionDto(
    val key: String,
    val label: String,
    val tau: Double,
    @SerializedName("alarm_at") val alarmAt: String,
    val probability: Int? = null,
)

data class RiskDistributionDto(
    val bins: List<RiskBinDto>,
    val p50: Int,
    val p90: Int,
    val source: String,
    @SerializedName("window_days") val windowDays: Int? = null,
)

data class RiskBinDto(val min: Int, val max: Int, val mass: Double)
