// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 지난 시작 시각 막기. 한 번뿐인 일정을 지난 시각으로 만들면 알람이 울리지 않고, 그날 첫 알람 자리를 차지해
 * 같은 날 뒤 일정의 알람까지 꺼진다.
 */
class AddTimeRulesTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 8)

    /** 오늘 오후 3시 */
    private val now = OffsetDateTime.parse("2026-11-08T15:00:00+09:00")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = startAtOf(date, hour, minute, seoul)

    @Test
    fun `폼의 날짜와 시각을 그 지역의 순간으로 바꾼다`() {
        assertEquals(OffsetDateTime.parse("2026-11-08T09:30+09:00"), startAtOf(today, 9, 30, seoul))
    }

    @Test
    fun `오늘 이미 지난 시각은 보내지 않는다`() {
        // Given 오후 3시에 오늘 오전 9시(오전 · 오후를 잘못 고름)
        val start = at(today, 9)

        // When / Then
        assertEquals(PAST_START_LINE, pastStartError(start, now, repeating = false, editBase = null))
    }

    @Test
    fun `지금과 같은 시각도 지난 시각이다`() {
        assertEquals(PAST_START_LINE, pastStartError(at(today, 15), now, repeating = false, editBase = null))
    }

    @Test
    fun `앞으로의 시각은 막지 않는다`() {
        assertNull(pastStartError(at(today.plusDays(1), 9), now, repeating = false, editBase = null))
        assertNull(pastStartError(at(today, 15, 1), now, repeating = false, editBase = null))
    }

    @Test
    fun `반복 일정은 서버가 따로 거절하므로 여기서 막지 않는다`() {
        assertNull(pastStartError(at(today, 9), now, repeating = true, editBase = null))
    }

    @Test
    fun `수정에서 지난 시각으로 옮기면 막는다`() {
        // Given 내일 오전 9시 일정을
        val base = at(today.plusDays(1), 9)

        // When 오늘 오전 9시로 옮긴다
        val error = pastStartError(at(today, 9), now, repeating = false, editBase = base)

        // Then
        assertEquals(PAST_START_LINE, error)
    }

    @Test
    fun `수정에서 시각을 그대로 두면 이미 시작한 일정도 고칠 수 있다`() {
        // Given 오후 2시 30분에 시작한 일정(서버 값에는 초가 붙어 있을 수 있다)
        val base = OffsetDateTime.parse("2026-11-08T14:30:45+09:00")

        // When 제목만 고친다. 폼은 분까지만 다룬다
        val error = pastStartError(at(today, 14, 30), now, repeating = false, editBase = base)

        // Then
        assertNull(error)
    }
}
