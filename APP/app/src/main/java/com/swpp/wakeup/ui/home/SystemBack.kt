// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState

/**
 * 시스템 뒤로(가장자리 밀기 · 3버튼)가 할 일. `MainActivity` 의 BackHandler 가 [systemBackOf] 를 따른다.
 *
 * **화면 왼쪽 위 ‹ 와 같은 일을 한다.** 예전에는 시스템 뒤로가 늘 한 칸 닫기였다. 그래서 ‹ 가 꺼진
 * 저장 중에도 화면을 떠났고(늦게 온 결과가 사용자가 보던 다른 화면을 닫았다), ‹ 가 "나중에" 인
 * ⑱ 캘린더 연동은 답한 것으로 남지 않아 다음 실행에 다시 떴고, 지도는 정리 없이 닫혔다.
 */
internal enum class SystemBack {
    /** 서버를 기다리는 중이다. ‹ 가 꺼진 것과 같이 움직이지 않는다. 받아서 버리므로 앱도 닫히지 않는다 */
    STAY,

    /** 블록 편집 폼만 닫는다. 폼은 같은 경로 안에서 겹쳐 그려 스택에 없다 */
    CLOSE_BLOCK_DRAFT,

    /** 경로 지도 미리보기만 닫고 경로 목록으로 돌아간다(⑬ 안에서 겹쳐 그림) */
    CLOSE_ROUTE_PREVIEW,

    /** ⑱ 의 ‹ · "나중에" 와 같다. 이 기기 · 계정에서는 다시 묻지 않는다 */
    SKIP_CALENDAR_LINK,

    /** ⑮ 의 ‹ · "나중에 입력" 과 같다(설정에서 열었으면 저장하지 않고 닫기) */
    SKIP_PREP,

    /** 지도 화면의 ‹ 와 같다. 지도 상태를 정리하고 닫는다 */
    CLOSE_MAP,

    /** 한 칸 닫는다 */
    POP,

    /** 홈 하나뿐이다. 앱이 받지 않고 시스템 기본 동작(앱을 뒤로 보냄)에 맡긴다 */
    SYSTEM,
}

/**
 * 지금 화면에서 시스템 뒤로가 할 일.
 *
 * 순서가 뜻을 가진다.
 * - 기다리는 중([STAY][SystemBack.STAY])을 가장 먼저 본다. 저장 중인 블록 폼을 닫으면 안 된다.
 * - 블록 폼 · 경로 미리보기는 **그 화면이 맨 위일 때만** 닫는다. 폼이 열린 채 알람 해제 · 알림이
 *   다른 화면을 위에 쌓으면, 그 화면에서의 뒤로가 보이지 않는 폼을 닫느라 한 번 헛돌고 쓰던 값이
 *   사라졌다. 이제는 위 화면이 닫히고, 돌아가면 폼이 그대로 있다.
 *
 * @param routeChoice ⑬ 경로 선택 상태. 미리보기가 열렸는지 본다
 */
internal fun systemBackOf(
    nav: NavState,
    add: HomeViewModel.AddState,
    homeSetup: HomeViewModel.HomeSetupState,
    prep: HomeViewModel.PrepOnboardingState,
    calendarImport: CalendarImportState,
    routine: RoutineEditorState,
    routeChoice: HomeViewModel.RouteState?,
): SystemBack {
    val top = nav.current
    val onRoutine = top == AppRoute.RoutineEditor || top is AppRoute.EventBlocks
    // 각 화면이 ‹ 를 끄는 조건과 같다(ScreenHeader(enabled = …)).
    val waiting = when (top) {
        AppRoute.AddEvent, is AppRoute.EditEvent -> add.submitting
        AppRoute.HomeSetup -> homeSetup.submitting
        AppRoute.PrepOnboarding -> prep.submitting
        AppRoute.CalendarImport -> calendarImport.importing
        AppRoute.RoutineEditor, is AppRoute.EventBlocks -> routine.saving
        else -> false
    }
    return when {
        waiting -> SystemBack.STAY
        onRoutine && routine.editing != null -> SystemBack.CLOSE_BLOCK_DRAFT
        top == AppRoute.RouteChoice && routeChoice?.previewKey != null -> SystemBack.CLOSE_ROUTE_PREVIEW
        !nav.canGoBack -> SystemBack.SYSTEM
        top == AppRoute.CalendarLink -> SystemBack.SKIP_CALENDAR_LINK
        top == AppRoute.PrepOnboarding -> SystemBack.SKIP_PREP
        top == AppRoute.MapPick -> SystemBack.CLOSE_MAP
        else -> SystemBack.POP
    }
}
