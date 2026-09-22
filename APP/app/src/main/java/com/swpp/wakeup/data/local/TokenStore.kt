package com.swpp.wakeup.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * access / refresh 토큰과 로그인한 사용자의 표시 정보를 보관한다.
 *
 * **평문 SharedPreferences 다.** 루팅되지 않은 기기에서는 앱 전용 저장소가
 * 다른 앱으로부터 격리되므로 P1 까지는 이 수준으로 둔다. 실기기 배포 전에
 * `androidx.security:security-crypto` 의 EncryptedSharedPreferences 로 바꾼다.
 * front-spec 의 보안 항목에 남겨 두었다.
 *
 * refresh 토큰 수명은 14일(back-spec 5.1)이라 만료되면 다시 로그인해야 한다.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        private set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)

    val nickname: String?
        get() = prefs.getString(KEY_NICKNAME, null)

    val email: String?
        get() = prefs.getString(KEY_EMAIL, null)

    /**
     * 인증된 요청을 보낼 수 있는 상태인가.
     *
     * **refresh 토큰도 함께 요구한다.** access 만 있으면 그것이 만료된 순간
     * 갱신할 재료가 없어서 복구 경로가 사라진다. 앱을 다시 열 때 이 값으로
     * 로그인 화면을 건너뛰므로(LoginActivity), access 하나만 보고 들어가면
     * 사용자가 홈에서 오류만 보면서 나갈 길을 못 찾는 상태가 된다.
     *
     * access 의 **만료는 검사하지 않는다.** 만료됐으면 첫 요청이 401 을 받고
     * [com.swpp.wakeup.data.remote.TokenRefreshAuthenticator] 가 갱신한다.
     * 클라이언트에서 JWT `exp` 를 파싱해 미리 판단할 수도 있지만, 기기 시계가
     * 서버와 어긋나면 **멀쩡한 세션을 버리게** 된다.
     */
    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun save(access: String, refresh: String, email: String, nickname: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }

    /** refresh 로 access 만 갱신한 경우. */
    fun updateAccess(access: String) {
        accessToken = access
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "jit_auth"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EMAIL = "email"
        const val KEY_NICKNAME = "nickname"
    }
}
