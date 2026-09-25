package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRouteRefreshGateTest {

    private val here = GeoPoint(37.5, 127.0)

    @Test
    fun `진행 중인 요청이 있으면 위치가 계속 와도 중복 시작하지 않는다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L, here, hasUsableRoute = false)

        assertNotNull(token)
        assertNull(gate.beginIfDue(61_000L, moved(500.0), hasUsableRoute = false))
    }

    @Test
    fun `실패한 요청은 정차 중에도 1분 뒤 재시도한다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L, here, hasUsableRoute = false)!!
        assertTrue(gate.finish(token, success = false))

        assertNull(gate.beginIfDue(60_999L, here, hasUsableRoute = true))
        assertNotNull(gate.beginIfDue(61_000L, here, hasUsableRoute = true))
    }

    @Test
    fun `성공 뒤에는 1분과 이동 거리 조건을 모두 지킨다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L, here, hasUsableRoute = false)!!
        assertTrue(gate.finish(token, success = true))

        assertNull(gate.beginIfDue(60_999L, moved(500.0), hasUsableRoute = true))
        assertNull(gate.beginIfDue(61_000L, moved(100.0), hasUsableRoute = true))
        assertNotNull(gate.beginIfDue(61_000L, moved(200.0), hasUsableRoute = true))
    }

    @Test
    fun `reset 전 요청의 늦은 완료는 새 세션 상태를 바꾸지 않는다`() {
        val gate = LiveRouteRefreshGate()
        val old = gate.beginIfDue(1_000L, here, hasUsableRoute = false)!!
        gate.reset()
        val fresh = gate.beginIfDue(2_000L, here, hasUsableRoute = false)!!

        assertFalse(gate.finish(old, success = true))
        assertTrue(gate.finish(fresh, success = true))
    }

    @Test
    fun `성공 뒤 정차해도 5분이면 새 요청을 시작한다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L, here, hasUsableRoute = false)!!
        assertTrue(gate.finish(token, success = true))

        assertNotNull(
            gate.beginIfDue(
                1_000L + LiveRouteDecision.MAX_USABLE_AGE_MILLIS,
                here,
                hasUsableRoute = true,
            )
        )
    }

    private fun moved(northMeters: Double) = GeoPoint(
        lat = here.lat + northMeters / 111_320.0,
        lng = here.lng,
    )
}
