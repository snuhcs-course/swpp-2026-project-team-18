// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.alarm.WEAK_SIGNAL_NOTE
import com.swpp.wakeup.ui.alarm.liveNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-18 센터필드 이동: 지하철에서 내려 걸어가는 중 "경로 이탈" 오판 · 지하에서 "위치를 아직 받지 못했어요" 가 오래 남음
 */
class JourneyProgressTest {

    // 남북으로 곧게 뻗은 지하철 경로선(약 2.2km). 위도 0.001° ≈ 111m
    private val path = (0..20).map { GeoPoint(37.500 + it * 0.001, 127.040) }
    private val station = path.last()
    // 마지막 역에서 동쪽으로 약 800m 떨어진 목적지 — 서버 경로선에는 이 도보 구간 좌표가 없다
    private val destination = GeoPoint(station.lat, 127.049)
    private val home = GeoPoint(37.4995, 127.040)

    private fun eastOfStation(fraction: Double, northM: Double = 0.0) =
        GeoPoint(station.lat + northM / 111_320.0, station.lng + (destination.lng - station.lng) * fraction)

    @Test
    fun `예전 방식은 마지막 도보 중간에 이탈로 본다`() {
        val old = RouteProgress.of(path, eastOfStation(0.6))!!
        assertFalse("경로선만 보면 역에서 480m 떨어져 이탈", old.onRoute)
    }

    @Test
    fun `도보 구간까지 붙이면 걸어가는 동안 이탈이 아니다`() {
        for (f in listOf(0.1, 0.5, 0.9, 1.0)) {
            val p = RouteProgress.ofJourney(path, home, destination, eastOfStation(f))!!
            assertTrue("목적지 쪽 ${f * 100}%", p.onRoute)
        }
        // 직선이 아니라 골목으로 돌아가 북쪽으로 350m 비켜 걸어도(도보 800m 의 절반 + 300m 안) 이탈이 아니다
        assertTrue(RouteProgress.ofJourney(path, home, destination, eastOfStation(0.5, northM = 350.0))!!.onRoute)
    }

    @Test
    fun `도보 구간에서도 아주 멀리 가면 이탈이다 · 대중교통 구간 기준은 그대로`() {
        val far = RouteProgress.ofJourney(path, home, destination, eastOfStation(0.5, northM = 900.0))!!
        assertFalse(far.onRoute)
        // 경로선 가운데에서 동쪽으로 400m — 대중교통 구간이라 여유가 없다(300m 기준)
        val mid = path[10]
        val offTransit = RouteProgress.ofJourney(path, home, destination, GeoPoint(mid.lat, mid.lng + 400 / (111_320.0 * 0.7934)))!!
        assertFalse(offTransit.onRoute)
        val nearTransit = RouteProgress.ofJourney(path, home, destination, GeoPoint(mid.lat, mid.lng + 200 / (111_320.0 * 0.7934)))!!
        assertTrue(nearTransit.onRoute)
    }

    @Test
    fun `목적지에 다다르면 진행률이 끝에 가깝다 · 도보 구간이 없으면 예전과 같다`() {
        val atDest = RouteProgress.ofJourney(path, home, destination, destination)!!
        assertEquals(1f, atDest.ratio, 0.01f)
        val here = GeoPoint(path[5].lat, path[5].lng)
        assertEquals(RouteProgress.of(path, here), RouteProgress.ofJourney(path, null, null, here))
    }

    @Test
    fun `위치 줄 - 못 받음 · 새 위치 · 오래된 위치`() {
        val now = 1_000_000_000L
        assertNull("이 이동의 위치가 하나도 없다 → 단계 안내", liveNote(null, usable = false, nowMillis = now))
        assertEquals("방금 갱신", liveNote(now - 10_000, usable = true, nowMillis = now))
        assertEquals("5분 전 갱신 · $WEAK_SIGNAL_NOTE", liveNote(now - 5 * 60_000, usable = false, nowMillis = now))
        assertEquals("방금 갱신 · $WEAK_SIGNAL_NOTE", liveNote(now - 10_000, usable = false, nowMillis = now))
    }
}
