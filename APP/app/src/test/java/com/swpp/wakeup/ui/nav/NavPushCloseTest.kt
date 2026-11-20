// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 두 번 눌러도 한 장만 · 저장을 마친 그 화면만 닫는다(fix/iter4-he-bugs 이식, 11-19) */
class NavPushCloseTest {

    private fun nav(vararg r: AppRoute) = NavState(stack = r.toList())

    @Test
    fun `맨 위가 이미 그 화면이면 다시 쌓지 않는다`() {
        val once = nav(AppRoute.Home).push(AppRoute.Settings)
        assertSame(once, once.push(AppRoute.Settings))
        assertEquals(listOf(AppRoute.Home, AppRoute.Settings, AppRoute.Homes), once.push(AppRoute.Homes).stack)
    }

    @Test
    fun `저장 중 위에 다른 화면이 열렸으면 그 화면은 두고 저장한 화면만 뺀다`() {
        val stack = nav(AppRoute.Home, AppRoute.HomeSetup, AppRoute.AlarmDecision(3))
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(3)), stack.close { it == AppRoute.HomeSetup }.stack)
    }

    @Test
    fun `맨 위면 한 칸 닫고 · 없거나 맨 아래면 그대로`() {
        assertEquals(listOf<AppRoute>(AppRoute.Home), nav(AppRoute.Home, AppRoute.HomeSetup).close { it == AppRoute.HomeSetup }.stack)
        val noMatch = nav(AppRoute.Home, AppRoute.Settings)
        assertSame(noMatch, noMatch.close { it == AppRoute.HomeSetup })
        val bottom = nav(AppRoute.Home)
        assertSame(bottom, bottom.close { it == AppRoute.Home })
    }

    @Test
    fun `뺀 자리 양옆이 같은 화면이면 한 장만 남긴다`() {
        val stack = nav(AppRoute.Home, AppRoute.AlarmDecision(3), AppRoute.EditEvent(3), AppRoute.AlarmDecision(3))
        assertEquals(listOf(AppRoute.Home, AppRoute.AlarmDecision(3)), stack.close { it is AppRoute.EditEvent }.stack)
    }
}
