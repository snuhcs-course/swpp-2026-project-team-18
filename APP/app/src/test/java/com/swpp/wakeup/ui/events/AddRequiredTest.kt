// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.home.AddField
import com.swpp.wakeup.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 알람 추가(H2-8 `545:192`)의 필수 칸 규칙 */
class AddRequiredTest {

    private fun place(name: String) = PlaceSearchItem(
        kakaoPlaceId = "id-$name",
        name = name,
        address = "서울 관악구",
        lat = 37.46,
        lng = 126.95,
        category = "",
    )

    private val empty = HomeViewModel.AddState()
    private val full = empty.copy(
        selectedPlace = place("서울대 301동"),
        timePicked = true,
        routeKey = "walk",
        routeLabel = "23분 · 도보",
    )

    @Test
    fun `아무것도 없으면 도착지 시각 경로가 남는다`() {
        assertEquals(
            listOf(AddRequired.DESTINATION, AddRequired.TIME, AddRequired.ROUTE),
            AddRequiredRule.missing(empty, hasHome = true),
        )
    }

    @Test
    fun `집도 출발지도 없으면 출발지까지 4개이고 출발지를 고르면 3개`() {
        assertEquals(
            listOf(AddRequired.ORIGIN, AddRequired.DESTINATION, AddRequired.TIME, AddRequired.ROUTE),
            AddRequiredRule.missing(empty, hasHome = false),
        )
        assertEquals(3, AddRequiredRule.missing(empty.copy(origin = place("친구 집")), hasHome = false).size)
    }

    @Test
    fun `하나씩 채우면 하나씩 줄고 다 채우면 비어 있다`() {
        var s = empty
        assertEquals(3, AddRequiredRule.missing(s, true).size)
        s = s.copy(selectedPlace = place("서울대 301동"))
        assertEquals(2, AddRequiredRule.missing(s, true).size)
        s = s.copy(timePicked = true)
        assertEquals(listOf(AddRequired.ROUTE), AddRequiredRule.missing(s, true))
        s = s.copy(routeKey = "walk")
        assertTrue(AddRequiredRule.missing(s, true).isEmpty())
        assertTrue(s.canSubmitAdd(hasHome = true))
    }

    @Test
    fun `선택 칸만 채우면 버튼이 꺼져 있다`() {
        val s = empty.copy(title = "수업", repeatWeekdays = setOf(0, 2), tagKey = "class")
        assertFalse(s.canSubmitAdd(hasHome = true))
    }

    @Test
    fun `경로를 고른 뒤 도착지를 바꿔 경로가 지워지면 다시 꺼진다`() {
        assertTrue(full.canSubmitAdd(true))
        val changed = full.copy(selectedPlace = place("강남역"), routeKey = null, routeLabel = null)
        assertFalse(changed.canSubmitAdd(true))
        assertEquals(listOf(AddRequired.ROUTE), AddRequiredRule.missing(changed, true))
    }

    @Test
    fun `저장 중이거나 줄 오류가 있으면 꺼진다`() {
        assertFalse(full.copy(submitting = true).canSubmitAdd(true))
        assertFalse(full.copy(fieldErrors = mapOf(AddField.ORIGIN to "50m 이상")).canSubmitAdd(true))
    }

    @Test
    fun `새 일정은 시각을 고르기 전과 종류를 고르기 전이 기본이다`() {
        assertFalse(empty.timePicked)
        assertNull(empty.tagKey)
    }

    @Test
    fun `안내 문구`() {
        assertNull(AddRequiredRule.hint(emptyList()))
        assertEquals("이동 경로만 정하면 돼요", AddRequiredRule.hint(listOf(AddRequired.ROUTE)))
        assertEquals(
            "도착지 · 도착 시각 · 이동 경로를 정해 주세요",
            AddRequiredRule.hint(AddRequiredRule.missing(empty, true)),
        )
        assertEquals(
            "출발지 · 도착지를 정해 주세요",
            AddRequiredRule.hint(listOf(AddRequired.ORIGIN, AddRequired.DESTINATION)),
        )
        assertEquals(
            "출발지 · 도착 시각을 정해 주세요",
            AddRequiredRule.hint(listOf(AddRequired.ORIGIN, AddRequired.TIME)),
        )
    }

    @Test
    fun `다른 날 칩 글자`() {
        val today = LocalDate.of(2026, 9, 18)
        assertEquals("다른 날", otherDateLabel(today, today))
        assertEquals("다른 날", otherDateLabel(today.plusDays(1), today))
        assertEquals("9월 20일 일", otherDateLabel(LocalDate.of(2026, 9, 20), today))
        assertEquals("10월 5일 월", otherDateLabel(LocalDate.of(2026, 10, 5), today))
    }
}
