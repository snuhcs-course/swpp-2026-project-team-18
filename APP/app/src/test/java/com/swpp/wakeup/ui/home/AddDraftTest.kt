// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.nav.AppRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 쓰다 만 일정 판정(A4)과 `client_request_id`(A5), 프로세스 보관함, 저장을 마친 폼과 같이 닫을 화면. */
class AddDraftTest {
    private val place = PlaceSearchItem(
        name = "서울대학교",
        address = "관악로 1",
        lat = 37.46,
        lng = 126.95,
        kakaoPlaceId = "8123",
        category = "대학교",
    )

    @After
    fun tearDown() = AddDraftHolder.clear()

    // --- 판정 -------------------------------------------------------------

    @Test
    fun `아무것도 쓰지 않은 폼은 초안이 아니다`() {
        assertFalse(HomeViewModel.AddState().isUnsavedDraft)
    }

    @Test
    fun `날짜 시각 종류만 바꾼 것은 초안이 아니다`() {
        val s = HomeViewModel.AddState().copy(hour = 14, minute = 30, tagKey = "work")
        assertFalse(s.isUnsavedDraft)
    }

    @Test
    fun `제목 도착지 출발지 경로 반복 요일 중 하나라도 있으면 초안이다`() {
        val base = HomeViewModel.AddState()
        assertTrue(base.copy(title = "수업").isUnsavedDraft)
        assertTrue(base.copy(selectedPlace = place).isUnsavedDraft)
        assertTrue(base.copy(origin = place).isUnsavedDraft)
        assertTrue(base.copy(routeKey = "transit:2호선").isUnsavedDraft)
        assertTrue(base.copy(repeatWeekdays = setOf(1)).isUnsavedDraft)
    }

    @Test
    fun `공백 제목은 초안이 아니다`() {
        assertFalse(HomeViewModel.AddState().copy(title = "   ").isUnsavedDraft)
    }

    @Test
    fun `수정 중이거나 저장을 마친 폼은 초안이 아니다`() {
        val s = HomeViewModel.AddState().copy(title = "수업")
        assertFalse(s.copy(editingEventId = 3L).isUnsavedDraft)
        assertFalse(s.copy(done = true).isUnsavedDraft)
    }

    // --- "일정 추가" 를 눌렀을 때 (AI-generated (Claude)) ------------------------

    @Test
    fun `저장 중인 폼은 이어 쓰기로 내놓지 않고 열지 않는다`() {
        // Given 실패한 일정을 떠난 뒤 홈에서 스낵바의 다시 시도로 저장이 돌고 있다
        val saving = HomeViewModel.AddState().copy(title = "회의", submitting = true)

        // When / Then 이어 쓰면 늦게 온 결과가 고치던 폼을 닫고 비운다. 묻지도 새로 열지도 않는다
        assertEquals(AddEntry.WAIT, addEntryOf(saving, pending = null))
        assertEquals(AddEntry.WAIT, addEntryOf(saving, pending = HomeViewModel.AddState().copy(title = "수업")))
    }

    @Test
    fun `수정 저장 중에도 새 폼을 열지 않는다`() {
        // 새 폼이 수정 폼을 갈아 끼우면 수정 결과가 새 폼을 닫는다
        val savingEdit = HomeViewModel.AddState().copy(editingEventId = 5L, title = "회의", submitting = true)
        assertEquals(AddEntry.WAIT, addEntryOf(savingEdit, pending = null))
    }

    @Test
    fun `쓰다 만 일정이 있으면 이어 쓸지 묻는다`() {
        assertEquals(AddEntry.ASK_RESUME, addEntryOf(HomeViewModel.AddState().copy(title = "회의"), pending = null))
        // 일정 수정이 맡겨 둔 초안
        val editing = HomeViewModel.AddState().copy(editingEventId = 5L, title = "고치는 중")
        assertEquals(AddEntry.ASK_RESUME, addEntryOf(editing, pending = HomeViewModel.AddState().copy(title = "수업")))
    }

    @Test
    fun `쓴 것이 없으면 새 폼을 연다`() {
        assertEquals(AddEntry.NEW, addEntryOf(HomeViewModel.AddState(), pending = null))
        assertEquals(AddEntry.NEW, addEntryOf(HomeViewModel.AddState().copy(title = "저장함", done = true), pending = null))
    }

    @Test
    fun `저장 중인 폼도 로그인이 끝날 때 맡길 초안으로는 남는다`() {
        // 401 로 화면이 끝나면 onCleared 가 이 폼을 맡긴다(A4). 막는 것은 "일정 추가" 쪽이다
        assertTrue(HomeViewModel.AddState().copy(title = "회의", submitting = true).isUnsavedDraft)
    }

    // --- 저장을 마친 폼과 같이 닫을 화면 (AI-generated (Claude)) ------------------

    @Test
    fun `폼 위에 그 폼이 연 경로 선택이나 지도만 있으면 같이 닫는다`() {
        // Given 실패한 일정 추가 · 수정에서 ⑬ · 지도를 연 채 스낵바의 다시 시도를 눌렀다
        val add = listOf(AppRoute.Home, AppRoute.AddEvent)
        val edit = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))

        // When 저장이 끝나 폼을 닫는다
        // Then 그 화면도 같이 닫는다 — 남으면 비워진 폼을 읽어 ⑬ 은 스피너로 남고 지도는 빈 폼에 장소를 넣는다
        assertEquals(
            listOf(AppRoute.RouteChoice),
            addFormChildrenAbove(add + AppRoute.RouteChoice, AppRoute.AddEvent, mapTarget = null),
        )
        assertEquals(
            listOf(AppRoute.MapPick),
            addFormChildrenAbove(add + AppRoute.MapPick, AppRoute.AddEvent, HomeViewModel.MapTarget.DESTINATION),
        )
        assertEquals(
            listOf(AppRoute.MapPick),
            addFormChildrenAbove(edit + AppRoute.MapPick, AppRoute.EditEvent(5), HomeViewModel.MapTarget.ORIGIN),
        )
        assertEquals(
            listOf(AppRoute.RouteChoice),
            addFormChildrenAbove(edit + AppRoute.RouteChoice, AppRoute.EditEvent(5), mapTarget = null),
        )
    }

    @Test
    fun `폼 위에 상관없는 화면이 있으면 같이 닫지 않는다`() {
        // Given 다시 시도를 누른 뒤 알림이 다른 화면을 위에 열었거나, 폼에서 집 주소를 열었다
        val add = listOf(AppRoute.Home, AppRoute.AddEvent)
        val overDeepLink = add + AppRoute.RouteChoice + AppRoute.AlarmDecision(9)

        // When 저장이 끝나 폼을 닫는다
        // Then 같이 닫을 것이 없다 — 위 화면은 두고 폼만 뺀다(NavState.close)
        assertTrue(addFormChildrenAbove(add + AppRoute.AlarmDecision(9), AppRoute.AddEvent, null).isEmpty())
        assertTrue(addFormChildrenAbove(overDeepLink, AppRoute.AddEvent, null).isEmpty())
        assertTrue("집 주소는 제 폼이 있다", addFormChildrenAbove(add + AppRoute.HomeSetup, AppRoute.AddEvent, null).isEmpty())
        assertTrue(
            "집 주소의 지도",
            addFormChildrenAbove(
                add + AppRoute.HomeSetup + AppRoute.MapPick,
                AppRoute.AddEvent,
                HomeViewModel.MapTarget.HOME,
            ).isEmpty(),
        )
        assertTrue(
            "지도 상태가 없으면 폼이 연 지도인지 모른다",
            addFormChildrenAbove(add + AppRoute.MapPick, AppRoute.AddEvent, mapTarget = null).isEmpty(),
        )
    }

    @Test
    fun `폼이 맨 위거나 스택에 없으면 같이 닫을 화면이 없다`() {
        // Given 폼에서 저장했거나, 폼을 떠나 다른 화면에서 다시 시도를 눌렀다
        val onForm = listOf(AppRoute.Home, AppRoute.AddEvent)
        val otherEdit = listOf(AppRoute.Home, AppRoute.AlarmDecision(9), AppRoute.EditEvent(9), AppRoute.RouteChoice)

        // When / Then 폼이 맨 위면 그 폼만 닫고, 다른 폼이 연 화면은 이 폼의 것이 아니다
        assertTrue(addFormChildrenAbove(onForm, AppRoute.AddEvent, null).isEmpty())
        assertTrue(addFormChildrenAbove(listOf(AppRoute.Home, AppRoute.RouteChoice), AppRoute.AddEvent, null).isEmpty())
        assertTrue(addFormChildrenAbove(otherEdit, AppRoute.EditEvent(5), null).isEmpty())
    }

    // --- 맡기는 사본 · 현재 위치 재기 (AI-generated (Claude)) -------------------

    @Test
    fun `맡기는 사본은 저장 중 실패 안내 현재 위치 재기를 내린다`() {
        // Given 현재 위치를 재는 중에 일정 수정이 이 폼을 맡긴다
        val notice = HomeViewModel.AddFailureNotice(id = 1L, message = "실패", retry = true)
        val live = HomeViewModel.AddState().copy(
            title = "수업",
            originLocating = true,
            submitting = true,
            failureNotice = notice,
        )

        // When
        val stashed = live.forStash()

        // Then 이어 쓰면 출발지 줄이 "현재 위치를 확인하는 중" 으로 막힌 채 남지 않는다. 입력은 그대로다
        assertFalse(stashed.originLocating)
        assertFalse(stashed.submitting)
        assertNull(stashed.failureNotice)
        assertEquals("수업", stashed.title)
        assertEquals("같은 일정이다", live.clientRequestId, stashed.clientRequestId)
    }

    @Test
    fun `현재 위치를 재는 동안에는 저장과 경로 고르기를 막는다`() {
        // Given 제목과 도착지를 넣고 현재 위치를 재고 있다
        val locating = HomeViewModel.AddState().copy(title = "수업", selectedPlace = place, originLocating = true)

        // When / Then 옛 출발지로 저장되거나 곧 바뀔 출발지로 경로를 고르지 않는다
        assertFalse(locating.canSubmit)
        assertFalse(locating.canPickRoute(hasHome = true))
        assertTrue(locating.copy(originLocating = false).canSubmit)
        assertTrue(locating.copy(originLocating = false).canPickRoute(hasHome = true))
    }

    // --- 저장 버튼이 꺼진 이유 (AI-generated (Claude)) --------------------------

    @Test
    fun `제목이 비면 저장 버튼 위에 이유를 말한다`() {
        // Given 다른 칸은 다 채웠지만 제목이 비었다
        val blank = HomeViewModel.AddState().copy(title = "  ", selectedPlace = place)

        // When / Then 일정 추가와 일정 수정의 버튼 이름에 맞춘다
        assertFalse(blank.canSubmit)
        assertEquals("일정 제목을 입력하면 추가할 수 있어요", blank.submitHint)
        assertEquals("일정 제목을 입력하면 저장할 수 있어요", blank.copy(editingEventId = 5L).submitHint)
    }

    @Test
    fun `현재 위치를 재는 중이면 그 이유를 말한다`() {
        val locating = HomeViewModel.AddState().copy(title = "수업", originLocating = true)
        assertEquals("현재 위치를 확인한 뒤에 추가할 수 있어요", locating.submitHint)
    }

    @Test
    fun `누를 수 있거나 저장 중이면 이유를 말하지 않는다`() {
        // 저장 중에는 버튼이 스피너로 바뀐다. 이유 줄이 없어야 한다
        val ready = HomeViewModel.AddState().copy(title = "수업")
        assertTrue(ready.canSubmit)
        assertNull(ready.submitHint)
        assertNull(ready.copy(submitting = true).submitHint)
        assertNull(HomeViewModel.AddState().copy(submitting = true).submitHint)
    }

    @Test
    fun `버튼이 꺼졌으면 저장 중이 아닌 한 늘 이유가 있다`() {
        // Given 제목 · 현재 위치 재기 · 수정 여부의 모든 조합
        for (title in listOf("", "수업")) for (locating in listOf(false, true)) for (editing in listOf(null, 5L)) {
            val s = HomeViewModel.AddState().copy(title = title, originLocating = locating, editingEventId = editing)

            // Then 이유 없이 꺼진 버튼이 없다
            assertEquals("title=$title locating=$locating editing=$editing", !s.canSubmit, s.submitHint != null)
        }
    }

    // --- client_request_id --------------------------------------------------

    @Test
    fun `새 폼마다 다른 client_request_id 이고 copy 는 유지한다`() {
        val a = HomeViewModel.AddState()
        val b = HomeViewModel.AddState()
        assertNotEquals(a.clientRequestId, b.clientRequestId)
        val edited = a.copy(title = "고침", submitting = true, error = "x")
        assertEquals("다시 시도·입력 고침은 같은 값", a.clientRequestId, edited.clientRequestId)
    }

    @Test
    fun `client_request_id 는 서버 규칙 8에서 64자 영숫자와 하이픈이다`() {
        val id = HomeViewModel.AddState().clientRequestId
        assertTrue(id.length in 8..64)
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]+")))
    }

    // --- 보관함 -------------------------------------------------------------

    @Test
    fun `같은 계정이면 한 번 돌려주고 비운다`() {
        val draft = HomeViewModel.AddState().copy(title = "수업")
        AddDraftHolder.put("User@Snu.ac.kr", draft)
        assertSame(draft, AddDraftHolder.take(" user@snu.ac.kr "))
        assertNull("두 번째는 없다", AddDraftHolder.take("user@snu.ac.kr"))
    }

    @Test
    fun `다른 계정이 열면 주지 않고 지운다`() {
        AddDraftHolder.put("a@snu.ac.kr", HomeViewModel.AddState().copy(title = "수업"))
        assertNull(AddDraftHolder.take("b@snu.ac.kr"))
        assertNull("남의 초안이 남아 있지 않다", AddDraftHolder.take("a@snu.ac.kr"))
    }

    @Test
    fun `소유자를 모르면 맡기지도 돌려주지도 않는다`() {
        AddDraftHolder.put(null, HomeViewModel.AddState().copy(title = "수업"))
        assertNull(AddDraftHolder.take(null))
        AddDraftHolder.put("a@snu.ac.kr", HomeViewModel.AddState().copy(title = "수업"))
        assertNull("지금 계정을 모르면 내놓지 않는다", AddDraftHolder.take(null))
    }

    @Test
    fun `clear 는 보관한 초안을 버린다`() {
        AddDraftHolder.put("a@snu.ac.kr", HomeViewModel.AddState().copy(title = "수업"))
        AddDraftHolder.clear()
        assertNull(AddDraftHolder.take("a@snu.ac.kr"))
    }
}
