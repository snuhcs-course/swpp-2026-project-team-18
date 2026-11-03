// Partly AI-generated (Claude)
package com.swpp.wakeup.sensing

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.TripObservationInput
import com.swpp.wakeup.domain.model.TripStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 수동 도착 관측(todo §1 P0 "수동 도착 판정 받기"의 앱 쪽). */
class ManualArrivalTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val now = 1_800_000_000_000L
    private val dest = GeoPoint(37.4600, 126.9500)

    @Test
    fun `최근 위치가 있으면 같이 보내고 목적지까지 거리를 단다`() {
        val fix = LocationFix(lat = 37.4601, lng = 126.9501, accuracyM = 85f, atMillis = now - 30_000)

        val obs = manualArrivalOf(eventId = 7, atMillis = now, fix = fix, destination = dest, zone = zone)

        assertEquals(TripObservationInput.KIND_ARRIVE, obs.kind)
        assertEquals(TripObservationInput.DETECTOR_MANUAL, obs.detector)
        assertEquals(37.4601, obs.lat!!, 1e-9)
        assertEquals(126.9501, obs.lng!!, 1e-9)
        assertEquals("수동은 오차가 커도 그대로 보낸다", 85.0, obs.accuracyM!!, 1e-9)
        assertTrue(obs.distanceM!! < 20.0)
        assertNull(obs.dwellSeconds)
    }

    @Test
    fun `위치가 오래됐거나 없으면 lat lng 를 둘 다 뺀다`() {
        val stale = LocationFix(lat = 37.0, lng = 127.0, accuracyM = 10f, atMillis = now - 3 * 60_000)

        val withStale = manualArrivalOf(eventId = 7, atMillis = now, fix = stale, destination = dest, zone = zone)
        val without = manualArrivalOf(eventId = 7, atMillis = now, fix = null, destination = dest, zone = zone)

        for (obs in listOf(withStale, without)) {
            assertNull(obs.lat)
            assertNull(obs.lng)
            assertNull(obs.accuracyM)
            assertNull(obs.distanceM)
        }
    }

    @Test
    fun `위치 없는 수동 도착은 JSON 에서 위치 키가 빠진다`() {
        val json = Gson().toJson(manualArrivalOf(eventId = 7, atMillis = now, fix = null, destination = null, zone = zone))

        assertFalse(json.contains("\"lat\""))
        assertFalse(json.contains("\"lng\""))
        assertTrue(json.contains("\"detector\":\"manual\""))
        assertNotNull(json)
    }

    @Test
    fun `도착했어요 는 가는 중일 때만 나온다`() {
        assertTrue(TripStage.IN_TRANSIT.canMarkArrived)
        assertTrue(TripStage.DEPARTURE_UNKNOWN.canMarkArrived)
        assertFalse(TripStage.ARRIVED.canMarkArrived)
        assertFalse(TripStage.PREPARING.canMarkArrived)
        assertFalse(TripStage.PAST.canMarkArrived)
        assertFalse(TripStage.BEFORE_ALARM.canMarkArrived)
    }
}
