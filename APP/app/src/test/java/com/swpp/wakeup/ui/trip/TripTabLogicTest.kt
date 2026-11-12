// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.trip

import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 이동 탭(iter4 H2-2, Figma `545:1080`)의 규칙 */
class TripTabLogicTest {

    private val today = LocalDate.of(2026, 9, 17)
    private val zone = ZoneId.of("Asia/Seoul")

    private fun event(
        id: Long,
        startHour: Int,
        date: LocalDate = today,
        planStatus: String = "ok",
        alarm: String? = "7:40",
        skipped: Boolean = false,
        title: String = "일정 $id",
    ) = UpcomingEvent(
        id = id,
        startTime = "%02d:00".format(startHour),
        dayLabel = "목",
        title = title,
        placeAndRoute = "302동",
        alarmAt = alarm,
        onTimeProbability = 90,
        tag = null,
        planStatus = planStatus,
        planStatusLabel = null,
        startAtEpochSecond = ZonedDateTime.of(date, LocalTime.of(startHour, 0), zone).toEpochSecond(),
        startDate = date,
        startClock = if (startHour < 12) "$startHour:00AM" else "${if (startHour == 12) 12 else startHour - 12}:00PM",
        skipped = skipped,
    )

    private fun walk(min: Int) = RouteSegment(RouteSegment.Kind.WALK, min * 60, "도보")
    private fun wait(min: Int) = RouteSegment(RouteSegment.Kind.WAIT, min * 60, "대기")
    private fun subway(min: Int, vararg stops: String) =
        RouteSegment(RouteSegment.Kind.SUBWAY, min * 60, "2호선", lineName = "2호선", stops = stops.toList())
    private fun bus(min: Int, vararg stops: String) =
        RouteSegment(RouteSegment.Kind.BUS, min * 60, "5513", busType = "지선", stops = stops.toList())

    @Test
    fun `경로가 정해진 가장 이른 일정을 고른다`() {
        val sections = listOf(
            EventSection("오늘", "9월 17일 목", listOf(
                event(1, 8, planStatus = "no_place", alarm = null),
                event(2, 9, skipped = true),
                event(3, 15),
            )),
            EventSection("내일", "9월 18일 금", listOf(event(4, 10, date = today.plusDays(1)))),
        )
        assertEquals(3L, TripTabLogic.pick(sections)?.id)
        assertNull(TripTabLogic.pick(emptyList()))
    }

    @Test
    fun `오늘 이동이 있으면 빈 상태가 아니다`() {
        assertNull(TripTabLogic.empty(event(1, 9), hasEvents = true, today = today))
    }

    @Test
    fun `오늘 갈 곳이 없고 내일 있으면 내일 첫 일정을 알려 준다`() {
        val tomorrow = event(1, 10, date = today.plusDays(1), title = "전공 발표")
        val empty = TripTabLogic.empty(tomorrow, hasEvents = true, today = today)!!
        assertEquals(TripTabLogic.Empty.Kind.LATER, empty.kind)
        assertEquals("오늘은 더 갈 곳이 없어요", empty.title)
        assertEquals("내일 첫 일정은 오전 10:00 전공 발표예요", empty.body)
        assertEquals("내일 가는 길 보기", empty.action)
    }

    @Test
    fun `모레 이후면 날짜를 쓴다`() {
        val later = event(1, 15, date = today.plusDays(3), title = "점심 약속")
        val empty = TripTabLogic.empty(later, hasEvents = true, today = today)!!
        assertEquals("다음 일정은 9월 20일 일 오후 3:00 점심 약속이에요", empty.body)
        assertEquals("가는 길 보기", empty.action)
    }

    @Test
    fun `일정이 없거나 경로가 없으면 일정 추가`() {
        assertEquals(TripTabLogic.Empty.Kind.NO_EVENTS, TripTabLogic.empty(null, hasEvents = false, today = today)?.kind)
        assertEquals(TripTabLogic.Empty.Kind.NO_ROUTE, TripTabLogic.empty(null, hasEvents = true, today = today)?.kind)
    }

    @Test
    fun `받침에 따라 예요 이에요`() {
        assertEquals("예요", TripTabLogic.copula("전공 발표"))
        assertEquals("이에요", TripTabLogic.copula("점심 약속"))
        assertEquals("예요", TripTabLogic.copula("SWPP"))
    }

    @Test
    fun `가는 길은 Figma 예시와 같은 줄을 만든다`() {
        val segments = listOf(
            walk(6), wait(1), subway(7, "신림", "봉천", "서울대입구"),
            walk(5), bus(17, "서울대입구역", "관악구청", "서울대정문"),
            walk(6),
        )
        val steps = TripTabLogic.steps(
            segments,
            departAt = LocalDateTime.of(2026, 9, 17, 8, 8),
            originTitle = "집에서 출발",
            destination = "302동 105호",
            endSub = "수업 시작 10분 전",
        )

        assertEquals(
            listOf("8:08", "8:15", "8:22", "8:27", "8:44", "8:50"),
            steps.map { "${it.time!!.hour % 12}:%02d".format(it.time!!.minute) },
        )
        assertEquals(
            listOf("집에서 출발", "신림역에서 타기", "서울대입구역에서 내리기", "서울대입구역에서 타기", "서울대정문에서 내리기", "302동 105호 도착"),
            steps.map { it.title },
        )
        assertEquals(
            listOf(
                "걸어서 6분 · 신림역까지",
                "2정거장 · 7분",
                "걸어서 5분 · 버스 정류장까지",
                "17분 · 서울대정문까지",
                "걸어서 6분 · 302동 105호까지",
                "수업 시작 10분 전",
            ),
            steps.map { it.sub },
        )
        assertEquals("2호선", steps[1].line?.label)
        assertEquals(RouteSegment.Kind.SUBWAY, steps[1].below)
        assertEquals(RouteSegment.Kind.WALK, steps[2].below)
        assertNull("마지막 줄은 아래 선이 없다", steps.last().below)
    }

    @Test
    fun `내린 곳에서 바로 타면 바로 갈아타기`() {
        val steps = TripTabLogic.steps(
            listOf(subway(5, "신림", "서울대입구"), subway(10, "서울대입구", "강남")),
            departAt = null,
            originTitle = "집에서 출발",
            destination = "회사",
            endSub = "일정 시작",
        )
        assertEquals("바로 갈아타기", steps[2].sub)
        assertEquals("걷지 않고 바로 탄다", "", steps[0].sub)
        assertNull("출발 시각을 모르면 시각을 쓰지 않는다", steps[0].time)
    }

    @Test
    fun `버스 지하철이 없으면 두 줄`() {
        assertTrue(TripTabLogic.steps(listOf(walk(20)), null, "집에서 출발", "302동", "").isEmpty())
        val simple = TripTabLogic.simpleSteps(
            routeKey = "walk",
            travelMinutes = 25,
            departAt = LocalDateTime.of(2026, 9, 17, 8, 25),
            arriveAt = LocalDateTime.of(2026, 9, 17, 8, 50),
            originTitle = "집에서 출발",
            destination = "302동 105호",
            endSub = "수업 시작 10분 전",
        )
        assertEquals(listOf("걸어서 25분 · 302동 105호까지", "수업 시작 10분 전"), simple.map { it.sub })
        assertEquals(LocalTime.of(8, 50), simple.last().time)
        assertEquals("차로 30분 · 회사까지", TripTabLogic.simpleSteps("car:fast", 30, null, null, "", "회사", "").first().sub)
    }

    @Test
    fun `머리 오른쪽은 걸리는 시간과 환승 수`() {
        val two = listOf(subway(7, "신림", "서울대입구"), bus(17, "서울대입구역", "서울대정문"))
        assertEquals("42분 · 환승 1번", TripTabLogic.summary(42, two))
        assertEquals("20분 · 환승 없음", TripTabLogic.summary(20, two.take(1)))
        assertEquals("25분", TripTabLogic.summary(25, null))
        assertNull(TripTabLogic.summary(null, null))
    }

    @Test
    fun `도착 줄은 시작까지 남는 시간`() {
        val start = ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val arrive = ZonedDateTime.of(2026, 9, 17, 8, 50, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("수업 시작 10분 전", TripTabLogic.endSub("수업", start, arrive))
        assertEquals("일정 시작 10분 전", TripTabLogic.endSub(null, start, arrive))
        assertEquals("수업 시작 3분 뒤", TripTabLogic.endSub("수업", start, start + 3 * 60_000))
        assertEquals("수업 시작", TripTabLogic.endSub("수업", null, arrive))
    }

    @Test
    fun `카드 셋째 줄은 출발까지 남은 시간과 도착 예정`() {
        val depart = ZonedDateTime.of(2026, 9, 17, 8, 8, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 9, 17, 6, 12, 30, 0, zone).toInstant().toEpochMilli()
        assertEquals("1시간 56분 뒤 · 8:50 도착 예정", TripTabLogic.whenLine(depart, now, "8:50 도착 예정"))
        assertEquals("지금 출발할 시각 · 8:50 도착 예정", TripTabLogic.whenLine(depart, depart + 60_000, "8:50 도착 예정"))
        assertNull(TripTabLogic.whenLine(null, now, null))
    }
}
