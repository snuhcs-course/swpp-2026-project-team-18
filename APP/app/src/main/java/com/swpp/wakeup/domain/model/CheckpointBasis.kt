package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 이동 중 "경로 정류장" 카드가 어느 경로의 정류장을 보여 줄지.
 *
 * 이번 이동에서 쓰는 **기준 경로**다. 순수 함수다(JVM 테스트 대상).
 */
object CheckpointBasis {
    /**
     * 우선순위: 이번 이동에서 바꾼 경로 → 서버가 기준 경로로 돌려준 현재-위치 경로(대안이 아닐 때만)
     * → 일정에 저장된 경로. 대안 경로는 기준이 아니므로 쓰지 않는다.
     *
     * @param liveFresh [live] 가 오래되지 않았고 바꾸기 전 것도 아닌가
     */
    fun baseRouteKey(
        planRouteKey: String?,
        appliedRerouteKey: String?,
        live: LiveRoute?,
        liveFresh: Boolean = true,
    ): String? {
        appliedRerouteKey?.takeIf { it.isNotBlank() }?.let { return it }
        if (live != null && liveFresh && !live.isAlternative) {
            live.routeKey.takeIf { it.isNotBlank() }?.let { return it }
        }
        return planRouteKey?.takeIf { it.isNotBlank() }
    }

    /** 정류장이 있는 경로는 대중교통뿐이다. 도보·자전거·자동차 key 로는 후보를 부르지 않는다 */
    fun hasStops(routeKey: String?): Boolean = routeKey?.startsWith("transit:") == true
}
