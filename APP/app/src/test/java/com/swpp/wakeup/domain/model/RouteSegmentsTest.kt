package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구간 막대의 폭 계산.
 *
 * 이 계산이 틀려도 아무도 버그로 신고하지 않는다 — 막대가 2px 짧은 것을
 * 누가 알아채겠는가. 그래서 눈으로 보는 대신 값으로 고정한다.
 *
 * 지키는 규칙 두 개.
 *   1. **비율 합이 정확히 1.0** 이다. 아니면 막대 오른쪽에 바탕색이 남아
 *      선택 테두리처럼 보인다.
 *   2. **있는 구간은 보인다.** 1분짜리 환승 도보가 1px 이 되면 없는 것과 같다.
 */
class RouteSegmentsTest {

    private fun walk(seconds: Int) = RouteSegment(RouteSegment.Kind.WALK, seconds, "도보")
    private fun bus(seconds: Int) =
        RouteSegment(RouteSegment.Kind.BUS, seconds, "5511", busType = "지선")

    // --- 합이 1.0 ----------------------------------------------------------

    @Test
    fun `비율 합이 정확히 1이다`() {
        val segments = RouteSegments(listOf(walk(240), bus(912), walk(330)))

        assertEquals(1f, segments.weights().sum(), 1e-6f)
    }

    @Test
    fun `나누어떨어지지_않는_구간도_합이_1이다`() {
        // 7초씩 세 개. 어떤 방식으로 나눠도 반올림 오차가 생긴다.
        val segments = RouteSegments(listOf(walk(7), bus(7), walk(7)))

        assertEquals(1f, segments.weights().sum(), 1e-6f)
    }

    @Test
    fun `구간이_많아도_합이_1이다`() {
        val many = (1..9).map { if (it % 2 == 0) bus(it * 37) else walk(it * 13) }

        assertEquals(1f, RouteSegments(many).weights().sum(), 1e-6f)
    }

    // --- 비율이 시간에 비례한다 --------------------------------------------

    @Test
    fun `긴 구간이 더 넓다`() {
        val segments = RouteSegments(listOf(walk(180), bus(1080), walk(360)))
        val w = segments.weights()

        assertTrue("버스가 가장 넓어야 한다", w[1] > w[0] && w[1] > w[2])
        assertTrue("뒤 도보가 앞 도보보다 넓어야 한다", w[2] > w[0])
    }

    @Test
    fun `절반인 구간은 비율도 절반이다`() {
        val segments = RouteSegments(listOf(bus(600), bus(600)))
        val w = segments.weights()

        assertEquals(0.5f, w[0], 1e-6f)
        assertEquals(0.5f, w[1], 1e-6f)
    }

    // --- 작은 구간도 보인다 ------------------------------------------------

    @Test
    fun `아주 짧은 구간도 최소 폭을 받는다`() {
        // 1분 환승 도보 + 59분 버스. 그대로면 1.7% 라 화면에서 1~2px 이다.
        val segments = RouteSegments(listOf(walk(60), bus(3540)))
        val w = segments.weights()

        assertTrue("짧은 구간이 최소 폭보다 좁다: ${w[0]}", w[0] >= RouteSegments.MIN_WEIGHT)
        assertEquals(1f, w.sum(), 1e-6f)
    }

    @Test
    fun `짧은_구간이_여러개여도_합이_1이다`() {
        // 최소 폭을 여러 번 밀어 올리면 합이 1을 넘을 수 있다. 마지막 구간이
        // 흡수하므로 합은 유지되지만 음수가 되지는 않아야 한다.
        val segments = RouteSegments(
            listOf(walk(30), walk(30), walk(30), walk(30), bus(3600))
        )
        val w = segments.weights()

        assertEquals(1f, w.sum(), 1e-6f)
        assertTrue("음수 비율이 나왔다: $w", w.all { it >= 0f })
    }

    // --- 빈 경우 ----------------------------------------------------------

    @Test
    fun `구간이 없으면 비율도 없다`() {
        assertTrue(RouteSegments(emptyList()).weights().isEmpty())
        assertTrue(RouteSegments(emptyList()).isEmpty)
    }

    @Test
    fun `시간 합이 0이면 비율이 없다`() {
        // 0초 구간만 있는 응답. 나누기 0 을 막는다.
        val segments = RouteSegments(listOf(walk(0), bus(0)))

        assertTrue(segments.weights().isEmpty())
    }

    // --- 총 시간과 라벨 ---------------------------------------------------

    @Test
    fun `총 시간은 구간의 합이다`() {
        assertEquals(1482, RouteSegments(listOf(walk(240), bus(912), walk(330))).totalSeconds)
    }

    @Test
    fun `분 라벨은 반올림한다`() {
        assertEquals("15분", bus(912).minutesLabel)
        assertEquals("4분", walk(240).minutesLabel)
    }

    @Test
    fun `0분이라고 쓰지 않는다`() {
        // 29초 구간은 반올림하면 0분이다. "0분" 은 말이 안 된다.
        assertEquals("1분", walk(29).minutesLabel)
        assertEquals(1, walk(1).minutes)
    }

    // --- 막대를 그릴 조건 -------------------------------------------------

    @Test
    fun `구간이 둘 이상일 때만 막대를 그린다`() {
        val one = RouteOption("car", "자동차", 14, "14분", "", null,
            RouteSegments(listOf(RouteSegment(RouteSegment.Kind.CAR, 840, "자동차"))))
        val two = RouteOption("transit:5511", "버스", 25, "25분", "", null,
            RouteSegments(listOf(walk(240), bus(912))))

        // 통짜 한 칸은 제목의 "14분" 이 이미 말한 것이다.
        assertFalse(one.hasSegmentBar)
        assertTrue(two.hasSegmentBar)
    }

    @Test
    fun `구간이 없는 후보는 막대를 그리지 않는다`() {
        // 구버전 서버는 segments 를 내리지 않는다.
        val old = RouteOption("transit:5511", "버스", 25, "25분", "", null)

        assertFalse(old.hasSegmentBar)
        assertTrue(old.segments.isEmpty)
    }

    // --- 종류 변환 --------------------------------------------------------

    @Test
    fun `서버 문자열을 종류로 바꾼다`() {
        assertEquals(RouteSegment.Kind.WALK, RouteSegment.Kind.from("walk"))
        assertEquals(RouteSegment.Kind.WAIT, RouteSegment.Kind.from("wait"))
        assertEquals(RouteSegment.Kind.BUS, RouteSegment.Kind.from("bus"))
        assertEquals(RouteSegment.Kind.SUBWAY, RouteSegment.Kind.from("subway"))
        assertEquals(RouteSegment.Kind.CAR, RouteSegment.Kind.from("car"))
        assertEquals(RouteSegment.Kind.BICYCLE, RouteSegment.Kind.from("bicycle"))
    }

    @Test
    fun `대소문자가_달라도_읽는다`() {
        assertEquals(RouteSegment.Kind.BUS, RouteSegment.Kind.from("BUS"))
        assertEquals(RouteSegment.Kind.SUBWAY, RouteSegment.Kind.from("Subway"))
    }

    @Test
    fun `모르는 종류에서 터지지 않는다`() {
        // 서버가 수단을 추가했을 때 구버전 앱이 경로 화면째로 죽으면 안 된다.
        assertEquals(RouteSegment.Kind.UNKNOWN, RouteSegment.Kind.from("ferry"))
        assertEquals(RouteSegment.Kind.UNKNOWN, RouteSegment.Kind.from(null))
        assertEquals(RouteSegment.Kind.UNKNOWN, RouteSegment.Kind.from(""))
    }
}
