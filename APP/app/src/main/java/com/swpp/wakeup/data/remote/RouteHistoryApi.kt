// Partly AI-generated (Claude)
package com.swpp.wakeup.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 다녀온 경로 기록(서버 0.21.0, `docs/iter4/server-route-history.md`). 지금은 알람 추가의 "최근 설정한 위치" 칩만
 * 쓴다(`sort=recent` 의 도착지, C8). 배포 전 서버는 404 다. 응답 DTO 는 `EventsApi.kt` 의 [RouteHistoryResponse].
 */
interface RouteHistoryApi {

    @GET("api/routes/history")
    suspend fun recent(
        @Query("sort") sort: String = "recent",
        @Query("limit") limit: Int = 10,
    ): Response<RouteHistoryResponse>
}
