// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

/**
 * 첫 실행 안내 · 홈 코치마크를 "본 적 있음" 을 계정(서버)과 기기 기록으로 판단한다(iter4 H2-4, 팀 결정 11-11).
 *
 * 정본은 계정이다(`Profile.intro_seen_at` · `coachmark_seen_at`, docs/iter4/first-run-guide-api.md). 다른 기기로
 * 로그인하거나 앱을 다시 깔아도 다시 띄우지 않는다. 기기 기록([Local], `FirstRunGuideStore`)은 오프라인이거나 서버가
 * 이 필드를 모를 때(구버전) 같은 기기에서 두 번 띄우지 않으려고 함께 본다.
 *
 * Context 없이 돌아가게 둔다. `GuideSeenRulesTest` 가 지킨다.
 */
internal object GuideSeenRules {

    /** 기기 기록 */
    data class Local(
        /** 이 기기에서 안내를 끝까지 넘겼거나 건너뛰었다 */
        val introShown: Boolean,
        /** 코치마크를 띄울 차례다(이 기기에서 안내를 띄웠거나 [처음 안내 다시 보기]를 눌렀다) */
        val coachmarkPending: Boolean,
        /** 이 기기에서 코치마크를 닫은 적이 있다 */
        val coachmarkDone: Boolean,
    )

    /** 계정 기록. 서버가 필드를 모르면 둘 다 false 로 온다 */
    data class Account(val introSeen: Boolean, val coachmarkSeen: Boolean)

    /** 서버에 올릴 것 */
    data class Upload(val intro: Boolean, val coachmark: Boolean) {
        val isEmpty: Boolean get() = !intro && !coachmark
    }

    /** 첫 실행 안내를 본 적이 있는가. 어느 한쪽이라도 봤으면 다시 띄우지 않는다. [account] 가 null 이면 서버를 모른다 */
    fun introSeen(local: Local, account: Account?): Boolean = local.introShown || account?.introSeen == true

    /**
     * 홈 코치마크를 띄울 차례인가.
     *
     * - 기기에 차례가 있으면 띄운다. [처음 안내 다시 보기] 도 이 길이라 계정이 이미 봤어도 다시 뜬다.
     * - 계정이 안내는 봤는데 코치마크는 안 봤으면(안내 도중 앱을 끄고 다른 기기로 옮겼다) 띄운다. 이 기기에서 이미
     *   닫았다면(서버에 아직 못 올렸다) 띄우지 않는다.
     */
    fun showCoachmark(local: Local, account: Account?): Boolean =
        local.coachmarkPending ||
            (account != null && account.introSeen && !account.coachmarkSeen && !local.coachmarkDone)

    /** 기기에서는 봤는데 계정이 모르는 것. 닫을 때 보낸 PATCH 가 실패했거나 서버가 필드를 몰랐던 때를 메운다 */
    fun toUpload(local: Local, account: Account): Upload = Upload(
        intro = local.introShown && !account.introSeen,
        coachmark = local.coachmarkDone && !account.coachmarkSeen,
    )
}
