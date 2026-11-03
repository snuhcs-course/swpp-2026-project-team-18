// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.bufferNote
import com.swpp.wakeup.data.repository.toView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** "적용 알람" 정리(경로 기준 문구를 앱이 만든다)와 안전 버퍼 표시. */
class RouteBasisTest {

    @Test
    fun `대중교통 key 는 노선을 화살표로 잇는다`() {
        assertEquals("9호선 → 2호선 · 6.0km 기준", RouteBasis.label("transit:9호선>2호선", "서버 문장", null, 6_000))
        assertEquals("5516 기준", RouteBasis.label("transit:5516", null, null, null))
    }

    @Test
    fun `자동차 도보 자전거는 수단 이름으로 쓴다`() {
        assertEquals("자동차 · 4.3km 기준", RouteBasis.label("car", null, null, 4_300))
        assertEquals("자동차 기준", RouteBasis.label("car:fastest", null, null, null))
        assertEquals("도보 기준", RouteBasis.label("walk", null, null, 0))
        assertEquals("자전거 기준", RouteBasis.label("bicycle", null, null, null))
    }

    @Test
    fun `key 를 읽지 못하면 서버 문장으로 돌아간다`() {
        assertEquals("2호선 경로 · 10.0km 기준", RouteBasis.label("weird", "2호선 경로", "2호선", 10_000))
        assertEquals("2호선 기준", RouteBasis.label(null, " ", "2호선", null))
        assertNull(RouteBasis.label(null, null, null, null))
        assertNull(RouteBasis.fromKey("transit:"))
    }

    @Test
    fun `안전 버퍼 줄은 지각 기록으로 조정됐는지 밝힌다`() {
        assertTrue(bufferNote("learned").startsWith("지각 기록으로 조정됨"))
        assertTrue(bufferNote("fixed").startsWith("고정값"))
        assertTrue("모르는 값은 고정값으로 본다", bufferNote(null).startsWith("고정값"))
    }

    @Test
    fun `주간 리포트는 그 주의 버퍼 조정 이유와 지금 버퍼를 보여 준다`() {
        val dto = Gson().fromJson(
            """{"week_start":"2026-10-26","week_end":"2026-11-01","event_count":3,
                "buffer":{"current_minutes":8,"current_source":"learned","adjustments":[
                  {"adjusted_at":"2026-10-28T14:00:00Z","previous_minutes":10,"minutes":8,
                   "reason":"unused","reason_text":"최근 5번 모두 먼저 도착했음. 버퍼를 10분에서 8분으로 줄임."},
                  {"reason":"unused","reason_text":"  "}]}}""",
            WeeklyReportDto::class.java,
        )

        val view = dto.toView(ZoneId.of("Asia/Seoul"))

        assertEquals(listOf("최근 5번 모두 먼저 도착했음. 버퍼를 10분에서 8분으로 줄임."), view.bufferNotes)
        assertEquals("지금 안전 버퍼 8분 · 지각 기록으로 조정됨", view.bufferCurrentLabel)
    }

    @Test
    fun `조정이 없던 주나 구버전 서버는 버퍼 카드를 그리지 않는다`() {
        val none = Gson().fromJson(
            """{"week_start":"2026-10-26","week_end":"2026-11-01",
                "buffer":{"current_minutes":10,"current_source":"fixed","adjustments":[]}}""",
            WeeklyReportDto::class.java,
        ).toView(ZoneId.of("Asia/Seoul"))
        val old = WeeklyReportDto(weekStart = "2026-10-26", weekEnd = "2026-11-01").toView(ZoneId.of("Asia/Seoul"))

        assertTrue(none.bufferNotes.isEmpty())
        assertNull(none.bufferCurrentLabel)
        assertTrue(old.bufferNotes.isEmpty())
    }
}
