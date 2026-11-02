// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "경로 정류장" 카드가 따라갈 기준 경로. */
class CheckpointBasisTest {
    private fun live(key: String, alternative: Boolean) = LiveRoute(
        routeKey = key,
        label = null,
        minutes = 20,
        isAlternative = alternative,
        fasterMinutes = null,
        path = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01)),
    )

    @Test
    fun `이번 이동에서 바꾼 경로가 우선`() {
        assertEquals(
            "transit:5513",
            CheckpointBasis.baseRouteKey("transit:2호선", "transit:5513", live("transit:9호선", false)),
        )
    }

    @Test
    fun `대안이 아닌 현재-위치 경로는 기준 경로다`() {
        assertEquals(
            "transit:9호선",
            CheckpointBasis.baseRouteKey("transit:2호선", null, live("transit:9호선", false)),
        )
    }

    @Test
    fun `대안 경로나 오래된 사본이면 일정 경로`() {
        assertEquals("transit:2호선", CheckpointBasis.baseRouteKey("transit:2호선", null, live("transit:9호선", true)))
        assertEquals(
            "transit:2호선",
            CheckpointBasis.baseRouteKey("transit:2호선", null, live("transit:9호선", false), liveFresh = false),
        )
    }

    @Test
    fun `아무 key 도 없으면 null`() {
        assertNull(CheckpointBasis.baseRouteKey(null, " ", null))
    }

    @Test
    fun `정류장은 대중교통 key 에만 있다`() {
        assertTrue(CheckpointBasis.hasStops("transit:2호선>5513"))
        assertFalse(CheckpointBasis.hasStops("walk"))
        assertFalse(CheckpointBasis.hasStops(null))
    }
}
