package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.help.HelpTopic
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.AppTab
import com.swpp.wakeup.ui.nav.NavState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account-scoped navigation policy, independent of Android storage and API calls.
 *
 * Settings and help are pushed onto their opener's stack. Completing an operation
 * closes its route rather than selecting Home, including when a notification has
 * temporarily switched tabs while a save was in flight.
 */
internal class HomeNavigation(initial: NavState = NavState()) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<NavState> = mutable.asStateFlow()
    private var ended = false

    /** Form-specific transitions still go through the same account lifetime. */
    @Synchronized
    fun update(change: (NavState) -> NavState) {
        if (!ended) mutable.value = change(mutable.value)
    }

    fun push(route: AppRoute) = update { it.push(route) }
    fun back() = update { it.back() }
    fun home() = update { it.home() }
    fun selectTab(tab: AppTab) = update { it.selectTab(tab) }

    /** Opening another FAQ while in Help replaces the topic, not its opener. */
    fun openHelp(topic: HelpTopic?) = update { nav ->
        val base = if (nav.current is AppRoute.Help) nav.stack.dropLast(1) else nav.stack
        nav.copy(stack = base + AppRoute.Help(topic), forward = true)
    }

    /**
     * A replay returns to Settings/Help/Me; first-run Intro already has Home below
     * it. A pending Home coachmark can wait until Home is actually visited.
     */
    fun finishIntro() = close { it == AppRoute.Intro }

    /** Close the completed screen without discarding a newer screen or tab. */
    fun close(match: (AppRoute) -> Boolean) = update { nav ->
        val active = nav.close(match)
        val parked = active.tabStacks.mapValues { (tab, routes) ->
            if (routes.isEmpty()) routes else NavState(stack = routes, tab = tab).close(match).stack
        }
        if (parked == active.tabStacks) active else active.copy(tabStacks = parked)
    }

    /** Old account callbacks cannot restore a screen after logout. */
    @Synchronized
    fun endSession() {
        ended = true
        mutable.value = NavState()
    }
}
