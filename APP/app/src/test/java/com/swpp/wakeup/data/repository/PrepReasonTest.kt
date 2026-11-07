// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.testing.fromJson
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ④ "계산 방법" 의 준비 시간 줄: 직접 켠 일정에만 이유가 앞에 붙는다(T2, task.md L224). */
class PrepReasonTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private fun prepNote(extra: String): String {
        val dto: EventDto = fromJson(
            """{
            "id":9,"title":"수업","start_at":"2099-01-01T01:00:00Z","source":"manual",
            "alarm_enabled":true,"alarm_on":true,
            "place":{"name":"서울대","lat":37.46,"lng":126.95},$extra
            "alarm_plan":{"status":"ok","alarm_at":"2099-01-01T00:00:00Z","prep_minutes":25,
              "prep_source":"declared_range","travel_minutes":20}
        }"""
        )
        return dto.toPlanView(zone)!!.breakdown.first { it.kind == PlanRow.Kind.PREP }.note!!
    }

    @Test
    fun `집이 아닌 출발지에서 직접 켠 일정은 이유가 앞에 붙고 기존 설명이 뒤따른다`() {
        val origin = """"origin_lat":37.50,"origin_lng":127.03,"origin_label":"친구 집","""
        val note = prepNote(""""include_prep":true,$origin""")
        assertTrue(note, note.startsWith("준비 시간 포함(직접 켬) · 신고 범위 기반"))
        // 켜지 않았거나, 출발지가 집이거나, 저장한 집이면 이유가 없다.
        assertFalse(prepNote(origin).startsWith("준비 시간 포함"))
        assertFalse(prepNote(""""include_prep":true,""").startsWith("준비 시간 포함"))
        assertFalse(prepNote(""""include_prep":true,"origin_place_id":4,$origin""").startsWith("준비 시간 포함"))
    }

    @Test
    fun `직접 켬 판정`() {
        assertTrue(prepEnabledByUser(includePrep = true, originLat = 37.5, originPlaceId = null))
        assertFalse(prepEnabledByUser(includePrep = false, originLat = 37.5, originPlaceId = null))
        assertFalse(prepEnabledByUser(includePrep = null, originLat = 37.5, originPlaceId = null))
        assertFalse(prepEnabledByUser(includePrep = true, originLat = null, originPlaceId = null))
        assertFalse(prepEnabledByUser(includePrep = true, originLat = 37.5, originPlaceId = 3L))
        assertEquals("준비 시간 포함(직접 켬) · ", PREP_USER_ENABLED_REASON)
    }
}