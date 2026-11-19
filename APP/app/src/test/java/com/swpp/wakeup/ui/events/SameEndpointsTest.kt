// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.home.AddField
import com.swpp.wakeup.ui.home.DestinationUndo
import com.swpp.wakeup.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 출발지 = 도착지 막기와 집으로 바꾼 뒤 되돌리기(H2-9, Figma ⑫ 변형 `546:1822`) */
class SameEndpointsTest {

    private fun place(name: String, lat: Double, lng: Double, kakao: String? = null) =
        PlaceSearchItem(kakaoPlaceId = kakao, name = name, address = null, lat = lat, lng = lng, category = null)

    private val home = place("신림동 서원9길", 37.4800, 126.9300)
    private val snu = place("302동 105호", 37.4490, 126.9520, "k1")
    private val ready = HomeViewModel.AddState(timePicked = true, routeKey = "walk", routeLabel = "42분 · 도보")

    @Test
    fun `도착지나 출발지가 없으면 경고하지 않는다`() {
        assertFalse(SameEndpoints.same(HomeViewModel.AddState(), home))
        assertFalse(SameEndpoints.same(HomeViewModel.AddState(selectedPlace = snu), null))
    }

    @Test
    fun `둘 다 집이면 같다`() {
        val s = ready.copy(selectedPlace = home, destinationIsHome = true)
        assertTrue(SameEndpoints.same(s, home))
        // 집 좌표를 아직 못 받았어도 둘 다 집인 것은 안다
        assertTrue(SameEndpoints.same(s, null))
        // 출발지를 따로 골랐으면 집 도착은 정상(퇴근)
        assertFalse(SameEndpoints.same(s.copy(origin = snu), home))
    }

    @Test
    fun `50m 안이면 같고 밖이면 다르다`() {
        val near = place("집 앞 편의점", 37.48030, 126.93000) // 약 33m
        val far = place("신림역", 37.48420, 126.92970) // 약 470m
        assertTrue(SameEndpoints.same(ready.copy(selectedPlace = near), home))
        assertFalse(SameEndpoints.same(ready.copy(selectedPlace = far), home))
    }

    @Test
    fun `카카오 장소 id 가 같으면 같다`() {
        val s = ready.copy(origin = snu, selectedPlace = snu.copy(lat = 37.0, lng = 127.0))
        assertTrue(SameEndpoints.same(s, home))
    }

    @Test
    fun `같으면 버튼이 꺼지고 안내 줄은 이 이유가 먼저다`() {
        val same = ready.copy(selectedPlace = home, destinationIsHome = true)
        assertFalse(same.canSubmitAdd(hasHome = true, home = home))
        assertTrue(ready.copy(selectedPlace = snu).canSubmitAdd(hasHome = true, home = home))
        assertEquals(AddRequiredRule.SAME_HINT, AddRequiredRule.hint(listOf(AddRequired.TIME), sameEndpoints = true))
        assertEquals("도착 시각만 정하면 돼요", AddRequiredRule.hint(listOf(AddRequired.TIME), sameEndpoints = false))
        // 경로 고르기도 막는다
        assertFalse(same.canPickRoute(hasHome = true, home = home))
    }

    @Test
    fun `일정 수정도 같은 규칙으로 막는다`() {
        val editing = ready.copy(title = "퇴근", editingEventId = 3, selectedPlace = home, destinationIsHome = true)
        assertTrue(editing.canSubmit)
        assertFalse(editing.canSubmitEdit(home))
        assertTrue(editing.copy(selectedPlace = snu, destinationIsHome = false).canSubmitEdit(home))
    }

    @Test
    fun `집으로 바꾸면 바꾸기 전 값을 보관하고 되돌리면 한꺼번에 되살린다`() {
        val before = ready.copy(selectedPlace = snu, place = HomeViewModel.PlaceSearch(query = "302동"))
        val undo = requireNotNull(DestinationUndo.capture(before, id = 1))
        assertEquals("도착지를 집으로 바꿨어요.\n고른 경로도 지웠어요", undo.message)

        val changed = before.copy(
            selectedPlace = home, destinationIsHome = true, routeKey = null, routeLabel = null,
            destinationUndo = undo, fieldErrors = mapOf(AddField.TITLE to "x"),
        )
        val restored = DestinationUndo.restore(changed)
        assertEquals(snu, restored.selectedPlace)
        assertFalse(restored.destinationIsHome)
        assertEquals("walk", restored.routeKey)
        assertEquals("42분 · 도보", restored.routeLabel)
        assertEquals("302동", restored.place.query)
        assertNull(restored.destinationUndo)
        assertEquals(DestinationUndo.SHOW_MS, 6_000L)
    }

    @Test
    fun `되돌릴 것이 없으면 보관하지 않는다`() {
        assertNull(DestinationUndo.capture(HomeViewModel.AddState(), 1))
        assertNull(DestinationUndo.capture(ready.copy(selectedPlace = home, destinationIsHome = true), 1))
        val noRoute = requireNotNull(DestinationUndo.capture(HomeViewModel.AddState(selectedPlace = snu), 2))
        assertEquals("도착지를 집으로 바꿨어요.", noRoute.message)
        val s = HomeViewModel.AddState(selectedPlace = snu)
        assertEquals(s, DestinationUndo.restore(s))
    }
}
