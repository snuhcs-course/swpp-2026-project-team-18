package com.swpp.wakeup.data.local

import android.content.Context

// Partly AI-generated (Claude)
/**
 * 마지막으로 이메일 로그인한 주소. 다음 로그인 화면의 이메일 칸을 채우는 데만 쓴다.
 *
 * **기기 단위 편의 값이다.** 계정 데이터가 아니고 비밀도 아니라서 로그아웃해도 지우지 않는다
 * ([LocalStores.wipeAll] 과 [TokenStore.clear] 둘 다 건드리지 않는다). 다른 기기로 복원되면
 * 남의 이메일이 채워지므로 백업에서 뺀다(`backup_rules.xml`, `data_extraction_rules.xml`).
 *
 * 소셜 로그인은 저장하지 않는다. 그쪽 이메일은 자리표시용일 수 있다.
 */
class LastEmailStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val email: String?
        get() = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun save(email: String) {
        val clean = email.trim()
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_EMAIL, clean).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "jit_last_email"
        const val KEY_EMAIL = "email"
    }
}
