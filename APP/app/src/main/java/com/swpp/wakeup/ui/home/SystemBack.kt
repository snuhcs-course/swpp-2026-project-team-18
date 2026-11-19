// AI-generated (Claude) — fix/iter4-he-bugs 의 같은 이름 파일을 지금 화면 구성에 맞춰 옮겼다(11-19)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState

/**
 * 시스템 뒤로(가장자리 밀기 · 3버튼)가 할 일. `MainActivity` 의 BackHandler 가 [systemBackOf] 를 따른다.
 *
 * **화면 왼쪽 위 ‹ 와 같은 일을 한다.** 예전에는 시스템 뒤로가 늘 한 칸 닫기였다. 그래서 ‹ 가 꺼진 저장 중에도 화면을
 * 떠났고(늦게 온 결과가 사용자가 보던 다른 화면을 닫았다), ‹ 가 "나중에" 인 캘린더 연동 · 준비 시간은 답한 것으로
 * 남지 않아 다음 실행에 다시 떴고, 지도는 정리 없이 닫혔다.
 */
internal enum class SystemBack {
    /** 서버를 기다리는 중이다. ‹ 가 꺼진 것과 같이 움직이지 않는다. 받아서 버리므로 앱도 닫히지 않는다 */
    STAY,

    /** 블록 편집 폼만 닫는다. 폼은 같은 경로 안에서 겹쳐 그려 스택에 없다 */
    CLOSE_BLOCK_DRAFT,

    /** 경로 지도 미리보기만 닫고 경로 목록으로 돌아간다(⑬ 안에서 겹쳐 그림) */
    CLOSE_ROUTE_PREVIEW,

    /** 캘린더 연동의 "나중에" 와 같다. 이 기기 · 계정에서는 다시 묻지 않는다 */
    SKIP_CALENDAR_LINK,

    /** 준비 시간의 "나중에 입력" 과 같다 */
    SKIP_PREP,

    /** 지도 화면의 ‹ 와 같다. 지도 상태를 정리하고 닫는다 */
    CLOSE_MAP,

    /** 한 칸 닫는다(홈이 아닌 탭의 첫 화면이면 홈 탭으로, [NavState.back]) */
    POP,

    /** 홈 하나뿐이다. 앱이 받지 않고 시스템 기본 동작(앱을 뒤로 보냄)에 맡긴다 */
    SYSTEM,
}

/**
 * 지금 화면에서 시스템 뒤로가 할 일. 순서가 뜻을 가진다 — 기다리는 중([SystemBack.STAY])을 가장 먼저 본다. 저장
 * 중인 블록 폼을 닫으면 안 된다. 블록 폼 · 경로 미리보기는 그 화면이 맨 위일 때만 닫는다.
 *
 * @param waiting 맨 위 화면이 서버를 기다리는 중인가(그 화면의 ‹ 가 꺼지는 조건과 같다)
 */
internal fun systemBackOf(
    nav: NavState,
    waiting: Boolean,
    blockDraftOpen: Boolean,
    routePreviewOpen: Boolean,
): SystemBack {
    val top = nav.current
    val onRoutine = top == AppRoute.RoutineEditor || top is AppRoute.EventBlocks
    return when {
        waiting -> SystemBack.STAY
        onRoutine && blockDraftOpen -> SystemBack.CLOSE_BLOCK_DRAFT
        top == AppRoute.RouteChoice && routePreviewOpen -> SystemBack.CLOSE_ROUTE_PREVIEW
        !nav.handlesBack -> SystemBack.SYSTEM
        top == AppRoute.CalendarLink -> SystemBack.SKIP_CALENDAR_LINK
        top == AppRoute.PrepOnboarding -> SystemBack.SKIP_PREP
        top == AppRoute.MapPick -> SystemBack.CLOSE_MAP
        else -> SystemBack.POP
    }
}

/**
 * 맨 위 화면이 서버를 기다리는 중인가. 각 화면이 ‹ 를 끄는 조건과 같다. 새 일정 추가는 홈 위 시트라 시트가 직접 막는다.
 */
internal fun waitingOn(
    top: AppRoute,
    editSubmitting: Boolean,
    homeSetupSubmitting: Boolean,
    prepSubmitting: Boolean,
    calendarImporting: Boolean,
    routineSaving: Boolean,
): Boolean = when (top) {
    is AppRoute.EditEvent -> editSubmitting
    AppRoute.HomeSetup -> homeSetupSubmitting
    AppRoute.PrepOnboarding -> prepSubmitting
    AppRoute.CalendarImport -> calendarImporting
    AppRoute.RoutineEditor, is AppRoute.EventBlocks -> routineSaving
    else -> false
}
