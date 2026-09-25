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
    fun `이동 중 live route가 없으면 계획 경로로 되돌아가지 않는다`() {
        val result = state().copy(inTransit = true)
            .withLiveRouteDisplay(inTransit = true, liveRoute = null)

        assertTrue(result.inTransit)
        assertTrue(result.path.isEmpty())
        assertFalse(result.pathFromCurrent)
        assertFalse(result.path == result.plannedPath)
        assertEquals("현재 위치에서 가장 빠른 경로 확인 중", result.summary)
    }

    @Test
    fun `이동 중에는 유효한 현재 위치 경로만 표시한다`() {
        val result = state().withLiveRouteDisplay(inTransit = true, liveRoute = live)

        assertTrue(result.inTransit)
        assertEquals(live.path, result.path)
        assertEquals(live.summary, result.summary)
        assertTrue(result.pathFromCurrent)
    }

    @Test
    fun `좌표가 부족한 live route는 갱신 대기 상태로 만든다`() {
        val broken = live.copy(path = listOf(live.path.first()))
        val result = state().withLiveRouteDisplay(inTransit = true, liveRoute = broken)

        assertTrue(result.path.isEmpty())
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
    fun `이동이 끝난 뒤에만 계획 경로를 복원한다`() {
        val liveState = state().withLiveRouteDisplay(inTransit = true, liveRoute = live)
        val result = liveState.withLiveRouteDisplay(inTransit = false, liveRoute = null)

        assertFalse(result.inTransit)
        assertEquals(planned, result.path)
        assertEquals("계획 경로", result.summary)
        assertFalse(result.pathFromCurrent)
    }
}
