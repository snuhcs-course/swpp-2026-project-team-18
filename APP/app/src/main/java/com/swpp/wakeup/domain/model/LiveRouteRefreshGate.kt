package com.swpp.wakeup.domain.model

/**
 * 이동 중 경로 재조회 하나를 직렬화하고 호출 간격을 지키는 작은 상태 기계.
 *
 * 위치 콜백은 메인 스레드에서 오지만 네트워크 완료는 IO 스레드에서 온다.
 * 단순한 `Boolean` 플래그로 막으면 완료와 다음 위치가 엇갈릴 때 중복 호출이
 * 생길 수 있으므로 모든 전이를 동기화하고, 요청마다 토큰을 발급한다.
 *
 * 성공·실패와 무관하게 다음 위치 콜백에서 1분 간격을 다시 검사한다.
 */
class LiveRouteRefreshGate {

    private var lastAtMillis: Long? = null
    private var nextToken = 0L
    private var activeToken: Long? = null

    /**
     * 지금 요청을 시작해도 되면 요청 토큰을 돌려준다.
     *
     * 반환값이 null 이면 1분 조건을 못 넘었거나 앞 요청이 아직 진행
     * 중이다. 토큰을 받은 호출부는 완료 시 반드시 [finish]를 불러야 한다.
     */
    @Synchronized
    fun beginIfDue(nowMillis: Long): Long? {
        if (activeToken != null) return null

        if (!LiveRouteDecision.shouldFetch(
                lastAtMillis = lastAtMillis,
                nowMillis = nowMillis,
            )
        ) return null

        lastAtMillis = nowMillis
        val token = ++nextToken
        activeToken = token
        return token
    }

    /**
     * [token] 요청을 끝낸다. reset 뒤 늦게 도착한 옛 요청이면 false 다.
     */
    @Synchronized
    fun finish(token: Long): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }

    /** 다른 여정이 시작되거나 추적이 끝났을 때 모든 기준을 버린다. */
    @Synchronized
    fun reset() {
        activeToken = null
        lastAtMillis = null
        // nextToken 은 일부러 되돌리지 않는다. reset 전 응답과 토큰이 겹치면 안 된다.
    }
}
