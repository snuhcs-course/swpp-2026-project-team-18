// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경로를 바꾼 뒤에는 바꾸기 전에 받은 제안을 다시 내놓지 않는다. */
class AppliedRerouteTest {

    private fun snapshot(eventId: Long, fetchedAt: Long) = LiveRouteStore.Snapshot(
        eventId = eventId,
        origin = GeoPoint(37.5, 127.0),
        fetchedAtMillis = fetchedAt,
        route = LiveRoute(
            routeKey = "transit:9호선",
            label = "9호선",
            minutes = 24,
            isAlternative = false,
            fasterMinutes = null,
            path = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01)),
        ),
    )

    private val applied = HomeViewModel.AppliedReroute(eventId = 7L, atMillis = 10_000L)

    @Test
    fun `바꾸기 전에 받은 경로는 가린다`() {
        assertTrue(applied.hides(snapshot(7L, fetchedAt = 9_000L)))
        assertTrue("같은 순간이면 바꾸기 전 응답이다", applied.hides(snapshot(7L, fetchedAt = 10_000L)))
    }

    @Test
    fun `바꾼 뒤에 받은 경로와 다른 일정은 가리지 않는다`() {
        assertFalse(applied.hides(snapshot(7L, fetchedAt = 10_001L)))
        assertFalse(applied.hides(snapshot(8L, fetchedAt = 9_000L)))
    }
}
