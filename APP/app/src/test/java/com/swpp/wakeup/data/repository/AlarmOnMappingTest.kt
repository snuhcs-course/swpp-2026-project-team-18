package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.ProfileDto
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AlarmOnMappingTest {
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Seoul")
    private val profile = gson.fromJson("""{"has_home":true,"home_lat":37.5,"home_lng":127.0}""", ProfileDto::class.java)

    private fun event(flag: String?): EventDto = gson.fromJson("""{
        "id":1,"title":"약속","start_at":"2030-01-01T01:00:00Z","source":"manual",
        "alarm_enabled":true,${if (flag == null) "" else "\"alarm_on\":$flag,"}
        "place":{"name":"카페","lat":37.6,"lng":127.1},
        "alarm_plan":{"status":"ok","alarm_at":"2030-01-01T00:00:00Z","prep_minutes":5}
    }""", EventDto::class.java)

    @Test
    fun `서버 OFF는 계산된 시각과 명시적 ON이 있어도 등록하지 않는다`() {
        val event = event("false")
        assertTrue(event.toUpcoming(zone)!!.hasAlarm)
        assertFalse(event.toUpcoming(zone)!!.alarmOn)
        assertNull(event.toSchedule(zone, profile))
    }

    @Test
    fun `서버 판정이 없거나 null이면 등록하지 않는다`() {
        for (flag in listOf(null, "null")) {
            assertFalse(event(flag).toUpcoming(zone)!!.alarmOn)
            assertNull(event(flag).toSchedule(zone, profile))
        }
    }

    @Test
    fun `서버 ON만 실제 예약 후보가 된다`() {
        val event = event("true")
        assertTrue(event.toUpcoming(zone)!!.alarmOn)
        assertEquals(1L, event.toSchedule(zone, profile)!!.eventId)
        assertEquals(event.toUpcoming(zone)!!.alarmAtEpochSecond!! * 1000,
            event.toSchedule(zone, profile)!!.alarmAtMillis)
    }
}
