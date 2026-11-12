// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

import com.swpp.wakeup.ui.onboarding.GuideSeenRules.Account
import com.swpp.wakeup.ui.onboarding.GuideSeenRules.Local
import com.swpp.wakeup.ui.onboarding.GuideSeenRules.Upload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 첫 실행 안내 · 홈 코치마크 "본 적 있음" 을 계정과 기기에서 합친다(iter4 H2-4, 팀 결정 11-11) */
class GuideSeenRulesTest {

    private val fresh = Local(introShown = false, coachmarkPending = false, coachmarkDone = false)
    private val nothing = Account(introSeen = false, coachmarkSeen = false)

    @Test
    fun `계정이 봤으면 새 기기에서도 안내를 다시 띄우지 않는다`() {
        assertTrue(GuideSeenRules.introSeen(fresh, Account(introSeen = true, coachmarkSeen = true)))
    }

    @Test
    fun `서버가 모르거나 오프라인이면 기기 기록으로 판단한다`() {
        val shownHere = fresh.copy(introShown = true)
        assertTrue(GuideSeenRules.introSeen(shownHere, nothing))
        assertTrue(GuideSeenRules.introSeen(shownHere, null))
        assertFalse(GuideSeenRules.introSeen(fresh, null))
    }

    @Test
    fun `이 기기에 차례가 있으면 계정이 봤어도 코치마크를 띄운다`() {
        // 처음 안내 다시 보기
        val replay = fresh.copy(introShown = true, coachmarkPending = true, coachmarkDone = true)
        assertTrue(GuideSeenRules.showCoachmark(replay, Account(introSeen = true, coachmarkSeen = true)))
    }

    @Test
    fun `안내만 보고 코치마크 전에 기기를 바꾸면 새 기기에서 코치마크를 띄운다`() {
        assertTrue(GuideSeenRules.showCoachmark(fresh, Account(introSeen = true, coachmarkSeen = false)))
    }

    @Test
    fun `이 기기에서 이미 닫았거나 계정이 봤거나 안내를 안 봤으면 띄우지 않는다`() {
        val closedHere = fresh.copy(introShown = true, coachmarkDone = true)
        assertFalse("서버에 아직 못 올렸어도 다시 띄우지 않는다", GuideSeenRules.showCoachmark(closedHere, Account(true, false)))
        assertFalse(GuideSeenRules.showCoachmark(fresh, Account(introSeen = true, coachmarkSeen = true)))
        assertFalse("안내 없이 가입한 예전 사용자", GuideSeenRules.showCoachmark(fresh, nothing))
        assertFalse(GuideSeenRules.showCoachmark(fresh, null))
    }

    @Test
    fun `기기에서만 본 것을 계정에 올린다`() {
        val both = fresh.copy(introShown = true, coachmarkDone = true)
        assertEquals(Upload(intro = true, coachmark = true), GuideSeenRules.toUpload(both, nothing))
        assertEquals(Upload(intro = false, coachmark = true), GuideSeenRules.toUpload(both, Account(true, false)))
        assertTrue(GuideSeenRules.toUpload(both, Account(true, true)).isEmpty)
        assertTrue("기기에서 안 봤으면 올릴 것이 없다", GuideSeenRules.toUpload(fresh, nothing).isEmpty)
    }
}
