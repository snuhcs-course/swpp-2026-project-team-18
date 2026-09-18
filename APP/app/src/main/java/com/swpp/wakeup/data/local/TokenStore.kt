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

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank()

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
