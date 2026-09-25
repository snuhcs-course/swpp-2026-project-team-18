package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 남은 시간 추정.
 *
 * 두 가지를 고정한다.
 *
 * 1. **멈춰 있어도 발산하지 않는다.** 관측 속도만 쓰면 지하철을 기다리는 동안
 *    이동 거리가 늘지 않아 속도가 0 에 수렴하고 도착 예정이 무한이 된다
 * 2. **실제로 느리면 그 사실이 반영된다.** 계획 속도만 쓰면 느린 수단을 탔는데도
 *    남은 구간은 계획대로 간다고 본다
 */
class TripEtaTest {

    /** 10km 를 20분에 가는 계획. 분당 500m. */
    private val travel = 20
    private val total = 10_000

    // --- 계획 속도만 --------------------------------------------------------

    @Test
    fun `아직 안 떠났으면 계획 이동 시간 전부가 남았다`() {
        assertEquals(20.0, TripEta.plannedRemainingMinutes(travel, 0f)!!, 1e-9)
    }

    @Test
    fun `절반 왔으면 절반이 남았다`() {
        assertEquals(10.0, TripEta.plannedRemainingMinutes(travel, 0.5f)!!, 1e-9)
    }

    @Test
    fun `도착했으면 남은 것이 없다`() {
        assertEquals(0.0, TripEta.plannedRemainingMinutes(travel, 1f)!!, 1e-9)
    }

    @Test
    fun `진행률이 범위를 벗어나도 잘린다`() {
        assertEquals(0.0, TripEta.plannedRemainingMinutes(travel, 5f)!!, 1e-9)
        assertEquals(20.0, TripEta.plannedRemainingMinutes(travel, -3f)!!, 1e-9)
    }

    @Test
    fun `계획 이동 시간을 모르면 내지 않는다`() {
        assertNull(TripEta.plannedRemainingMinutes(null, 0f))
        assertNull(TripEta.plannedRemainingMinutes(-5, 0f))
    }

    // --- 관측을 섞을 때 -----------------------------------------------------

    @Test
    fun `계획대로 가고 있으면 계획과 같은 값이 나온다`() {
        // 10분에 5km. 계획도 분당 500m 다. 어떻게 섞어도 10분이 남는다.
        val remaining = TripEta.remainingMinutes(travel, total, traveledM = 5_000, movingMinutes = 10.0)!!
        assertEquals(10.0, remaining, 1e-6)
    }

    @Test
    fun `계획보다 느리면 남은 시간이 늘어난다`() {
        // 10분에 3km 만 왔다. 계획이면 5km 였다.
        val remaining = TripEta.remainingMinutes(travel, total, traveledM = 3_000, movingMinutes = 10.0)!!
        assertTrue("느린데 계획과 같거나 짧다 ($remaining)", remaining > 14.0)
    }

    @Test
    fun `계획보다 빠르면 남은 시간이 줄어든다`() {
        val remaining = TripEta.remainingMinutes(travel, total, traveledM = 7_000, movingMinutes = 10.0)!!
        assertTrue("빠른데 계획보다 길다 ($remaining)", remaining < 6.0)
    }

    @Test
    fun `멈춰 있어도 발산하지 않는다`() {
        // 관측 속도가 0 이다. 축소 추정이 유효 속도를 (1-w) 배로만 떨어뜨린다.
        val remaining = TripEta.remainingMinutes(travel, total, traveledM = 0, movingMinutes = 10.0)!!
        assertTrue("발산했다 ($remaining)", remaining.isFinite())
        // w = 10/15 이므로 유효 속도는 계획의 1/3, 남은 시간은 약 3배다.
        assertEquals(60.0, remaining, 1.0)
    }

    @Test
    fun `오래 멈춰 있으면 계속 나빠진다`() {
        val ten = TripEta.remainingMinutes(travel, total, 0, 10.0)!!
        val thirty = TripEta.remainingMinutes(travel, total, 0, 30.0)!!
        assertTrue("더 오래 멈췄는데 전망이 낫다", thirty > ten)
        assertTrue(thirty.isFinite())
    }

    @Test
    fun `이동 직후에는 계획 속도에 가깝다`() {
        // 30초 만에 낸 속도는 심하게 튄다. w 가 거의 0 이라 계획 쪽이 이긴다.
        val remaining = TripEta.remainingMinutes(travel, total, traveledM = 10, movingMinutes = 0.5)!!
        assertEquals(20.0, remaining, 2.5)
    }

    @Test
    fun `표본이 없으면 계획 속도로 떨어진다`() {
        assertEquals(20.0, TripEta.remainingMinutes(travel, total, 0, movingMinutes = 0.0)!!, 1e-9)
        assertEquals(20.0, TripEta.remainingMinutes(travel, total, 0, movingMinutes = -1.0)!!, 1e-9)
    }

    @Test
    fun `목적지에 닿았으면 0이다`() {
        assertEquals(0.0, TripEta.remainingMinutes(travel, total, traveledM = total, movingMinutes = 25.0)!!, 1e-9)
        // 반올림으로 살짝 넘겨도 음수가 되지 않는다.
        assertEquals(0.0, TripEta.remainingMinutes(travel, total, traveledM = total + 50, movingMinutes = 25.0)!!, 1e-9)
    }

    @Test
    fun `경로 길이를 모르면 계획 속도로 떨어진다`() {
        assertEquals(20.0, TripEta.remainingMinutes(travel, totalM = 0, traveledM = 0, movingMinutes = 10.0)!!, 1e-9)
    }

    @Test
    fun `축소 상수는 서버 보정과 같은 5분이다`() {
        assertEquals(5.0, TripEta.PACE_SHRINKAGE_MINUTES, 1e-9)
    }
}
