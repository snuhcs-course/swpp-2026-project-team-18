// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 같은 끝점을 다시 고르면 고른 경로를 지우지 않는다 · 늦게 온 경로 후보는 쓰지 않는다(fix/iter4-he-bugs 이식, 11-19) */
class AddEndpointsTest {

    private fun place(id: String?, lat: Double, lng: Double, name: String = "곳") =
        PlaceSearchItem(kakaoPlaceId = id, name = name, address = null, lat = lat, lng = lng, category = null)

    @Test
    fun `카카오 id 가 둘 다 있으면 id 로 · 하나라도 없으면 좌표로 · 이름은 보지 않는다`() {
        assertTrue(sameEndpoint(place("1", 37.5, 127.0), place("1", 37.6, 127.1)))
        assertFalse("한 건물의 다른 가게", sameEndpoint(place("1", 37.5, 127.0), place("2", 37.5, 127.0)))
        assertTrue(sameEndpoint(place(null, 37.5, 127.0, "가"), place("1", 37.5, 127.0, "나")))
        assertFalse(sameEndpoint(place(null, 37.5, 127.0), place(null, 37.5001, 127.0)))
        assertTrue("둘 다 집 출발", sameEndpoint(null, null))
        assertFalse(sameEndpoint(null, place(null, 37.5, 127.0)))
    }

    @Test
    fun `같은 도착지 · 출발지를 다시 고른 것을 알아본다`() {
        val dest = place("9", 37.45, 126.95)
        val form = HomeViewModel.AddState(selectedPlace = dest, routeKey = "transit:5513")
        assertTrue(form.sameDestination(place("9", 37.45, 126.95), isHome = false))
        assertFalse("같은 좌표라도 집 버튼은 다른 도착지", form.sameDestination(dest, isHome = true))
        assertTrue(form.sameOrigin(null))
        assertFalse("저장한 집과의 연결이 달라짐", form.sameOrigin(null, placeId = 3))
    }

    @Test
    fun `마지막 요청의 응답만 쓰고 비운 자리는 다시 채우지 않는다`() {
        assertTrue(candidatesApply(cleared = false, requestGen = 4, currentGen = 4))
        assertFalse(candidatesApply(cleared = false, requestGen = 3, currentGen = 4))
        assertFalse(candidatesApply(cleared = true, requestGen = 4, currentGen = 4))
    }

    // --- 집이 옮겨졌을 때 -----------------------------------------------------

    private val home = place("h", 37.4800, 126.9500, "집")
    private val movedHome = place("h", 37.5172, 127.0473, "새집")
    private val parents = place(null, 37.5600, 126.9200, "본가")
    private val parentsAsHome = place("h", parents.lat, parents.lng, "본가")
    private val campus = place("c", 37.4590, 126.9520, "학교")

    private fun routed() = HomeViewModel.AddState(
        selectedPlace = campus,
        routeKey = "transit:2호선",
        routeLabel = "23분 · 지하철+도보",
        fieldErrors = mapOf(AddField.TITLE to "제목 오류", AddField.ROUTE to "경로 오류", AddField.ORIGIN to "출발 오류"),
    )

    @Test
    fun `집에서 출발하는 초안은 집이 옮겨지면 고른 경로를 버린다`() {
        val next = routed().afterHomeMoved(movedHome)
        assertNull(next.routeKey)
        assertNull(next.routeLabel)
        assertEquals(campus, next.selectedPlace)
        assertEquals(mapOf(AddField.TITLE to "제목 오류"), next.fieldErrors)
    }

    @Test
    fun `집 도착 초안은 새 집을 가리키고 경로를 버린다`() {
        val draft = routed().copy(origin = parents, originLabel = "본가", selectedPlace = home, destinationIsHome = true)
        val next = draft.afterHomeMoved(movedHome)
        assertEquals(movedHome, next.selectedPlace)
        assertTrue(next.destinationIsHome)
        assertEquals("새집", next.place.query)
        assertNull(next.routeKey)
        assertEquals(parents, next.origin)
    }

    @Test
    fun `집과 무관한 초안은 그대로다`() {
        val draft = routed().copy(origin = campus, selectedPlace = parents)
        assertSame(draft, draft.afterHomeMoved(movedHome))
    }

    @Test
    fun `기본으로 바꾼 저장한 집 출발은 준비 시간이 있으면 집 출발 · 없으면 연결만 끊는다`() {
        val withPrep = routed().copy(origin = parents, originLabel = "본가", originPlaceId = 7L, includePrep = true)
        val a = withPrep.afterHomeMoved(parentsAsHome)
        assertNull(a.origin)
        assertNull(a.originPlaceId)
        assertFalse(a.includePrep)
        assertEquals("transit:2호선", a.routeKey)

        val noPrep = routed().copy(origin = parents, originPlaceId = 7L, includePrep = false)
        val b = noPrep.afterHomeMoved(parentsAsHome)
        assertEquals(parents, b.origin)
        assertNull(b.originPlaceId)
        assertEquals("transit:2호선", b.routeKey)

        assertSame("다른 자리의 저장한 집", withPrep, withPrep.afterHomeMoved(movedHome))
    }

    @Test
    fun `집이 옮겨졌는지는 좌표로 보고 받아 둔 집 출발 후보는 버린다`() {
        assertTrue(homeMoved(home, movedHome))
        assertFalse(homeMoved(home, home.copy(name = "우리 집")))
        assertFalse("앞의 집을 모름(처음 받음)", homeMoved(null, home))
        assertFalse(homeMoved(home, null))

        assertTrue(routeCacheStaleAfterHomeMove(HomeViewModel.RouteState(origin = null), formChanged = false))
        assertFalse(routeCacheStaleAfterHomeMove(HomeViewModel.RouteState(origin = parents), formChanged = false))
        assertTrue(routeCacheStaleAfterHomeMove(HomeViewModel.RouteState(origin = parents), formChanged = true))
        assertFalse(routeCacheStaleAfterHomeMove(null, formChanged = true))
    }
}
