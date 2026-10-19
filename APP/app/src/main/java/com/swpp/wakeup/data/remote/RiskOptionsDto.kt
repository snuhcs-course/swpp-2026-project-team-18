package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName

/** Server-modeled risk choices; absent uncertainty remains absent. */
data class RiskOptionsDto(
    val options: List<RiskOptionDto> = emptyList(),
    @SerializedName("selected_key") val selectedKey: String? = null,
    @SerializedName("auto_adjusted") val autoAdjusted: Boolean = false,
    @SerializedName("auto_reason") val autoReason: String? = null,
    val distribution: RiskDistributionDto? = null,
    @SerializedName("unavailable_reason") val unavailableReason: String? = null,
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
