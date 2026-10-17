package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.ProfileDto
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HomeDestinationMappingTest {
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Seoul")
    private val profile = gson.fromJson(
        """{"has_home":true,"home_lat":37.46,"home_lng":126.95}""", ProfileDto::class.java,
    )
    private val homeEvent = """{
        "id":1,"title":"귀가","start_at":"2030-01-01T01:00:00Z","source":"manual",
        "destination_is_home":true,"alarm_on":true,"origin_lat":37.50,"origin_lng":126.96,
        "place":{"id":null,"kakao_place_id":null,"name":"새 집","lat":37.46,"lng":126.95},
        "alarm_plan":{"status":"ok","alarm_at":"2030-01-01T00:00:00Z","prep_minutes":0}
    }"""

    @Test
    fun `서버의 집 목적지 플래그는 DTO와 캐시 직렬화를 거쳐도 보존된다`() {
        val event = gson.fromJson(homeEvent, EventDto::class.java)
        assertEquals(true, gson.toJsonTree(event).asJsonObject.get("destination_is_home")?.asBoolean)
    }

    @Test
    fun `집 목적지 생성 요청은 집 플래그를 보존하고 장소를 생략한다`() {
        val request = gson.fromJson(
            """{"title":"귀가","start_at":"2030-01-01T01:00:00Z","destination_is_home":true}""",
            EventCreateRequest::class.java,
        )
        val body = gson.toJsonTree(request).asJsonObject
        assertEquals(true, body.get("destination_is_home")?.asBoolean)
        assertFalse(body.has("place"))
    }

    @Test
    fun `집으로 가는 추적은 명시적 출발지와 서버가 해석한 현재 목적지를 쓴다`() {
        val event = gson.fromJson(homeEvent, EventDto::class.java)
        val schedule = event.toSchedule(zone, profile)!!
        assertEquals(37.50, schedule.homeLat!!, 0.0)
        assertEquals(126.96, schedule.homeLng!!, 0.0)
        assertEquals(37.46, schedule.destLat!!, 0.0)
        assertEquals(126.95, schedule.destLng!!, 0.0)
        assertEquals("새 집", event.toUpcoming(zone)!!.placeName)
    }
}
