// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알람 추가 도착지 칩의 최근 도착지(H2-8 `545:192`) */
class RecentDestinationsTest {

    private fun place(name: String, lat: Double, lng: Double, kakao: String? = null) =
        PlaceSearchItem(kakaoPlaceId = kakao, name = name, address = null, lat = lat, lng = lng, category = null)

    private val home = place("집", 37.4800, 126.9300)
    private val snu = place("302동 105호", 37.4490, 126.9520, "k1")
    private val gangnam = place("강남역", 37.4980, 127.0276, "k2")
    private val hongdae = place("홍대입구역", 37.5572, 126.9245, "k3")

    @Test
    fun `최신순 2곳`() {
        val picked = RecentDestinations.pick(
            listOf(
                DestinationCandidate(1, hongdae, false),
                DestinationCandidate(3, snu, false),
                DestinationCandidate(2, gangnam, false),
            ),
            home,
        )
        assertEquals(listOf("302동 105호", "강남역"), picked.map { it.name })
    }

    @Test
    fun `같은 장소는 한 번만`() {
        val sameByCoords = place("서울대 302동", 37.4491, 126.9521)
        val picked = RecentDestinations.pick(
            listOf(
                DestinationCandidate(5, snu, false),
                DestinationCandidate(4, snu, false),
                DestinationCandidate(3, sameByCoords, false),
                DestinationCandidate(2, gangnam, false),
            ),
            home,
        )
        assertEquals(listOf("302동 105호", "강남역"), picked.map { it.name })
    }

    @Test
    fun `집은 뺀다`() {
        val picked = RecentDestinations.pick(
            listOf(
                DestinationCandidate(9, home, true),
                DestinationCandidate(8, place("우리집", 37.48001, 126.93001), false),
                DestinationCandidate(7, gangnam, false),
            ),
            home,
        )
        assertEquals(listOf("강남역"), picked.map { it.name })
    }

    @Test
    fun `일정이 없으면 빈 목록`() {
        assertTrue(RecentDestinations.pick(emptyList(), null).isEmpty())
    }

    @Test
    fun `카카오 id 가 같으면 좌표가 달라도 같은 장소`() {
        assertTrue(RecentDestinations.samePlace(snu, snu.copy(lat = 37.0, lng = 127.5)))
    }
}
