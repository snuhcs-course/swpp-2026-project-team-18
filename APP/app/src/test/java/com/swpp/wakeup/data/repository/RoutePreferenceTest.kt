// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RouteCandidateDto
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.domain.model.RouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 경로 기본 선택(preferred_key)과 버스만 다른 경로 한 카드(minutes_max). */
class RoutePreferenceTest {

    private val gson = Gson()

    private fun response(json: String) = gson.fromJson(json, RouteCandidateResponse::class.java)

    private val destination = gson.fromJson(
        """{"name":"서울대","address":"관악로 1","lat":37.46,"lng":126.95}""",
        PlaceSearchItem::class.java,
    )

    private val candidates = """
        "car":[{"key":"car","mode":"자동차","minutes":14,"distance_m":4300,"fare":null}],
        "transit":[{"key":"transit:2호선>5513","mode":"지하철+버스","minutes":23,"distance_m":5100,"fare":1550},
                   {"key":"transit:5516","mode":"버스","minutes":27,"minutes_max":29,"distance_m":4600,"fare":1500}],
        "fastest_key":"car"
    """

    @Test
    fun `서버가 고른 후보가 있으면 그 카드와 탭으로 연다`() {
        val choice = response(
            """{"origin":null,$candidates,"preferred_key":"transit:5516","preferred_mode":"transit",
                "preferred_basis":"same_destination"}"""
        ).toChoice(destination, origin = null)

        assertEquals("transit:5516", choice.selectedKey)
        assertEquals(RouteMode.TRANSIT, choice.defaultMode())
        assertEquals("지난번에 고른 경로", choice.preferredCaption)
    }

    @Test
    fun `자주 쓰는 수단이면 그 문구를 단다`() {
        val choice = response(
            """{"origin":null,$candidates,"preferred_key":"transit:2호선>5513","preferred_basis":"recent_mode"}"""
        ).toChoice(destination, origin = null)

        assertEquals("transit:2호선>5513", choice.selectedKey)
        assertEquals("자주 쓰는 수단", choice.preferredCaption)
    }

    @Test
    fun `고를 근거가 없거나 후보에 없으면 가장 빠른 후보로 연다`() {
        val none = response("""{"origin":null,$candidates}""").toChoice(destination, origin = null)
        val missing = response(
            """{"origin":null,$candidates,"preferred_key":"transit:9999","preferred_basis":"recent_mode"}"""
        ).toChoice(destination, origin = null)

        assertEquals("car", none.selectedKey)
        assertNull(none.preferredCaption)
        assertEquals("car", missing.selectedKey)
        assertNull(missing.preferredKey)
    }

    @Test
    fun `버스만 다른 후보를 합친 카드는 시간을 범위로 쓴다`() {
        val merged = gson.fromJson(
            """{"key":"transit:5516","mode":"버스","minutes":27,"minutes_max":29,"distance_m":4600,"fare":1500}""",
            RouteCandidateDto::class.java,
        ).toOption()
        val same = gson.fromJson(
            """{"key":"walk","mode":"도보","minutes":40,"minutes_max":40,"distance_m":3000,"fare":null}""",
            RouteCandidateDto::class.java,
        ).toOption()
        val old = gson.fromJson(
            """{"key":"car","mode":"자동차","minutes":14,"distance_m":4300,"fare":null}""",
            RouteCandidateDto::class.java,
        ).toOption()

        assertEquals("27~29분", merged.minutesLabel)
        assertEquals(27, merged.minutes)
        assertEquals("40분", same.minutesLabel)
        assertEquals("14분", old.minutesLabel)
    }
}
