package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.data.remote.TripObservationDto
import com.swpp.wakeup.data.remote.TripObservationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 판정 결과 → 업로드할 관측([tripObservationOf]). 반경 진입 시각(db-schema.md A1)이 큐까지
 * 닿는지, 서버가 읽는 이름으로 나가는지, 이 필드가 생기기 전 큐도 읽히는지를 본다.
 */
class TripObservationsTest {

    private val gson = Gson()
    private val seoul = ZoneId.of("Asia/Seoul")
    private val decidedAt = OffsetDateTime.parse("2026-10-05T08:55:00+09:00").toInstant().toEpochMilli()
    private val fix = LocationFix(37.4486, 126.9526, 12f, decidedAt)

    private fun arrival(enteredAt: Long?) = tripObservationOf(
        eventId = 3,
        kind = TripObservationInput.KIND_ARRIVE,
        fix = fix,
        distanceM = 18.0,
        dwellMillis = 150_000,
        enteredAtMillis = enteredAt,
        zone = seoul,
        clientUuid = "u-1",
    )

    @Test
    fun `도착은 진입 시각과 체류를 함께 담는다`() {
        val entered = decidedAt - 150_000
        val obs = arrival(entered)

        assertEquals("2026-10-05T08:52:30+09:00", obs.enteredAt)
        // 판정 시각은 판정 fix 그대로다. 진입 시각으로 바꾸지 않는다(§5-3).
        assertEquals("2026-10-05T08:55+09:00", obs.observedAt)
        assertEquals(150, obs.dwellSeconds)
        assertEquals("u-1", obs.clientUuid)
    }

    @Test
    fun `출발에는 진입 시각도 체류도 없다`() {
        val obs = tripObservationOf(
            eventId = 3,
            kind = TripObservationInput.KIND_DEPART,
            fix = fix,
            distanceM = 160.0,
            dwellMillis = 10_000,
            enteredAtMillis = decidedAt - 10_000,
            zone = seoul,
        )
        assertNull(obs.enteredAt)
        assertNull(obs.dwellSeconds)
    }

    @Test
    fun `판정보다 늦은 진입은 보내지 않는다`() {
        // 서버가 400 으로 막고, 400 이면 큐가 그 배치를 통째로 버린다.
        assertNull(arrival(decidedAt + 1).enteredAt)
        assertEquals(arrival(decidedAt).observedAt, arrival(decidedAt).enteredAt)
    }

    @Test
    fun `서버가 읽는 이름으로 직렬화한다`() {
        val json = JsonParser.parseString(gson.toJson(arrival(decidedAt - 150_000))).asJsonObject
        assertEquals("2026-10-05T08:52:30+09:00", json["entered_at"].asString)
        assertTrue(json.has("dwell_seconds"))
        assertFalse("카멜 이름이 새면 서버가 모르는 키로 버린다", json.has("enteredAt"))
    }

    @Test
    fun `진입 시각이 없으면 키를 보내지 않는다`() {
        val json = JsonParser.parseString(gson.toJson(arrival(null))).asJsonObject
        assertFalse(json.has("entered_at"))
    }

    @Test
    fun `이 필드가 생기기 전 큐에 쌓인 관측도 읽는다`() {
        val old = """[{"event":3,"kind":"arrive","detector":"gps",
            "observed_at":"2026-10-05T08:55:00+09:00","lat":37.4486,"lng":126.9526,
            "accuracy_m":12.0,"distance_m":18.0,"dwell_seconds":150,"client_uuid":"old-1"}]"""
        val type = object : TypeToken<List<TripObservationInput>>() {}.type
        val items: List<TripObservationInput> = gson.fromJson(old, type)

        assertEquals("old-1", items.single().clientUuid)
        assertNull(items.single().enteredAt)
    }

    @Test
    fun `목록 응답에 진입 시각이 없어도 읽는다`() {
        val base = """"id":1,"event":3,"kind":"arrive","observed_at":"2026-10-05T08:55:00+09:00",
            "lat":37.4,"lng":126.9"""
        val old = gson.fromJson("{$base}", TripObservationDto::class.java)
        val new = gson.fromJson(
            "{$base,\"entered_at\":\"2026-10-05T08:52:30+09:00\"}", TripObservationDto::class.java,
        )

        assertNull(old.enteredAt)
        assertEquals("2026-10-05T08:52:30+09:00", new.enteredAt)
    }
}
