// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/** 일정 수정 PATCH 본문(docs/iter3/event-edit-api.md §1·§6). */
class EventEditPatchTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun place(name: String, lat: Double, lng: Double) =
        PlaceSearchItem(kakaoPlaceId = null, name = name, address = null, lat = lat, lng = lng, category = null)

    private val snu = place("서울대", 37.46, 126.95)
    private val library = place("중앙도서관", 37.4664, 126.952)
    private val friend = place("친구 집", 37.50, 127.00)

    private val base = EventEditValues(
        title = "자료구조",
        startAt = OffsetDateTime.parse("2026-11-03T09:00+09:00"),
        tagKey = "class",
        place = snu,
        destinationIsHome = false,
        origin = null,
        routeKey = "transit:2호선>5513",
        includePrep = null,
    )

    /** 실제로 보낼 문자열을 다시 읽는다. null 이 빠지지 않았는지까지 본다. */
    private fun sent(edited: EventEditValues) =
        JsonParser.parseString(EventEditPatch.build(base, edited).toString()).asJsonObject

    @Test
    fun `아무것도 안 바꾸면 빈 본문이다`() {
        assertEquals(0, EventEditPatch.build(base, base).size())
    }

    @Test
    fun `시각만 바꾸면 start_at 하나만 보낸다`() {
        val body = sent(base.copy(startAt = OffsetDateTime.parse("2026-11-03T10:00+09:00")))

        assertEquals(setOf("start_at"), body.keySet())
        assertEquals("2026-11-03T10:00+09:00", body["start_at"].asString)
    }

    @Test
    fun `장소를 바꾸면 장소 객체와 함께 경로를 보낸다 고르지 않았으면 빈 문자열`() {
        val body = sent(base.copy(place = library, routeKey = null))

        assertEquals("중앙도서관", body["place"].asJsonObject["name"].asString)
        assertEquals("", body["route_key"].asString)
    }

    @Test
    fun `집에서 출발로 되돌리면 출발지를 null 로 지운다`() {
        val fromFriend = base.copy(origin = friend, includePrep = true)
        val body = JsonParser.parseString(
            EventEditPatch.build(fromFriend, fromFriend.copy(origin = null, includePrep = true, routeKey = null)).toString()
        ).asJsonObject

        assertTrue(body.has("origin_lat") && body["origin_lat"].isJsonNull)
        assertTrue(body.has("origin_lng") && body["origin_lng"].isJsonNull)
        assertEquals("", body["origin_label"].asString)
        assertEquals("", body["route_key"].asString)
        assertTrue("집이면 준비 시간 스위치가 없으니 켜 둔 값을 지운다", body["include_prep"].isJsonNull)
    }

    @Test
    fun `다른 곳에서 자고 출발하면 출발지와 include_prep 를 보낸다`() {
        val body = sent(base.copy(origin = friend, includePrep = true, routeKey = "car"))

        assertEquals(37.50, body["origin_lat"].asDouble, 1e-9)
        assertEquals("친구 집", body["origin_label"].asString)
        assertEquals("car", body["route_key"].asString)
        assertTrue(body["include_prep"].asBoolean)
    }

    @Test
    fun `목적지를 집으로 바꾸면 destination_is_home 만 보낸다`() {
        val body = sent(base.copy(place = place("집", 37.48, 126.93), destinationIsHome = true, routeKey = null))

        assertTrue(body["destination_is_home"].asBoolean)
        assertFalse(body.has("place"))
    }

    @Test
    fun `태그를 기타로 바꾸면 tag_key 를 null 로 보낸다`() {
        val body = sent(base.copy(tagKey = null))

        assertTrue(body.has("tag_key") && body["tag_key"].isJsonNull)
    }

    @Test
    fun `같은 좌표의 장소는 이름이 달라도 바뀐 것이 아니다`() {
        assertEquals(0, EventEditPatch.build(base, base.copy(place = snu.copy(name = "서울대학교"))).size())
    }

    @Test
    fun `서버 일정을 수정 화면 값으로 옮긴다`() {
        val dto = Gson().fromJson(
            """{"id":3,"title":"동아리","start_at":"2026-11-03T01:30:00Z","source":"manual",
                "place":{"id":1,"name":"학생회관","address":"관악로 1","lat":37.46,"lng":126.95,"kakao_place_id":"k1"},
                "tag":{"id":1,"key":"club","label":"동아리","default_tau":0.8,"penalty_shape":null},
                "tau_override":null,"route_key":"walk","alarm_plan":null,"created_at":null,
                "origin_lat":37.5,"origin_lng":127.0,"origin_label":"본가","include_prep":true}""",
            EventDto::class.java,
        )

        val values = EventEditValues.from(dto, homePlace = null, zone = zone)!!

        assertEquals("2026-11-03T10:30+09:00", values.startAt.toString())
        assertEquals("club", values.tagKey)
        assertEquals("학생회관", values.place?.name)
        assertEquals("본가", values.origin?.name)
        assertEquals("walk", values.routeKey)
        assertEquals(true, values.includePrep)
        assertNull(EventEditValues.from(dto.copy(startAt = "bad"), null, zone))
    }
}
