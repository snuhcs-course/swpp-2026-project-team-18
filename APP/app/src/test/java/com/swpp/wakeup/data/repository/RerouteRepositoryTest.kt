// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.LiveRouteRequest
import com.swpp.wakeup.data.remote.LiveRouteResponse
import com.swpp.wakeup.data.remote.RerouteRequest
import com.swpp.wakeup.data.remote.RerouteResponse
import com.swpp.wakeup.data.remote.SwitchSuggestionDto
import com.swpp.wakeup.data.repository.EventRepository.Result
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

/** 이전 제안의 매핑·경로 변경 호환성과 온디바이스 위치 정책을 확인한다. */
class RerouteRepositoryTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private class LiveApi : FakeEventsApi() {
        val calls = mutableListOf<LiveRouteRequest>()
        override suspend fun liveRoute(body: LiveRouteRequest): Response<LiveRouteResponse> {
            calls += body
            throw AssertionError("측위 좌표로 서버 경로를 조회하면 안 된다")
        }
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
                arriveAtLabel = "오전 8:57",
                lateMinutes = 0,
                savesMinutes = 6,
            ),
            dto.toSuggestion(seoul),
        )
        assertEquals(
            "UTC 로 와도 기기 시간대로 읽는다",
            "오전 8:57",
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
    fun `이동 중 경로 확인은 측위 좌표를 API로 보내지 않는다`() = runTest {
        val api = LiveApi()
        val result = EventRepository(api, FakeProfileApi()).liveRoute(1L, GeoPoint(37.5, 127.0))
        assertEquals(Result.Success(null), result)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `출발 전 경로 확인도 서버를 호출하지 않는다`() = runTest {
        val api = LiveApi()
        val result = EventRepository(api, FakeProfileApi()).liveRoute(1L, GeoPoint(37.5, 127.0), preDeparture = true)
        assertEquals(Result.Success(null), result)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `측위 좌표의 값이나 일정 키에 상관없이 원격 경로 조회는 꺼져 있다`() = runTest {
        val api = LiveApi()
        val repository = EventRepository(api, FakeProfileApi())
        for (point in listOf(GeoPoint(0.0, 0.0), GeoPoint(Double.NaN, Double.POSITIVE_INFINITY))) {
            assertEquals(Result.Success(null), repository.liveRoute(-1L, point))
        }
        assertTrue(api.calls.isEmpty())
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
        assertEquals(Result.Failure("일정 시작 6시간 전후에만 경로를 바꿀 수 있어요"), result)
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
