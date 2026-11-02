// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이동 중 늦을 때의 경로 바꾸기 제안(todo §2 "이동 중 늦으면 경로 바꾸기"). */
class RerouteAlertTest {

    private val now = 1_800_000_000_000L

    private fun suggestion(
        key: String = "transit:2호선",
        label: String? = "9호선 → 2호선",
        arrive: String? = "8:57",
        late: Int = 0,
        saves: Int = 6,
    ) = SwitchSuggestion(key, label, arrive, late, saves)

    private fun route(late: Int?, suggestion: SwitchSuggestion?) = LiveRoute(
        routeKey = "transit:9호선",
        label = "9호선",
        minutes = 24,
        isAlternative = false,
        fasterMinutes = null,
        path = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.51, 127.01)),
        lateMinutes = late,
        suggestion = suggestion,
    )

    // --- 탑승 중 -------------------------------------------------------------

    @Test
    fun `빠르게 움직이면 그때부터 2분은 탑승 중으로 본다`() {
        assertEquals(
            now + RerouteAlert.RIDING_HOLD_MILLIS,
            RerouteAlert.ridingUntil(null, speedMps = 9f, atMillis = now),
        )
        assertEquals(
            "경계값도 탑승이다",
            now + RerouteAlert.RIDING_HOLD_MILLIS,
            RerouteAlert.ridingUntil(null, speedMps = RerouteAlert.RIDING_SPEED_MPS, atMillis = now),
        )
    }

    @Test
    fun `걷거나 속도를 모르면 마감을 그대로 둔다`() {
        assertNull(RerouteAlert.ridingUntil(null, speedMps = 1.4f, atMillis = now))
        assertNull(RerouteAlert.ridingUntil(null, speedMps = null, atMillis = now))
        assertNull(RerouteAlert.ridingUntil(null, speedMps = Float.NaN, atMillis = now))
        assertEquals(now + 30_000L, RerouteAlert.ridingUntil(now + 30_000L, speedMps = 0f, atMillis = now))
    }

    @Test
    fun `늦게 온 위치가 마감을 앞당기지 않는다`() {
        val later = now + 5 * 60_000L
        assertEquals(later, RerouteAlert.ridingUntil(later, speedMps = 10f, atMillis = now))
    }

    // --- 알릴지 --------------------------------------------------------------

    private fun notify(
        suggestion: SwitchSuggestion? = suggestion(),
        notified: Set<String> = emptySet(),
        ridingUntil: Long? = null,
        minutes: Int? = 20,
        meters: Double? = 3_000.0,
    ) = RerouteAlert.shouldNotify(suggestion, notified, now, ridingUntil, minutes, meters)

    @Test
    fun `제안이 있고 걷는 중이며 도착이 멀면 알린다`() {
        assertTrue(notify())
        assertTrue("남은 시간·거리를 몰라도 알린다", notify(minutes = null, meters = null))
        assertTrue("탑승 마감이 지났으면 알린다", notify(ridingUntil = now - 1))
    }

    @Test
    fun `제안이 없거나 이미 알린 경로면 알리지 않는다`() {
        assertFalse(notify(suggestion = null))
        assertFalse(notify(notified = setOf("transit:2호선")))
        assertTrue("다른 경로는 다시 알린다", notify(notified = setOf("transit:9호선")))
    }

    @Test
    fun `버스·지하철 안이나 도착 직전에는 미룬다`() {
        assertFalse(notify(ridingUntil = now + 1))
        assertFalse(notify(minutes = RerouteAlert.NEAR_ARRIVAL_MINUTES))
        assertFalse(notify(meters = RerouteAlert.NEAR_ARRIVAL_METERS))
        assertTrue(notify(minutes = RerouteAlert.NEAR_ARRIVAL_MINUTES + 1, meters = 401.0))
    }

    // --- 문구 ----------------------------------------------------------------

    @Test
    fun `머리말은 늦는 분을 말한다`() {
        assertEquals("지금 경로로는 6분 늦어요", RerouteAlert.headline(6))
        assertEquals("지금 경로로는 늦어요", RerouteAlert.headline(0))
        assertEquals("지금 경로로는 늦어요", RerouteAlert.headline(null))
    }

    @Test
    fun `대안으로 안 늦으면 그렇게, 늦으면 덜 늦는 분을 말한다`() {
        assertEquals("9호선 → 2호선으로 가면 안 늦어요", RerouteAlert.body(suggestion()))
        assertEquals(
            "5516으로 가면 3분 덜 늦어요",
            RerouteAlert.body(suggestion(label = "5516", late = 2, saves = 3)),
        )
        assertEquals("다른 경로로 가면 안 늦어요", RerouteAlert.body(suggestion(label = null)))
        assertEquals("다른 경로로 가면 안 늦어요", RerouteAlert.body(suggestion(label = "  ")))
    }

    @Test
    fun `조사는 마지막 소리를 따른다`() {
        assertEquals("으로", RerouteAlert.roParticle("2호선"))
        assertEquals("로", RerouteAlert.roParticle("버스"))
        assertEquals("로", RerouteAlert.roParticle("지하철"))
        assertEquals("으로", RerouteAlert.roParticle("2호선(급행)"))
        assertEquals("으로", RerouteAlert.roParticle("5516"))
        assertEquals("으로", RerouteAlert.roParticle("730"))
        assertEquals("으로", RerouteAlert.roParticle("143"))
        assertEquals("로", RerouteAlert.roParticle("9"))
        assertEquals("로", RerouteAlert.roParticle("152"))
        assertEquals("로", RerouteAlert.roParticle("N61"))
        assertEquals("로", RerouteAlert.roParticle("GTX-A"))
        assertEquals("로", RerouteAlert.roParticle(""))
    }

    // --- 화면 카드 ------------------------------------------------------------

    @Test
    fun `바꿀 경로가 있으면 도착 예상과 경로 key 를 단다`() {
        val banner = RerouteAlert.banner(route(late = 6, suggestion = suggestion()))
        assertEquals(
            RerouteBanner(
                headline = "지금 경로로는 6분 늦어요",
                body = "9호선 → 2호선으로 가면 안 늦어요 · 8:57 도착 예상",
                routeKey = "transit:2호선",
            ),
            banner,
        )
        assertEquals(
            "도착 시각을 모르면 붙이지 않는다",
            "9호선 → 2호선으로 가면 안 늦어요",
            RerouteAlert.banner(route(late = 6, suggestion = suggestion(arrive = null)))?.body,
        )
    }

    @Test
    fun `대안 없이 늦기만 하면 버튼 없는 카드다`() {
        assertEquals(
            RerouteBanner(
                headline = "지금 경로로 가면 6분 늦어요",
                body = "더 일찍 닿는 경로가 없음",
                routeKey = null,
            ),
            RerouteAlert.banner(route(late = 6, suggestion = null)),
        )
    }

    @Test
    fun `안 늦거나 모르면 카드를 내지 않는다`() {
        assertNull(RerouteAlert.banner(null))
        assertNull(RerouteAlert.banner(route(late = 0, suggestion = null)))
        assertNull("구버전 서버", RerouteAlert.banner(route(late = null, suggestion = null)))
    }
}
