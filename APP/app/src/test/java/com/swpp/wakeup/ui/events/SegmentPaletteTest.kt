package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구간 색 결정.
 *
 * 색을 잘못 매핑해도 빌드와 lint 는 통과한다. 특히 카카오는 서울 1호선과 부산
 * 1호선을 둘 다 `"1호선"` 으로만 주므로, 권역을 무시하면 부산 1호선(주황)을
 * 서울 1호선(파랑)으로 칠한다. 그 실제 버그를 값으로 고정한다.
 */
class SegmentPaletteTest {

    private fun bus(type: String) =
        RouteSegment(RouteSegment.Kind.BUS, 600, "1234", busType = type)

    private fun subway(region: String, line: String) =
        RouteSegment(
            RouteSegment.Kind.SUBWAY,
            600,
            line,
            lineName = line,
            region = region,
        )

    // --- 버스 종류 ---------------------------------------------------------

    @Test
    fun `버스 종류마다 색이 다르다`() {
        val colors = listOf("간선", "지선", "마을", "광역", "순환")
            .map { SegmentPalette.busColor(it) }

        assertEquals("같은 색으로 겹친 종류가 있다", colors.size, colors.toSet().size)
    }

    @Test
    fun `간선은 파랑 지선은 초록 광역은 빨강이다`() {
        assertEquals(0xFF3D5BABL, SegmentPalette.busColor("간선"))
        assertEquals(0xFF53B332L, SegmentPalette.busColor("지선"))
        assertEquals(0xFFE60012L, SegmentPalette.busColor("광역"))
    }

    @Test
    fun `종류에 말이 붙어 와도 찾는다`() {
        assertEquals(SegmentPalette.busColor("지선"), SegmentPalette.busColor("지선버스"))
        assertEquals(SegmentPalette.busColor("간선"), SegmentPalette.busColor("간선버스"))
    }

    @Test
    fun `모르는 버스 종류는 중립색이다`() {
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor("농어촌"))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor(""))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor(null))
    }

    // --- 지하철 권역·노선 --------------------------------------------------

    @Test
    fun `서울 1호선은 파랑 부산 1호선은 주황이다`() {
        val seoul = SegmentPalette.subwayColor("metro_seoul", "1호선")
        val busan = SegmentPalette.subwayColor("metro_busan", "1호선")

        assertEquals(0xFF0052A4L, seoul)
        assertEquals(0xFFF06A00L, busan)
        assertNotEquals("같은 이름이어도 권역별 색이 달라야 한다", seoul, busan)
    }

    @Test
    fun `서울 1에서 9호선 색이 서로 다르다`() {
        val colors = (1..9).map {
            SegmentPalette.subwayColor("metro_seoul", "${it}호선")
        }

        assertEquals(colors.size, colors.toSet().size)
        assertTrue(colors.none { it == SegmentPalette.FALLBACK_TRANSIT })
    }

    @Test
    fun `서울 2호선은 초록 3호선은 주황이다`() {
        assertEquals(0xFF00A84DL, SegmentPalette.subwayColor("metro_seoul", "2호선"))
        assertEquals(0xFFEF7C1CL, SegmentPalette.subwayColor("metro_seoul", "3호선"))
    }

    @Test
    fun `부산 1에서 4호선 색이 서로 다르다`() {
        val colors = (1..4).map {
            SegmentPalette.subwayColor("metro_busan", "${it}호선")
        }

        assertEquals(colors.size, colors.toSet().size)
        assertTrue(colors.none { it == SegmentPalette.FALLBACK_TRANSIT })
    }

    @Test
    fun `부산김해경전철과 동해선도 부산색을 받는다`() {
        assertEquals(
            0xFF875CACL,
            SegmentPalette.subwayColor("metro_busan", "부산김해경전철"),
        )
        assertEquals(0xFF0054A6L, SegmentPalette.subwayColor("metro_busan", "동해선"))
    }

    @Test
    fun `수도권의 긴 노선명이 숫자 노선에 잡아먹히지 않는다`() {
        assertEquals(
            0xFF77C4A3L,
            SegmentPalette.subwayColor("metro_seoul", "경의중앙선"),
        )
        assertEquals(
            0xFFF5A200L,
            SegmentPalette.subwayColor("metro_seoul", "수인분당선"),
        )
        assertEquals(
            0xFFD4003BL,
            SegmentPalette.subwayColor("metro_seoul", "신분당선"),
        )
    }

    @Test
    fun `표기에 군더더기가 붙어도 찾는다`() {
        assertEquals(
            SegmentPalette.subwayColor("metro_seoul", "2호선"),
            SegmentPalette.subwayColor("metro_seoul", "수도권 2호선"),
        )
    }

    @Test
    fun `권역을 모르면 서울로 추측하지 않는다`() {
        // 구버전 서버는 region 을 안 준다. 부산 1호선일 수 있으므로 파랑으로
        // 단정하지 않고 중립색을 쓴다.
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor("", "1호선"))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor(null, "1호선"))
        assertEquals(
            SegmentPalette.FALLBACK_TRANSIT,
            SegmentPalette.subwayColor("metro_unknown", "1호선"),
        )
    }

    @Test
    fun `권역 안에서도 모르는 노선은 중립색이다`() {
        assertEquals(
            SegmentPalette.FALLBACK_TRANSIT,
            SegmentPalette.subwayColor("metro_seoul", "부산김해경전철"),
        )
        assertEquals(
            SegmentPalette.FALLBACK_TRANSIT,
            SegmentPalette.subwayColor("metro_busan", "9호선"),
        )
    }

    // --- 구간 객체와 연결 --------------------------------------------------

    @Test
    fun `지하철 구간은 권역과 노선색을 함께 따른다`() {
        val busanLine1 = subway("metro_busan", "1호선")

        assertEquals(
            SegmentPalette.subwayColor("metro_busan", "1호선"),
            SegmentPalette.fill(busanLine1),
        )
    }

    @Test
    fun `도보와 대기는 색이 다르다`() {
        val walk = SegmentPalette.fill(RouteSegment(RouteSegment.Kind.WALK, 540, "도보"))
        val wait = SegmentPalette.fill(RouteSegment(RouteSegment.Kind.WAIT, 540, "대기"))

        assertNotEquals(walk, wait)
    }

    @Test
    fun `버스 구간은 종류색을 따른다`() {
        assertEquals(SegmentPalette.busColor("간선"), SegmentPalette.fill(bus("간선")))
        assertEquals(SegmentPalette.busColor("지선"), SegmentPalette.fill(bus("지선")))
    }

    @Test
    fun `모르는 종류도 중립색이 나온다`() {
        val unknown = RouteSegment(RouteSegment.Kind.UNKNOWN, 600, "이동")
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.fill(unknown))
    }

    @Test
    fun `회색 칸과 색칸의 글자색이 다르다`() {
        val onWalk = SegmentPalette.onFill(RouteSegment(RouteSegment.Kind.WALK, 60, "도보"))
        val onBus = SegmentPalette.onFill(bus("간선"))

        assertEquals(SegmentPalette.ON_NEUTRAL, onWalk)
        assertEquals(SegmentPalette.ON_VEHICLE, onBus)
    }

    @Test
    fun `모든 주요 색이 불투명하다`() {
        val all = buildList {
            listOf("간선", "지선", "마을", "광역", "순환", "심야", "공항").forEach {
                add(SegmentPalette.busColor(it))
            }
            (1..9).forEach { add(SegmentPalette.subwayColor("metro_seoul", "${it}호선")) }
            (1..4).forEach { add(SegmentPalette.subwayColor("metro_busan", "${it}호선")) }
            addAll(
                listOf(
                    SegmentPalette.WALK,
                    SegmentPalette.WAIT,
                    SegmentPalette.CAR,
                    SegmentPalette.BICYCLE,
                    SegmentPalette.TRACK,
                    SegmentPalette.FALLBACK_TRANSIT,
                )
            )
        }

        all.forEach { argb ->
            assertEquals(0xFFL, (argb shr 24) and 0xFF)
        }
    }
}
