package com.swpp.wakeup.data.remote

import android.util.Log
import com.swpp.wakeup.data.local.SessionState
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
            "/api/auth/password/check",
            // 비밀번호 재설정은 로그인 전에 쓴다(docs/iter3/backend-requests.md R3-9)
            "/api/auth/password/reset",
            "/api/auth/password/reset/confirm",
            "/api/auth/token",
            "/api/auth/token/refresh",
            // 로그아웃은 본문의 refresh 로 판단한다. 만료된 access 를 붙이면 401 이 날 수 있다.
            "/api/auth/logout",
            // 소셜 로그인은 ID 토큰으로 판단한다(social-login-api.md §2). 앞 세션의 access 를 붙이지
            // 않는다. 연결·끊기(`/api/auth/me/social/...`)는 인증이 필요하므로 여기 넣지 않는다.
            "/api/auth/social/kakao",
            "/api/auth/social/google",
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
 * 락으로 동시에 여러 요청이 401 을 받아도 refresh 를 한 번만 한다.
 *
 * **refresh 요청 자신의 401 은 락을 잡기 전에 돌려보낸다.** refresh 는 같은
 * 클라이언트를 타고 다른 디스패처 스레드에서 돈다. 그 401 이 다시 여기로
 * 들어와 락을 기다리면, 락을 쥔 스레드는 그 응답을 기다리고 있으므로 둘 다
 * 영영 멈춘다. 그 뒤로 인증이 필요한 모든 요청이 같은 락에 줄을 서고,
 * callTimeout 도 막힌 스레드를 풀지 못해 화면이 로딩 상태로 굳는다.
 * 다른 서버에서 받은 토큰(로컬 → 배포 서버 전환)으로 실제로 재현됐다.
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: () -> AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 토큰 발급·갱신 경로의 401 은 갱신으로 풀 수 없다. 그대로 올려
        // 호출부가 판단한다. `/api/auth/me` 같은 인증 경로는 여기 들지 않는다.
        if (isTokenPath(response.request)) return null
        if (responseCount(response) >= 2) return null
        return synchronized(lock) { authenticateLocked(response) }
    }

    private fun isTokenPath(request: Request): Boolean {
        val path = request.url.encodedPath
        return TOKEN_PATHS.any { path.endsWith(it) }
    }

    private fun authenticateLocked(response: Response): Request? {

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

        return when (val outcome = tryRefresh(refresh)) {
            is RefreshOutcome.Renewed -> {
                // 서버가 refresh 를 회전시켰으면 새 것도 저장한다(token-security-api.md §5).
                // 저장하지 못하면(보안 저장소 오류) 세션을 끝낸다 — 앱이 죽지 않고 다시 로그인한다.
                val stored = runCatching { tokenStore.updateTokens(outcome.access, outcome.refresh) }
                if (stored.isFailure) {
                    Log.w(TAG, "갱신한 토큰을 저장하지 못했다. 다시 로그인해야 한다")
                    tokenStore.clear()
                    SessionState.markExpired("토큰 저장 실패")
                    return null
                }
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${outcome.access}")
                    .build()
            }

            is RefreshOutcome.Rejected -> {
                // 서버가 refresh 를 거절했다. 14일이 지났거나 토큰이 무효다.
                Log.i(TAG, "세션 종료: ${outcome.reason}")
                tokenStore.clear()
                // 화면에 알린다. 알리지 않으면 사용자가 홈에 남아 "다시 로그인해야
                // 한다" 만 반복해서 보고, 로그인 화면으로 갈 길을 스스로 찾아야 한다.
                SessionState.markExpired(outcome.reason)
                null
            }

            is RefreshOutcome.Undecided -> {
                // **토큰을 지우지 않는다.** 네트워크가 끊긴 것을 만료로 오인하면
                // 지하철에서 앱을 열었다는 이유로 로그아웃된다.
                Log.w(TAG, "갱신 보류: ${outcome.reason}. 토큰을 유지한다")
                null
            }
        }
    }

    /**
     * refresh 를 한 번 시도하고 결과를 분류한다. 예외를 밖으로 던지지 않는다.
     *
     * 응답에 새 refresh 가 있으면 함께 돌려준다. 서버는 회전을 기본으로 꺼 두었고(본문
     * `access` 하나), 이 앱이 배포된 뒤 `JWT_ROTATE_REFRESH_TOKENS=1` 로 켠다
     * (todo §1 토큰 보안). 켜진 뒤에 새 refresh 를 저장하지 않으면 옛 것이 취소돼
     * 다음 갱신에서 전원이 로그아웃된다.
     */
    private fun tryRefresh(refresh: String): RefreshOutcome {
        return try {
            val result = kotlinx.coroutines.runBlocking {
                refreshApi().refresh(RefreshRequest(refresh))
            }
            RefreshOutcome.of(
                code = result.code(),
                access = result.body()?.access,
                refresh = result.body()?.refresh,
            )
        } catch (e: Throwable) {
            RefreshOutcome.of(code = null, access = null, error = e)
        }
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

    private companion object {
        const val TAG = "TokenRefresh"

        // 소셜 로그인의 401(`social_token_invalid`)은 SDK 토큰이 틀렸다는 뜻이다. 갱신으로 풀 수 없다.
        val TOKEN_PATHS = listOf(
            "/api/auth/token",
            "/api/auth/token/refresh",
            "/api/auth/social/kakao",
            "/api/auth/social/google",
            "/api/auth/password/reset",
            "/api/auth/password/reset/confirm",
        )
    }
}
