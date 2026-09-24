package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지도 화면 상태의 판단들.
 *
 * 중앙 표식은 "이것을 골랐다" 는 주장이다. 그 주장이 참일 때만 그려야 한다.
 * 실기기에서 영역 재검색 직후 표식이 **아무 가게도 없는 지점**에 놓여 있었고,
 * 화면만 보면 그곳을 고른 것처럼 읽혔다. 끌기·줌·재검색이 중심은 그대로 두고
 * 선택만 바꾸기 때문이다.
 */
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

    // --- 중앙 표식 --------------------------------------------------------

    @Test
    fun `고른 장소가 중앙에 있으면 표식을 그린다`() {
        val here = place(37.4783, 126.9516)
        assertTrue(state(GeoPoint(37.4783, 126.9516), here).selectedAtCenter)
    }

    @Test
    fun `아무것도 고르지 않았으면 표식이 없다`() {
        assertFalse(state(GeoPoint(37.4783, 126.9516), null).selectedAtCenter)
    }

    @Test
    fun `지도를 끌어 중심이 옮겨지면 표식이 사라진다`() {
        // onMapDragEnd 는 center 만 바꾸고 selected 는 그대로 둔다.
        val picked = place(37.4783, 126.9516)
        val dragged = state(GeoPoint(37.4820, 126.9560), picked)
        assertFalse("중심이 옮겨졌는데도 표식을 그린다", dragged.selectedAtCenter)
    }

    @Test
    fun `영역 재검색으로 선택만 바뀌면 표식이 사라진다`() {
        // researchMapArea 는 selected 를 새 목록의 첫 건으로 바꾸지만 center 는
        // 사용자가 옮겨 둔 그 자리에 남긴다.
        val center = GeoPoint(37.4820, 126.9560)
        val newFirst = place(37.4791, 126.9502, "재검색 첫 결과")
        assertFalse(state(center, newFirst).selectedAtCenter)
    }

    @Test
    fun `부동소수 잡음은 같은 자리로 본다`() {
        // 같은 값에서 복사되므로 보통 정확히 같지만, 0.1m 미만 차이로 표식이
        // 깜빡이면 안 된다.
        val here = place(37.4783000001, 126.9516000001)
        assertTrue(state(GeoPoint(37.4783, 126.9516), here).selectedAtCenter)
    }

    @Test
    fun `한 블록 떨어진 곳은 다른 자리로 본다`() {
        // 1e-4도는 약 11m. 옆 건물이면 다른 장소다.
        val here = place(37.4784, 126.9516)
        assertFalse(state(GeoPoint(37.4783, 126.9516), here).selectedAtCenter)
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
