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

    /**
     * Figma ⑭ 집 주소. 알람 계산의 출발지라서 없으면 계산이 안 된다.
     *
     * 두 곳에서 들어온다 — 가입 직후 온보딩과 [Settings] 의 "집 주소 변경".
     * 문구만 다르고 하는 일은 같아서 경로를 나누지 않았다.
     */
    data object HomeSetup : AppRoute

    /**
     * Figma ⑯ 설정.
     *
     * 가입할 때 한 번 정한 값(집 주소·준비 시간)을 고칠 수 있는 유일한 곳이다.
     * 예전에는 아바타를 누르면 읽기 전용 다이얼로그가 떴고, 그래서 집을 한 번
     * 저장한 뒤에는 바꿀 길이 없었다.
     */
    data object Settings : AppRoute

    /**
     * Figma ⑮ 준비 시간. 가입 직후 평소 외출 준비 시간을 받는다.
     *
     * **알람 시각의 시작값이라서 아무 값이나 넣어 둘 수 없다.** 전원에게 30분을
     * 주면 그 30분이 각자의 실제 준비 시간과 무관해서, 관측이 쌓이기 전까지
     * 알람이 누구에게는 이르고 누구에게는 늦는다. 한 번 물어보는 것으로 그
     * 구간을 없앤다.
     *
     * 프로필의 `onboarding_prep_min` 이 null 인 동안만 띄운다. 건너뛰어도
     * 앱은 쓸 수 있고(추정기가 30분으로 떨어진다), 다음에 열 때 다시 묻는다.
     */
    data object PrepOnboarding : AppRoute

    /**
     * 아침 루틴 블록 **정의** 편집.
     *
     * 여기서 고친 것은 설정 변경이라 서버가 즉시 재계산하지 않는다. 다음
     * 계산에 반영되고, 당장 보고 싶으면 알람 결정 화면의 재계산을 쓴다.
     */
    data object RoutineEditor : AppRoute

    /**
     * 일정 하나의 블록 체크.
     *
     * [RoutineEditor] 와 목록은 같지만 저장 대상이 다르다 — 이쪽은 "이 아침에
     * 뭘 할지" 라서 저장하면 **그 일정만 즉시 재계산**된다.
     */
    data class EventBlocks(val eventId: Long) : AppRoute

    /**
     * 기기 캘린더에서 일정 가져오기.
     *
     * **사용자가 고른 것만 서버로 보낸다.** 캘린더에는 일정 제목이 들어 있고
     * 그건 민감할 수 있다. 자동 동기화를 두지 않은 이유다.
     */
    data object CalendarImport : AppRoute

    /**
     * Figma ⑨ 주간 리포트.
     *
     * **앱을 변호하는 화면이 아니다.** 앱이 말한 확률과 실제 정시율을 나란히
     * 놓아 과신하고 있는지 사용자가 직접 보게 한다.
     */
    data object WeeklyReport : AppRoute

    /**
     * 아침 기록. 알람을 해제하면 여기로 온다.
     *
     * **준비 시간 학습의 유일한 입구다.** 신고 범위("샤워 12~18분")는 분포의
     * 사전값일 뿐이고, 여기서 실제 소요가 쌓여야 평균이 움직인다.
     */
    data object MorningProgress : AppRoute
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
