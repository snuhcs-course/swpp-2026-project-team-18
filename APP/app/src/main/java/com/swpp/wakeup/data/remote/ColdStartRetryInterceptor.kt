package com.swpp.wakeup.data.remote

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 잠든 서버가 깨어나는 동안의 실패를 흡수한다.
 *
 * 공용 서버는 Render 무료 플랜이라 15분 무응답이면 잠든다. 다시 깨는 데
 * 측정값 11초, 문서상 최대 1분이 걸린다. 그 사이 요청은 타임아웃이나
 * 502/503 으로 실패한다.
 *
 * **GET 만 재시도한다.** 이것이 이 클래스의 핵심 제약이다. POST 를 재시도하면
 * 서버가 첫 요청을 이미 처리했는데 응답만 늦은 경우 같은 일정이 두 번 생긴다.
 * 요청이 처리됐는지 안 됐는지 클라이언트는 알 수 없다. GET 은 몇 번 불러도
 * 상태가 바뀌지 않으므로 안전하다.
 *
 * 쓰기 요청(로그인·일정 생성 등)의 콜드 스타트는 다른 방법으로 막는다 —
 * [ServerWarmup] 이 먼저 GET 으로 서버를 깨운다.
 *
 * `retryOnConnectionFailure`(OkHttp 기본값 true)와 역할이 다르다. 그쪽은
 * 연결 자체가 실패했을 때 다른 경로로 재시도하고, 여기는 **응답이 늦거나
 * 서버가 5xx 를 준 경우**를 다룬다.
 */
class ColdStartRetryInterceptor(
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 쓰기 요청은 한 번만 보낸다. 중복 생성이 타임아웃보다 나쁜 결과다.
        if (request.method != "GET") {
            return chain.proceed(request)
        }

        var lastError: IOException? = null

        repeat(maxAttempts) { attempt ->
            // 코루틴이 취소됐으면(화면을 떠났다) 더 시도하지 않는다.
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("취소됨")
            }

            try {
                val response = chain.proceed(request)

                // 깨어나는 중에는 프록시가 502·503·504 를 준다. 재시도할 가치가 있다.
                // 4xx 는 우리 잘못이므로 재시도하지 않는다.
                if (response.code in WAKING_CODES && attempt < maxAttempts - 1) {
                    Log.i(TAG, "${response.code} — 서버가 깨어나는 중. 재시도 ${attempt + 1}")
                    response.close()
                    sleep(attempt)
                    return@repeat
                }
                return response
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxAttempts - 1) {
                    Log.w(TAG, "재시도 ${maxAttempts}회 모두 실패: ${e.javaClass.simpleName}")
                    throw e
                }
                Log.i(TAG, "${e.javaClass.simpleName} — 재시도 ${attempt + 1}")
                sleep(attempt)
            }
        }

        // repeat 이 끝났는데 응답이 없으면 마지막 오류를 올린다.
        throw lastError ?: IOException("재시도를 모두 소진했다.")
    }

    /**
     * 재시도 간격. 지수적으로 늘린다.
     *
     * 잠든 서버를 0.5초 간격으로 두드려도 빨리 깨지 않는다. 깨는 데 수십 초가
     * 걸리므로 간격을 벌려 기다리는 편이 낫다.
     */
    private fun sleep(attempt: Int) {
        val millis = BACKOFF_MILLIS * (1L shl attempt)
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("대기 중 취소됨")
        }
    }

    companion object {
        private const val TAG = "ColdStartRetry"

        /** 첫 시도 + 재시도 2회. callTimeout(90초) 안에 끝나야 한다. */
        private const val MAX_ATTEMPTS = 3

        /** 1초 → 2초. 총 대기 3초 + 각 시도의 응답 대기 시간. */
        private const val BACKOFF_MILLIS = 1_000L

        /** 깨어나는 중에 프록시가 주는 코드. */
        private val WAKING_CODES = setOf(502, 503, 504)
    }
}
