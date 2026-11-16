// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.ui.nav.AppRoute

/*
 * 일정 추가·경로 선택의 실패를 화면 어디에 둘지, 받은 후보를 ⑬ 에 쓸지, 받아 둔 후보를 버린 뒤 ⑬ 을 어떻게 할지
 * 정하는 순수 함수.
 *
 * `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없다. 판정만
 * 여기로 빼서 `RouteChoiceRulesTest` 가 본다. 저장 실패의 줄 나누기는 `EventSaveErrors.kt`.
 *
 * 규칙(출발·도착 50m 등)은 서버가 소유한다. 앱은 미리 검사하지 않고 숫자도 두지
 * 않는다. 서버가 `error.details` 에 붙인 필드 이름으로 줄만 고른다.
 */

/**
 * 경로 후보 실패를 "다시 시도" 로 풀 수 있는가.
 *
 * 가까운 출발·도착(400 `details.destination`)은 다시 불러도 같은 400 이다. 장소를
 * 바꿔야 풀리므로 false. 그 밖(네트워크·5xx·429·집 없음 등)은 지금처럼 다시 시도.
 */
internal fun candidatesRetryable(failure: EventRepository.Result.Failure): Boolean =
    failure.fields["destination"] == null

/**
 * 후보를 다시 받은 뒤에도 미리보기를 열어 둘지. 열려 있던 후보가 새 목록에 없으면 닫는다.
 */
internal fun previewKeyAfterReload(previewKey: String?, options: List<RouteOption>): String? =
    previewKey?.takeIf { key -> options.any { it.key == key } }

// AI-generated (Claude)
/**
 * 경로 후보 응답을 지금 ⑬ 상태([state])에 써도 되는가. [requestGen] 은 그 요청을 보낼 때의 세대,
 * [currentGen] 은 지금 세대다(`HomeViewModel.routeChoiceGeneration`).
 *
 * - 상태가 null 이면 쓰지 않는다. null 은 "받아 둔 후보 없음" 이다 — 도착지 · 출발지를 바꾸거나 다른 일정을
 *   열 때 일부러 비운 것이다.
 * - 세대가 다르면 그 뒤에 캐시를 비웠거나 새로 요청한 것이다. 마지막 요청의 응답만 쓴다.
 */
internal fun candidatesApply(state: HomeViewModel.RouteState?, requestGen: Long, currentGen: Long): Boolean =
    state != null && requestGen == currentGen

// AI-generated (Claude)
/**
 * 받은 후보 [choice] 를 쓴 ⑬ 상태. 쓸 수 없는 응답([candidatesApply])이면 그대로 돌려준다.
 *
 * 예전에는 늦게 온 응답이 비운 상태를 새로 만들었다(출발지 null = 집). 다음에 ⑬ 을 열면 이전 도착지의 후보가
 * 받아 둔 것처럼 보였고, "이 경로로 계산" 은 다른 구간의 경로를 넣으며 고른 출발지를 집으로 바꿨다.
 *
 * @param keepKey 이미 고른 경로. 새 후보에 있으면 고른 채로 둔다. null 이면 서버가 고른 것을 쓴다
 */
internal fun HomeViewModel.RouteState?.withCandidates(
    requestGen: Long,
    currentGen: Long,
    choice: RouteChoice,
    keepKey: String?,
): HomeViewModel.RouteState? {
    val state = this ?: return null
    if (!candidatesApply(state, requestGen, currentGen)) return state
    val kept = choice.copy(
        selectedKey = keepKey?.takeIf { key -> choice.options.any { it.key == key } } ?: choice.selectedKey,
    )
    return state.copy(
        loading = false,
        error = null,
        choice = kept,
        previewKey = previewKeyAfterReload(state.previewKey, kept.options),
    )
}

// AI-generated (Claude)
/** 후보를 받지 못한 ⑬ 상태. 쓸 수 없는 응답이면 그대로 둔다([withCandidates] 와 같은 규칙) */
internal fun HomeViewModel.RouteState?.withCandidatesFailure(
    requestGen: Long,
    currentGen: Long,
    failure: EventRepository.Result.Failure,
): HomeViewModel.RouteState? {
    val state = this ?: return null
    if (!candidatesApply(state, requestGen, currentGen)) return state
    return state.copy(loading = false, error = failure.message, retryable = candidatesRetryable(failure))
}

// --- 집이 옮겨졌을 때 -------------------------------------------------------------

// AI-generated (Claude)
/**
 * 계정의 집이 옮겨졌을 때 받아 둔 ⑬ 후보 [cache] 를 버려야 하는가. [formChanged] 는 지금 폼이 새 집에 맞춰
 * 바뀌었는가다([afterHomeMoved]). 받아 둔 후보가 없으면 버릴 것도 없다.
 *
 * 폼이 그대로여도 집에서 출발하는 후보(출발지 null)는 버린다. 서버가 그때의 계정 집에서 만든 목록이다. 경로를 고르지
 * 않은 집 출발 폼은 지울 경로가 없어 집이 옮겨져도 그대로라, 폼만 보면 예전 집의 목록이 남았다 — 다음 ⑬ 이 그 목록을
 * 바로 보였고, 거기서 고른 경로는 새 집에서 출발하는 서버가 지키지 못했다. 집이 옮겨져도 그대로 두는 일정 수정 폼의
 * 집 출발 후보도 같다.
 */
internal fun routeCacheStaleAfterHomeMove(cache: HomeViewModel.RouteState?, formChanged: Boolean): Boolean =
    cache != null && (formChanged || cache.origin == null)

// AI-generated (Claude)
/** 받아 둔 ⑬ 후보를 비운 뒤 지금 폼의 ⑬ 을 어떻게 할지([routeChoiceAfterClear]) */
internal enum class RouteChoiceAfterClear {
    /** 지금 폼의 ⑬ 이 스택에 없다. 다음에 열 때 새로 받는다 */
    NONE,

    /** 지금 폼으로 다시 받는다 */
    RELOAD,

    /** 지금 폼으로는 경로를 고를 수 없다(저장 중 등). ⑬ 을 닫는다 */
    CLOSE,
}

// AI-generated (Claude)
/**
 * 받아 둔 ⑬ 후보를 비운 뒤 지금 폼 [add] 의 ⑬ 을 어떻게 할지. [stack] 은 지금 화면 스택이다.
 *
 * ⑬ 은 비운 상태(null)를 "받는 중" 으로 그린다. 다시 시도가 없고 응답도 오지 않아, 열린 채로 비우면 사용자가 뒤로 갈
 * 때까지 스피너만 돌았다. 그래서 지금 폼으로 경로를 고를 수 있으면([HomeViewModel.AddState.canPickRoute]) 다시 받고,
 * 고를 수 없으면(저장 중 등) 닫는다.
 *
 * ⑬ 은 폼 바로 위에 쌓인다. 그 밑이 지금 폼(일정 추가 · 그 일정의 수정)일 때만 지금 폼의 ⑬ 이다. 알림이 연 화면
 * 밑에 깔려 있어도 같다 — 뒤로 와서 다시 보일 때 스피너가 아니어야 한다. 밑이 다른 폼이면(일정 수정이 쓰던 폼을 맡기고
 * 열림) 그 ⑬ 을 지금 폼으로 채우지 않는다.
 */
internal fun routeChoiceAfterClear(
    stack: List<AppRoute>,
    add: HomeViewModel.AddState,
    hasHome: Boolean,
): RouteChoiceAfterClear {
    val at = stack.lastIndexOf(AppRoute.RouteChoice)
    val form = add.editingEventId?.let { AppRoute.EditEvent(it) } ?: AppRoute.AddEvent
    if (at < 1 || stack[at - 1] != form) return RouteChoiceAfterClear.NONE
    return if (add.canPickRoute(hasHome)) RouteChoiceAfterClear.RELOAD else RouteChoiceAfterClear.CLOSE
}
