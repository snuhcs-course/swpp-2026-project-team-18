package com.swpp.wakeup.ui.nav

/**
 * `MainActivity` 안의 화면 경로.
 *
 * **Navigation Compose 를 쓰지 않는다.** 화면이 다섯이고 한 줄로 파고드는
 * 구조라 스택 리스트와 `BackHandler` 로 충분하다. 의존성을 늘리는 것보다
 * 이쪽이 가볍다.
 *
 * Figma ⑥~⑪ 이 붙으면 분기가 생긴다. 그때 Navigation Compose 로 옮긴다.
 * 옮기기 쉽게 경로를 sealed 타입으로 두고 이동은 전부
 * [com.swpp.wakeup.ui.home.HomeViewModel] 의 push/pop 을 지나게 했다.
 */
sealed interface AppRoute {

    /** Figma ③ 홈 · 일정 목록 */
    data object Home : AppRoute

    /** Figma ④ 알람 결정 */
    data class AlarmDecision(val eventId: Long) : AppRoute

    /** Figma ⑤ 리스크 선택 */
    data class RiskChoice(val eventId: Long) : AppRoute

    /** Figma ⑫ 일정 추가 */
    data object AddEvent : AppRoute

    /**
     * Figma ⑬ 경로 선택. ⑫ 에서 장소를 고른 뒤 진입한다.
     *
     * 일정이 아직 저장되지 않은 상태라 `eventId` 가 없다. 선택 결과는
     * `AddState.routeKey` 에 담아 두고 일정 생성 요청에 함께 보낸다.
     */
    data object RouteChoice : AppRoute

    /** 집 위치 설정. 알람 계산의 출발지라서 없으면 계산이 안 된다 */
    data object HomeSetup : AppRoute
}

/**
 * 화면 스택과 마지막 이동 방향.
 *
 * [forward] 를 들고 다니는 이유는 전환 애니메이션의 방향을 정해야 하기
 * 때문이다. 파고들 때와 되돌아올 때 같은 방향으로 밀면 어색하다.
 */
data class NavState(
    val stack: List<AppRoute> = listOf(AppRoute.Home),
    val forward: Boolean = true,
) {
    val current: AppRoute get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
}
