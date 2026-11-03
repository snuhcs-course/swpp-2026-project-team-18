package com.swpp.wakeup.sensing

import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.domain.model.LiveRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripLiveStateTest {

    @After
    fun tearDown() {
        TripLiveState.clear()
    }

    @Test
    fun `디스크 복원은 서비스가 게시한 더 새 경로를 덮지 않는다`() {
        val newer = snapshot(fetchedAtMillis = 2_000L)
        TripLiveState.publishLiveRoute(newer)

        TripLiveState.restoreLiveRoute(snapshot(fetchedAtMillis = 1_000L))

        assertEquals(newer, TripLiveState.liveRoute.value)
    }

    @Test
    fun `오래된 사본 삭제는 같은 일정의 더 새 경로를 지우지 않는다`() {
        val newer = snapshot(fetchedAtMillis = 2_000L)
        TripLiveState.publishLiveRoute(newer)

        TripLiveState.clearLiveRoute(
            eventId = EVENT_ID,
            expectedFetchedAtMillis = 1_000L,
        )

        assertEquals(newer, TripLiveState.liveRoute.value)
    }

    @Test
    fun `도착 후 추적 종료는 위치와 경로를 지우고 도착 결과를 유지한다`() {
        TripLiveState.publish(
            TripLiveState.Snapshot(
                eventId = EVENT_ID,
                point = GeoPoint(37.5, 127.0),
                accuracyM = 5f,
                atMillis = 2_000L,
                phase = TripGeofence.Phase.ARRIVED,
            )
        )
        TripLiveState.publishLiveRoute(snapshot(1_000L))
        TripLiveState.recordArrival(EVENT_ID, 2_000L)

        TripLiveState.clearTracking()

        assertNull(TripLiveState.snapshot.value)
        assertNull(TripLiveState.liveRoute.value)
        assertEquals(TripLiveState.Arrival(EVENT_ID, 2_000L), TripLiveState.arrivalFor(EVENT_ID))
        assertNull(TripLiveState.arrivalFor(EVENT_ID + 1))
    }

    @Test
    fun `도착하지 않은 추적을 종료하면 도착 결과를 만들지 않는다`() {
        TripLiveState.publish(
            TripLiveState.Snapshot(
                eventId = EVENT_ID,
                point = GeoPoint(37.5, 127.0),
                accuracyM = 5f,
                atMillis = 2_000L,
                phase = TripGeofence.Phase.IN_TRANSIT,
            )
        )

        TripLiveState.clearTracking()

        assertNull(TripLiveState.snapshot.value)
        assertNull(TripLiveState.arrival.value)
    }

    @Test
    fun `로그아웃은 이전 도착 결과를 모두 지운다`() {
        TripLiveState.recordArrival(EVENT_ID, 2_000L)
        TripLiveState.recordArrival(EVENT_ID + 1, 3_000L)

        TripLiveState.clear()

        assertNull(TripLiveState.arrival.value)
        assertEquals(emptyMap<Long, TripLiveState.Arrival>(), TripLiveState.arrivals.value)
    }

    // Partly AI-generated (Claude) — todo §2 "앱 재시작 후 도착 판정 복원"
    @Test
    fun `하루 여러 일정의 도착을 모두 기억한다`() {
        TripLiveState.recordArrival(EVENT_ID, 2_000L)
        TripLiveState.recordArrival(EVENT_ID + 1, 3_000L)

        assertEquals(TripLiveState.Arrival(EVENT_ID, 2_000L), TripLiveState.arrivalFor(EVENT_ID))
        assertEquals(TripLiveState.Arrival(EVENT_ID + 1, 3_000L), TripLiveState.arrivalFor(EVENT_ID + 1))
        assertEquals("마지막 도착", EVENT_ID + 1, TripLiveState.arrival.value?.eventId)
    }

    @Test
    fun `새 추적은 그 일정의 도착만 지운다`() {
        TripLiveState.recordArrival(EVENT_ID, 2_000L)
        TripLiveState.recordArrival(EVENT_ID + 1, 3_000L)

        TripLiveState.resetForNewTrip(EVENT_ID + 1)

        assertEquals(TripLiveState.Arrival(EVENT_ID, 2_000L), TripLiveState.arrivalFor(EVENT_ID))
        assertNull(TripLiveState.arrivalFor(EVENT_ID + 1))
        assertNull("지운 일정이 마지막 도착이었다", TripLiveState.arrival.value)
    }

    @Test
    fun `디스크 사본은 메모리의 도착을 덮지 않는다`() {
        TripLiveState.recordArrival(EVENT_ID, 2_000L)

        TripLiveState.restoreArrivals(mapOf(EVENT_ID to 1_000L, EVENT_ID + 1 to 5_000L))

        assertEquals(2_000L, TripLiveState.arrivalFor(EVENT_ID)?.atMillis)
        assertEquals(5_000L, TripLiveState.arrivalFor(EVENT_ID + 1)?.atMillis)
    }

    @Test
    fun `수동 뒤 GPS 도착이 오면 GPS 시각으로 바꾼다`() {
        TripLiveState.recordArrival(EVENT_ID, 2_000L)
        TripLiveState.recordArrival(EVENT_ID, 2_500L)

        assertEquals(2_500L, TripLiveState.arrivalFor(EVENT_ID)?.atMillis)
    }

    private fun snapshot(fetchedAtMillis: Long) = LiveRouteStore.Snapshot(
        eventId = EVENT_ID,
        origin = GeoPoint(37.5, 127.0),
        fetchedAtMillis = fetchedAtMillis,
        route = LiveRoute(
            routeKey = "transit:test",
            label = "2호선",
            minutes = 20,
            isAlternative = false,
            fasterMinutes = null,
            path = listOf(
                GeoPoint(37.5, 127.0),
                GeoPoint(37.51, 127.01),
            ),
        ),
    )

    // Partly AI-generated (Claude)
    @Test
    fun `바꾼 경로는 기록되고 같은 일정을 새로 추적하면 지워진다`() {
        TripLiveState.recordReroute(EVENT_ID, "R1", atMillis = 5L)
        assertEquals(TripLiveState.Rerouted(EVENT_ID, "R1", 5L), TripLiveState.rerouted.value)
        TripLiveState.resetForNewTrip(EVENT_ID + 1)
        assertEquals(TripLiveState.Rerouted(EVENT_ID, "R1", 5L), TripLiveState.rerouted.value)
        TripLiveState.resetForNewTrip(EVENT_ID)
        assertNull(TripLiveState.rerouted.value)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `전체 초기화는 바꾼 경로 기록도 지운다`() {
        TripLiveState.recordReroute(EVENT_ID, "R1", atMillis = 5L)
        TripLiveState.clear()
        assertNull(TripLiveState.rerouted.value)
    }

    private companion object {
        const val EVENT_ID = 42L
    }
}
