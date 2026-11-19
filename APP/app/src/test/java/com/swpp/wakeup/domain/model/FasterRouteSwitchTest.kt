// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.ui.events.AlarmConfirmText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 더 빠른 경로 카드 문구 · 바꾼 뒤 스낵바 · 알람 확인 시트의 표시 조건(11-16 사용자: 고를 수 있는 버튼) */
class FasterRouteSwitchTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 17, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `카드 둘째 줄은 경로 이름 · 이동 시간 · 몇 분 빠른지`() {
        val offer = FasterRouteOffer("transit:2", "지하철 2호선 → 버스", 7, at(7, 40), travelMinutes = 35)
        assertEquals("지하철 2호선 → 버스 · 35분 (7분 빠름)", FasterRoute.detail(offer))
        assertEquals("다른 경로 (6분 빠름)", FasterRoute.detail(FasterRouteOffer("k", null, 6, 0L)))
    }

    @Test
    fun `대안의 이동 시간은 지금 경로에서 빠른 만큼 뺀 값이다`() {
        val offer = FasterRoute.offer(true, false, true, at(7, 40), at(6, 0), "walk", "transit", "2호선", 7, currentTravelMinutes = 42)
        assertEquals(35, offer?.travelMinutes)
        val unknown = FasterRoute.offer(true, false, true, at(7, 40), at(6, 0), "walk", "transit", "2호선", 7)
        assertNull(unknown?.travelMinutes)
    }

    @Test
    fun `알람 시각이 바뀌면 바뀐 시각을 말한다`() {
        assertEquals("경로를 바꿨어요. 알람이 오전 7:47로 바뀌었어요", FasterRoute.switchedMessage(at(7, 40), at(7, 47), zone = zone))
        assertEquals("경로를 바꿨어요. 알람이 오전 7:50으로 바뀌었어요", FasterRoute.switchedMessage(at(7, 40), at(7, 50), zone = zone))
        assertEquals("경로를 바꿨어요. 알람 시각은 그대로예요", FasterRoute.switchedMessage(at(7, 40), at(7, 40), zone = zone))
        assertEquals("경로를 바꿨어요. 알람을 다시 맞췄어요", FasterRoute.switchedMessage(at(7, 40), null, zone = zone))
        assertEquals("경로를 되돌렸어요. 알람이 오전 7:40으로 바뀌었어요", FasterRoute.switchedMessage(at(7, 47), at(7, 40), undo = true, zone = zone))
    }

    private fun plan(alt: String) = Gson().fromJson(
        """{"status":"ok","alarm_at":"2026-09-18T08:25:00+09:00","depart_by":"2026-09-18T08:53:00+09:00",
            "arrive_at":"2026-09-18T09:35:00+09:00","prep_minutes":28,"travel_minutes":42,"buffer_minutes":25$alt}""",
        AlarmPlanDto::class.java,
    )

    private fun confirm(p: AlarmPlanDto, routeKey: String? = "walk") = AlarmConfirmText.build(
        eventId = 7, title = "전공 발표", placeName = "302동 105호", startAt = "2026-09-18T10:00:00+09:00", plan = p,
        originLabel = null, routeLabel = null, tagLabel = null, routeKey = routeKey,
        today = LocalDate.of(2026, 9, 17), zone = zone,
        nowMillis = ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
    )

    @Test
    fun `알람 확인 시트는 만들기 응답이 준 더 빠른 경로만 보인다`() {
        val v = confirm(plan(""","alt_route_key":"transit","alt_route_label":"2호선","alt_faster_minutes":7"""))!!
        assertEquals("transit", v.faster?.routeKey)
        assertEquals(35, v.faster?.travelMinutes)
        assertEquals("walk", v.routeKey)
        // 대안이 없으면 지어내지 않는다
        assertNull(confirm(plan(""))!!.faster)
        // 서버가 대안을 붙이는 기준(1분 이상)과 같다 — 지도에 대안 선이 있으면 버튼도 있다(11-17)
        assertEquals(3, confirm(plan(""","alt_route_key":"transit","alt_faster_minutes":3"""))!!.faster?.fasterMinutes)
        assertNull(confirm(plan(""","alt_route_key":"transit","alt_faster_minutes":0"""))!!.faster)
        // 이미 그 경로면 제안하지 않는다
        assertNull(confirm(plan(""","alt_route_key":"walk","alt_faster_minutes":9"""), routeKey = "walk")!!.faster)
    }
}
