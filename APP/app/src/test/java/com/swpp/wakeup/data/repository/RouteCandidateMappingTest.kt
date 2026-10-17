package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.domain.model.RouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCandidateMappingTest {
    private val gson = Gson()
    private val destination = gson.fromJson(
        """{"name":"서울대학교","lat":37.5,"lng":126.95}""", PlaceSearchItem::class.java,
    )

    private fun candidate(key: String, minutes: Int) =
        """{"key":"$key","mode":"수단","minutes":$minutes,"guide":"서버의 안내 문장"}"""

    private fun map(json: String) = gson.fromJson(json, RouteCandidateResponse::class.java).toChoice(destination)

    @Test
    fun `수단별 후보 아홉 개와 안내를 보존하고 서버의 최단 후보를 고른다`() {
        val choice = map("""
            {"origin":{"label":"신림역"},
             "car":[${(1..4).joinToString { candidate("car:$it", 20 + it) }}],
             "transit":[${(1..3).joinToString { candidate("transit:$it", 10 + it) }}],
             "walk":[${candidate("walk", 40)}],"bicycle":[${candidate("bicycle", 30)}],
             "results":[${candidate("stale", 1)}],"fastest_key":"transit:1"}
        """)
        assertEquals(9, choice.options.size)
        assertEquals(listOf(4, 3, 1, 1), RouteMode.entries.map { choice.optionsOf(it).size })
        assertEquals("transit:1", choice.selectedKey)
        assertEquals(RouteMode.TRANSIT, choice.defaultMode())
        assertEquals("신림역", choice.originLabel)
        assertEquals("서울대학교", choice.destination)
        assertTrue(choice.options.all { it.guide == "서버의 안내 문장" })
        assertEquals(RouteMode.CAR, choice.options.first().travelMode)
    }

    @Test
    fun `구버전 results로 전환하고 잘못된 최단 키는 실제 후보로 대체한다`() {
        val choice = map("""
            {"results":[${candidate("transit:A", 10)},
              {"key":"car","mode":"자동차","minutes":10,"guide":null}],
             "car":null,"transit":null,"fastest_key":"missing"}
        """)
        assertEquals(2, choice.options.size)
        assertEquals("transit:A", choice.selectedKey)
        assertNull(choice.options.first { it.key == "car" }.guide)
        assertEquals(RouteMode.TRANSIT, choice.defaultMode())
    }

    @Test
    fun `빈 수단별 응답은 오래된 results를 사용하지 않고 선택도 비운다`() {
        val choice = map("""
            {"car":[],"transit":[],"walk":[],"bicycle":[],"degraded":true,
             "results":[${candidate("car", 10)}],"fastest_key":"car"}
        """)
        assertTrue(choice.options.isEmpty())
        assertNull(choice.selectedKey)
        assertTrue(map("{}").options.isEmpty())
    }
}
