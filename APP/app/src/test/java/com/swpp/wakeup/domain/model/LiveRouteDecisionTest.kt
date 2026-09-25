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
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun `위치 콜백이 자주 와도 1분 전에는 조회하지 않는다`() {
        assertFalse(decide(afterMillis = 59_999L))
    }

    @Test
    fun `1분이 지나면 현재 위치 이동량과 무관하게 조회한다`() {
        assertTrue(decide(afterMillis = 60_000L))
    }

    @Test
    fun `정차 중이어도 1분마다 교통 상황을 다시 확인한다`() {
        assertTrue(decide(afterMillis = 60_000L))
        assertTrue(decide(afterMillis = 5 * 60_000L))
    }

    @Test
    fun `벽시계가 뒤로 가면 기준을 다시 잡는다`() {
        assertTrue(
            LiveRouteDecision.shouldFetch(
                lastAtMillis = 10_000L,
                nowMillis = 9_999L,
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

    private fun decide(afterMillis: Long): Boolean =
        LiveRouteDecision.shouldFetch(
            lastAtMillis = 1_000L,
            nowMillis = 1_000L + afterMillis,
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
