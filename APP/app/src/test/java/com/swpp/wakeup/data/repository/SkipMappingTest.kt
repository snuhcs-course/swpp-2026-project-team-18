// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** "안 감" 표시(skip-event-api.md §6)의 응답 매핑. */
class SkipMappingTest {

    private val gson = Gson()
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun week(skipped: Int?, vararg outcomes: String): WeeklyReportDto = gson.fromJson(
        """{"week_start":"2026-10-26","week_end":"2026-11-01","event_count":${outcomes.size},
            ${skipped?.let { "\"skipped_count\":$it," } ?: ""}
            "outcomes":[${outcomes.joinToString(",")}]}""",
        WeeklyReportDto::class.java,
    )

    private fun outcome(id: Long?, result: String?) =
        """{${id?.let { "\"event\":$it," } ?: ""}"title":"일정","date":"2026-10-27",
            "start_at":"2026-10-27T00:00:00Z"${result?.let { ",\"result\":\"$it\"" } ?: ""}}"""

    @Test
    fun `도착 기록이 없는 일정만 안 감으로 표시할 수 있다`() {
        val view = week(
            null,
            outcome(1, "unobserved"),
            outcome(2, "on_time"),
            outcome(3, "late"),
            outcome(4, "brand_new_result"),
            outcome(null, "unobserved"),
        ).toView(seoul)

        val byId = view.days.flatMap { it.outcomes }.associate { it.eventId to it.skippable }
        assertEquals(true, byId[1L])
        assertEquals(false, byId[2L])
        assertEquals(false, byId[3L])
        assertEquals("모르는 결과 값은 기록 없음으로 보이지만 표시는 내놓지 않는다", false, byId[4L])
        assertEquals("id 가 없으면 보낼 곳이 없다", false, byId[null])
    }

    @Test
    fun `뺀 일정이 있을 때만 리포트에 한 줄을 단다`() {
        assertEquals(
            "안 감으로 표시한 일정 2건은 빼고 계산함",
            week(2, outcome(1, "on_time")).toView(seoul).skippedNote,
        )
        assertNull(week(0, outcome(1, "on_time")).toView(seoul).skippedNote)
        assertNull("구버전 서버는 키가 없다", week(null, outcome(1, "on_time")).toView(seoul).skippedNote)
    }

    @Test
    fun `마지막 일정을 빼면 그날은 빈다`() {
        val view = week(null, outcome(1, "unobserved"), outcome(2, "unobserved")).toView(seoul)
        val day = view.days.first { it.outcomes.isNotEmpty() }

        assertFalse(day.isEmptyWithout(1))
        val single = week(null, outcome(7, "unobserved")).toView(seoul).days.first { it.outcomes.isNotEmpty() }
        assertTrue(single.isEmptyWithout(7))
    }

    @Test
    fun `일정 응답의 skipped 는 없으면 false 다`() {
        val old = gson.fromJson(
            """{"id":1,"title":"a","start_at":"2026-11-02T00:00:00Z","source":"manual"}""",
            EventDto::class.java,
        )
        val marked = gson.fromJson(
            """{"id":1,"title":"a","start_at":"2026-11-02T00:00:00Z","source":"manual",
                "skipped":true,"skipped_at":"2026-11-01T22:00:00Z"}""",
            EventDto::class.java,
        )

        assertFalse(old.skipped)
        assertTrue(marked.skipped)
        assertTrue(marked.toUpcoming(seoul)!!.skipped)
    }
}
