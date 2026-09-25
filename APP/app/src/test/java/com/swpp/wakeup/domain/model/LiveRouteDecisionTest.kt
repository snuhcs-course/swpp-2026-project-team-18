package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRouteDecisionTest {

    private val start = GeoPoint(37.500000, 127.000000)

    @Test
    fun `첫 위치는 즉시 조회한다`() {
        assertTrue(
            LiveRouteDecision.shouldFetch(
                lastAtMillis = null,
                lastPoint = null,
                nowMillis = 1_000L,
                here = start,
            )
        )
    }

    @Test
    fun `많이 움직여도 1분 전에는 조회하지 않는다`() {
        assertFalse(decide(afterMillis = 59_999L, northMeters = 500.0))
    }

    @Test
    fun `1분이 지나고 충분히 움직였을 때 조회한다`() {
        assertTrue(decide(afterMillis = 60_000L, northMeters = 200.0))
    }

    @Test
    fun `1분이 지나도 이동량이 작으면 쿼터를 쓰지 않는다`() {
        assertFalse(decide(afterMillis = 60_000L, northMeters = 100.0))
        assertFalse(decide(afterMillis = 4 * 60_000L, northMeters = 100.0))
    }

    @Test
    fun `정차 중이어도 5분이면 교통 상황을 다시 확인한다`() {
        assertTrue(
            decide(
                afterMillis = LiveRouteDecision.MAX_USABLE_AGE_MILLIS,
                northMeters = 0.0,
            )
        )
    }

    @Test
    fun `첫 조회가 실패했다면 정차 중에도 1분 뒤 재시도한다`() {
        assertTrue(
            LiveRouteDecision.shouldFetch(
                lastAtMillis = 1_000L,
                lastPoint = start,
                nowMillis = 61_000L,
                here = start,
                hasUsableRoute = false,
            )
        )
    }

    @Test
    fun `벽시계가 뒤로 가면 기준을 다시 잡는다`() {
        assertTrue(
            LiveRouteDecision.shouldFetch(
                lastAtMillis = 10_000L,
                lastPoint = start,
                nowMillis = 9_999L,
                here = start,
            )
        )
    }

    @Test
    fun `대안이면 피그마 문구처럼 몇 분 빠른지 말한다`() {
        val route = route(
            label = "9호선 → 2호선",
            minutes = 17,
            isAlternative = true,
            fasterMinutes = 3,
        )

        assertEquals("여기서부터 9호선 → 2호선 · 3분 빠름", route.summary)
    }

    @Test
    fun `선택 경로가 이미 최단이어도 현재 위치 기준 시간은 표시한다`() {
        val route = route(
            label = "2호선",
            minutes = 20,
            isAlternative = false,
            fasterMinutes = null,
        )

        assertEquals("여기서부터 2호선 · 20분", route.summary)
    }

    private fun decide(afterMillis: Long, northMeters: Double): Boolean =
        LiveRouteDecision.shouldFetch(
            lastAtMillis = 1_000L,
            lastPoint = start,
            nowMillis = 1_000L + afterMillis,
            here = GeoPoint(
                lat = start.lat + northMeters / 111_320.0,
                lng = start.lng,
            ),
        )

    private fun route(
        label: String?,
        minutes: Int,
        isAlternative: Boolean,
        fasterMinutes: Int?,
    ) = LiveRoute(
        routeKey = "transit:test",
        label = label,
        minutes = minutes,
        isAlternative = isAlternative,
        fasterMinutes = fasterMinutes,
        path = listOf(start, GeoPoint(37.51, 127.01)),
    )
}
