package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.help.HelpTopic
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.AppTab
import com.swpp.wakeup.ui.nav.NavState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeNavigationTest {
    @Test fun `settings subpages return through the screen that opened them`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Settings)
        navigation.push(AppRoute.ChangePassword)
        navigation.back()
        assertEquals(AppRoute.Settings, navigation.state.value.current)
        navigation.push(AppRoute.VerifyEmail)
        navigation.back()
        assertEquals(AppRoute.Settings, navigation.state.value.current)
        navigation.back()
        assertEquals(AppTab.ME, navigation.state.value.tab)
        assertEquals(AppRoute.Me, navigation.state.value.current)
        assertFalse(navigation.state.value.forward)
    }

    @Test fun `home permission banner returns to home while Me permission returns to Me`() {
        val navigation = HomeNavigation()
        navigation.push(AppRoute.Permissions)
        navigation.back()
        assertEquals(AppRoute.Home, navigation.state.value.current)
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Permissions)
        navigation.back()
        assertEquals(AppRoute.Me, navigation.state.value.current)
    }

    @Test fun `home edit completion returns to the saved homes screen`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Homes)
        navigation.push(AppRoute.HomeSetup)
        navigation.close { it == AppRoute.HomeSetup }
        assertEquals(AppRoute.Homes, navigation.state.value.current)
        navigation.back()
        assertEquals(AppRoute.Me, navigation.state.value.current)
    }

    @Test fun `calendar help and replayed intro return to their actual opener`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.CalendarImport)
        navigation.openHelp(HelpTopic.CALENDAR)
        navigation.push(AppRoute.Intro)
        navigation.finishIntro()
        assertEquals(AppRoute.Help(HelpTopic.CALENDAR), navigation.state.value.current)
        navigation.back()
        assertEquals(AppRoute.CalendarImport, navigation.state.value.current)
        navigation.close { it == AppRoute.CalendarImport }
        assertEquals(AppRoute.Me, navigation.state.value.current)
    }

    @Test fun `replaying intro from Me does not force a home tab change`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Intro)
        navigation.finishIntro()
        assertEquals(AppTab.ME, navigation.state.value.tab)
        assertEquals(AppRoute.Me, navigation.state.value.current)
    }

    @Test fun `first run intro still reaches home`() {
        val navigation = HomeNavigation(NavState(stack = listOf(AppRoute.Home, AppRoute.Intro)))
        navigation.finishIntro()
        assertEquals(NavState(forward = false), navigation.state.value)
    }

    @Test fun `changing help topics keeps one help screen and preserves deep report opener`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.REPORT)
        val report = AppRoute.ReportDay(LocalDate.of(2026, 11, 28), 7)
        navigation.push(report)
        navigation.openHelp(null)
        navigation.openHelp(HelpTopic.CALENDAR)
        assertEquals(3, navigation.state.value.stack.size)
        navigation.back()
        assertEquals(report, navigation.state.value.current)
        assertEquals(AppTab.REPORT, navigation.state.value.tab)
    }

    @Test fun `a completed save in a parked tab does not pop a new alarm screen`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Homes)
        navigation.push(AppRoute.HomeSetup)
        // A notification can switch away while the save is awaiting a response.
        navigation.selectTab(AppTab.TRIP)
        navigation.push(AppRoute.AlarmDecision(19))
        navigation.close { it == AppRoute.HomeSetup }
        assertEquals(AppRoute.AlarmDecision(19), navigation.state.value.current)
        assertEquals(AppTab.TRIP, navigation.state.value.tab)
        navigation.selectTab(AppTab.ME)
        assertEquals(AppRoute.Homes, navigation.state.value.current)
    }

    @Test fun `replayed guide finishing after a tab change leaves the new screen untouched`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.openHelp(null)
        navigation.push(AppRoute.Intro)
        navigation.selectTab(AppTab.HOME)
        navigation.push(AppRoute.AlarmDecision(2))
        navigation.finishIntro()
        assertEquals(AppRoute.AlarmDecision(2), navigation.state.value.current)
        navigation.selectTab(AppTab.ME)
        assertEquals(AppRoute.Help(), navigation.state.value.current)
    }

    @Test fun `a completed operation below a newer screen only removes itself`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Settings)
        navigation.push(AppRoute.VerifyEmail)
        navigation.openHelp(null)
        navigation.close { it == AppRoute.VerifyEmail }
        assertEquals(AppRoute.Help(), navigation.state.value.current)
        navigation.back()
        assertEquals(AppRoute.Settings, navigation.state.value.current)
    }

    @Test fun `logout discards all parked screens and rejects late account callbacks`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.REPORT)
        navigation.push(AppRoute.ReportDay(LocalDate.of(2026, 11, 28), 7))
        navigation.selectTab(AppTab.ME)
        navigation.push(AppRoute.Settings)
        navigation.endSession()
        navigation.push(AppRoute.VerifyEmail)
        navigation.openHelp(null)
        navigation.selectTab(AppTab.REPORT)
        navigation.update { it.copy(stack = listOf(AppRoute.Home, AppRoute.HomeSetup)) }
        assertEquals(NavState(), navigation.state.value)
    }

    @Test fun `closing absent routes and root screens preserves the current tab`() {
        val navigation = HomeNavigation()
        navigation.selectTab(AppTab.ME)
        val before = navigation.state.value
        navigation.close { it == AppRoute.HomeSetup || it == AppRoute.Me }
        assertEquals(before, navigation.state.value)
    }
}
