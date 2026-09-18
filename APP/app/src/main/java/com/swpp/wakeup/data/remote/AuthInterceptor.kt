package com.swpp.wakeup.data.remote

import com.swpp.wakeup.data.local.TokenStore
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 저장된 access 토큰을 `Authorization` 헤더로 붙인다.
 *
 * 인증이 필요 없는 경로는 건드리지 않는다. `/api/auth/token` 에 만료된 토큰을
 * 실어 보내면 서버가 그 헤더를 먼저 검사해 401 을 주는 경우가 있어서다.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isPublic(request)) return chain.proceed(request)

        val token = tokenStore.accessToken
        if (token.isNullOrBlank()) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }

    private fun isPublic(request: Request): Boolean {
        val path = request.url.encodedPath
        return PUBLIC_PATHS.any { path.endsWith(it) }
    }

    private companion object {
        val PUBLIC_PATHS = listOf(
            "/api/health",
            "/api/auth/register",
            "/api/auth/token",
            "/api/auth/token/refresh",
        )
    }
}

/**
 * 401 을 만나면 refresh 로 access 를 갱신하고 **한 번만** 재시도한다.
 *
 * OkHttp 의 [Authenticator] 는 401 응답을 받은 뒤에 호출된다. null 을 돌려주면
 * 재시도하지 않고 401 을 그대로 올린다.
 *
 * 무한 루프를 막는 장치가 두 개다.
 *  1. [responseCount] 로 이미 재시도한 요청은 포기한다
 *  2. refresh 자체가 실패하면 토큰을 지우고 null 을 돌려준다
 *
 * refresh 는 동기 호출이다. OkHttp 가 워커 스레드에서 부르므로 막아도 된다.
 * `@Synchronized` 로 동시에 여러 요청이 401 을 받아도 refresh 를 한 번만 한다.
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: () -> AuthApi,
) : Authenticator {

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        val refresh = tokenStore.refreshToken
        if (refresh.isNullOrBlank()) return null

        // 다른 요청이 이미 갱신했으면 그 토큰으로 바로 재시도한다.
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val current = tokenStore.accessToken
        if (!current.isNullOrBlank() && current != sentToken) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer $current")
                .build()
        }

        val newAccess = runCatching {
            val result = kotlinx.coroutines.runBlocking {
                refreshApi().refresh(RefreshRequest(refresh))
            }
            if (result.isSuccessful) result.body()?.access else null
        }.getOrNull()

        if (newAccess.isNullOrBlank()) {
            // refresh 도 만료됐다. 다시 로그인해야 한다.
            tokenStore.clear()
            return null
        }

        tokenStore.updateAccess(newAccess)
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
