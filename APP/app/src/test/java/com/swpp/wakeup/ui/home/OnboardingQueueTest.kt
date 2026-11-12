package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.nav.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Partly AI-generated (Claude)
/**
 * 가입 직후 온보딩 순서. 스택은 마지막에 넣은 것이 먼저 보이므로, 보이는 순서는
 * [OnboardingQueue.pushOrder] 의 역순이다.
 */
class OnboardingQueueTest {

    private fun visible(askHome: Boolean, askPrep: Boolean, askCalendar: Boolean, askIntro: Boolean = false) =
        OnboardingQueue.pushOrder(askHome, askPrep, askCalendar, askIntro).reversed()

    @Test
    fun `가입 직후에는 집 주소, 준비 시간, 캘린더 연동 순이다`() {
        assertEquals(
            listOf(AppRoute.HomeSetup, AppRoute.PrepOnboarding, AppRoute.CalendarLink),
            visible(askHome = true, askPrep = true, askCalendar = true),
        )
    }

    @Test
    fun `준비 시간만 남았으면 그 다음에 캘린더 연동이다`() {
        assertEquals(
            listOf(AppRoute.PrepOnboarding, AppRoute.CalendarLink),
            visible(askHome = false, askPrep = true, askCalendar = true),
        )
    }

    @Test
    fun `가입을 마친 사용자에게는 캘린더만 따로 묻지 않는다`() {
        // 앱을 열 때마다 캘린더 화면이 뜨면 건너뛴 사용자를 귀찮게만 한다. 연동은 설정에서 한다.
        assertTrue(visible(askHome = false, askPrep = false, askCalendar = true).isEmpty())
    }

    @Test
    fun `이미 보여 준 기기에서는 캘린더 연동을 다시 넣지 않는다`() {
        assertEquals(
            listOf(AppRoute.HomeSetup, AppRoute.PrepOnboarding),
            visible(askHome = true, askPrep = true, askCalendar = false),
        )
    }

    @Test
    fun `첫 실행 안내는 캘린더 연동 다음, 홈 바로 앞이다`() {
        // iter4 H2-4(Figma 545:1305): ⑭ → ⑮ → ⑱ → 첫 실행 안내 → 홈
        assertEquals(
            listOf(AppRoute.HomeSetup, AppRoute.PrepOnboarding, AppRoute.CalendarLink, AppRoute.Intro),
            visible(askHome = true, askPrep = true, askCalendar = true, askIntro = true),
        )
        assertEquals(
            "⑱ 을 이미 본 기기여도 안내는 따로 뜬다",
            listOf(AppRoute.PrepOnboarding, AppRoute.Intro),
            visible(askHome = false, askPrep = true, askCalendar = false, askIntro = true),
        )
    }

    @Test
    fun `가입을 마친 사용자에게는 첫 실행 안내를 띄우지 않는다`() {
        // 업데이트로 안내가 생겨도 이미 쓰던 사용자를 막지 않는다. 내 정보 › 처음 안내 다시 보기로 본다.
        assertTrue(visible(askHome = false, askPrep = false, askCalendar = true, askIntro = true).isEmpty())
        assertEquals(
            "이미 본 기기에서는 다시 넣지 않는다",
            listOf(AppRoute.HomeSetup, AppRoute.PrepOnboarding, AppRoute.CalendarLink),
            visible(askHome = true, askPrep = true, askCalendar = true, askIntro = false),
        )
    }
}
