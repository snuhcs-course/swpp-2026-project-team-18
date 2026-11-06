// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 쓰다 만 일정 판정(A4)과 `client_request_id`(A5), 프로세스 보관함. */
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
