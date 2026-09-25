package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint

/**
 * 이동 중 경로 재조회 하나를 직렬화하고 호출 간격을 지키는 작은 상태 기계.
 *
 * 위치 콜백은 메인 스레드에서 오지만 네트워크 완료는 IO 스레드에서 온다.
 * 단순한 `Boolean` 플래그로 막으면 완료와 다음 위치가 엇갈릴 때 중복 호출이
 * 생길 수 있으므로 모든 전이를 동기화하고, 요청마다 토큰을 발급한다.
 *
 * 실패한 호출은 usable 경로가 있더라도 1분 뒤 다시 시도한다. 지하철에서 정차한
 * 동안 한 번 끊긴 네트워크 때문에 그 뒤로 영원히 갱신하지 않는 상황을 막는다.
 */
class LiveRouteRefreshGate {

    private var lastAtMillis: Long? = null
    private var lastPoint: GeoPoint? = null
    private var lastSuccessAtMillis: Long? = null
    private var lastAttemptSucceeded = false
    private var nextToken = 0L
    private var activeToken: Long? = null

    /**
     * 지금 요청을 시작해도 되면 요청 토큰을 돌려준다.
     *
     * 반환값이 null 이면 1분/이동 거리 조건을 못 넘었거나 앞 요청이 아직 진행
     * 중이다. 토큰을 받은 호출부는 완료 시 반드시 [finish]를 불러야 한다.
     */
    @Synchronized
    fun beginIfDue(
        nowMillis: Long,
        here: GeoPoint,
        hasUsableRoute: Boolean,
    ): Long? {
        if (activeToken != null) return null

        val successAt = lastSuccessAtMillis
        val freshSuccessfulRoute = hasUsableRoute && lastAttemptSucceeded &&
            successAt != null && nowMillis >= successAt &&
            nowMillis - successAt < LiveRouteDecision.MAX_USABLE_AGE_MILLIS

        if (!LiveRouteDecision.shouldFetch(
                lastAtMillis = lastAtMillis,
                lastPoint = lastPoint,
                nowMillis = nowMillis,
                here = here,
                // 직전 요청이 실패했다면 정차 중이어도 1분 뒤 재시도한다.
                hasUsableRoute = freshSuccessfulRoute,
            )
        ) return null

        lastAtMillis = nowMillis
        lastPoint = here
        val token = ++nextToken
        activeToken = token
        return token
    }

    /**
     * [token] 요청을 끝낸다. reset 뒤 늦게 도착한 옛 요청이면 false 다.
     */
    @Synchronized
    fun finish(token: Long, success: Boolean): Boolean {
        if (activeToken != token) return false
        activeToken = null
        lastAttemptSucceeded = success
        if (success) lastSuccessAtMillis = lastAtMillis
        return true
    }

    /** 다른 여정이 시작되거나 추적이 끝났을 때 모든 기준을 버린다. */
    @Synchronized
    fun reset() {
        activeToken = null
        lastAtMillis = null
        lastPoint = null
        lastSuccessAtMillis = null
        lastAttemptSucceeded = false
        // nextToken 은 일부러 되돌리지 않는다. reset 전 응답과 토큰이 겹치면 안 된다.
    }
}
