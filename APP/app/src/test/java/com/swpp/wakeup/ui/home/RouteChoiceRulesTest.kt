// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.ui.nav.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로 후보 실패의 다시 시도 여부와 미리보기 정리.
 *
 * 규칙(50m)은 서버가 소유한다. 여기서는 서버가 붙인 필드 이름으로 줄만 고른다.
 */
class RouteChoiceRulesTest {

    private val message = "출발지와 도착지는 50m 이상 떨어져 있어야 한다."

    private fun failure(fields: Map<String, String>) =
        EventRepository.Result.Failure("서버 문구", fields)

    @Test
    fun `가까운 출발·도착은 다시 시도로 풀리지 않는다`() {
        assertFalse(candidatesRetryable(failure(mapOf("destination" to message))))
        assertTrue("네트워크·5xx 등은 지금처럼 다시 시도", candidatesRetryable(failure(emptyMap())))
    }

    @Test
    fun `다시 받은 목록에 없는 후보의 미리보기는 닫는다`() {
        val walk = RouteOption("walk", "도보", 18, "18분", "1.2km", null)
        val car = RouteOption("car", "자동차", 9, "9분", "4.3km", null)

        assertEquals("walk", previewKeyAfterReload("walk", listOf(walk, car)))
        assertNull(previewKeyAfterReload("bicycle", listOf(walk, car)))
        assertNull(previewKeyAfterReload(null, listOf(walk)))
    }

    // --- 늦게 온 후보 (AI-generated (Claude)) ----------------------------------

    private val walkOption = RouteOption("walk", "도보", 18, "18분", "1.2km", null)
    private val transitOption = RouteOption("transit:2호선", "지하철+도보", 23, "23분", "2호선", null)

    /** 신림역 → 제2공학관 후보. 서버는 도보를 골라 준다 */
    private val choice = RouteChoice(
        originLabel = "신림역",
        destination = "제2공학관",
        options = listOf(walkOption, transitOption),
        selectedKey = "walk",
    )

    /** ⑬ 을 열어 받는 중인 상태. 출발지는 일정 추가에서 고른 신림역이다 */
    private val loading = HomeViewModel.RouteState(
        loading = true,
        origin = PlaceSearchItem(
            kakaoPlaceId = "21160",
            name = "신림역",
            address = null,
            lat = 37.4842,
            lng = 126.9297,
            category = null,
        ),
    )

    @Test
    fun `비운 경로 캐시에는 늦게 온 후보를 쓰지 않는다`() {
        // Given 받는 동안 도착지를 바꿔 캐시를 비웠다(null)
        val cleared: HomeViewModel.RouteState? = null

        // When 이전 도착지의 응답이 늦게 온다
        val next = cleared.withCandidates(requestGen = 1, currentGen = 1, choice = choice, keepKey = null)

        // Then 비운 그대로다. 출발지가 집(null)인 새 상태를 만들지 않는다
        assertNull(next)
        assertFalse(candidatesApply(cleared, requestGen = 1, currentGen = 1))
    }

    @Test
    fun `이전 요청의 응답은 지금 상태를 바꾸지 않는다`() {
        // Given 다시 열어 새로 받는 중이다(세대 2)
        val state = loading

        // When 세대 1 의 응답이 늦게 온다
        val next = state.withCandidates(requestGen = 1, currentGen = 2, choice = choice, keepKey = null)

        // Then
        assertSame(state, next)
    }

    @Test
    fun `마지막 요청의 응답이면 후보를 쓰고 고른 경로를 지킨다`() {
        // When 이미 고른 대중교통이 새 후보에 있다
        val kept = loading.withCandidates(requestGen = 2, currentGen = 2, choice = choice, keepKey = "transit:2호선")

        // Then 고른 것이 그대로 골라져 있고 출발지는 일정 추가의 값이다
        assertFalse(kept!!.loading)
        assertEquals("transit:2호선", kept.choice?.selectedKey)
        assertEquals(loading.origin, kept.origin)

        // When 고른 경로가 새 후보에 없다 → 서버가 고른 것
        val fallback = loading.withCandidates(requestGen = 2, currentGen = 2, choice = choice, keepKey = "car")
        assertEquals("walk", fallback?.choice?.selectedKey)
    }

    @Test
    fun `늦게 온 실패도 버린다`() {
        val tooClose = failure(mapOf("destination" to message))

        // 비운 캐시 · 이전 세대는 그대로
        assertNull((null as HomeViewModel.RouteState?).withCandidatesFailure(1, 1, tooClose))
        assertSame(loading, loading.withCandidatesFailure(requestGen = 1, currentGen = 2, failure = tooClose))

        // 마지막 요청의 실패면 이유를 쓰고, 가까운 출발·도착은 다시 시도를 두지 않는다
        val failed = loading.withCandidatesFailure(requestGen = 2, currentGen = 2, failure = tooClose)
        assertEquals("서버 문구", failed?.error)
        assertEquals(false, failed?.retryable)
        assertEquals(false, failed?.loading)
    }

    // --- 집이 옮겨졌을 때 (STAT-2, AI-generated (Claude)) ------------------------

    /** 출발지가 집(null)인 폼에서 받아 둔 후보. 서버가 그때의 계정 집에서 만든 목록이다 */
    private val homeList = HomeViewModel.RouteState(loading = false, choice = choice, origin = null)

    @Test
    fun `집이 옮겨지면 집에서 출발하는 후보는 폼이 그대로여도 버린다`() {
        // Given 경로를 고르지 않은 집 출발 폼이다. 지울 경로가 없어 집이 옮겨져도 폼은 그대로다

        // When / Then 예전 집에서 만든 목록이라 버린다. 남기면 다음 ⑬ 이 예전 집의 경로를 바로 보인다
        assertTrue(routeCacheStaleAfterHomeMove(homeList, formChanged = false))
        val loadingHomeList = homeList.copy(loading = true, choice = null)
        assertTrue("받는 중인 집 출발 후보도 버린다", routeCacheStaleAfterHomeMove(loadingHomeList, formChanged = false))
    }

    @Test
    fun `집과 무관한 후보는 폼이 새 집에 맞춰 바뀌었을 때만 버린다`() {
        // Given 신림역에서 출발하는 후보
        val sillimList = loading.copy(loading = false, choice = choice)

        // When / Then
        assertFalse(routeCacheStaleAfterHomeMove(sillimList, formChanged = false))
        assertTrue("[집] 도착지가 새 집을 가리키게 됐다", routeCacheStaleAfterHomeMove(sillimList, formChanged = true))
        assertFalse("받아 둔 후보가 없으면 버릴 것이 없다", routeCacheStaleAfterHomeMove(null, formChanged = true))
    }

    /** ⑬ 을 연 일정 추가 폼. 출발지는 집이고 도착지가 있다 */
    private val addForm = HomeViewModel.AddState().copy(
        title = "수업",
        selectedPlace = PlaceSearchItem(
            kakaoPlaceId = "8123",
            name = "서울대학교 제2공학관",
            address = null,
            lat = 37.4488,
            lng = 126.9527,
            category = null,
        ),
    )

    private val routeOnTop = listOf(AppRoute.Home, AppRoute.AddEvent, AppRoute.RouteChoice)

    @Test
    fun `맨 위의 ⑬ 후보를 비웠으면 지금 폼으로 다시 받는다`() {
        // Given ⑬ 을 열어 둔 채 다른 기기에서 집을 바꿔, 돌아온 새로고침이 후보를 비웠다

        // When / Then 끝나지 않는 스피너 대신 새 집 기준 후보를 받는다
        assertEquals(RouteChoiceAfterClear.RELOAD, routeChoiceAfterClear(routeOnTop, addForm, hasHome = true))
    }

    @Test
    fun `경로를 고를 수 없는 폼이면 ⑬ 을 닫는다`() {
        // Given ⑬ 에서 실패 스낵바의 [다시 시도] 를 눌러 저장하는 중이다
        val saving = addForm.copy(submitting = true)

        // When / Then 다시 받을 수 없으니 스피너를 두지 않고 폼으로 돌아간다
        assertEquals(RouteChoiceAfterClear.CLOSE, routeChoiceAfterClear(routeOnTop, saving, hasHome = true))
    }

    @Test
    fun `알림이 연 화면 밑에 깔린 지금 폼의 ⑬ 도 다시 받는다`() {
        // Given ⑬ 위에 알림이 다른 일정의 알람 결정 화면을 열었다
        val stack = routeOnTop + AppRoute.AlarmDecision(9)

        // When / Then 뒤로 와서 ⑬ 이 다시 보일 때 스피너가 아니어야 한다
        assertEquals(RouteChoiceAfterClear.RELOAD, routeChoiceAfterClear(stack, addForm, hasHome = true))
        assertEquals(RouteChoiceAfterClear.CLOSE, routeChoiceAfterClear(stack, addForm.copy(submitting = true), hasHome = true))
    }

    @Test
    fun `일정 수정 폼의 ⑬ 도 같다`() {
        // Given 일정 9 의 수정 폼에서 ⑬ 을 열었다
        val editing = addForm.copy(editingEventId = 9L)
        val stack = listOf(AppRoute.Home, AppRoute.AlarmDecision(9), AppRoute.EditEvent(9), AppRoute.RouteChoice)

        // When / Then
        assertEquals(RouteChoiceAfterClear.RELOAD, routeChoiceAfterClear(stack, editing, hasHome = true))
    }

    @Test
    fun `지금 폼의 ⑬ 이 스택에 없으면 아무것도 하지 않는다`() {
        // Given ⑬ 을 닫고 폼에 있다
        assertEquals(
            RouteChoiceAfterClear.NONE,
            routeChoiceAfterClear(listOf(AppRoute.Home, AppRoute.AddEvent), addForm, hasHome = true),
        )

        // Given 깔린 ⑬ 위에서 일정 수정을 열어 쓰던 폼을 맡겼다. 그 ⑬ 은 맡긴 폼의 것이다
        val editing = addForm.copy(editingEventId = 9L)
        val stack = routeOnTop + AppRoute.AlarmDecision(9) + AppRoute.EditEvent(9)

        // When / Then 지금 폼(일정 9)으로 받으면 맡긴 폼의 ⑬ 에 다른 일정의 후보가 들어간다
        assertEquals(RouteChoiceAfterClear.NONE, routeChoiceAfterClear(stack, editing, hasHome = true))
    }
}
