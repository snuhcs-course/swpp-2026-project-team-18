package com.swpp.wakeup.domain.model

/**
 * 리스크 선택 화면(Figma ⑤)이 그리는 값.
 *
 * 옵션(τ·알람 시각·확률)과 소요시간 분포는 **서버가 계산해서** 준다(task.md B-5,
 * `GET /api/events/{id}/risk-options`). 앱은 받은 값을 그리기만 한다. τ 별 알람
 * 시각이나 확률을 앱에서 계산하지 않는다.
 *
 * 관측이 없어 서버가 옵션을 만들 수 없으면 이 값 자체가 없고(null), 화면은
 * "관측이 쌓이면" 안내를 보여준다.
 */
data class RiskOptionsView(
    /** 보통 안전 · 보통 · 도박 세 개. 순서는 서버가 준 그대로 그린다 */
    val options: List<RiskOptionView>,
    /** 지금 적용된 옵션의 [RiskOptionView.key]. 없으면 첫 옵션을 고른 상태로 시작한다 */
    val selectedKey: String?,
    /** 일정 종류 때문에 서버가 자동으로 옵션을 조정했는지 */
    val autoAdjusted: Boolean,
    /** 자동 조정 이유. 예 "시험·발표 일정은 기본적으로 안전 모드" */
    val autoReason: String?,
    /** 총 소요시간 분포. 서버가 아직 만들 수 없으면 null */
    val distribution: DurationDistributionView?,
)

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
    /** 몇 일치 기록인지. 예 30 */
    val windowDays: Int,
) {
    data class Bin(val minMinutes: Int, val maxMinutes: Int, val count: Int)
}
