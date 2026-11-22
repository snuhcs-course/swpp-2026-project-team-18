// AI-generated (Claude) — A-6 크래시 보고(Firebase Crashlytics), 11-20
package com.swpp.wakeup.diagnostics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * 크래시 보고에 붙는 값을 한 곳에서 거른다. 앱의 다른 코드는 `FirebaseCrashlytics` 를 직접 부르지 않는다.
 *
 * **개인정보를 보내지 않는다.**
 * - 사용자 식별값은 서버의 숫자 사용자 id 만 쓴다. 이메일 · 닉네임은 넣지 않는다. 로그아웃하면 지운다.
 * - 커스텀 키는 [ALLOWED_KEYS](앱 버전 · 서버 버전 · 화면 이름)만 받는다. 좌표 · 장소 이름 · 일정 제목 · 토큰이 들어갈
 *   자리를 두지 않는다. 값도 [safeValue] 가 이메일 · 토큰 모양 · 숫자 좌표를 거른다.
 *
 * Firebase 를 부를 수 없는 곳(JVM 단위 테스트, Firebase 초기화 전)에서도 앱이 죽지 않게 모든 호출을 감싼다.
 * 테스트는 [sink] 를 바꿔 실제로 무엇이 나가는지 확인한다.
 */
object CrashReport {

    /** 크래시 보고로 실제 값을 넘기는 곳. 기본은 Firebase. 테스트가 바꾼다 */
    interface Sink {
        fun setUserId(id: String)
        fun setKey(key: String, value: String)
        fun setCollectionEnabled(enabled: Boolean)
    }

    const val KEY_APP_VERSION = "app_version"
    const val KEY_SERVER_VERSION = "server_version"
    const val KEY_SCREEN = "screen"

    /** 커스텀 키는 이것만 받는다 */
    val ALLOWED_KEYS = setOf(KEY_APP_VERSION, KEY_SERVER_VERSION, KEY_SCREEN)

    private const val TAG = "CrashReport"
    private const val MAX_VALUE = 64

    @Volatile
    internal var sink: Sink = FirebaseSink

    /** 서버 사용자 id(숫자)를 붙인다. 0 이하는 무시한다 */
    fun setUser(serverUserId: Long) {
        val id = userIdentifier(serverUserId) ?: return
        call { sink.setUserId(id) }
    }

    /** 로그아웃 · 세션 만료. 다음 크래시가 앞 사용자에게 붙지 않게 지운다 */
    fun clearUser() = call { sink.setUserId("") }

    /** [ALLOWED_KEYS] 의 키만, [safeValue] 를 통과한 값만 붙인다. 아니면 아무것도 보내지 않는다 */
    fun setKey(key: String, value: String?) {
        if (key !in ALLOWED_KEYS) return
        val safe = safeValue(value) ?: return
        call { sink.setKey(key, safe) }
    }

    /** 기기 테스트 실행 중이면 수집을 끈다. 테스트가 일부러 낸 오류가 크래시 보고에 섞이지 않게 */
    fun disableIfInstrumented() {
        if (runningInstrumentation()) call { sink.setCollectionEnabled(false) }
    }

    /** 숫자 사용자 id → 보고에 쓸 문자열. 0 이하는 null */
    internal fun userIdentifier(serverUserId: Long): String? = serverUserId.takeIf { it > 0 }?.toString()

    /**
     * 커스텀 키 값으로 보내도 되는가. 짧은 영문 · 숫자 · 점 · 밑줄 · 하이픈만 받는다(앱 버전 "0.16.0", 화면 "AlarmDecision").
     * 그래서 이메일(@), 한글(장소 이름 · 일정 제목), 공백 섞인 문장, 긴 토큰, 쉼표 좌표가 들어갈 수 없다.
     * 소수점 숫자 하나(37.4812 같은 좌표 한 축)도 막는다.
     */
    internal fun safeValue(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v.length > MAX_VALUE) return null
        if (!SAFE.matches(v)) return null
        if (COORDINATE.matches(v)) return null
        return v
    }

    private val SAFE = Regex("[A-Za-z0-9._-]+")
    private val COORDINATE = Regex("-?\\d{1,3}\\.\\d{3,}")

    private fun runningInstrumentation(): Boolean =
        runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess

    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // Firebase 를 쓸 수 없는 환경(초기화 전 · JVM 테스트). 보고는 부가 기능이라 삼킨다.
            Log.d(TAG, "크래시 보고를 건너뛴다: ${t.javaClass.simpleName}")
        }
    }

    private object FirebaseSink : Sink {
        override fun setUserId(id: String) = FirebaseCrashlytics.getInstance().setUserId(id)
        override fun setKey(key: String, value: String) = FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        override fun setCollectionEnabled(enabled: Boolean) {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
        }
    }
}
