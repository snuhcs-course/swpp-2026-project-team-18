package com.swpp.wakeup.domain.model

import kotlin.math.roundToInt

// Partly AI-generated (Claude)
/**
 * 이동 중 늦을 때 이유 한 줄(todo "이동 중 재계획 ⑦: 늦는 원인").
 *
 * 원인은 둘로 가른다. 계획한 출발보다 **늦게 나온 것**과, 나온 뒤 **이동이 예상보다 길어진 것**이다.
 * 둘 중 더 큰 쪽 하나만 적는다. 근거 숫자가 없으면 말하지 않는다 — 지어내지 않는다.
 * 순수 함수다(JVM 테스트 대상).
 */
object LateCause {
    /** 이보다 작은 차이는 잡음으로 보고 원인에서 뺀다(서버 `MIN_SAVING_WHEN_STILL_LATE` 와 같은 값) */
    const val MIN_MINUTES = 2

    private const val MILLIS_PER_MINUTE = 60_000.0

    /**
     * @param lateMinutes 기준 경로로 가면 늦는 분(서버 `arrival.late_minutes`). 안 늦거나 모르면 한 줄도 없다
     * @param departByMillis 계획한 출발 시각. 모르면 null
     * @param departedAtMillis 실제로 집을 나선 시각. 모르면 null(첫 위치부터 집 밖이었던 경우 포함)
     * @param travelMinutes 계획한 이동 분
     * @param liveArriveAtMillis 지금 기준 경로로 가면 닿는 시각(서버 `arrival.arrive_at`)
     */
    fun line(
        lateMinutes: Int?,
        departByMillis: Long?,
        departedAtMillis: Long?,
        travelMinutes: Int?,
        liveArriveAtMillis: Long?,
    ): String? {
        if (lateMinutes == null || lateMinutes < 1) return null
        if (departedAtMillis == null) return null
        val departDelay = departByMillis?.let { minutesBetween(it, departedAtMillis) }
            ?.takeIf { it >= MIN_MINUTES }
        // 계획한 도착이 아니라 "실제 출발 + 계획 이동" 과 비교한다. 늦은 출발이 이동 탓으로
        // 겹쳐 세어지지 않는다.
        val travelOverrun = if (liveArriveAtMillis != null && travelMinutes != null) {
            minutesBetween(departedAtMillis + travelMinutes * 60_000L, liveArriveAtMillis)
                .takeIf { it >= MIN_MINUTES }
        } else {
            null
        }
        return when {
            departDelay == null && travelOverrun == null -> null
            // 같으면 출발을 말한다. 다음에 사용자가 바꿀 수 있는 쪽이다.
            travelOverrun == null || (departDelay != null && departDelay >= travelOverrun) ->
                "출발이 ${departDelay}분 늦었어요"
            else -> "이동이 예상보다 ${travelOverrun}분 길어요"
        }
    }

    private fun minutesBetween(fromMillis: Long, toMillis: Long): Int =
        ((toMillis - fromMillis) / MILLIS_PER_MINUTE).roundToInt()
}
