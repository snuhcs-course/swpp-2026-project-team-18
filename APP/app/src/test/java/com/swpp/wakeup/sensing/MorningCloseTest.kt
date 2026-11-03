// Partly AI-generated (Claude)
package com.swpp.wakeup.sensing

import com.swpp.wakeup.sensing.TripGeofence.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorningCloseTest {

    @Test
    fun `출발지에서 본 뒤 나가면 아침 기록을 닫는다`() {
        assertEquals(
            MorningClose.Reason.DEPARTED,
            MorningClose.reasonFor(Phase.BEFORE_DEPARTURE, Phase.IN_TRANSIT, sawOriginFix = true),
        )
    }

    @Test
    fun `첫 위치부터 출발지 밖이면 출발로 닫지 않는다`() {
        // 다른 곳에서 잔 아침이다. 그때 누른 블록 기록은 진짜 준비 시간이다.
        assertNull(MorningClose.reasonFor(Phase.BEFORE_DEPARTURE, Phase.IN_TRANSIT, sawOriginFix = false))
    }

    @Test
    fun `도착하면 어디서 출발했든 닫는다`() {
        assertEquals(
            MorningClose.Reason.ARRIVED,
            MorningClose.reasonFor(Phase.IN_TRANSIT, Phase.ARRIVED, sawOriginFix = false),
        )
        assertEquals(
            MorningClose.Reason.ARRIVED,
            MorningClose.reasonFor(Phase.BEFORE_DEPARTURE, Phase.ARRIVED, sawOriginFix = false),
        )
    }

    @Test
    fun `단계가 그대로면 닫지 않는다`() {
        assertNull(MorningClose.reasonFor(Phase.BEFORE_DEPARTURE, Phase.BEFORE_DEPARTURE, sawOriginFix = true))
        assertNull(MorningClose.reasonFor(Phase.IN_TRANSIT, Phase.IN_TRANSIT, sawOriginFix = true))
        assertNull(MorningClose.reasonFor(Phase.ARRIVED, Phase.ARRIVED, sawOriginFix = true))
    }

    @Test
    fun `출발지 반경 안의 위치를 받으면 sawOriginFix 가 켜지고 꺼지지 않는다`() {
        val home = GeoPoint(37.48, 126.95)
        val fence = TripGeofence(home = home, destination = GeoPoint(37.46, 126.95))

        fence.offer(LocationFix(lat = 37.48, lng = 126.95, accuracyM = 10f, atMillis = 1_000L))

        assertEquals(true, fence.sawOriginFix)
    }

    @Test
    fun `첫 위치가 출발지 밖이면 sawOriginFix 는 꺼진 채다`() {
        val fence = TripGeofence(home = GeoPoint(37.48, 126.95), destination = GeoPoint(37.40, 126.95))

        fence.offer(LocationFix(lat = 37.45, lng = 126.95, accuracyM = 10f, atMillis = 1_000L))

        assertEquals(false, fence.sawOriginFix)
    }
}
