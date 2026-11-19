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
    fun `띠 머리 - 사본이 있으면 받아 둔 경로라고 말한다`() {
        assertEquals("네트워크가 없어 받아 둔 경로를 보여 줘요", TripTabLogic.savedRouteLabel(now - 12 * minute))
        assertEquals("오프라인 · 가는 길을 받지 못했어요", TripTabLogic.savedRouteLabel(null))
    }

    @Test
    fun `띠 설명 - 저장 시각을 앱 표기로 · 어제 · 날짜`() {
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        fun at(d: Int, h: Int, m: Int) =
            java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, seoul).toInstant().toEpochMilli()
        val nowAt = at(19, 9, 0)
        assertEquals(
            "오전 8:13에 받은 경로예요. 실시간 도착 정보는 연결되면 다시 보여요",
            TripTabLogic.savedRouteBody(at(19, 8, 13), nowAt, seoul),
        )
        assertTrue(TripTabLogic.savedRouteBody(at(18, 17, 50), nowAt, seoul).startsWith("어제 오후 5:50에 받은 경로예요"))
        assertTrue(TripTabLogic.savedRouteBody(at(17, 0, 5), nowAt, seoul).startsWith("11월 17일 오전 12:05에 받은 경로예요"))
        assertTrue(TripTabLogic.savedRouteBody(null, nowAt, seoul).contains("받지 못했어요"))
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
