package com.swpp.wakeup.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRouteRefreshGateTest {

    @Test
    fun `진행 중인 요청이 있으면 위치가 계속 와도 중복 시작하지 않는다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L)

        assertNotNull(token)
        assertNull(gate.beginIfDue(61_000L))
    }

    @Test
    fun `완료한 요청은 정차 중에도 1분 뒤 다시 시작한다`() {
        val gate = LiveRouteRefreshGate()
        val token = gate.beginIfDue(1_000L)!!
        assertTrue(gate.finish(token))

        assertNull(gate.beginIfDue(60_999L))
        assertNotNull(gate.beginIfDue(61_000L))
    }

    @Test
    fun `reset 전 요청의 늦은 완료는 새 세션 상태를 바꾸지 않는다`() {
        val gate = LiveRouteRefreshGate()
        val old = gate.beginIfDue(1_000L)!!
        gate.reset()
        val fresh = gate.beginIfDue(2_000L)!!

        assertFalse(gate.finish(old))
        assertTrue(gate.finish(fresh))
    }
}
