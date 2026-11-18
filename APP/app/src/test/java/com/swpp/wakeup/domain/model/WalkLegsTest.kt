// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ④ 지도의 앞뒤 도보 구간(11-17 사용자: 걸어가는 구간이 지도에 빠져 있었다) */
class WalkLegsTest {

    private val path = listOf(GeoPoint(37.4812, 126.9527), GeoPoint(37.4600, 126.9520))
    private val home = GeoPoint(37.4776, 126.9520) // 첫 정류장에서 약 400m
    private val dest = GeoPoint(37.4585, 126.9490) // 마지막 정류장에서 약 300m

    @Test
    fun `출발지 · 도착지가 경로선 끝에서 떨어져 있으면 도보 구간으로 잇는다`() {
        val legs = WalkLegs.of(path, home, dest)
        assertEquals(listOf(home, path.first()), legs.head)
        assertEquals(listOf(path.last(), dest), legs.tail)
        assertEquals(4, legs.all.size)
    }

    @Test
    fun `경로선이 이미 출발지 · 도착지에 닿아 있으면 잇지 않는다`() {
        val near = GeoPoint(path.first().lat + 0.0001, path.first().lng) // 약 11m
        val legs = WalkLegs.of(path, near, path.last())
        assertNull(legs.head)
        assertNull(legs.tail)
    }

    @Test
    fun `좌표를 모르거나 경로선이 없으면 잇지 않는다`() {
        assertNull(WalkLegs.of(path, null, null).head)
        assertNull(WalkLegs.of(emptyList(), home, dest).tail)
    }
}
