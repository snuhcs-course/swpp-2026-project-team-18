// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegment.Kind
import com.swpp.wakeup.domain.model.RouteSegments
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "경로 정류장" 카드 행: 출발 시각을 모르면 시각을 비우고, 도착 행에는 시각을 달지 않는다. */
class CheckpointRowsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun walk(min: Int) = RouteSegment(Kind.WALK, min * 60, "도보")
    private fun wait(min: Int) = RouteSegment(Kind.WAIT, min * 60, "대기")
    private fun subway(min: Int, vararg stops: String) =
        RouteSegment(Kind.SUBWAY, min * 60, "2호선", lineName = "2호선", stops = stops.toList())
    private fun bus(min: Int, vararg stops: String) =
        RouteSegment(Kind.BUS, min * 60, "5513", busType = "지선", stops = stops.toList())

    private val segments = RouteSegments(
        listOf(
            walk(2), wait(3), subway(6, "신림", "서울대입구"),
            wait(0), bus(9, "서울대입구", "서울대정문"), walk(1),
        )
    )

    private fun millis(h: Int, m: Int) =
        LocalDateTime.of(2026, 11, 3, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `출발 시각에 구간 시간을 더해 적고 도착 행은 시각이 없다`() {
        val rows = checkpointRows(segments, "서울대학교", millis(8, 22), zone)
        assertEquals(listOf("출발", "신림", "서울대입구", "서울대정문", "서울대학교"), rows.map { it.name })
        assertEquals(listOf("출발", "승차", "환승", "하차", "도착"), rows.map { it.sub })
        assertEquals("오전 8:22", rows[0].timeLabel)
        assertEquals("8:24", rows[1].timeLabel)
        assertEquals(true, rows.last().isEnd)
        assertNull(rows.last().timeLabel)
    }

    @Test
    fun `오전 오후가 바뀌는 행은 오전 오후를 다시 적는다`() {
        val rows = checkpointRows(segments, "서울대학교", millis(11, 50), zone)
        assertEquals("오전 11:50", rows[0].timeLabel)
        assertTrue(rows[2].timeLabel!!.startsWith("오후"))
    }

    @Test
    fun `출발 시각을 모르면 이름만 남긴다`() {
        val rows = checkpointRows(segments, "서울대학교", null, zone)
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.timeLabel == null })
    }

    @Test
    fun `대중교통 구간이 없으면 빈 목록`() {
        assertTrue(checkpointRows(RouteSegments(listOf(walk(20))), "학교", millis(8, 0), zone).isEmpty())
    }
}
