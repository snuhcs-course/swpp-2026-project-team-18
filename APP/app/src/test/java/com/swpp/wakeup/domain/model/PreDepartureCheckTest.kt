// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알람을 끈 뒤 출발 전의 "지금 나가도 늦나" 확인 시각과 문구. */
class PreDepartureCheckTest {
    private val min = 60_000L
    private val first = 1_800_000_000_000L
    private val start = first + 120 * min

    // --- 두 번째 확인 시각 ----------------------------------------------------
    @Test
    fun `일찍 알람을 껐으면 출발 10분 전에 확인한다`() {
        val departBy = first + 60 * min
        assertEquals(departBy - 10 * min, PreDepartureCheck.secondCallAtMillis(departBy, first, start))
    }

    @Test
    fun `출발 직전에 껐으면 첫 확인에서 5분 뒤에 확인한다`() {
        val departBy = first + 8 * min
        assertEquals(first + 5 * min, PreDepartureCheck.secondCallAtMillis(departBy, first, start))
    }

    @Test
    fun `일정 시작 이후가 되거나 출발 시각을 모르면 확인하지 않는다`() {
        assertNull(PreDepartureCheck.secondCallAtMillis(null, first, start))
        assertNull(PreDepartureCheck.secondCallAtMillis(first + 8 * min, first, first + 5 * min))
    }

    @Test
    fun `최대 두 번이고 오래된 결과는 버린다`() {
        assertEquals(2, PreDepartureCheck.MAX_CALLS)
        assertTrue(PreDepartureCheck.isFresh(first, first + 20 * min))
        assertFalse(PreDepartureCheck.isFresh(first, first + 20 * min + 1))
        assertFalse("미래 시각은 믿지 않는다", PreDepartureCheck.isFresh(first + 1, first))
    }

    // --- 문구 -----------------------------------------------------------------
    private fun route(late: Int?, suggestion: SwitchSuggestion?) = LiveRoute(
        routeKey = "transit:9호선",
        label = "9호선",
        minutes = 24,
        isAlternative = false,
        fasterMinutes = null,
        path = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01)),
        lateMinutes = late,
        suggestion = suggestion,
    )

    private val suggestion = SwitchSuggestion("transit:2호선", "9호선 → 2호선", "8:57", 0, 6)

    @Test
    fun `제안이 있으면 지금 나가도 늦다는 말과 바꿀 경로를 낸다`() {
        val banner = PreDepartureCheck.banner(route(6, suggestion))!!
        assertEquals("지금 나가도 6분 늦어요", banner.headline)
        assertEquals("9호선 → 2호선으로 가면 안 늦어요 · 8:57 도착 예상", banner.body)
        assertEquals("transit:2호선", banner.routeKey)
    }

    @Test
    fun `제안 없이 늦기만 하면 경로 없이 늦음만 알린다`() {
        val banner = PreDepartureCheck.banner(route(6, null))!!
        assertEquals("지금 나가도 6분 늦어요", banner.headline)
        assertEquals("더 일찍 닿는 경로가 없음", banner.body)
        assertNull(banner.routeKey)
    }

    @Test
    fun `안 늦으면 카드가 없다`() {
        assertNull(PreDepartureCheck.banner(route(0, null)))
        assertNull(PreDepartureCheck.banner(route(null, null)))
        assertNull(PreDepartureCheck.banner(null))
    }

    @Test
    fun `분을 모르면 분 없이 말한다`() {
        assertEquals("지금 나가도 늦어요", PreDepartureCheck.headline(null))
        assertEquals("지금 나가도 늦어요", PreDepartureCheck.headline(0))
        assertNotNull(PreDepartureCheck.banner(route(null, suggestion)))
        assertEquals("지금 나가도 늦어요", PreDepartureCheck.banner(route(null, suggestion))!!.headline)
    }
}
