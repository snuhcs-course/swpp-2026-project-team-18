package com.swpp.wakeup.sensing

import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.domain.model.LiveRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TripLiveStateTest {

    @After
    fun tearDown() {
        TripLiveState.clear()
    }

    @Test
    fun `디스크 복원은 서비스가 게시한 더 새 경로를 덮지 않는다`() {
        val newer = snapshot(fetchedAtMillis = 2_000L)
        TripLiveState.publishLiveRoute(newer)

        TripLiveState.restoreLiveRoute(snapshot(fetchedAtMillis = 1_000L))

        assertEquals(newer, TripLiveState.liveRoute.value)
    }

    @Test
    fun `오래된 사본 삭제는 같은 일정의 더 새 경로를 지우지 않는다`() {
        val newer = snapshot(fetchedAtMillis = 2_000L)
        TripLiveState.publishLiveRoute(newer)

        TripLiveState.clearLiveRoute(
            eventId = EVENT_ID,
            expectedFetchedAtMillis = 1_000L,
        )

        assertEquals(newer, TripLiveState.liveRoute.value)
    }

    private fun snapshot(fetchedAtMillis: Long) = LiveRouteStore.Snapshot(
        eventId = EVENT_ID,
        origin = GeoPoint(37.5, 127.0),
        fetchedAtMillis = fetchedAtMillis,
        route = LiveRoute(
            routeKey = "transit:test",
            label = "2호선",
            minutes = 20,
            isAlternative = false,
            fasterMinutes = null,
            path = listOf(
                GeoPoint(37.5, 127.0),
                GeoPoint(37.51, 127.01),
            ),
        ),
    )

    private companion object {
        const val EVENT_ID = 42L
    }
}
