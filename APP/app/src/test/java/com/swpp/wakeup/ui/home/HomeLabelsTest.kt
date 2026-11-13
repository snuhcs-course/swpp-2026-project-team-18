// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 홈 다시 그림(iter4 H2-2, Figma `540:123`)의 글자 */
class HomeLabelsTest {

    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-09-17(목) */
    private val today = LocalDate.of(2026, 9, 17)

    private fun millis(hour: Int, minute: Int, second: Int = 0, day: Int = 17) =
        ZonedDateTime.of(2026, 9, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun event(
        alarmOn: Boolean = true,
        alarmClock: String? = "7:40",
        planStatus: String = "ok",
        skipped: Boolean = false,
    ) = UpcomingEvent(
        id = 1,
        startTime = "09:00",
        dayLabel = "목",
        title = "자료구조 및 알고리즘",
        placeAndRoute = "302동 105호",
        alarmAt = alarmClock,
        onTimeProbability = 90,
        tag = null,
        planStatus = planStatus,
        planStatusLabel = null,
        startAtEpochSecond = 0,
        startDate = today,
        alarmClock = alarmClock,
        alarmMeridiem = alarmClock?.let { "AM" },
        placeName = "302동 105호",
        startClock = "9:00AM",
        alarmOn = alarmOn,
        skipped = skipped,
    )

    @Test
    fun `머리는 오늘 날짜를 요일까지 쓴다`() {
        assertEquals("9월 17일 목요일", HomeLabels.headerDate(today))
        assertEquals("11월 1일 일요일", HomeLabels.headerDate(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `묶음 머리 오른쪽은 오늘이면 개수만 내일이면 날짜와 개수`() {
        val two = listOf(event(), event())
        assertEquals("일정 2개", HomeLabels.sectionSummary(EventSection("오늘", "9월 17일 목", two)))
        assertEquals("9월 18일 금 · 일정 2개", HomeLabels.sectionSummary(EventSection("내일", "9월 18일 금", two)))
        assertEquals(
            "머리가 이미 날짜면 날짜를 두 번 쓰지 않는다",
            "일정 1개",
            HomeLabels.sectionSummary(EventSection("9월 20일 일", "9월 20일 일", listOf(event()))),
        )
    }

    @Test
    fun `카드 머리는 알람이 울리는 날로 센다`() {
        assertEquals("오늘", HomeLabels.alarmDayWord(today, today))
        assertEquals("내일", HomeLabels.alarmDayWord(today.plusDays(1), today))
        assertEquals("9월 19일 토", HomeLabels.alarmDayWord(today.plusDays(2), today))
    }

    @Test
    fun `남은 시간은 화면의 두 시각을 뺀 값이다`() {
        assertEquals("1시간 28분 뒤", HomeLabels.untilLabel(millis(7, 40), millis(6, 12)))
        assertEquals("초가 지나도 분으로 센다", "1시간 28분 뒤", HomeLabels.untilLabel(millis(7, 40), millis(6, 12, 59)))
        assertEquals("28분 뒤", HomeLabels.untilLabel(millis(7, 40), millis(7, 12)))
        assertEquals("2시간 뒤", HomeLabels.untilLabel(millis(9, 0), millis(7, 0)))
        assertEquals("1분 뒤", HomeLabels.untilLabel(millis(7, 40), millis(7, 39, 30)))
    }

    @Test
    fun `같은 분이거나 지난 알람은 곧`() {
        assertEquals("곧", HomeLabels.untilLabel(millis(7, 40, 30), millis(7, 40, 10)))
        assertEquals("곧", HomeLabels.untilLabel(millis(7, 40), millis(7, 45)))
    }

    @Test
    fun `하루가 넘으면 분은 버린다`() {
        assertEquals("1일 3시간 뒤", HomeLabels.untilLabel(millis(9, 30, day = 18), millis(6, 12)))
        assertEquals("2일 뒤", HomeLabels.untilLabel(millis(6, 40, day = 19), millis(6, 12)))
    }

    @Test
    fun `시작 시각은 줄에서는 AM PM 을 띄어 쓴다`() {
        assertEquals("9:00 AM", HomeLabels.rowStartClock("9:00AM"))
        assertEquals("12:00 PM", HomeLabels.rowStartClock("12:00PM"))
        assertEquals("붙은 말이 없으면 그대로", "09:00", HomeLabels.rowStartClock("09:00"))
    }

    @Test
    fun `카드의 시작 시각은 알람과 같은 오전 오후면 숫자만 쓴다`() {
        assertEquals("9:00", HomeLabels.heroStartClock("9:00AM", "AM"))
        assertEquals("오후 3:30", HomeLabels.heroStartClock("3:30PM", "AM"))
        assertEquals("3:30", HomeLabels.heroStartClock("3:30PM", "PM"))
        assertEquals("09:00", HomeLabels.heroStartClock("09:00", "AM"))
    }

    @Test
    fun `카드 아래 두 줄`() {
        assertEquals("1시간 28분 뒤 · 9:00 일정", HomeLabels.heroWhenLine("1시간 28분 뒤", "9:00"))
        assertEquals(
            "302동 105호 · 9:00 자료구조 및 알고리즘",
            HomeLabels.heroPlaceLine("302동 105호", "9:00", "자료구조 및 알고리즘"),
        )
        assertEquals("장소가 없으면 시각과 제목만", "9:00 자료구조 및 알고리즘", HomeLabels.heroPlaceLine(null, "9:00", "자료구조 및 알고리즘"))
    }

    @Test
    fun `오전 오후`() {
        assertEquals("오전", HomeLabels.koreanMeridiem("AM"))
        assertEquals("오후", HomeLabels.koreanMeridiem("PM"))
        assertEquals(null, HomeLabels.koreanMeridiem(null))
    }

    @Test
    fun `줄의 알람 칸은 켜면 시각 끄면 꺼짐`() {
        assertEquals("7:40", HomeLabels.rowAlarmLabel(event(alarmOn = true)))
        assertEquals("꺼짐", HomeLabels.rowAlarmLabel(event(alarmOn = false)))
        assertEquals("안 감", HomeLabels.rowAlarmLabel(event(skipped = true)))
        assertEquals("알람 없음", HomeLabels.rowAlarmLabel(event(alarmClock = null, planStatus = "no_home")))
        assertEquals("알람 없음", HomeLabels.rowAlarmLabel(event(planStatus = "route_failed")))
    }

    @Test
    fun `홈 다음 알람 카드 넷째 줄은 장소와 제목만 쓴다`() {
        assertEquals("302동 105호 · 자료구조 및 알고리즘", HomeLabels.heroPlaceTitleLine("302동 105호", "자료구조 및 알고리즘"))
        assertEquals("자료구조 및 알고리즘", HomeLabels.heroPlaceTitleLine(null, "자료구조 및 알고리즘"))
        assertEquals("자료구조 및 알고리즘", HomeLabels.heroPlaceTitleLine(" ", "자료구조 및 알고리즘"))
    }
}
