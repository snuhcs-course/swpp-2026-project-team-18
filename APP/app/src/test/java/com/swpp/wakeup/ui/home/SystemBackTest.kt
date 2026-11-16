package com.swpp.wakeup.ui.home

import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Test

// AI-generated (Claude)
/**
 * 시스템 뒤로(가장자리 밀기 · 3버튼)가 화면의 ‹ 와 같은 일을 하는지. ‹ 가 꺼진 저장 중에는 움직이지
 * 않고, ‹ 가 "나중에" 인 화면에서는 "나중에" 이고, 폼은 그 화면이 맨 위일 때만 닫는다.
 */
class SystemBackTest {

    private fun nav(vararg routes: AppRoute) = NavState(stack = listOf(AppRoute.Home) + routes)

    private fun back(
        nav: NavState,
        add: HomeViewModel.AddState = HomeViewModel.AddState(),
        homeSetup: HomeViewModel.HomeSetupState = HomeViewModel.HomeSetupState(),
        prep: HomeViewModel.PrepOnboardingState = HomeViewModel.PrepOnboardingState(),
        calendarImport: CalendarImportState = CalendarImportState(),
        routine: RoutineEditorState = RoutineEditorState(),
        routeChoice: HomeViewModel.RouteState? = null,
    ) = systemBackOf(nav, add, homeSetup, prep, calendarImport, routine, routeChoice)

    // --- 저장 중에는 움직이지 않는다 ------------------------------------------

    @Test
    fun `수정 저장 중에는 뒤로가 아무것도 닫지 않는다`() {
        // Given 알람 결정 → 일정 수정 → 수정 저장을 눌러 기다리는 중(‹ 가 꺼져 있다)
        val editing = nav(AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))
        val saving = HomeViewModel.AddState().copy(editingEventId = 5, title = "회의", submitting = true)

        // When / Then 저장이 끝나기 전에 뒤로를 밀어도 그대로다(앱도 닫히지 않는다)
        assertEquals(SystemBack.STAY, back(editing, add = saving))
    }

    @Test
    fun `저장 중이라 ‹ 가 꺼지는 화면은 모두 머문다`() {
        assertEquals(
            SystemBack.STAY,
            back(nav(AppRoute.AddEvent), add = HomeViewModel.AddState().copy(title = "수업", submitting = true)),
        )
        assertEquals(
            SystemBack.STAY,
            back(nav(AppRoute.Settings, AppRoute.HomeSetup), homeSetup = HomeViewModel.HomeSetupState(submitting = true)),
        )
        assertEquals(
            SystemBack.STAY,
            back(nav(AppRoute.Settings, AppRoute.PrepOnboarding), prep = HomeViewModel.PrepOnboardingState(submitting = true)),
        )
        assertEquals(
            SystemBack.STAY,
            back(nav(AppRoute.CalendarImport), calendarImport = CalendarImportState(importing = true)),
        )
        assertEquals(SystemBack.STAY, back(nav(AppRoute.RoutineEditor), routine = RoutineEditorState(saving = true)))
        assertEquals(SystemBack.STAY, back(nav(AppRoute.EventBlocks(5)), routine = RoutineEditorState(saving = true)))
    }

    @Test
    fun `다른 화면의 저장은 지금 화면의 뒤로를 막지 않는다`() {
        // Given 실패한 일정 추가를 떠난 뒤 스낵바의 다시 시도로 저장이 돌고 있고, 사용자는 설정에 있다
        val saving = HomeViewModel.AddState().copy(title = "수업", submitting = true)

        // Then 설정은 평소대로 닫히고, 홈에서는 시스템에 맡긴다
        assertEquals(SystemBack.POP, back(nav(AppRoute.Settings), add = saving))
        assertEquals(SystemBack.SYSTEM, back(NavState(), add = saving))
    }

    @Test
    fun `저장 중인 블록 폼은 닫지 않는다`() {
        val routine = RoutineEditorState(saving = true, editing = BlockDraft(name = "샤워"))
        assertEquals(SystemBack.STAY, back(nav(AppRoute.RoutineEditor), routine = routine))
    }

    // --- 블록 폼 · 경로 미리보기는 그 화면이 맨 위일 때만 -----------------------

    @Test
    fun `아침 루틴에서 블록 폼이 열려 있으면 폼만 닫는다`() {
        val routine = RoutineEditorState(editing = BlockDraft(name = "샤워"))
        assertEquals(SystemBack.CLOSE_BLOCK_DRAFT, back(nav(AppRoute.RoutineEditor), routine = routine))
        assertEquals(SystemBack.CLOSE_BLOCK_DRAFT, back(nav(AppRoute.AlarmDecision(5), AppRoute.EventBlocks(5)), routine = routine))
    }

    @Test
    fun `폼이 열린 채 위에 쌓인 화면에서는 그 화면을 닫는다`() {
        // Given 블록 폼을 연 채 알람을 해제해 아침 기록이 위에 쌓였다
        val routine = RoutineEditorState(editing = BlockDraft(name = "샤워"))
        val stack = nav(AppRoute.RoutineEditor, AppRoute.MorningProgress)

        // When / Then 보이지 않는 폼을 닫느라 헛돌지 않고 아침 기록이 닫힌다(폼은 돌아가면 그대로다)
        assertEquals(SystemBack.POP, back(stack, routine = routine))
    }

    @Test
    fun `홈에 남은 블록 폼 값은 뒤로를 붙잡지 않는다`() {
        val routine = RoutineEditorState(editing = BlockDraft(name = "샤워"))
        assertEquals(SystemBack.SYSTEM, back(NavState(), routine = routine))
    }

    @Test
    fun `경로 지도 미리보기가 열려 있으면 미리보기만 닫는다`() {
        val preview = HomeViewModel.RouteState(previewKey = "transit:2호선")
        assertEquals(
            SystemBack.CLOSE_ROUTE_PREVIEW,
            back(nav(AppRoute.AddEvent, AppRoute.RouteChoice), routeChoice = preview),
        )
        assertEquals(SystemBack.POP, back(nav(AppRoute.AddEvent, AppRoute.RouteChoice), routeChoice = HomeViewModel.RouteState()))
    }

    // --- ‹ 가 "나중에" · 정리인 화면 -----------------------------------------

    @Test
    fun `캘린더 연동에서 뒤로는 나중에와 같다`() {
        // Given 가입 직후 ⑱ 캘린더 연동
        val onboarding = nav(AppRoute.CalendarLink)

        // Then ‹ · "나중에" 처럼 다시 묻지 않는다고 적는 쪽으로 간다
        assertEquals(SystemBack.SKIP_CALENDAR_LINK, back(onboarding))
    }

    @Test
    fun `준비 시간에서 뒤로는 나중에 입력과 같다`() {
        assertEquals(SystemBack.SKIP_PREP, back(nav(AppRoute.CalendarLink, AppRoute.PrepOnboarding)))
        assertEquals(
            SystemBack.SKIP_PREP,
            back(nav(AppRoute.Settings, AppRoute.PrepOnboarding), prep = HomeViewModel.PrepOnboardingState(fromSettings = true)),
        )
    }

    @Test
    fun `지도에서 뒤로는 지도의 ‹ 처럼 정리하고 닫는다`() {
        assertEquals(SystemBack.CLOSE_MAP, back(nav(AppRoute.AddEvent, AppRoute.MapPick)))
    }

    @Test
    fun `나머지 화면은 한 칸 닫는다`() {
        assertEquals(SystemBack.POP, back(nav(AppRoute.AlarmDecision(5))))
        assertEquals(SystemBack.POP, back(nav(AppRoute.WeeklyReport)))
        assertEquals(SystemBack.POP, back(nav(AppRoute.Settings, AppRoute.HomeSetup)))
    }
}
