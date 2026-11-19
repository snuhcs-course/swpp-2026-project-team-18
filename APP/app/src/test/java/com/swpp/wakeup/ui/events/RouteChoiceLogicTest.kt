// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** 경로 선택 화면(Figma 시안 11-18)의 묶음 · 문구 · 막대 · 구간 목록 */
class RouteChoiceLogicTest {

    private fun seg(kind: RouteSegment.Kind, sec: Int, label: String = "", line: String = "", bus: String = "") =
        RouteSegment(kind, sec, label, lineName = line, busType = bus)

    private val subwayBus = RouteOption(
        "transit:2호선>5513", "지하철+도보+버스", 42, "42분", "2호선 → 5513 · 5.1km · 환승 1회 · 1,550원", null,
        RouteSegments(
            listOf(
                seg(RouteSegment.Kind.WALK, 480, "도보"),
                seg(RouteSegment.Kind.SUBWAY, 420, "2호선", line = "2호선"),
                seg(RouteSegment.Kind.WALK, 360, "도보"),
                seg(RouteSegment.Kind.BUS, 1020, "5513", bus = "지선"),
                seg(RouteSegment.Kind.WAIT, 240, "대기"),
            )
        ),
    )
    private val busOnly = RouteOption(
        "transit:750A", "버스", 45, "45분", "750A · 4.6km · 1,500원", "환승 없음",
        RouteSegments(listOf(seg(RouteSegment.Kind.WALK, 660), seg(RouteSegment.Kind.BUS, 1800, "750A", bus = "간선"))),
    )
    private val subwayOnly = RouteOption(
        "transit:2호선>신림선", "지하철", 48, "48분", "2호선 → 신림선 · 1,550원", null,
        RouteSegments(
            listOf(
                seg(RouteSegment.Kind.SUBWAY, 360, "2호선", line = "2호선"),
                seg(RouteSegment.Kind.SUBWAY, 420, "신림선", line = "신림선"),
            )
        ),
    )
    private val walk = RouteOption("walk", "도보", 30, "30분", "1.2km", null)

    @Test
    fun `묶음 탭은 탄 수단으로 나누고 후보가 없는 묶음은 만들지 않는다`() {
        val all = listOf(subwayBus, busOnly, subwayOnly)
        assertEquals(
            listOf(RouteChoiceLogic.Group.ALL to 3, RouteChoiceLogic.Group.BUS to 1, RouteChoiceLogic.Group.SUBWAY to 1, RouteChoiceLogic.Group.BUS_SUBWAY to 1),
            RouteChoiceLogic.groups(all),
        )
        assertEquals(listOf(RouteChoiceLogic.Group.ALL to 1, RouteChoiceLogic.Group.BUS to 1), RouteChoiceLogic.groups(listOf(busOnly)))
        assertEquals(listOf(busOnly), RouteChoiceLogic.filter(all, RouteChoiceLogic.Group.BUS))
        assertEquals(all, RouteChoiceLogic.filter(all, RouteChoiceLogic.Group.ALL))
    }

    @Test
    fun `소요 시간 옆 설명 - 환승 횟수 · 탄 수단`() {
        assertEquals("환승 1회", RouteChoiceLogic.subtitle(subwayBus))
        assertEquals("버스", RouteChoiceLogic.subtitle(busOnly))
        assertEquals("환승 1회", RouteChoiceLogic.subtitle(subwayOnly))
        assertNull(RouteChoiceLogic.subtitle(walk))
    }

    @Test
    fun `시각 · 요금 줄 - 요금은 서버 요약에 있을 때만`() {
        val arrive = LocalDateTime.of(2030, 1, 1, 8, 50)
        assertEquals("오전 8:08 - 8:50 · 1,550원", RouteChoiceLogic.timeLine(subwayBus, arrive))
        assertEquals("1,550원", RouteChoiceLogic.timeLine(subwayBus, null))
        assertEquals("오전 8:20 - 8:50", RouteChoiceLogic.timeLine(walk, arrive))
        assertNull(RouteChoiceLogic.fare(walk))
    }

    @Test
    fun `머리 부제 - 출발 → 도착 · 도착 목표 시각`() {
        assertEquals("집 → 302동 105호 · 오전 9:00까지", RouteChoiceLogic.header("집", "302동 105호", LocalDateTime.of(2030, 1, 1, 9, 0)))
        assertEquals("집 → 302동 105호", RouteChoiceLogic.header("집", "302동 105호", null))
    }

    @Test
    fun `막대는 도보 합계 · 탄 구간 · 대기 합계 순서이고 아래 줄이 합계라고 밝힌다`() {
        val cells = RouteChoiceLogic.barCells(subwayBus)
        assertEquals(
            listOf(RouteSegment.Kind.WALK, RouteSegment.Kind.SUBWAY, RouteSegment.Kind.BUS, RouteSegment.Kind.WAIT),
            cells.map { it.kind },
        )
        assertEquals(listOf(14, 7, 17, 4), cells.map { it.minutes })
        assertEquals("도보 14분 · 대기 4분은 전체 합계", RouteChoiceLogic.totalsNote(subwayBus))
        assertNull(RouteChoiceLogic.totalsNote(subwayOnly))
    }

    @Test
    fun `구간 목록 - 노선 이름과 두 번째부터 환승`() {
        val steps = RouteChoiceLogic.steps(subwayBus)
        assertEquals(listOf("2호선", "지선 5513"), steps.map { it.name })
        assertEquals(listOf("지하철 · 7분 탑승", "버스로 환승 · 17분 탑승"), steps.map { it.sub })
        assertEquals("지하철로 환승 · 7분 탑승", RouteChoiceLogic.steps(subwayOnly)[1].sub)
        assertEquals("간선 750A", RouteChoiceLogic.steps(busOnly)[0].name)
    }
}
