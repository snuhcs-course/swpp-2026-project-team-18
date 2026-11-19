// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.AppTab
import com.swpp.wakeup.ui.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시스템 뒤로는 화면의 ‹ 와 같다(fix/iter4-he-bugs 이식, 11-19) */
class SystemBackTest {

    private fun nav(vararg routes: AppRoute, tab: AppTab = AppTab.HOME) = NavState(stack = routes.toList(), tab = tab)

    private fun back(nav: NavState, waiting: Boolean = false, draft: Boolean = false, preview: Boolean = false) =
        systemBackOf(nav, waiting, draft, preview)

    @Test
    fun `저장을 기다리는 동안에는 받되 움직이지 않는다`() {
        assertEquals(SystemBack.STAY, back(nav(AppRoute.Home, AppRoute.EditEvent(1)), waiting = true))
        // 블록 폼이 열려 있어도 저장 중이면 닫지 않는다
        assertEquals(SystemBack.STAY, back(nav(AppRoute.Home, AppRoute.RoutineEditor), waiting = true, draft = true))
    }

    @Test
    fun `겹쳐 그린 폼 · 미리보기는 그 화면이 맨 위일 때만 닫는다`() {
        assertEquals(SystemBack.CLOSE_BLOCK_DRAFT, back(nav(AppRoute.Home, AppRoute.RoutineEditor), draft = true))
        assertEquals(SystemBack.POP, back(nav(AppRoute.Home, AppRoute.RoutineEditor, AppRoute.AlarmDecision(3)), draft = true))
        assertEquals(SystemBack.CLOSE_ROUTE_PREVIEW, back(nav(AppRoute.Home, AppRoute.RouteChoice), preview = true))
    }

    @Test
    fun `캘린더 연동 · 준비 시간은 나중에와 같고 지도는 정리하고 닫는다`() {
        assertEquals(SystemBack.SKIP_CALENDAR_LINK, back(nav(AppRoute.Home, AppRoute.CalendarLink)))
        assertEquals(SystemBack.SKIP_PREP, back(nav(AppRoute.Home, AppRoute.PrepOnboarding)))
        assertEquals(SystemBack.CLOSE_MAP, back(nav(AppRoute.Home, AppRoute.MapPick)))
        assertEquals(SystemBack.POP, back(nav(AppRoute.Home, AppRoute.Settings)))
    }

    @Test
    fun `홈 탭 첫 화면은 앱이 받지 않고 다른 탭 첫 화면은 홈으로`() {
        assertEquals(SystemBack.SYSTEM, back(nav(AppRoute.Home)))
        assertEquals(SystemBack.POP, back(nav(AppRoute.Me, tab = AppTab.ME)))
    }

    @Test
    fun `기다리는지는 맨 위 화면의 저장만 본다`() {
        assertTrue(waitingOn(AppRoute.HomeSetup, false, true, false, false, false))
        assertTrue(waitingOn(AppRoute.EventBlocks(2), false, false, false, false, true))
        assertFalse(waitingOn(AppRoute.Home, true, true, true, true, true))
    }
}
