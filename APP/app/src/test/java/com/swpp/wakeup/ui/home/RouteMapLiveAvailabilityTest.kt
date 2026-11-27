package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.domain.model.LiveRouteDecision
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapLiveAvailabilityTest {

    private val planned = listOf(
        GeoPoint(37.480, 126.930),
        GeoPoint(37.490, 127.020),
    )
    private val live = LiveRoute(
        routeKey = "live",
        label = "도보",
        minutes = 12,
        isAlternative = false,
        fasterMinutes = null,
        path = listOf(
            GeoPoint(37.485, 126.980),
            GeoPoint(37.490, 127.020),
        ),
    )

    private fun state() = HomeViewModel.RouteMapState(
        eventId = 18L,
        path = planned,
        plannedPath = planned,
        center = GeoPoint(37.485, 126.975),
        level = 9,
        summary = "계획 경로",
        plannedSummary = "계획 경로",
    )

    @Test
    fun `이동 중에는 사용자가 선택한 경로를 계속 보여 준다`() {
        val result = state().copy(inTransit = true)
            .withLiveRouteDisplay(inTransit = true, liveRoute = null)

        assertTrue(result.inTransit)
        assertEquals(planned, result.path)
        assertFalse(result.pathFromCurrent)
        assertEquals("계획 경로", result.summary)
    }

    @Test
    fun `이전 앱의 현재 위치 경로가 들어와도 경로와 지도 중심에 반영하지 않는다`() {
        val result = state().withLiveRouteDisplay(inTransit = true, liveRoute = live)

        assertTrue(result.inTransit)
        assertEquals(planned, result.path)
        assertEquals("계획 경로", result.summary)
        assertFalse(result.pathFromCurrent)
        assertEquals(state().center, result.center)
        assertEquals(state().level, result.level)
    }

    @Test
    fun `깨진 이전 경로도 무시하고 선택한 경로를 유지한다`() {
        val broken = live.copy(path = listOf(live.path.first()))
        val result = state().withLiveRouteDisplay(inTransit = true, liveRoute = broken)

        assertEquals(planned, result.path)
        assertFalse(result.pathFromCurrent)
    }

    @Test
    fun `만료된 snapshot은 현재 위치 경로로 사용할 수 없다`() {
        val fetchedAt = 1_000_000L
        val snapshot = LiveRouteStore.Snapshot(
            eventId = 18L,
            origin = live.path.first(),
            fetchedAtMillis = fetchedAt,
            route = live,
        )

        assertNull(
            snapshot.usableLiveRoute(
                nowMillis = fetchedAt + LiveRouteDecision.MAX_USABLE_AGE_MILLIS + 1L,
            ),
        )
        assertEquals(live, snapshot.usableLiveRoute(nowMillis = fetchedAt))
    }

    @Test
    fun `이동이 끝난 뒤에도 같은 선택 경로를 유지한다`() {
        val liveState = state().withLiveRouteDisplay(inTransit = true, liveRoute = live)
        val result = liveState.withLiveRouteDisplay(inTransit = false, liveRoute = null)

        assertFalse(result.inTransit)
        assertEquals(planned, result.path)
        assertEquals("계획 경로", result.summary)
        assertFalse(result.pathFromCurrent)
    }

    @Test
    fun `기기에서 측위한 마커는 보존하지만 서버로 보낼 지도 중심은 바꾸지 않는다`() {
        val measured = HomeViewModel.MapLocationFix(GeoPoint(37.8, 127.8), 10f, 1_000L)
        val before = state().copy(currentLocation = measured)
        val result = before.withLiveRouteDisplay(inTransit = true, liveRoute = live)
        assertEquals(measured, result.currentLocation)
        assertEquals(before.center, result.center)
        assertEquals(before.level, result.level)
    }
}
