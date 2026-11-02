package com.swpp.wakeup.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * access / refresh 토큰과 로그인한 사용자의 표시 정보를 보관한다.
 *
 * **토큰은 암호화해 둔다**([TokenCipher], Android Keystore AES-GCM). 이메일·닉네임은 화면
 * 표시용이라 평문이다. 암호화 전 버전이 남긴 평문 토큰은 처음 읽을 때 암호화해 다시 쓴다.
 *
 * 풀지 못하면(키가 사라짐, 백업 복원, 값이 깨짐) 토큰을 지운다. 그러면 [isLoggedIn] 이
 * false 가 되어 다시 로그인한다 — 앱이 죽지 않는다. 토큰 값은 로그에 남기지 않는다.
 *
 * refresh 토큰 수명은 14일(back-spec 5.1)이라 만료되면 다시 로그인해야 한다.
 */
class TokenStore(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher,
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val accessToken: String?
        get() = readSecret(KEY_ACCESS)

    val refreshToken: String?
        get() = readSecret(KEY_REFRESH)

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

    /** @throws TokenStorageException 암호화할 수 없으면. 아무것도 저장하지 않는다 */
    fun save(access: String, refresh: String, email: String, nickname: String) {
        val (sealedAccess, sealedRefresh) = sealAll(access, refresh)
        prefs.edit()
            .putString(KEY_ACCESS, sealedAccess)
            .putString(KEY_REFRESH, sealedRefresh)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }

    /** refresh 로 access 만 갱신한 경우. */
    fun updateAccess(access: String) = updateTokens(access, refresh = null)

    /**
     * 갱신 결과를 저장한다. 서버가 refresh 를 회전시켰으면([refresh] 가 있으면) 그것도 바꾼다.
     * 회전된 옛 refresh 는 서버가 취소하므로 새 것을 저장하지 않으면 다음 갱신에서 로그아웃된다.
     *
     * @throws TokenStorageException 암호화할 수 없으면
     */
    fun updateTokens(access: String, refresh: String?) {
        val sealedAccess = seal(access)
        val sealedRefresh = refresh?.let(::seal)
        prefs.edit()
            .putString(KEY_ACCESS, sealedAccess)
            .apply { if (sealedRefresh != null) putString(KEY_REFRESH, sealedRefresh) }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // --- 내부 -------------------------------------------------------------

    private fun sealAll(access: String, refresh: String): Pair<String, String> =
        try {
            cipher.encrypt(access) to cipher.encrypt(refresh)
        } catch (first: Exception) {
            // 키가 망가졌을 수 있다(기기 복원 등). 한 번만 새 키로 다시 해 본다.
            Log.w(TAG, "토큰 암호화 실패. 키를 새로 만들어 다시 시도한다", first)
            cipher.reset()
            try {
                cipher.encrypt(access) to cipher.encrypt(refresh)
            } catch (second: Exception) {
                throw TokenStorageException(second)
            }
        }

    private fun seal(value: String): String =
        try {
            cipher.encrypt(value)
        } catch (e: Exception) {
            throw TokenStorageException(e)
        }

    private fun readSecret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(TokenCipher.PREFIX)) {
            // 암호화 전 버전이 남긴 평문. 이번에 암호화해 둔다. 실패하면 다음에 다시 한다.
            runCatching { prefs.edit().putString(key, cipher.encrypt(stored)).apply() }
                .onFailure { Log.w(TAG, "옛 토큰을 암호화하지 못했다. 다음에 다시 한다", it) }
            return stored
        }
        decrypted[stored]?.let { return it }
        return try {
            cipher.decrypt(stored).also { plain ->
                if (decrypted.size > CACHE_MAX) decrypted.clear()
                decrypted[stored] = plain
            }
        } catch (e: Exception) {
            // 값은 남기지 않는다. 종류만 남긴다.
            Log.w(TAG, "토큰을 풀지 못했다(${e.javaClass.simpleName}). 다시 로그인해야 한다")
            prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
            null
        }
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE = "jit_auth"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EMAIL = "email"
        const val KEY_NICKNAME = "nickname"
        const val CACHE_MAX = 8
    }

    /**
     * 푼 값의 사본. 암호문 → 평문.
     *
     * 요청마다 Keystore 를 부르지 않게 한다(인증 헤더가 요청마다 access 를 읽는다). 키가
     * **저장된 암호문**이라 다른 인스턴스가 토큰을 바꾸거나 지워도 어긋나지 않는다.
     */
    private val decrypted = java.util.concurrent.ConcurrentHashMap<String, String>()
}
