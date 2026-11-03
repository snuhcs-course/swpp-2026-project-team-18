package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 이동 중 "경로 정류장" 카드가 어느 경로의 정류장을 보여 줄지.
 *
 * 이번 이동에서 쓰는 **기준 경로**다. 순수 함수다(JVM 테스트 대상).
 */
object CheckpointBasis {
    /**
     * 우선순위: 이번 이동에서 바꾼 경로 → 일정에 저장된 경로.
     *
     * 실시간 응답의 경로(`live.routeKey`)는 현재 위치에서 다시 찾은 길이라 출발지에서 받는 경로 후보에
     * 없다(에뮬레이터에서 5511>5516 으로 확인). 그래서 쓰지 않는다. 정류장은 처음 계획한 길 기준이다.
     */
    fun baseRouteKey(
        planRouteKey: String?,
        appliedRerouteKey: String?,
    ): String? {
        appliedRerouteKey?.takeIf { it.isNotBlank() }?.let { return it }
        return planRouteKey?.takeIf { it.isNotBlank() }
    }

    /** 정류장이 있는 경로는 대중교통뿐이다. 도보·자전거·자동차 key 로는 후보를 부르지 않는다 */
    fun hasStops(routeKey: String?): Boolean = routeKey?.startsWith("transit:") == true
}
