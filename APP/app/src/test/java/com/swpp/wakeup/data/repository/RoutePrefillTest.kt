// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.data.remote.RouteHistoryResponse
import com.swpp.wakeup.domain.model.RecentDestinations
import com.swpp.wakeup.ui.events.RoutePrefill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C8: 같은 도착지에 간 지난 선택이 있으면 경로 칸을 자동으로 채운다 · "최근 설정한 위치" 칩을 다녀온 경로 기록으로.
 * 서버(0.21.0 기록 API)는 배포 전이라 가짜 응답으로 본다.
 */
class RoutePrefillTest {

    private val gson = Gson()
    private fun response(json: String) = gson.fromJson(json, RouteCandidateResponse::class.java)
    private fun place(json: String) = gson.fromJson(json, PlaceSearchItem::class.java)

    private val destination = place("""{"name":"서울대","address":"관악로 1","lat":37.46,"lng":126.95}""")
    private val other = place("""{"name":"신림역","address":"신림로","lat":37.48,"lng":126.93}""")
    private val home = place("""{"name":"집","address":"봉천로","lat":37.48,"lng":126.95}""")

    private val candidates = """
        "car":[{"key":"car","mode":"자동차","minutes":14,"distance_m":4300,"fare":null}],
        "transit":[{"key":"transit:2호선>5513","mode":"지하철+버스","minutes":23,"distance_m":5100,"fare":1550},
                   {"key":"transit:5516","mode":"버스","minutes":27,"distance_m":4600,"fare":1500}],
        "fastest_key":"car"
    """

    @Test
    fun `같은 도착지에 간 지난 선택이면 그 경로로 채운다`() {
        val choice = response(
            """{"origin":null,$candidates,"preferred_key":"transit:5516","preferred_basis":"same_destination"}"""
        ).toChoice(destination, origin = null)
        val option = RoutePrefill.pick(choice)!!
        assertEquals("transit:5516", option.key)
        assertEquals("${option.minutesLabel} · 버스", RoutePrefill.label(option))
        assertEquals("same_destination", choice.preferredBasis)
    }

    @Test
    fun `자주 쓰는 수단 근거 · 근거 없음 · 후보에 없는 key 는 채우지 않는다`() {
        val recentMode = response(
            """{"origin":null,$candidates,"preferred_key":"transit:5516","preferred_basis":"recent_mode"}"""
        ).toChoice(destination, origin = null)
        assertNull(RoutePrefill.pick(recentMode))
        assertEquals("경로 선택 화면에서는 여전히 미리 골라 둔다", "transit:5516", recentMode.selectedKey)

        assertNull(RoutePrefill.pick(response("""{"origin":null,$candidates}""").toChoice(destination, origin = null)))
        assertNull(
            RoutePrefill.pick(
                response(
                    """{"origin":null,$candidates,"preferred_key":"transit:9999","preferred_basis":"same_destination"}"""
                ).toChoice(destination, origin = null)
            )
        )
    }

    @Test
    fun `기다리는 동안 사용자가 고르거나 바꿨으면 버린다`() {
        assertTrue(RoutePrefill.stillApplies(null, false, destination, null, destination, null))
        assertFalse("사용자가 이미 골랐다", RoutePrefill.stillApplies("car", false, destination, null, destination, null))
        assertFalse("도착지를 바꿨다", RoutePrefill.stillApplies(null, false, other, null, destination, null))
        assertFalse("출발지를 바꿨다", RoutePrefill.stillApplies(null, false, destination, other, destination, null))
        assertFalse("저장 중", RoutePrefill.stillApplies(null, true, destination, null, destination, null))
    }

    private val history = gson.fromJson(
        """{"sort":"recent","days":90,"results":[
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"관악캠퍼스 302동","address":"관악로 1",
              "lat":37.45,"lng":126.95,"kakao_place_id":"111"},"route_key":"transit:2호선>5513","trip_count":12},
            {"origin":{"kind":"point"},"destination":{"kind":"home","label":"집","lat":37.48,"lng":126.95}},
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"302동 정문","lat":37.4501,"lng":126.9501,
              "kakao_place_id":"111"}},
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"좌표 없음"}},
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"집 앞 카페","lat":37.4801,"lng":126.9501}},
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"신림역","lat":37.48,"lng":126.93}},
            {"origin":{"kind":"home"},"destination":{"kind":"place","name":"세 번째","lat":37.50,"lng":127.00}}
        ]}""",
        RouteHistoryResponse::class.java,
    )

    @Test
    fun `칩은 기록 순서대로 - 집 · 같은 장소 · 좌표 없는 줄은 빼고 두 곳`() {
        val chips = RecentDestinations.fromHistory(history.results, home)
        assertEquals(listOf("관악캠퍼스 302동", "신림역"), chips.map { it.name })
        assertEquals("111", chips[0].kakaoPlaceId)
    }

    @Test
    fun `404 · 연결 안 됨이면 null - 화면은 홈 목록 칩 그대로`() {
        val fromHome = listOf(other)
        val notDeployed = RecentDestinations.fromHistoryResponse(404, null, home)
        assertNull(notDeployed)
        assertNull(RecentDestinations.fromHistoryResponse(null, null, home))
        assertNull(RecentDestinations.fromHistoryResponse(500, history, home))
        assertEquals(fromHome, RecentDestinations.choose(notDeployed, fromHome))
    }

    @Test
    fun `정상 응답이면 기록 칩 - 비어 있으면 홈 목록 칩`() {
        val ok = RecentDestinations.fromHistoryResponse(200, history, home)!!
        assertEquals(listOf("관악캠퍼스 302동", "신림역"), RecentDestinations.choose(ok, listOf(other)).map { it.name })
        val empty = RecentDestinations.fromHistoryResponse(200, RouteHistoryResponse(), home)!!
        assertEquals(listOf(other), RecentDestinations.choose(empty, listOf(other)))
    }
}
