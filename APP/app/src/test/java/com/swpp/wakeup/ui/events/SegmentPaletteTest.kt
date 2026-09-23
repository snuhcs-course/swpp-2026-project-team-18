package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구간 색 결정.
 *
 * 색을 잘못 매핑해도 **빌드는 통과하고 lint 도 조용하다.** 기기에서 경로
 * 화면을 열어 봐야 드러나고, 그마저도 "초록이 좀 다른가?" 로 지나간다.
 * 그래서 규칙을 값으로 고정한다.
 *
 * 여기서 지키는 것은 두 가지다.
 *   1. 사용자가 이미 아는 색을 쓴다 (2호선은 초록, 간선버스는 파랑).
 *   2. **모르는 값을 억지로 칠하지 않는다.** 틀린 색으로 확신을 주는 것보다
 *      중립색이 낫다.
 */
class SegmentPaletteTest {

    private fun bus(type: String) =
        RouteSegment(RouteSegment.Kind.BUS, 600, "1234", busType = type)

    private fun subway(line: String) =
        RouteSegment(RouteSegment.Kind.SUBWAY, 600, line, lineName = line)

    // --- 버스 종류 ---------------------------------------------------------

    @Test
    fun `버스 종류마다 색이 다르다`() {
        val colors = listOf("간선", "지선", "마을", "광역", "순환")
            .map { SegmentPalette.busColor(it) }

        assertEquals("같은 색으로 겹친 종류가 있다", colors.size, colors.toSet().size)
    }

    @Test
    fun `간선은 파랑 지선은 초록이다`() {
        // 2004년 서울 버스 개편의 색 체계다. 뒤집으면 사용자가 노선을 착각한다.
        assertEquals(0xFF3D5BABL, SegmentPalette.busColor("간선"))
        assertEquals(0xFF53B332L, SegmentPalette.busColor("지선"))
    }

    @Test
    fun `광역은 빨강이다`() {
        assertEquals(0xFFE60012L, SegmentPalette.busColor("광역"))
    }

    @Test
    fun `종류에 말이 붙어 와도 찾는다`() {
        // 카카오는 "지선" 으로 주지만 다른 출처는 "지선버스" 로 줄 수 있다.
        assertEquals(SegmentPalette.busColor("지선"), SegmentPalette.busColor("지선버스"))
        assertEquals(SegmentPalette.busColor("간선"), SegmentPalette.busColor("간선버스"))
    }

    @Test
    fun `모르는 버스 종류는 중립색이다`() {
        // 지방 버스는 "일반", "농어촌" 같은 값이 온다. 아무 색이나 칠하면
        // 사용자가 그 색에 의미를 부여한다.
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor("농어촌"))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor(""))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.busColor(null))
    }

    // --- 지하철 노선 -------------------------------------------------------

    @Test
    fun `1에서 9호선 색이 서로 다르다`() {
        val colors = (1..9).map { SegmentPalette.subwayColor("${it}호선") }

        assertEquals("같은 색으로 겹친 노선이 있다", colors.size, colors.toSet().size)
        assertTrue(
            "중립색으로 떨어진 노선이 있다",
            colors.none { it == SegmentPalette.FALLBACK_TRANSIT },
        )
    }

    @Test
    fun `2호선은 초록 3호선은 주황이다`() {
        // 사람들이 가장 확실히 기억하는 두 개다. 틀리면 바로 눈에 띈다.
        assertEquals(0xFF00A84DL, SegmentPalette.subwayColor("2호선"))
        assertEquals(0xFFEF7C1CL, SegmentPalette.subwayColor("3호선"))
    }

    @Test
    fun `이름이 긴 노선이 숫자 노선에 잡아먹히지 않는다`() {
        // "경의중앙선" 이 "중앙선" 규칙에, "수인분당선" 이 "분당선" 규칙에
        // 먼저 걸리는지가 아니라, 각자 제 색을 받는지를 본다.
        val gyeongui = SegmentPalette.subwayColor("경의중앙선")
        val suin = SegmentPalette.subwayColor("수인분당선")

        assertNotEquals(SegmentPalette.FALLBACK_TRANSIT, gyeongui)
        assertNotEquals(SegmentPalette.FALLBACK_TRANSIT, suin)
        assertEquals(0xFF77C4A3L, gyeongui)
        assertEquals(0xFFF5A200L, suin)
    }

    @Test
    fun `신분당선은 1호선으로 잡히지 않는다`() {
        // "신분당선" 에 "1호선" 은 없지만, 부분 일치 규칙이 느슨해지면
        // 이런 사고가 난다. 회귀로 못 박는다.
        assertEquals(0xFFD4003BL, SegmentPalette.subwayColor("신분당선"))
        assertNotEquals(
            SegmentPalette.subwayColor("1호선"),
            SegmentPalette.subwayColor("신분당선"),
        )
    }

    @Test
    fun `표기에 군더더기가 붙어도 찾는다`() {
        assertEquals(
            SegmentPalette.subwayColor("2호선"),
            SegmentPalette.subwayColor("수도권 2호선"),
        )
    }

    @Test
    fun `모르는 노선은 중립색이다`() {
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor("동해선"))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor("부산김해경전철"))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor(""))
        assertEquals(SegmentPalette.FALLBACK_TRANSIT, SegmentPalette.subwayColor(null))
    }

    @Test
    fun `수도권_밖의_같은_번호_노선은_서울_색으로_잡힌다`() {
        // **이건 한계다.** 부산 1호선은 주황인데 "1호선" 이 들어 있으니 서울
        // 1호선 파랑이 된다. 노선명만으로는 지역을 알 수 없고, 카카오 응답에
        // 지역 필드가 없다.
        //
        // 지금은 고치지 않는다. 이 앱은 서울대 통학을 전제로 만들고 있고,
        // 수도권 밖 경로를 쓰기 시작하면 그때 좌표로 지역을 판단해야 한다.
        // 모르고 있다가 발견하는 것보다 적어 두는 편이 낫다.
        assertEquals(SegmentPalette.subwayColor("1호선"), SegmentPalette.subwayColor("부산 1호선"))
    }

    // --- 종류별 채움·글자색 -------------------------------------------------

    @Test
    fun `도보와 대기는 색이 다르다`() {
        // "걸어서 9분" 과 "서서 기다려 9분" 은 다른 일이다. 같은 회색이면
        // 막대에서 구분되지 않는다.
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
    fun `지하철 구간은 노선색을 따른다`() {
        assertEquals(SegmentPalette.subwayColor("4호선"), SegmentPalette.fill(subway("4호선")))
    }

    @Test
    fun `모르는 종류도 색이 나온다`() {
        // 서버가 수단을 추가했을 때 여기서 예외가 나면 경로 화면이 죽는다.
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
    fun `모든 색이 불투명하다`() {
        // 알파가 빠지면(0x00…) 칸이 투명해져 막대가 비어 보인다.
        val all = buildList {
            listOf("간선", "지선", "마을", "광역", "순환", "심야", "공항").forEach {
                add(SegmentPalette.busColor(it))
            }
            (1..9).forEach { add(SegmentPalette.subwayColor("${it}호선")) }
            add(SegmentPalette.WALK)
            add(SegmentPalette.WAIT)
            add(SegmentPalette.CAR)
            add(SegmentPalette.BICYCLE)
            add(SegmentPalette.TRACK)
            add(SegmentPalette.FALLBACK_TRANSIT)
        }

        all.forEach { argb ->
            val alpha = (argb shr 24) and 0xFF
            assertEquals("알파가 불투명하지 않다: ${argb.toString(16)}", 0xFFL, alpha)
        }
    }
}
