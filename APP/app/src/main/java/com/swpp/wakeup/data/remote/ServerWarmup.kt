package com.swpp.wakeup.data.remote

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 잠든 서버를 미리 깨운다.
 *
 * **왜 필요한가.** 공용 서버는 Render 무료 플랜이라 15분 무응답이면 잠들고,
 * 깨는 데 측정값 11초·문서상 최대 1분이 걸린다. 그 사이 첫 요청은 실패한다.
 *
 * [ColdStartRetryInterceptor] 가 GET 은 재시도해 주지만 **POST 는 재시도하지
 * 않는다** — 서버가 이미 처리했는데 응답만 늦은 경우 같은 일정이 두 번 생긴다.
 * 그래서 로그인·회원가입 같은 쓰기 요청 앞에서는 GET 하나로 서버를 깨우고,
 * 깨어난 뒤에 본 요청을 보낸다.
 *
 * `/api/health` 를 쓴다. 인증이 필요 없고 상태를 바꾸지 않아 몇 번 불러도 된다.
 */
object ServerWarmup {

    private const val TAG = "ServerWarmup"

    /** 깨우기에 쓸 시간 상한. 이걸 넘으면 포기하고 본 요청을 그냥 보낸다. */
    private const val TIMEOUT_MILLIS = 70_000L

    /** 이 시간 안에 깨운 적이 있으면 다시 확인하지 않는다. */
    private const val FRESH_MILLIS = 60_000L

    // 동시에 여러 화면이 부를 수 있다. 한 번만 깨우면 되므로 잠금을 건다.
    private val mutex = Mutex()

    @Volatile
    private var lastOkAt: Long = 0L

    /**
     * 서버가 응답할 수 있는 상태인지 확인한다.
     *
     * @return 깨어 있음을 확인했으면 true. 실패해도 **호출자는 계속 진행해야
     *   한다** — 이 함수의 실패가 곧 서버가 죽었다는 뜻은 아니고, 본 요청이
     *   더 정확한 오류 메시지를 준다.
     */
    suspend fun ensureAwake(): Boolean {
        val since = System.currentTimeMillis() - lastOkAt
        if (since < FRESH_MILLIS) {
            return true
        }

        return mutex.withLock {
            // 잠금을 기다리는 동안 다른 호출이 깨웠을 수 있다.
            if (System.currentTimeMillis() - lastOkAt < FRESH_MILLIS) {
                return@withLock true
            }

            val started = System.currentTimeMillis()
            val ok = withTimeoutOrNull(TIMEOUT_MILLIS) {
                runCatching { ApiClient.health.health() }
                    .onFailure { Log.i(TAG, "깨우기 실패: ${it.javaClass.simpleName}") }
                    .getOrNull()
                    ?.ok == true
            } ?: false

            val elapsed = System.currentTimeMillis() - started
            if (ok) {
                lastOkAt = System.currentTimeMillis()
                // 오래 걸렸으면 잠들어 있던 것이다. 로그로 남겨 두면 나중에
                // "로그인이 느리다" 는 제보의 원인을 바로 확인할 수 있다.
                if (elapsed > 3_000) {
                    Log.i(TAG, "서버가 잠들어 있었다. 깨우는 데 ${elapsed}ms")
                } else {
                    Log.d(TAG, "서버 정상 (${elapsed}ms)")
                }
            } else {
                Log.w(TAG, "깨우지 못했다 (${elapsed}ms). 본 요청을 그대로 보낸다.")
            }
            ok
        }
    }

    /**
     * 깨어 있다고 기록된 상태를 지운다.
     *
     * 요청이 타임아웃으로 실패했을 때 호출한다. 그러지 않으면 [FRESH_MILLIS]
     * 동안 "깨어 있다" 고 잘못 믿고 깨우기를 건너뛴다.
     */
    fun invalidate() {
        lastOkAt = 0L
    }
}
