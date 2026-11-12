package com.swpp.wakeup.ui.nav

import com.swpp.wakeup.ui.help.HelpTopic
import java.time.LocalDate

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
 *
 * 하단 탭(iter4 H2-2)도 같은 방식이다. 탭마다 스택을 하나씩 두고([NavState.tabStacks]) 지금 탭의
 * 스택만 [NavState.stack] 으로 보인다. 그래서 기존 push/pop 코드는 탭을 몰라도 된다.
 */
sealed interface AppRoute {

    /** Figma ③ 홈 · 일정 목록 */
    data object Home : AppRoute

    // Partly AI-generated (Claude)
    /**
     * 하단 탭 "이동"의 첫 화면(Figma `545:1080`). 다음 이동 카드와 "가는 길" 타임라인. 카드를 누르면
     * ④ 알람 결정을 이 탭 안에서 연다(이동 중 진행 카드는 아직 ④ 에 있다).
     */
    data object Trip : AppRoute

    /** Figma ④ 알람 결정 */
    data class AlarmDecision(val eventId: Long) : AppRoute

    /** Figma ⑤ 리스크 선택 */
    data class RiskChoice(val eventId: Long) : AppRoute

    /** Figma ⑫ 일정 추가 */
    data object AddEvent : AppRoute

    /**
     * Figma ⑫ 변형 · 일정 수정(`488:9`). ④ 의 "일정 수정" 에서 들어온다. 화면은 ⑫ 을 수정
     * 모드로 다시 쓴다(`AddState.editingEventId`). 팀이 다른 구성을 고르면 이 경로만 바꾼다.
     */
    data class EditEvent(val eventId: Long) : AppRoute

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
     * 두 곳에서 들어온다 — 가입 직후 온보딩과 [Homes] 의 "기본 집" [변경].
     * 문구만 다르고 하는 일은 같아서 경로를 나누지 않았다.
     */
    data object HomeSetup : AppRoute

    /**
     * Figma ⑰ 지도에서 고르기.
     *
     * 목적지·집 주소·출발지 검색이 모두 여기로 들어온다. 어느 검색을 위한
     * 것인지는 `HomeViewModel.MapPickState.target` 이 들고 있다 — 경로에
     * 담으면 같은 화면이 세 개로 갈라진다.
     *
     * **지도 SDK 를 쓰지 않는다.** 서버가 카카오 정적 지도 이미지를 만들어
     * 주므로 네이티브 앱 키를 APK 에 넣고 서명 키 해시를 등록할 필요가 없다.
     */
    data object MapPick : AppRoute

    // Partly AI-generated (Claude)
    /**
     * 내 정보 탭 첫 화면(iter4 H2-2, Figma `547:1244`). "내 아침 · 일정 · 도움 · 계정" 네 묶음의 입구다.
     * 가입할 때 한 번 정한 값(집 주소 · 준비 시간)을 고치는 길도 여기서 시작한다.
     */
    data object Me : AppRoute

    /**
     * 설정(예전 Figma ⑯ 에서 계정 쪽만 남김). 로그인 연결 · 비밀번호 · 이메일 인증 · 로그아웃.
     * 내 정보 탭 › "설정" 줄에서 연다.
     */
    data object Settings : AppRoute

    /** 집 주소(기본 집 + 다른 집, 예전 ⑯ 의 집 칸 · `498:9`). 내 정보 탭 › "집 주소" 줄에서 연다 */
    data object Homes : AppRoute

    /** 알림 · 권한과 지금 상태(예전 ⑯ 의 진단 줄). 내 정보 탭 › "알림·권한" 줄에서 연다 */
    data object Permissions : AppRoute

    /** Figma ⑯ 변형 "비밀번호 변경"(F-41). [Settings] 의 "비밀번호" 줄에서 들어온다 */
    data object ChangePassword : AppRoute

    /** Figma ⑯ 변형 이메일 인증(F-53). [Settings] 의 이메일 인증 줄에서 들어온다. 로그인을 막지 않는다 */
    data object VerifyEmail : AppRoute

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

    // Partly AI-generated (Claude)
    /**
     * Figma ⑱ 캘린더 연동. 가입 직후 ⑮ 준비 시간 다음에 한 번 뜬다.
     *
     * 어느 기기 캘린더를 앱이 읽을지 고른다. 고른 캘린더의 일정은 [CalendarImport]
     * 에 나오고, **그중 고른 것만** 서버로 간다 — 연동했다고 자동으로 올리지 않는다.
     * 건너뛰면 다시 묻지 않고, ⑯ 설정의 스위치로 켜고 끈다.
     */
    data object CalendarLink : AppRoute

    // Partly AI-generated (Claude)
    /**
     * 첫 실행 안내 세 장(iter4 H2-4, Figma `545:1305` · `546:2214` · `547:2009`). 가입 직후 ⑱ 다음, 홈에
     * 들어가기 전에 한 번 뜬다. [건너뛰기] 와 3장의 [시작하기] 는 홈으로 가고, 홈에 처음 들어가면 코치마크가
     * 이어진다. 내 정보 › "처음 안내 다시 보기" 로 다시 연다.
     */
    data object Intro : AppRoute

    // Partly AI-generated (Claude)
    /**
     * 도움말(iter4 H2-4, Figma `551:1820`). 내 정보 › 도움말, 홈 · 이동 · 리포트 오른쪽 위 ?, 화면 곳곳의 i 에서 연다.
     * [expand] 는 펼친 채로 열 질문이다. null 이면 첫 질문(알람 시각)을 펼친다. 하단 탭은 숨긴다(Figma 프레임에
     * 없고, 맨 아래 [처음 안내 다시 보기] 자리와 겹친다).
     */
    data class Help(val expand: HelpTopic? = null) : AppRoute

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
     *
     * 연동한 캘린더([CalendarLink], ⑯ 설정)의 일정만 읽는다. 연동한 것이 없으면 화면
     * 위에 캘린더 스위치를 먼저 보인다. (Partly AI-generated (Claude))
     */
    data object CalendarImport : AppRoute

    /**
     * Figma ⑧ 주간 리포트.
     *
     * **앱을 변호하는 화면이 아니다.** 앱이 말한 확률과 실제 정시율을 나란히
     * 놓아 과신하고 있는지 사용자가 직접 보게 한다.
     */
    data object WeeklyReport : AppRoute

    /**
     * Figma ⑨ 상세 리포트. ⑧ 의 요일 칸이나 지각한 날 줄에서 들어온다.
     *
     * 값은 ⑧ 이 받은 주간 응답에서 찾는다(따로 부르지 않는다). [eventId] 는 처음
     * 고를 일정이고, null 이면 그날 대표 일정이다. (Partly AI-generated (Claude))
     */
    data class ReportDay(val date: LocalDate, val eventId: Long?) : AppRoute

    /**
     * 아침 기록. 알람을 해제하면 여기로 온다.
     *
     * **준비 시간 학습의 유일한 입구다.** 신고 범위("샤워 12~18분")는 분포의
     * 사전값일 뿐이고, 여기서 실제 소요가 쌓여야 평균이 움직인다.
     */
    data object MorningProgress : AppRoute
}

// Partly AI-generated (Claude)
/**
 * 하단 탭 네 개(iter4 H2-2, Figma `540:123` · `545:1080` · `546:1943` · `547:1244`).
 *
 * @property root 탭을 처음 열 때의 화면. 탭 안에서 더 들어간 화면은 그 탭의 스택에 쌓인다
 */
enum class AppTab(val label: String, val root: AppRoute) {
    HOME("홈", AppRoute.Home),
    TRIP("이동", AppRoute.Trip),
    REPORT("리포트", AppRoute.WeeklyReport),
    ME("내 정보", AppRoute.Me),
}

// Partly AI-generated (Claude)
/**
 * 하단 탭 바를 보일 화면. 탭 첫 화면과 그 안에서 둘러보는 화면(④ 알람 결정, ⑨ 상세 리포트, 내 정보의
 * 설정 · 집 주소 · 알림·권한)이다.
 *
 * 입력하는 화면(일정 추가·수정, 경로 선택, 지도, 집 주소, 온보딩, 비밀번호 등)과 아침 기록에서는 숨긴다.
 * 아래에 저장 버튼이 있거나, 하던 일을 끝내야 다른 곳으로 가는 화면이다.
 */
val AppRoute.showsTabBar: Boolean
    get() = when (this) {
        AppRoute.Home, AppRoute.Trip, AppRoute.WeeklyReport, AppRoute.Me,
        AppRoute.Settings, AppRoute.Homes, AppRoute.Permissions,
        is AppRoute.AlarmDecision, is AppRoute.ReportDay,
        -> true
        else -> false
    }

/**
 * 화면 스택과 마지막 이동 방향.
 *
 * [forward] 를 들고 다니는 이유는 전환 애니메이션의 방향을 정해야 하기
 * 때문이다. 파고들 때와 되돌아올 때 같은 방향으로 밀면 어색하다.
 */
data class NavState(
    /** 지금 탭의 스택. 기존 push/pop 은 모두 이것만 고친다 */
    val stack: List<AppRoute> = listOf(AppRoute.Home),
    val forward: Boolean = true,
    /** 지금 보고 있는 하단 탭 (Partly AI-generated (Claude)) */
    val tab: AppTab = AppTab.HOME,
    /**
     * 다른 탭에 두고 온 스택. 탭을 바꿀 때 지금 스택을 여기에 맡기고, 돌아오면 꺼낸다. 그래서 리포트에서
     * 상세로 들어간 채 홈에 다녀와도 상세가 그대로 있다. 지금 탭의 값은 [stack] 이 진짜다.
     */
    val tabStacks: Map<AppTab, List<AppRoute>> = emptyMap(),
) {
    val current: AppRoute get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** 하단 탭 바를 그릴지([showsTabBar]) */
    val showsTabBar: Boolean get() = current.showsTabBar

    /**
     * 시스템 뒤로 가기를 앱이 받을지. 스택이 깊거나, 홈이 아닌 탭의 첫 화면이면 받는다(홈 탭으로 간다).
     * 홈 탭의 첫 화면이면 받지 않아 앱이 닫힌다.
     */
    val handlesBack: Boolean get() = canGoBack || tab != AppTab.HOME

    /** 모든 탭의 스택에 있는 화면. 다른 탭에 열어 둔 리포트를 새로 고칠 때 본다 */
    val allRoutes: List<AppRoute>
        get() = stack + tabStacks.filterKeys { it != tab }.values.flatten()

    // Partly AI-generated (Claude)
    /**
     * [target] 탭으로 간다. 지금 스택은 맡겨 두고, [target] 에 두고 온 스택이 있으면 그대로 연다.
     * 지금 탭을 다시 누르면 그 탭의 첫 화면으로 돌아간다.
     */
    fun selectTab(target: AppTab): NavState {
        if (target == tab) {
            return if (canGoBack) copy(stack = listOf(stack.first()), forward = false) else this
        }
        val saved = tabStacks + (tab to stack)
        return copy(
            stack = saved[target]?.takeIf { it.isNotEmpty() } ?: listOf(target.root),
            forward = target.ordinal > tab.ordinal,
            tab = target,
            tabStacks = saved - target,
        )
    }

    // Partly AI-generated (Claude)
    /** 뒤로 가기. 스택이 깊으면 하나 빼고, 홈이 아닌 탭의 첫 화면이면 홈 탭으로 간다([handlesBack]) */
    fun back(): NavState = when {
        canGoBack -> copy(stack = stack.dropLast(1), forward = false)
        tab != AppTab.HOME -> selectTab(AppTab.HOME).copy(forward = false)
        else -> this
    }

    // Partly AI-generated (Claude)
    /**
     * 홈 탭의 첫 화면으로. 일정을 지운 뒤처럼 하던 일이 끝났을 때 쓴다. 지금 탭은 첫 화면으로 되돌려 두고
     * (지운 일정의 화면이 남지 않게), 다른 탭에 두고 온 스택은 그대로 둔다.
     */
    fun home(): NavState {
        val saved = if (tab == AppTab.HOME) tabStacks else tabStacks + (tab to listOf(tab.root))
        return copy(
            stack = listOf(AppRoute.Home),
            forward = false,
            tab = AppTab.HOME,
            tabStacks = saved - AppTab.HOME,
        )
    }
}
