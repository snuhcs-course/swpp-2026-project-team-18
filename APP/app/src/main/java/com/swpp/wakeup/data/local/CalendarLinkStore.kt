package com.swpp.wakeup.data.local

import android.content.Context
import androidx.core.content.edit

// Partly AI-generated (Claude)
/**
 * 어느 기기 캘린더를 연동했는지(⑱ 캘린더 연동 · ⑯ 설정).
 *
 * ## 왜 서버가 아니라 기기에 두는가
 *
 * 캘린더 id(`CalendarContract.Calendars._ID`)는 **이 기기 안에서만** 뜻이 있다. 다른
 * 기기에서는 같은 구글 캘린더가 다른 번호다. 서버에 둬도 다른 기기에서 쓸 수 없다.
 *
 * ## 연동해도 서버로 보내지 않는다
 *
 * 연동은 "이 캘린더의 일정을 앱이 읽어도 된다" 까지다. 읽은 일정은 캘린더 가져오기
 * 화면에 나오고, **그중 사용자가 고른 것만** 서버로 간다. 자동 동기화를 두지 않은
 * 이유(제목이 민감함, 앱에서 지운 일정이 되살아남)는 그대로다.
 *
 * ## 계정
 *
 * 다른 저장소와 같이 소유자를 붙인다([editOwned], [discardIfForeign]). 로그아웃·로그인은
 * [LocalStores.wipeAll] 이 지운다 — 다시 로그인하면 연동을 다시 고른다.
 */
class CalendarLinkStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 연동한 캘린더 id. 기기에서 사라진 캘린더의 id 가 남아 있을 수 있다 */
    fun linkedIds(): Set<Long> {
        if (prefs.discardIfForeign(appContext)) return emptySet()
        // getStringSet 이 돌려준 집합은 고치면 안 된다. 새 집합으로 옮긴다.
        return prefs.getStringSet(KEY_LINKED, null)
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()
    }

    /** 연동 목록을 통째로 바꾼다. 로그아웃 상태면 쓰지 않고 false 다 */
    fun setLinked(ids: Set<Long>): Boolean =
        prefs.editOwned(appContext) { putStringSet(KEY_LINKED, ids.map(Long::toString).toSet()) }

    /** 가입 흐름에서 ⑱ 을 이미 보여 줬는가. 건너뛰었어도 true 다 */
    fun wasAsked(): Boolean {
        if (prefs.discardIfForeign(appContext)) return false
        return prefs.getBoolean(KEY_ASKED, false)
    }

    fun markAsked(): Boolean = prefs.editOwned(appContext) { putBoolean(KEY_ASKED, true) }

    /** 소유자 표시까지 전부 지운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE = "jit_calendar_link"
        const val KEY_LINKED = "linked_ids"
        const val KEY_ASKED = "asked"
    }
}
