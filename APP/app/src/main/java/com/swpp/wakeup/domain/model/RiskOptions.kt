// AI-generated (Claude)
package com.swpp.wakeup.domain.model

/**
 * 리스크 선택 화면(Figma ⑤)이 그리는 값.
 *
 * 옵션(τ·알람 시각·확률)과 소요시간 분포는 **서버가 계산해서** 준다(task.md B-5,
 * `GET /api/events/{id}/risk-options`). 앱은 받은 값을 그리기만 한다. τ 별 알람
 * 시각이나 확률을 앱에서 계산하지 않는다.
 *
 * 불확실성이나 저장된 경로 원값이 없으면 서버의 빈 상태 사유를 보여준다.
 */
data class RiskOptionsView(
    /** 보통 안전 · 보통 · 도박 세 개. 순서는 서버가 준 그대로 그린다 */
    val options: List<RiskOptionView>,
    /** 현재 τ와 정확히 일치하는 옵션. null이면 사용자가 직접 골라야 한다. */
    val selectedKey: String?,
    /** 직접 지정 없이 일정 종류의 τ가 프로필 기본값 대신 적용됐는지 */
    val autoAdjusted: Boolean,
    /** 실제 적용된 일정 종류·프로필 기본값에 대한 서버 설명 */
    val autoReason: String?,
    /** 총 소요시간 분포. 서버가 아직 만들 수 없으면 null */
    val distribution: DurationDistributionView?,
    val unavailableReason: String? = null,
) {
    fun option(key: String?): RiskOptionView? = options.firstOrNull { it.key == key }
}

data class RiskOptionView(
    /** `safe` / `normal` / `gamble` */
    val key: String,
    /** 예 "안전" */
    val label: String,
    /** 예 "7:20" */
    val alarmAt: String,
    /** "AM" / "PM" */
    val meridiem: String,
    /** 정시 도착 확률(%). 서버가 못 주면 null */
    val probability: Int?,
    /** 예 "수면 6시간 20분". 취침 시각을 모르면 null(B-9) */
    val sleepLabel: String?,
    /** 저장할 서버 제공 τ. 앱에서 옵션 key를 τ로 다시 계산하지 않는다. */
    val tau: Double,
) {
    companion object {
        const val KEY_SAFE = "safe"
        const val KEY_NORMAL = "normal"
        const val KEY_GAMBLE = "gamble"
    }
}

/** 총 소요시간(준비 + 이동) 분포. 히스토그램과 요약 문구에 쓴다 */
data class DurationDistributionView(
    val bins: List<Bin>,
    /** 중앙값(분). "절반은 N분 이내" */
    val p50Minutes: Int,
    /** 90% 값(분). "90%는 N분 이내" */
    val p90Minutes: Int,
    /** 관측 기간이 아닌 모델 분포는 null. */
    val windowDays: Int?,
    val source: String,
) {
    /** 관측 횟수가 아닌 모델 확률 질량. */
    data class Bin(val minMinutes: Int, val maxMinutes: Int, val mass: Double)
}
