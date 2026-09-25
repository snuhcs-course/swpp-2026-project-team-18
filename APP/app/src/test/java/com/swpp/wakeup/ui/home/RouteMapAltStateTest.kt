package com.swpp.wakeup.ui.home

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보라 경로선을 언제 그리는가.
 *
 * ## 색 어휘가 한 화면에서 겹친다
 *
 * 바로 위 진행 바가 초록을 **"정시 도착"** 으로 쓰므로 지도 경로는 보라로
 * 분리했다. 보라 선도 출발지 기준 대안인지 현재 위치 기준 경로인지 글씨와
 * **항상 함께** 밝혀야 한다.
 *
 * 그래서 [HomeViewModel.RouteMapState.hasAltPath] 가 좌표와 라벨을 **둘 다**
 * 요구한다. 뜻 없는 색은 지어낸 숫자보다 나쁘다.
 */
class RouteMapAltStateTest {

    private val path = listOf(GeoPoint(37.48, 126.93), GeoPoint(37.49, 127.02))

    private fun state(
        altPath: List<GeoPoint> = emptyList(),
        altSummary: String? = null,
    ) = HomeViewModel.RouteMapState(
        eventId = 1L,
        path = path,
        center = GeoPoint(37.485, 126.975),
        level = 9,
        altPath = altPath,
        altSummary = altSummary,
    )

    @Test
    fun `좌표와 라벨이 모두 있으면 그린다`() {
        assertTrue(state(path, "9호선 → 2호선 · 4분 빠름").hasAltPath)
    }

    @Test
    fun `라벨이 없으면 그리지 않는다`() {
        // 뜻을 밝히지 못하는 색은 쓰지 않는다.
        assertFalse(state(path, null).hasAltPath)
    }

    @Test
    fun `점이 하나면 그리지 않는다`() {
        // 선이 되지 않는다. 점 하나를 찍으면 사용자는 그것을 경유지로 읽는다.
        assertFalse(state(listOf(GeoPoint(37.48, 126.93)), "4분 빠름").hasAltPath)
    }

    @Test
    fun `기본값은 그리지 않는다`() {
        // 대안이 없는 일정이 대다수다. 기본이 "안 그림" 이어야 한다.
        assertFalse(state().hasAltPath)
    }

    @Test
    fun `대안이 고른 경로를 대신하지 않는다`() {
        // 진행률과 알람은 `path` 로만 계산한다. 대안이 있어도 그대로다.
        val withAlt = state(path, "9호선 → 2호선 · 4분 빠름")
        assertEquals(path, withAlt.path)
        assertEquals(2, withAlt.altPath.size)
    }
}
