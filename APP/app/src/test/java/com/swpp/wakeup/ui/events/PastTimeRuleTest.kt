// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.repository.EventEditValues
import com.swpp.wakeup.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 이미 지난 시각 막기(11-16 버그: 지난 시각으로 만들면 알람이 안 걸리는데 "추가했어요" 가 떴다) */
class PastTimeRuleTest {

    private val now = LocalDateTime.of(2026, 11, 16, 17, 30)
    private val today = now.toLocalDate()

    @Test
    fun `지금보다 이르거나 같으면 지난 시각이다`() {
        assertTrue(PastTimeRule.isPast(today, 17, 0, now))
        assertTrue(PastTimeRule.isPast(today, 17, 30, now))
        assertFalse(PastTimeRule.isPast(today, 17, 35, now))
        assertFalse(PastTimeRule.isPast(today.plusDays(1), 9, 0, now))
        assertTrue(PastTimeRule.isPast(today.minusDays(1), 23, 0, now))
    }

    @Test
    fun `새 일정은 시각을 고른 뒤에만 본다`() {
        val s = HomeViewModel.AddState(date = today, hour = 9, minute = 0)
        assertFalse("시각을 고르기 전에는 막지 않는다", PastTimeRule.isPast(s, now))
        assertTrue(PastTimeRule.isPast(s.copy(timePicked = true), now))
    }

    @Test
    fun `수정은 시각을 바꿨을 때만 본다`() {
        // 이미 시작한 일정의 제목만 고치는 것은 막지 않는다
        val start = ZonedDateTime.of(today, java.time.LocalTime.of(17, 0), ZoneId.systemDefault()).toOffsetDateTime()
        val base = EventEditValues(
            title = "수업", startAt = start, tagKey = null, place = null, destinationIsHome = false,
            origin = null, routeKey = null, includePrep = null,
        )
        val editing = HomeViewModel.AddState(
            title = "수업 2", date = today, hour = 17, minute = 0, editingEventId = 1, editBase = base,
        )
        assertFalse(PastTimeRule.isPast(editing, now))
        assertTrue("다른 지난 시각으로 바꾸면 막는다", PastTimeRule.isPast(editing.copy(hour = 16), now))
        assertFalse(PastTimeRule.isPast(editing.copy(date = today.plusDays(1)), now))
    }

    @Test
    fun `지난 시각이면 버튼 위 한 줄도 그 이유가 가장 먼저다`() {
        assertEquals(PastTimeRule.ADD_MESSAGE, AddRequiredRule.hint(listOf(AddRequired.ROUTE), sameEndpoints = true, pastTime = true))
        assertEquals(AddRequiredRule.SAME_HINT, AddRequiredRule.hint(listOf(AddRequired.ROUTE), sameEndpoints = true, pastTime = false))
    }

    @Test
    fun `서버가 정한 알람 시각이 이미 지났는지`() {
        val nowMillis = java.time.OffsetDateTime.parse("2026-11-16T17:30:00+09:00").toInstant().toEpochMilli()
        assertTrue(PastTimeRule.alarmPassed("2026-11-16T16:50:00+09:00", nowMillis))
        assertTrue(PastTimeRule.alarmPassed("2026-11-16T17:30:00+09:00", nowMillis))
        assertFalse(PastTimeRule.alarmPassed("2026-11-17T07:40:00+09:00", nowMillis))
        assertFalse("읽지 못하면 지났다고 하지 않는다", PastTimeRule.alarmPassed(null, nowMillis))
        assertFalse(PastTimeRule.alarmPassed("not-a-time", nowMillis))
    }

    private val nowMillis = java.time.OffsetDateTime.parse("2026-11-16T17:30:00+09:00").toInstant().toEpochMilli()

    private fun plan(status: String, alarmAt: String?, effective: String? = null) =
        com.google.gson.Gson().fromJson(
            """{"status":"$status"${alarmAt?.let { ",\"alarm_at\":\"$it\"" } ?: ""}${effective?.let { ",\"effective_alarm_at\":\"$it\"" } ?: ""}}""",
            com.swpp.wakeup.data.remote.AlarmPlanDto::class.java,
        )

    @Test
    fun `계산된 계획의 울릴 시각이 지났으면 알람을 맞출 수 없다`() {
        assertTrue(PastTimeRule.tooLate(plan("ok", "2026-11-16T17:10:00+09:00"), nowMillis))
        assertTrue("지금과 같아도 늦다", PastTimeRule.tooLate(plan("ok", "2026-11-16T17:30:00+09:00"), nowMillis))
        assertFalse(PastTimeRule.tooLate(plan("ok", "2026-11-17T07:40:00+09:00"), nowMillis))
    }

    @Test
    fun `내가 정한 알람 시각(effective)이 추천보다 먼저다`() {
        assertTrue(PastTimeRule.tooLate(plan("ok", "2026-11-17T07:40:00+09:00", "2026-11-16T17:00:00+09:00"), nowMillis))
        assertFalse(PastTimeRule.tooLate(plan("ok", "2026-11-16T17:00:00+09:00", "2026-11-17T07:40:00+09:00"), nowMillis))
    }

    @Test
    fun `반복 일정은 서버가 준 다음 회차 알람으로 본다 - 오늘 회차만 지났으면 막지 않는다`() {
        // 서버는 반복 일정에 다음 회차(내일)의 계획을 준다
        assertFalse(PastTimeRule.tooLate(plan("ok", "2026-11-17T08:00:00+09:00"), nowMillis))
    }

    @Test
    fun `계산 전이거나 실패한 계획은 시각을 모르니 막지 않는다`() {
        assertFalse(PastTimeRule.tooLate(plan("route_failed", "2026-11-16T17:00:00+09:00"), nowMillis))
        assertFalse(PastTimeRule.tooLate(plan("no_place", null), nowMillis))
        assertFalse(PastTimeRule.tooLate(null, nowMillis))
    }

    @Test
    fun `지금 바로 출발해도 이동 시간만으로 늦으면 보내기 전에 막는다`() {
        val now = java.time.Instant.ofEpochMilli(nowMillis)
        val start = java.time.OffsetDateTime.parse("2026-11-16T18:00:00+09:00")
        assertTrue(PastTimeRule.travelTooLate(start, 31, now))
        assertFalse("딱 맞으면 막지 않는다", PastTimeRule.travelTooLate(start, 30, now))
        assertFalse(PastTimeRule.travelTooLate(start, 20, now))
        assertFalse("이동 시간을 모르면 막지 않는다", PastTimeRule.travelTooLate(start, null, now))
    }

    @Test
    fun `늦는 시각 안내는 알람을 맞추지 않았다고 분명히 말한다`() {
        assertTrue(PastTimeRule.ADD_TOO_LATE.contains("알람을 맞추지 않았어요"))
        assertTrue(PastTimeRule.EDIT_TOO_LATE.contains("저장하지 않았어요"))
        listOf(PastTimeRule.ADD_TOO_LATE, PastTimeRule.EDIT_TOO_LATE, PastTimeRule.ADD_UNDO_FAILED, PastTimeRule.EDIT_UNDO_FAILED)
            .forEach { assertFalse(it, it.contains("깨워")) }
        // 되돌리지 못했으면 일정이 남았다는 것을 숨기지 않는다
        assertTrue(PastTimeRule.ADD_UNDO_FAILED.contains("지워 주세요"))
        assertTrue(LocalDate.of(2026, 1, 1).isBefore(today))
    }
}
