// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.LiveRouteRequest
import com.swpp.wakeup.data.remote.LiveRouteResponse
import com.swpp.wakeup.data.remote.RerouteRequest
import com.swpp.wakeup.data.remote.RerouteResponse
import com.swpp.wakeup.data.remote.SwitchSuggestionDto
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.domain.model.SwitchSuggestion
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 이동 중 경로 바꾸기(reroute-api.md)의 응답 매핑과 저장소 동작. */
class RerouteRepositoryTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private val livePath = """[[37.50,127.00],[37.51,127.01]]"""

    private fun liveJson(extra: String = "") =
        """{"route":{"route_key":"transit:9호선","label":"9호선","minutes":24,
            "is_alternative":false,"path":$livePath},"degraded":false$extra}"""

    private fun liveApi(json: String) = object : FakeEventsApi() {
        override suspend fun liveRoute(body: LiveRouteRequest): Response<LiveRouteResponse> =
            ok(fromJson(json))
    }

    private fun liveRoute(json: String): LiveRoute {
        val result = runBlockingLive(json)
        assertTrue("성공이어야 한다: $result", result is Result.Success)
        return (result as Result.Success).data!!
    }

    private fun runBlockingLive(json: String): Result<LiveRoute?> = kotlinx.coroutines.runBlocking {
        EventRepository(liveApi(json), FakeProfileApi()).liveRoute(1L, GeoPoint(37.5, 127.0))
    }

    // --- 제안 매핑 -------------------------------------------------------------

    @Test
    fun `제안을 기기 시간대의 도착 시각과 함께 옮긴다`() {
        val dto = fromJson<SwitchSuggestionDto>(
            """{"reason":"late_now","route_key":"transit:2호선","label":"9호선 → 2호선",
                "arrive_at":"2026-11-02T08:57:00+09:00","late_minutes":0,"saves_minutes":6,
                "realtime":true}"""
        )
        assertEquals(
            SwitchSuggestion(
                routeKey = "transit:2호선",
                label = "9호선 → 2호선",
                arriveAtLabel = "8:57",
                lateMinutes = 0,
                savesMinutes = 6,
            ),
            dto.toSuggestion(seoul),
        )
        assertEquals(
            "UTC 로 와도 기기 시간대로 읽는다",
            "8:57",
            fromJson<SwitchSuggestionDto>("""{"route_key":"walk","arrive_at":"2026-11-01T23:57:00Z"}""")
                .toSuggestion(seoul)?.arriveAtLabel,
        )
    }

    @Test
    fun `경로 key 가 없으면 바꿀 수 없으니 버린다`() {
        assertNull(fromJson<SwitchSuggestionDto>("""{"label":"2호선"}""").toSuggestion(seoul))
        assertNull(fromJson<SwitchSuggestionDto>("""{"route_key":"  "}""").toSuggestion(seoul))
    }

    @Test
    fun `빠진 값과 깨진 값은 안전한 쪽으로 채운다`() {
        val view = fromJson<SwitchSuggestionDto>(
            """{"route_key":"car","label":" ","arrive_at":"내일","late_minutes":-3,"saves_minutes":0}"""
        ).toSuggestion(seoul)!!
        assertNull(view.label)
        assertNull(view.arriveAtLabel)
        assertEquals(0, view.lateMinutes)
        assertEquals("몇 분 일찍인지 모르면 1분으로 본다", 1, view.savesMinutes)
    }

    @Test
    fun `현재 위치 경로에 늦는 분과 제안을 붙인다`() {
        val route = liveRoute(
            liveJson(
                ""","arrival":{"route_key":"transit:9호선","arrive_at":"2026-11-02T09:06:00+09:00",
                    "late_minutes":6,"realtime":false},
                   "switch_suggestion":{"reason":"late_now","route_key":"transit:2호선",
                    "label":"9호선 → 2호선","late_minutes":0,"saves_minutes":9}"""
            )
        )
        assertEquals(6, route.lateMinutes)
        assertEquals("transit:2호선", route.suggestion?.routeKey)
        assertEquals(9, route.suggestion?.savesMinutes)
        assertEquals(
            java.time.OffsetDateTime.parse("2026-11-02T09:06:00+09:00").toInstant().toEpochMilli(),
            route.arriveAtMillis,
        )
    }
    @Test
    fun `도착 시각이 없거나 깨졌으면 null`() {
        assertNull(liveRoute(liveJson()).arriveAtMillis)
        assertNull(
            liveRoute(liveJson(""","arrival":{"arrive_at":"내일","late_minutes":2}""")).arriveAtMillis
        )
    }

    @Test
    fun `구버전 서버면 늦는 분과 제안이 없다`() {
        val route = liveRoute(liveJson())
        assertNull(route.lateMinutes)
        assertNull(route.suggestion)
        assertEquals(24, route.minutes)
    }

    // --- 바꾸기 ---------------------------------------------------------------

    private class RerouteApi(private val respond: () -> Response<RerouteResponse>) : FakeEventsApi() {
        val calls = mutableListOf<Pair<Long, RerouteRequest>>()
        override suspend fun reroute(id: Long, body: RerouteRequest): Response<RerouteResponse> {
            calls += id to body
            return respond()
        }
    }

    private fun conflict(code: String) =
        httpError<RerouteResponse>(409, """{"error":{"code":"$code","message":"…","details":{}}}""")

    @Test
    fun `알림을 보고 바꾸면 late_now 를 함께 보낸다`() = runTest {
        val api = RerouteApi { ok(RerouteResponse(toRouteKey = "transit:2호선")) }
        val result = EventRepository(api, FakeProfileApi()).reroute(7L, "transit:2호선", fromSuggestion = true)
        assertTrue(result is Result.Success)
        assertEquals(listOf(7L to RerouteRequest("transit:2호선", "late_now")), api.calls)

        EventRepository(api, FakeProfileApi()).reroute(7L, "walk", fromSuggestion = false)
        assertNull("직접 고르면 reason 을 비운다", api.calls.last().second.reason)
    }

    @Test
    fun `이미 그 경로면 바꾼 것으로 본다`() = runTest {
        val result = EventRepository(RerouteApi { conflict("same_route") }, FakeProfileApi())
            .reroute(7L, "transit:2호선", fromSuggestion = true)
        assertTrue("same_route 는 성공이다: $result", result is Result.Success)
    }

    @Test
    fun `이동 범위 밖이면 그 이유를 말한다`() = runTest {
        val result = EventRepository(RerouteApi { conflict("outside_trip") }, FakeProfileApi())
            .reroute(7L, "transit:2호선", fromSuggestion = true)
        assertEquals(Result.Failure("일정 시작 6시간 전후에만 경로를 바꿀 수 있음"), result)
    }

    @Test
    fun `그 밖의 실패는 실패로 올린다`() = runTest {
        val result = EventRepository(
            RerouteApi { httpError(400, """{"error":{"code":"invalid_route_key","message":"x","details":{}}}""") },
            FakeProfileApi(),
        ).reroute(7L, "bad", fromSuggestion = true)
        assertTrue("실패여야 한다: $result", result is Result.Failure)
    }
}
