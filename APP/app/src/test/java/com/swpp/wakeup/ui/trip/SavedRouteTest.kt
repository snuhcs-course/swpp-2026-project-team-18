// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.trip

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.data.local.RouteSegmentsStore
import com.swpp.wakeup.domain.model.RouteArrival
import com.swpp.wakeup.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 오프라인 이동 탭의 저장된 가는 길(11-17 사용자: 인터넷이 없으면 가는 길이 비었다) */
class SavedRouteTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `띠 머리는 사본의 나이를 말한다`() {
        assertEquals("오프라인 · 방금 정보", TripTabLogic.savedRouteLabel(now - 20_000, now))
        assertEquals("오프라인 · 12분 전 정보", TripTabLogic.savedRouteLabel(now - 12 * minute, now))
        assertEquals("오프라인 · 3시간 전 정보", TripTabLogic.savedRouteLabel(now - 190 * minute, now))
        assertEquals("오프라인 · 2일 전 정보", TripTabLogic.savedRouteLabel(now - 2 * 24 * 60 * minute, now))
        assertEquals("오프라인 · 가는 길을 받지 못했어요", TripTabLogic.savedRouteLabel(null, now))
    }

    @Test
    fun `띠 설명은 실시간 도착이 비었다는 것과 사본이 없을 때를 가른다`() {
        assertTrue(TripTabLogic.savedRouteBody(true).contains("실시간 도착 정보는 연결되면 다시 보여요"))
        assertTrue(TripTabLogic.savedRouteBody(false).contains("받지 못했어요"))
    }

    private val bus = RouteSegment(
        kind = RouteSegment.Kind.BUS, seconds = 900, label = "5511", lineName = "5511", busType = "지선",
        stops = listOf("서울대입구역", "봉천사거리", "서울대 정문"),
        arrivals = listOf(RouteArrival(120, "2분 후")),
        headwayMinutes = 8,
        alternatives = listOf(
            RouteSegment(RouteSegment.Kind.BUS, 900, "5513", lineName = "5513", arrivals = listOf(RouteArrival(300, "5분 후"))),
        ),
    )

    @Test
    fun `사본에는 실시간 도착을 남기지 않고 노선 · 정류장 · 배차는 남긴다`() {
        val kept = RouteSegmentsStore.withoutLive(bus)
        assertTrue(kept.arrivals.isEmpty())
        assertTrue(kept.alternatives.all { it.arrivals.isEmpty() })
        assertEquals(bus.stops, kept.stops)
        assertEquals(8, kept.headwayMinutes)
        assertEquals("5511", kept.label)
    }

    @Test
    fun `사본은 JSON 으로 저장했다 그대로 되살아난다`() {
        val saved = RouteSegmentsStore.Saved("transit:1", now, listOf(RouteSegmentsStore.withoutLive(bus)))
        val gson = Gson()
        val type = object : TypeToken<Map<String, RouteSegmentsStore.Saved>>() {}.type
        val back: Map<String, RouteSegmentsStore.Saved> = gson.fromJson(gson.toJson(mapOf("7" to saved)), type)
        assertEquals(saved, back["7"])
    }
}
