package com.swpp.wakeup.data.remote

import android.util.Log
import com.swpp.wakeup.BuildConfig
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
     * 마지막으로 확인한 서버 버전. 아직 확인하지 못했으면 null.
     *
     * `/api/health` 가 처음부터 주고 있던 값인데 앱이 버리고 있었다. 배포가
     * 뒤처지면 새 엔드포인트가 404 가 되고, 그 증상은 앱 버그와 **구별되지
     * 않는다.** 이 값을 들고 있으면 그 자리에서 원인을 말할 수 있다.
     */
    @Volatile
    var serverVersion: String? = null
        private set

    /** 서버가 앱보다 오래됐다고 확인됐는가. 모르면 false. */
    val serverBehind: Boolean
        get() = ServerVersion.isServerBehind(BuildConfig.VERSION_NAME, serverVersion)

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
                val response = runCatching { ApiClient.health.health() }
                    .onFailure { Log.i(TAG, "깨우기 실패: ${it.javaClass.simpleName}") }
                    .getOrNull()
                response?.version?.let { rememberVersion(it) }
                response?.ok == true
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
     * 확인한 버전을 기억하고, 뒤처졌으면 **한 번만** 크게 남긴다.
     *
     * 매번 찍으면 로그가 그 줄로 덮여 다른 원인을 못 찾는다. 버전이 바뀌면
     * (즉 배포가 되면) 다시 찍을 수 있게 값으로 비교한다.
     */
    private fun rememberVersion(version: String) {
        if (serverVersion == version) return
        serverVersion = version

        if (ServerVersion.isServerBehind(BuildConfig.VERSION_NAME, version)) {
            // Log.e 를 쓴다. 이 상태에서는 앱이 하는 모든 새 요청이 404 로
            // 실패하고, 그 증상은 앱 버그와 똑같이 보인다. 원인을 찾는 사람이
            // 로그를 훑을 때 눈에 걸려야 한다.
            Log.e(TAG, ServerVersion.behindMessage(BuildConfig.VERSION_NAME, version))
        } else {
            Log.i(TAG, "서버 버전 $version (앱 ${BuildConfig.VERSION_NAME})")
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
