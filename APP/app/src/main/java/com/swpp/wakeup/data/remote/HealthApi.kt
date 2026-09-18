package com.swpp.wakeup.data.remote

import retrofit2.http.GET

/**
 * back-spec.md 9.3 의 `/api/health`.
 * 인증이 필요 없는 유일한 엔드포인트이며, 클라이언트 연결 문제를 진단하는 첫 관문이다.
 */
interface HealthApi {
    @GET("api/health")
    suspend fun health(): HealthResponse
}

data class HealthResponse(
    val ok: Boolean,
    val version: String? = null,
)
