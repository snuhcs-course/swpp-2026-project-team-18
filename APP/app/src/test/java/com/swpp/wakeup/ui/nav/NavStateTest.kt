// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.nav

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 하단 탭 골격(iter4 H2-2). 탭마다 스택을 따로 두고, 탭을 바꿔도 보던 화면이 남는다.
 */
class NavStateTest {

    private val day = LocalDate.of(2026, 11, 9)

    private fun NavState.push(route: AppRoute) = copy(stack = stack + route, forward = true)

    @Test
    fun `처음은 홈 탭의 홈 화면이다`() {
        val nav = NavState()
        assertEquals(AppTab.HOME, nav.tab)
        assertEquals(AppRoute.Home, nav.current)
        assertTrue(nav.showsTabBar)
        // 홈 탭의 첫 화면에서는 뒤로 가기를 받지 않는다(앱이 닫힌다).
        assertFalse(nav.handlesBack)
    }

    @Test
    fun `탭을 처음 열면 그 탭의 첫 화면이다`() {
        assertEquals(AppRoute.Trip, NavState().selectTab(AppTab.TRIP).current)
        assertEquals(AppRoute.WeeklyReport, NavState().selectTab(AppTab.REPORT).current)
        assertEquals(AppRoute.Me, NavState().selectTab(AppTab.ME).current)
    }

    @Test
    fun `탭을 바꿨다 돌아오면 보던 화면이 그대로 있다`() {
        val inReport = NavState()
            .push(AppRoute.AlarmDecision(1))
            .selectTab(AppTab.REPORT)
            .push(AppRoute.ReportDay(day, null))

        val backHome = inReport.selectTab(AppTab.HOME)
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(1)), backHome.stack)

        val backReport = backHome.selectTab(AppTab.REPORT)
        assertEquals(listOf(AppRoute.WeeklyReport, AppRoute.ReportDay(day, null)), backReport.stack)
    }

    @Test
    fun `지금 탭을 다시 누르면 그 탭의 첫 화면으로 간다`() {
        val deep = NavState().push(AppRoute.AlarmDecision(1)).push(AppRoute.RiskChoice(1))
        val top = deep.selectTab(AppTab.HOME)
        assertEquals(listOf(AppRoute.Home), top.stack)
        assertFalse(top.forward)

        val atRoot = NavState()
        assertSame(atRoot, atRoot.selectTab(AppTab.HOME))
    }

    @Test
    fun `뒤로 가기는 스택을 하나 빼고, 탭 첫 화면이면 홈 탭으로 간다`() {
        val report = NavState().selectTab(AppTab.REPORT).push(AppRoute.ReportDay(day, 3))
        assertTrue(report.handlesBack)

        val reportRoot = report.back()
        assertEquals(AppRoute.WeeklyReport, reportRoot.current)
        assertTrue(reportRoot.handlesBack)

        val home = reportRoot.back()
        assertEquals(AppTab.HOME, home.tab)
        assertEquals(AppRoute.Home, home.current)
        assertFalse(home.forward)
        // 리포트 탭은 첫 화면으로 남아 있다.
        assertEquals(AppRoute.WeeklyReport, home.selectTab(AppTab.REPORT).current)

        assertSame(home, home.back())
    }

    @Test
    fun `홈으로 가면 지금 탭은 첫 화면으로 되돌리고 다른 탭은 둔다`() {
        val nav = NavState()
            .selectTab(AppTab.REPORT).push(AppRoute.ReportDay(day, null))
            .selectTab(AppTab.TRIP).push(AppRoute.AlarmDecision(7))

        val home = nav.home()
        assertEquals(AppTab.HOME, home.tab)
        assertEquals(listOf(AppRoute.Home), home.stack)
        // 일정을 지우고 홈으로 왔으면 이동 탭에 지운 일정 화면이 남지 않는다.
        assertEquals(listOf(AppRoute.Trip), home.selectTab(AppTab.TRIP).stack)
        assertEquals(AppRoute.ReportDay(day, null), home.selectTab(AppTab.REPORT).current)
    }

    @Test
    fun `모든 탭의 화면을 볼 수 있다`() {
        val nav = NavState()
            .selectTab(AppTab.REPORT).push(AppRoute.ReportDay(day, null))
            .selectTab(AppTab.HOME).push(AppRoute.CalendarImport)

        assertTrue(AppRoute.WeeklyReport in nav.allRoutes)
        assertTrue(nav.allRoutes.any { it is AppRoute.ReportDay })
        assertTrue(AppRoute.CalendarImport in nav.allRoutes)
    }

    @Test
    fun `입력하는 화면에서는 탭 바를 숨긴다`() {
        listOf(AppRoute.Home, AppRoute.Trip, AppRoute.WeeklyReport, AppRoute.Me, AppRoute.Settings,
            AppRoute.Homes, AppRoute.Permissions, AppRoute.AlarmDecision(1), AppRoute.ReportDay(day, null)).forEach {
            assertTrue("$it", it.showsTabBar)
        }
        listOf(AppRoute.AddEvent, AppRoute.EditEvent(1), AppRoute.RouteChoice, AppRoute.MapPick,
            AppRoute.HomeSetup, AppRoute.PrepOnboarding, AppRoute.CalendarLink, AppRoute.ChangePassword,
            AppRoute.VerifyEmail, AppRoute.RiskChoice(1), AppRoute.MorningProgress, AppRoute.RoutineEditor,
            AppRoute.EventBlocks(1), AppRoute.CalendarImport, AppRoute.Intro, AppRoute.Help(),
            AppRoute.Help(com.swpp.wakeup.ui.help.HelpTopic.CALENDAR)).forEach {
            assertFalse("$it", it.showsTabBar)
        }
    }

    @Test
    fun `탭 전환 방향은 탭 순서를 따른다`() {
        assertTrue(NavState().selectTab(AppTab.ME).forward)
        assertFalse(NavState().selectTab(AppTab.ME).selectTab(AppTab.TRIP).forward)
    }
}
