package com.swpp.wakeup.data.local

import android.content.Context
import androidx.core.content.edit

// Partly AI-generated (Claude)
/**
 * 첫 실행 안내(iter4 H2-4)를 이 기기·계정에서 보여 줬는가.
 *
 * - [introShown]: 세 장짜리 첫 실행 안내를 마쳤거나 건너뛰었다. ⑱ 캘린더 연동처럼 한 번만 띄운다.
 * - [coachmarkPending]: 홈 첫 방문 안내(코치마크)를 띄워야 한다. 안내를 띄울 때 켜고, 코치마크를 닫으면 끈다.
 *   안내를 넘기다 앱을 꺼도 다음에 홈에 들어오면 코치마크가 뜬다.
 * - [coachmarkDone]: 이 기기에서 코치마크를 닫은 적이 있다. 서버에 아직 못 올렸을 때 같은 코치마크를 다시
 *   띄우지 않게 한다.
 *
 * "본 적 있음" 의 정본은 계정(서버 `intro_seen_at` · `coachmark_seen_at`, 팀 결정 11-11)이다. 이 저장소는 오프라인
 * · 구버전 서버에서도 같은 기기에 두 번 띄우지 않으려고 함께 쓴다. 판단은 [com.swpp.wakeup.ui.onboarding.GuideSeenRules].
 * 다른 저장소처럼 소유자를 붙이고([editOwned], [discardIfForeign]) 로그아웃 때 [LocalStores.wipeAll] 이 지운다.
 */
class FirstRunGuideStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun introShown(): Boolean {
        if (prefs.discardIfForeign(appContext)) return false
        return prefs.getBoolean(KEY_INTRO_SHOWN, false)
    }

    fun markIntroShown(): Boolean = prefs.editOwned(appContext) { putBoolean(KEY_INTRO_SHOWN, true) }

    fun coachmarkPending(): Boolean {
        if (prefs.discardIfForeign(appContext)) return false
        return prefs.getBoolean(KEY_COACHMARK_PENDING, false)
    }

    fun setCoachmarkPending(pending: Boolean): Boolean =
        prefs.editOwned(appContext) { putBoolean(KEY_COACHMARK_PENDING, pending) }

    fun coachmarkDone(): Boolean {
        if (prefs.discardIfForeign(appContext)) return false
        return prefs.getBoolean(KEY_COACHMARK_DONE, false)
    }

    /** 코치마크를 닫았다. 띄울 차례도 함께 끈다 */
    fun markCoachmarkDone(): Boolean = prefs.editOwned(appContext) {
        putBoolean(KEY_COACHMARK_PENDING, false)
        putBoolean(KEY_COACHMARK_DONE, true)
    }

    /** 소유자 표시까지 전부 지운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE = "jit_first_run_guide"
        const val KEY_INTRO_SHOWN = "intro_shown"
        const val KEY_COACHMARK_PENDING = "coachmark_pending"
        const val KEY_COACHMARK_DONE = "coachmark_done"
    }
}
