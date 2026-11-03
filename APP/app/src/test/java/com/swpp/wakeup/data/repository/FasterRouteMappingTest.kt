// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventUpdateRequest
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.ok
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 알람 전 더 빠른 경로: 홈 행·계획 화면으로 옮기는 값과 경로 변경 요청. */
class FasterRouteMappingTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private fun eventJson(alarmAt: String, alt: String = """"alt_route_key":"transit:2호선",
        "alt_route_label":"9호선 → 2호선","alt_faster_minutes":6,""") = """{
        "id":9,"title":"수업","start_at":"2099-01-01T01:00:00Z","source":"manual",
        "alarm_enabled":true,"alarm_on":true,
        "place":{"name":"서울대","lat":37.46,"lng":126.95},
        "alarm_plan":{"status":"ok","alarm_at":"$alarmAt","depart_by":"2098-12-31T23:50:00Z",
          "arrive_at":"2099-01-01T00:50:00Z","route_key":"transit:9호선",$alt "prep_minutes":5}
    }"""

    private fun event(alarmAt: String = "2099-01-01T00:00:00Z"): EventDto = fromJson(eventJson(alarmAt))

    @Test
    fun `알람 전이고 5분 이상 빠른 대안이 있으면 홈 행에 제안이 붙는다`() {
        val offer = event().toUpcoming(zone)!!.fasterRoute
        assertEquals("transit:2호선", offer?.routeKey)
        assertEquals("9호선 → 2호선", offer?.label)
        assertEquals(6, offer?.fasterMinutes)
    }

    @Test
    fun `알람이 이미 울렸으면 제안이 없다`() {
        assertNull(event("2000-01-01T00:00:00Z").toUpcoming(zone)!!.fasterRoute)
    }

    @Test
    fun `대안이 없으면 제안이 없다`() {
        val dto: EventDto = fromJson(eventJson("2099-01-01T00:00:00Z", alt = ""))
        assertNull(dto.toUpcoming(zone)!!.fasterRoute)
    }

    @Test
    fun `계획 화면 값에 알람 출발 도착 시각과 대안 key 가 들어간다`() {
        val dto = event()
        val upcomingAlarm = dto.toUpcoming(zone)!!.alarmAtMillis
        val plan = dto.toPlanView(zone)!!
        assertEquals(upcomingAlarm, plan.alarmAtMillis)
        assertEquals(
            java.time.Instant.parse("2098-12-31T23:50:00Z").toEpochMilli(),
            plan.departByMillis,
        )
        assertEquals(
            java.time.Instant.parse("2099-01-01T00:50:00Z").toEpochMilli(),
            plan.arriveAtMillis,
        )
        assertEquals("transit:2호선", plan.altRouteKey)
    }

    @Test
    fun `경로 바꾸기는 route_key 만 보낸다`() = runTest {
        var sent: EventUpdateRequest? = null
        val api = object : FakeEventsApi() {
            override suspend fun update(id: Long, body: EventUpdateRequest): Response<EventDto> {
                sent = body
                return ok(event())
            }
        }
        val result = EventRepository(api, FakeProfileApi()).switchRoute(9L, "transit:2호선")
        assertTrue("$result", result is Result.Success)
        assertEquals(EventUpdateRequest(routeKey = "transit:2호선"), sent)
    }
}
