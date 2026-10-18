package com.swpp.wakeup.domain.model

import com.swpp.wakeup.calendar.DeviceCalendarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Partly AI-generated (Claude)
/**
 * 캘린더 연동 규칙(⑱ · ⑯ · 가져오기).
 *
 * 여기 담긴 판단이 틀리면 **사용자가 켜지 않은 캘린더를 앱이 읽는다.** 공유받은
 * 캘린더에는 남의 일정이 들어 있다.
 */
class CalendarLinkStateTest {

    private fun cal(id: Long, name: String = "캘린더 $id", account: String? = "a@example.com", primary: Boolean = false) =
        DeviceCalendarSource(id = id, name = name, accountName = account, color = null, primary = primary)

    @Test
    fun `기기에서 사라진 캘린더는 연동으로 세지 않는다`() {
        // 계정을 지우면 저장소에 id 만 남는다. 그 id 로 읽으면 아무것도 안 나오지만,
        // 화면이 "1개 연동" 이라고 말하면 거짓이다.
        val link = CalendarLinkState(
            permission = CalendarPermission.GRANTED,
            calendars = listOf(cal(1), cal(2)),
            storedIds = setOf(2L, 99L),
        )
        assertEquals(setOf(2L), link.linkedIds)
        assertEquals(1, link.linkedCount)
        assertTrue(link.isLinked(2))
        assertFalse(link.isLinked(99))
    }

    @Test
    fun `권한 없음과 캘린더 없음을 구분한다`() {
        val missing = CalendarLinkState(permission = CalendarPermission.MISSING)
        assertFalse(missing.noCalendars)
        assertFalse(missing.showList)

        val none = CalendarLinkState(permission = CalendarPermission.GRANTED, calendars = emptyList())
        assertTrue(none.noCalendars)
        assertFalse(none.showList)
    }

    @Test
    fun `처음 읽는 중에는 캘린더 없음이 아니다`() {
        // 목록을 받기 전에 "기기에 캘린더가 없음" 이 번쩍이면 사용자가 계정을 추가하러 간다.
        val loading = CalendarLinkState(permission = CalendarPermission.GRANTED, loading = true)
        assertFalse(loading.noCalendars)
    }

    @Test
    fun `다시 읽는 중에도 이미 받은 목록은 그대로 그린다`() {
        val reloading = CalendarLinkState(
            permission = CalendarPermission.GRANTED,
            loading = true,
            calendars = listOf(cal(1)),
        )
        assertTrue(reloading.showList)
    }
}

class CalendarOnboardingStateTest {

    private val link = CalendarLinkState(
        permission = CalendarPermission.GRANTED,
        calendars = listOf(
            DeviceCalendarSource(1, "수업 시간표", "a@example.com", null, false),
            DeviceCalendarSource(2, "a@example.com", "a@example.com", null, true),
        ),
    )

    @Test
    fun `고른 것이 없으면 시작할 수 없다`() {
        val state = CalendarOnboardingState()
        assertFalse(state.canSubmit(link))
        assertEquals("연동할 캘린더를 고를 것", state.submitLabel(link))
    }

    @Test
    fun `고른 수를 버튼에 적는다`() {
        val state = CalendarOnboardingState(selected = setOf(1L, 2L))
        assertTrue(state.canSubmit(link))
        assertEquals("2개 연동하고 시작하기", state.submitLabel(link))
    }

    @Test
    fun `목록에서 사라진 캘린더는 고른 것으로 세지 않는다`() {
        // 화면을 띄운 사이 계정이 지워질 수 있다. 없는 캘린더를 연동했다고 저장하면 안 된다.
        val state = CalendarOnboardingState(selected = setOf(1L, 99L))
        assertEquals(setOf(1L), state.chosen(link))
        assertEquals("1개 연동하고 시작하기", state.submitLabel(link))
    }

    @Test
    fun `건너뛰면 저장한 수가 없다`() {
        // 스낵바가 "0개를 연동했습니다" 를 띄우면 저장하지 않은 것을 저장했다고 말한다.
        assertNull(CalendarOnboardingState(done = true).savedCount)
    }
}

class CalendarSourcesTest {

    @Test
    fun `계정끼리 모으고 기본 캘린더를 먼저 둔다`() {
        val ordered = CalendarSources.ordered(
            listOf(
                DeviceCalendarSource(1, "동아리", "b@example.com", null, false),
                DeviceCalendarSource(2, "수업", "a@example.com", null, false),
                DeviceCalendarSource(3, "a@example.com", "a@example.com", null, true),
                DeviceCalendarSource(4, "내 휴대전화", null, null, false),
                DeviceCalendarSource(5, "b@example.com", "b@example.com", null, true),
            )
        )
        assertEquals(listOf(3L, 2L, 5L, 1L, 4L), ordered.map { it.id })
    }

    @Test
    fun `이름 아래 줄은 계정과 기본 여부다`() {
        assertEquals(
            "a@example.com · 기본",
            CalendarSources.subtitle(DeviceCalendarSource(1, "x", "a@example.com", null, true)),
        )
        assertEquals(
            "a@example.com",
            CalendarSources.subtitle(DeviceCalendarSource(1, "x", "a@example.com", null, false)),
        )
        // 계정 없는 로컬 캘린더는 빈 줄을 두지 않는다.
        assertNull(CalendarSources.subtitle(DeviceCalendarSource(1, "x", null, null, false)))
    }
}
