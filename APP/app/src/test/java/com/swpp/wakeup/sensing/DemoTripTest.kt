package com.swpp.wakeup.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Partly AI-generated (Claude)
/** HE 시연 이동([DemoTrip])이 진짜 판별기에서 출발·도착 판정을 내는지 본다. */
class DemoTripTest {

    private val home = GeoPoint(37.4842, 126.9297)
    private val dest = GeoPoint(37.4601, 126.9520)
    private val path = listOf(GeoPoint(37.4800, 126.9350), GeoPoint(37.4700, 126.9450))
    private val seoul = java.time.ZoneId.of("Asia/Seoul")

    private fun millis(text: String) = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun schedule() = com.swpp.wakeup.domain.model.AlarmSchedule(
        eventId = 1L,
        alarmAtMillis = millis("2026-11-05T09:09:00+09:00"),
        departByMillis = millis("2026-11-05T10:15:00+09:00"),
        arriveAtMillis = millis("2026-11-05T10:45:00+09:00"),
        startAtMillis = millis("2026-11-05T11:00:00+09:00"),
        alarmLabel = "9:09",
        meridiem = "AM",
        eventLine = "11:00 컴퓨터 구조",
        placeName = null,
        arrivalLine = "10:45 도착 예정",
    )

    @Test
    fun `fast forward puts the alarm ten seconds from now and keeps the gaps`() {
        val now = millis("2026-11-02T20:24:38+09:00")
        val original = schedule()
        val delta = DemoTrip.fastForwardDelta(original.alarmAtMillis, now)
        val shifted = DemoTrip.shift(original, delta, seoul)

        assertEquals(now + DemoTrip.FAST_FORWARD_LEAD_MILLIS, shifted.alarmAtMillis)
        assertEquals(
            original.startAtMillis - original.alarmAtMillis,
            shifted.startAtMillis - shifted.alarmAtMillis,
        )
        assertEquals(
            original.arriveAtMillis!! - original.departByMillis!!,
            shifted.arriveAtMillis!! - shifted.departByMillis!!,
        )
        assertEquals("20:24", shifted.alarmLabel)
        assertEquals("PM", shifted.meridiem)
        assertEquals("22:15 컴퓨터 구조", shifted.eventLine)
        assertEquals("22:00 도착 예정", shifted.arrivalLine)
    }

    @Test
    fun `shift by zero changes nothing`() {
        assertEquals(schedule(), DemoTrip.shift(schedule(), 0L, seoul))
    }

    @Test
    fun `stays home first then walks the route to the destination`() {
        val points = DemoTrip.points(home, dest, path)
        assertEquals(DemoTrip.HOME_FIXES + DemoTrip.SAMPLE_COUNT - 1, points.size)
        assertEquals(home, points[0])
        assertEquals(home, points[1])
        assertEquals(dest, points.last())
    }

    @Test
    fun `samples are evenly spaced along the line`() {
        val line = DemoTrip.polyline(home, dest, path)
        val samples = DemoTrip.sample(line, 11)
        val gaps = samples.zipWithNext { a, b -> TripGeofence.distanceMeters(a, b) }
        val mean = gaps.average()
        gaps.forEach { assertEquals(mean, it, mean * 0.05) }
    }

    @Test
    fun `real geofence sees departure then arrival`() {
        val fence = TripGeofence(home = home, destination = dest)
        var now = 1_000_000L
        val events = DemoTrip.points(home, dest, path).mapNotNull { p ->
            now += DemoTrip.STEP_MILLIS
            fence.offer(LocationFix(p.lat, p.lng, DemoTrip.ACCURACY_M, now))
        }
        assertEquals(2, events.size)
        assertTrue(events[0] is TripEvent.Departed)
        assertTrue(events[1] is TripEvent.Arrived)
        assertEquals(false, fence.departureMissed)
    }

    @Test
    fun `straight line when the plan has no route path`() {
        val points = DemoTrip.points(home, dest, emptyList())
        assertEquals(dest, points.last())
        assertNull(points.firstOrNull { it.lat !in dest.lat..home.lat })
    }

    @Test
    fun `clock stretches the walk to the planned travel time`() {
        val now = 10_000_000L
        val departBy = now + 30 * 60_000L
        val arriveAt = departBy + 20 * 60_000L
        val clock = DemoTrip.DemoClock.planned(now, departBy, arriveAt, walkSteps = 28)
        val firstWalk = now + DemoTrip.HOME_FIXES * DemoTrip.STEP_MILLIS
        assertEquals(departBy, clock.map(firstWalk))
        assertEquals(arriveAt, clock.map(firstWalk + 28 * DemoTrip.STEP_MILLIS))
    }

    @Test
    fun `pressing late departs late`() {
        val now = 10_000_000L
        val departBy = now - 5 * 60_000L
        val clock = DemoTrip.DemoClock.planned(now, departBy, departBy + 20 * 60_000L, walkSteps = 28)
        val firstWalk = now + DemoTrip.HOME_FIXES * DemoTrip.STEP_MILLIS
        assertEquals(firstWalk, clock.map(firstWalk))
    }
}
