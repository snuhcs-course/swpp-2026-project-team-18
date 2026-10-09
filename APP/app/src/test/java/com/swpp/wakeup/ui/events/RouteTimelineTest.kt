// AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegment.Kind
import com.swpp.wakeup.domain.model.RouteSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 경로 카드 출발~도착 시각과 정류장 타임라인(Figma 13-b).
 * B-4 전까지는 일정 시작 시각을 도착 목표로 두고 앱이 임시로 계산한다.
 */
class RouteTimelineTest {

    private val start = LocalDateTime.of(2026, 10, 6, 12, 30)

    private fun walk(min: Int) = RouteSegment(Kind.WALK, min * 60, "도보")
    private fun wait(min: Int) = RouteSegment(Kind.WAIT, min * 60, "대기")
    private fun bus(min: Int, label: String, vararg stops: String) =
        RouteSegment(Kind.BUS, min * 60, label, busType = "간선", stops = stops.toList())
    private fun subway(min: Int, vararg stops: String) =
        RouteSegment(Kind.SUBWAY, min * 60, "2호선", lineName = "2호선", stops = stops.toList())

    @Test
    fun `출발은 일정 시작에서 소요시간을 뺀 시각`() {
        assertEquals(LocalDateTime.of(2026, 10, 6, 12, 1), departAt(start, 29))
        assertEquals(null, departAt(null, 29))
    }

    @Test
    fun `시각 범위 표기`() {
        assertEquals("오후 12:00 - 12:29", timeRangeLabel(LocalTime.of(12, 0), LocalTime.of(12, 29)))
        assertEquals("오전 11:40 - 오후 12:09", timeRangeLabel(LocalTime.of(11, 40), LocalTime.of(12, 9)))
    }

    @Test
    fun `버스에서 내려 같은 역에서 지하철로 갈아타면 환승 한 행`() {
        val segs = RouteSegments(
            listOf(
                walk(2), wait(1), bus(4, "5516", "쑥고개입구", "신림역"),
                walk(1), wait(2), subway(16, "신림", "당산"), walk(3),
            )
        )
        val rows = timelineRows(segs, "집", "당산아카이브", LocalDateTime.of(2026, 10, 6, 12, 0))

        assertEquals(listOf("집", "쑥고개입구", "신림역", "당산", "당산아카이브"), rows.map { it.name })
        assertEquals(listOf("출발", "승차", "환승", "하차", "도착"), rows.map { it.sub })
        // 승차 행은 정류장에 닿는 시각(대기 전): 12:00 + 도보 2분
        assertEquals(LocalTime.of(12, 2), rows[1].time)
        // 하차(환승) 행: 12:02 + 대기 1 + 버스 4
        assertEquals(LocalTime.of(12, 7), rows[2].time)
        assertEquals("2호선", rows[2].line?.label)
        // 도착: 전체 합 29분
        assertEquals(LocalTime.of(12, 29), rows.last().time)
        assertEquals("도착", rows.last().caption)
    }

    @Test
    fun `출발 시각을 모르면 시각 없이 행만`() {
        val rows = timelineRows(RouteSegments(listOf(walk(2), bus(10, "5513", "봉천", "관악구청"))), "집", "학교", null)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.time == null })
    }

    @Test
    fun `버스 지하철이 없으면 타임라인도 없다`() {
        assertTrue(timelineRows(RouteSegments(listOf(walk(20))), "집", "학교", start).isEmpty())
    }
}
