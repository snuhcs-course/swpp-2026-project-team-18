package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 목록과 Compose 마커가 한 선택 상태를 공유하는지 고정한다. */
class MapPickStateTest {

    private fun place(lat: Double, lng: Double, name: String = "가게") = PlaceSearchItem(
        kakaoPlaceId = name,
        name = name,
        address = "주소",
        lat = lat,
        lng = lng,
        category = null,
    )

    private fun state(
        center: GeoPoint,
        selected: PlaceSearchItem?,
        level: Int = StaticMapScale.DEFAULT_LEVEL,
    ) = HomeViewModel.MapPickState(center = center, selected = selected, level = level)

    // --- 선택 마커 --------------------------------------------------------

    @Test
    fun `카카오 id가 같으면 같은 선택 마커다`() {
        val selected = place(37.4783, 126.9516, "12345")
        val sameId = place(37.5000, 127.0000, "12345")
        assertTrue(state(GeoPoint(37.48, 126.95), selected).isSelected(sameId))
    }

    @Test
    fun `다른 카카오 id는 선택되지 않는다`() {
        val selected = place(37.4783, 126.9516, "12345")
        val other = place(37.4783, 126.9516, "67890")
        assertFalse(state(GeoPoint(37.48, 126.95), selected).isSelected(other))
    }

    @Test
    fun `선택이 없으면 어떤 마커도 선택되지 않는다`() {
        assertFalse(
            state(GeoPoint(37.4783, 126.9516), null)
                .isSelected(place(37.4783, 126.9516)),
        )
    }

    @Test
    fun `영역 재검색에도 같은 장소가 있으면 사용자 선택을 유지한다`() {
        val first = place(37.47, 126.94, "1")
        val picked = place(37.48, 126.95, "4")
        val next = listOf(first, picked, place(37.49, 126.96, "5"))

        val replaced = state(GeoPoint(37.48, 126.95), picked)
            .copy(markers = listOf(picked))
            .withSearchResults(next)

        assertTrue(replaced.isSelected(picked))
        assertEquals(next, replaced.markers)
    }

    @Test
    fun `영역 재검색이 0건이면 선택도 비운다`() {
        val picked = place(37.48, 126.95, "4")
        val replaced = state(GeoPoint(37.48, 126.95), picked)
            .copy(markers = listOf(picked))
            .withSearchResults(emptyList())

        assertTrue(replaced.markers.isEmpty())
        assertTrue(replaced.selected == null)
    }

    // --- 이 장소로 선택 (AI-generated (Claude)) ---------------------------------

    @Test
    fun `미리 골라 둔 지금 값을 그대로 확인하면 바뀌지 않는다`() {
        // Given 도착지가 집이라 지도가 집을 1번 핀으로 골라 둔 채 열렸다(집은 카카오 id 가 없다)
        val home = PlaceSearchItem(
            kakaoPlaceId = null,
            name = "우리집",
            address = null,
            lat = 37.4781,
            lng = 126.9515,
            category = null,
        )

        // When / Then 그대로 확인하면 집 표시와 경로를 지키려고 값을 다시 넣지 않는다
        assertFalse(mapPickChangesValue(current = home, picked = home))
        assertFalse(mapPickChangesValue(current = home, picked = home.copy()))
        // 검색한 장소는 카카오 id 로 같은 곳이다
        assertFalse(mapPickChangesValue(place(37.48, 126.95, "12345"), place(37.48, 126.95, "12345")))
    }

    @Test
    fun `다른 핀을 고르면 바뀐다`() {
        val current = place(37.4783, 126.9516, "12345")
        assertTrue(mapPickChangesValue(current, place(37.4783, 126.9516, "67890")))
        // 값이 없던 칸(집 출발 · 아직 없는 도착지)에 고르면 늘 바뀐다
        assertTrue(mapPickChangesValue(current = null, picked = current))
    }

    // AI-generated (Claude)
    @Test
    fun `집 핀 대신 같은 자리의 검색한 장소 핀을 고르면 바뀐다`() {
        // Given 도착지가 집이라 지도가 집을 1번 핀으로 열었다. 집은 그 아파트를 검색해 정해서 좌표가 똑같다
        val home = PlaceSearchItem(
            kakaoPlaceId = null,
            name = "우리집",
            address = null,
            lat = 37.4781,
            lng = 126.9515,
            category = null,
        )
        val apartment = place(home.lat, home.lng, "5501")

        // When / Then 지도는 두 핀을 따로 그린다. 다른 핀을 골랐으니 넣는다 — 집이 아닌 도착지가 된다
        assertTrue(mapPickChangesValue(current = home, picked = apartment))
    }

    // --- 줌 한계 ----------------------------------------------------------

    @Test
    fun `최대로 확대하면 더 확대할 수 없다`() {
        val s = state(GeoPoint(37.0, 127.0), null, level = StaticMapScale.MIN_LEVEL)
        assertFalse(s.canZoomIn)
        assertTrue(s.canZoomOut)
    }

    @Test
    fun `최대로 축소하면 더 축소할 수 없다`() {
        val s = state(GeoPoint(37.0, 127.0), null, level = StaticMapScale.MAX_LEVEL)
        assertTrue(s.canZoomIn)
        assertFalse(s.canZoomOut)
    }
}
