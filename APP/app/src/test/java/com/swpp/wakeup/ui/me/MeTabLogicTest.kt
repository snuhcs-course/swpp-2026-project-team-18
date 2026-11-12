// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.me

import com.swpp.wakeup.calendar.DeviceCalendarSource
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.CalendarPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 정보 탭(iter4 H2-2, Figma `547:1244`) 줄마다 "지금 값" 문구 */
class MeTabLogicTest {

    private fun value(text: String, warn: Boolean = false) = MeTabLogic.Value(text, warn)

    @Test
    fun `집 주소는 기본 집과 다른 집 수, 없으면 주황`() {
        assertEquals(value("신림동 서원9길 · 다른 집 1곳"), MeTabLogic.home("신림동 서원9길", 1))
        assertEquals("다른 집이 없으면 주소만", value("신림동 서원9길"), MeTabLogic.home("신림동 서원9길", 0))
        assertEquals(value("아직 정하지 않았어요", warn = true), MeTabLogic.home(null, 2))
        assertEquals(value("아직 정하지 않았어요", warn = true), MeTabLogic.home("  ", 0))
    }

    @Test
    fun `준비 시간은 다이얼과 같은 표기`() {
        assertEquals(value("일어나서 집을 나서기까지 · 평소 35분"), MeTabLogic.prep(35))
        assertEquals(value("일어나서 집을 나서기까지 · 평소 1시간 5분"), MeTabLogic.prep(65))
        assertTrue(MeTabLogic.prep(null).warn)
    }

    @Test
    fun `아침 루틴은 항목 수와 이름`() {
        assertEquals(
            value("준비 항목 3개 · 샤워, 옷 입기, 아침 식사"),
            MeTabLogic.routine(listOf("샤워", "옷 입기", "아침 식사")),
        )
        assertEquals(value("아직 없어요 · 항목으로 나누면 알람 시각의 근거가 보여요"), MeTabLogic.routine(emptyList()))
        assertEquals("못 받았으면 무엇을 하는 곳인지만", value("준비 시간을 항목으로 나눠요"), MeTabLogic.routine(null))
    }

    @Test
    fun `캘린더는 지금 기기에 있는 것 중 켜 둔 수`() {
        val calendars = listOf(source(1), source(2), source(3))
        val linked = CalendarLinkState(
            permission = CalendarPermission.GRANTED,
            calendars = calendars,
            // 9 는 기기에서 사라진 캘린더라 세지 않는다
            storedIds = setOf(1L, 3L, 9L),
        )
        assertEquals(value("켜 둔 캘린더 2개 · 고른 일정만 가져와요"), MeTabLogic.calendar(linked))

        val none = value("앞으로 2주 일정을 보고 고른 것만 가져와요")
        assertEquals(none, MeTabLogic.calendar(linked.copy(storedIds = emptySet())))
        assertEquals("권한이 없어도 주황이 아니다", none, MeTabLogic.calendar(CalendarLinkState(permission = CalendarPermission.DENIED)))
    }

    @Test
    fun `알림 권한 줄은 꺼진 것을 말한다`() {
        val on = MeTabLogic.Permissions(notifications = true, fullScreen = true, preciseLocation = true)
        assertEquals(value("알림과 위치 모두 켜져 있어요"), MeTabLogic.permissions(on))
        assertTrue(on.allOn)

        assertEquals(value("위치 권한이 꺼져 있어요", warn = true), MeTabLogic.permissions(on.copy(preciseLocation = false)))
        assertEquals(
            value("정밀 위치가 꺼져 있어요", warn = true),
            MeTabLogic.permissions(on.copy(preciseLocation = false, coarseOnly = true)),
        )
        assertEquals(value("알림이 꺼져 있어요", warn = true), MeTabLogic.permissions(on.copy(notifications = false)))
        assertEquals(value("잠금 화면 알람이 꺼져 있어요", warn = true), MeTabLogic.permissions(on.copy(fullScreen = false)))
        assertEquals(
            value("알림 · 위치 권한이 꺼져 있어요", warn = true),
            MeTabLogic.permissions(on.copy(notifications = false, preciseLocation = false)),
        )
    }

    @Test
    fun `알림 권한 화면 줄`() {
        val off = MeTabLogic.Permissions(notifications = false, fullScreen = true, preciseLocation = false, coarseOnly = true)
        val rows = MeTabLogic.permissionRows(off)
        assertEquals(listOf("알림", "잠금 화면 알람", "위치"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.on })
        assertEquals("대략 위치만 켜짐 · 정밀 위치가 있어야 도착이 기록돼요", rows[2].value)
        assertFalse(rows[0].on)
    }

    @Test
    fun `주간 리포트 알림 줄은 알림 권한이 꺼져 있으면 주황`() {
        assertEquals(value("일요일 낮 12시에 이번 주 결과를 알려 줘요"), MeTabLogic.reminder(on = true, notificationsOn = true))
        assertEquals(value("켜짐 · 알림이 꺼져 있어 오지 않아요", warn = true), MeTabLogic.reminder(on = true, notificationsOn = false))
        assertEquals(value("꺼짐"), MeTabLogic.reminder(on = false, notificationsOn = false))
    }

    @Test
    fun `설정 줄은 비밀번호가 없는 계정이면 비밀번호를 뺀다`() {
        assertEquals("로그인 연결 · 비밀번호 · 로그아웃", MeTabLogic.settings(null))
        assertEquals("로그인 연결 · 비밀번호 · 로그아웃", MeTabLogic.settings(listOf("password", "kakao")))
        assertEquals("로그인 연결 · 로그아웃", MeTabLogic.settings(listOf("google")))
    }

    private fun source(id: Long) =
        DeviceCalendarSource(id = id, name = "캘린더 $id", accountName = "me@example.com", color = null, primary = false)
}
