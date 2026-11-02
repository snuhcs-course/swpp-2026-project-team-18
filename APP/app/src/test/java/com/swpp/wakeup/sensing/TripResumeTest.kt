// Partly AI-generated (Claude)
package com.swpp.wakeup.sensing

import com.google.gson.Gson
import com.swpp.wakeup.domain.model.TripProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 집을 나선 뒤 죽은 추적을 이어 간다(todo §2 "추적 서비스 재시작 복원"). */
class TripResumeTest {

    private val home = GeoPoint(37.4800, 126.9500)
    private val destination = GeoPoint(37.4600, 126.9520)
    private val now = 1_800_000_000_000L

    private fun fix(point: GeoPoint, at: Long = now, accuracy: Float = 10f) =
        LocationFix(lat = point.lat, lng = point.lng, accuracyM = accuracy, atMillis = at)

    /** 집에서 약 1km 떨어진 곳 */
    private val midway = GeoPoint(37.4710, 126.9510)

    // --- 판별기 ---------------------------------------------------------------

    @Test
    fun `이어 간 추적은 출발을 다시 기다리지 않고 도착을 본다`() {
        val fence = TripGeofence(home = home, destination = destination)
        fence.resumeInTransit(sawOriginFix = true, departureMissed = false)

        assertEquals(TripGeofence.Phase.IN_TRANSIT, fence.phase)
        assertNull("밖의 첫 위치로 출발을 놓친 것으로 남기지 않는다", fence.offer(fix(midway)))
        assertFalse(fence.departureMissed)
        assertTrue(fence.sawOriginFix)
        assertTrue(fence.offer(fix(destination, at = now + 60_000L)) is TripEvent.Arrived)
    }

    @Test
    fun `출발을 놓쳤던 이동은 이어 가도 그대로 남긴다`() {
        val fence = TripGeofence(home = home, destination = destination)
        fence.resumeInTransit(sawOriginFix = false, departureMissed = true)
        assertTrue(fence.departureMissed)
        assertFalse(fence.sawOriginFix)
    }

    @Test
    fun `판정을 시작한 뒤에는 되돌리지 않는다`() {
        val fence = TripGeofence(home = home, destination = destination)
        fence.offer(fix(home))
        fence.resumeInTransit(sawOriginFix = false, departureMissed = true)
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, fence.phase)
        assertFalse(fence.departureMissed)
    }

    // --- 디스크 사본 -----------------------------------------------------------

    private fun progress(
        eventId: Long = 7L,
        movingSince: Long? = now - 20 * 60_000L,
        savedAt: Long = now - 60_000L,
    ) = TripProgress(
        eventId = eventId,
        movingSinceMillis = movingSince,
        sawOriginFix = true,
        departureMissed = false,
        rerouteNotifiedKeys = listOf("transit:2호선"),
        savedAtMillis = savedAt,
    )

    @Test
    fun `같은 일정의 최근 사본만 쓴다`() {
        assertTrue(progress().usableFor(7L, now))
        assertFalse("다른 일정", progress().usableFor(8L, now))
        assertFalse("오래된 사본", progress(savedAt = now - TripProgress.MAX_AGE_MILLIS - 1).usableFor(7L, now))
        assertFalse("미래에 남긴 사본", progress(savedAt = now + 1).usableFor(7L, now))
        assertFalse("미래의 이동 시작", progress(movingSince = now + 1).usableFor(7L, now))
        assertTrue("이동 시작을 모르면 다음 위치에서 잡는다", progress(movingSince = null).usableFor(7L, now))
    }

    @Test
    fun `디스크 사본을 그대로 되읽고 빠진 칸은 null 로 읽는다`() {
        val gson = Gson()
        val saved = progress()
        assertEquals(saved, gson.fromJson(gson.toJson(saved), TripProgress::class.java))

        val old = gson.fromJson("""{"eventId":7,"savedAtMillis":$now}""", TripProgress::class.java)
        assertNull(old.rerouteNotifiedKeys)
        assertNull(old.movingSinceMillis)
        assertTrue(old.usableFor(7L, now))
    }
}
