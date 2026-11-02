// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.ok
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 이동 중 "경로 정류장" 카드가 쓰는 기준 경로 구간 조회. */
class BaseRouteSegmentsTest {
    private val eventJson = """{"id":5,"title":"수업","start_at":"2026-11-03T09:00:00+09:00",
        "source":"manual","place":{"name":"서울대학교","lat":37.4598,"lng":126.9511},
        "tag":null,"tau_override":null,"route_key":"transit:2호선>5513","alarm_plan":null,
        "created_at":null}"""
    private val candidatesJson = """{"origin":{"label":"집"},"transit":[
        {"key":"transit:2호선>5513","mode":"대중교통","minutes":24,"segments":[
          {"kind":"walk","seconds":120,"label":"도보"},
          {"kind":"subway","seconds":360,"label":"2호선","vehicle":"2호선","region":"metro_seoul",
           "stops":["신림","서울대입구"]},
          {"kind":"bus","seconds":540,"label":"5513","vehicle":"5513","vehicle_type":"지선",
           "stops":["서울대입구","서울대정문"]}]},
        {"key":"transit:walkonly","mode":"대중교통","minutes":30,"segments":[
          {"kind":"walk","seconds":1800,"label":"도보"}]}],
        "car":[],"walk":[],"bicycle":[],"fastest_key":"transit:2호선>5513"}"""

    private val api = object : FakeEventsApi() {
        var candidateCalls = 0
        override suspend fun get(id: Long): Response<EventDto> = ok(fromJson(eventJson))
        override suspend fun routeCandidates(
            destLat: Double,
            destLng: Double,
            originLat: Double?,
            originLng: Double?,
            originLabel: String?,
            destLabel: String?,
        ): Response<RouteCandidateResponse> {
            candidateCalls++
            return ok(fromJson(candidatesJson))
        }
    }

    private val repository = EventRepository(api, FakeProfileApi())

    @Test
    fun `같은 key 후보의 구간을 돌려준다`() = runTest {
        val result = repository.baseRouteSegments(5L, "transit:2호선>5513")
        assertTrue("$result", result is Result.Success)
        val segments = (result as Result.Success).data
        assertNotNull(segments)
        assertEquals(3, segments!!.items.size)
        assertEquals(listOf("신림", "서울대입구"), segments.items[1].stops)
    }

    @Test
    fun `같은 key 가 없으면 null`() = runTest {
        val result = repository.baseRouteSegments(5L, "transit:9호선")
        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).data)
    }

    @Test
    fun `정류장 있는 대중교통 구간이 없으면 null`() = runTest {
        val result = repository.baseRouteSegments(5L, "transit:walkonly")
        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).data)
    }
}
