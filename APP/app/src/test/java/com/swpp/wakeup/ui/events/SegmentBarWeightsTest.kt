package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegment.Kind
import com.swpp.wakeup.domain.model.RouteSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로 카드 구간 막대.
 * - 대기 칸은 빼고 도보·버스·지하철처럼 움직이는 구간만 그린다.
 * - 모든 칸에 "N분" 을 쓰므로 짧은 칸도 최소 폭을 받는다(비율은 정확하지 않아도 된다).
 */
class SegmentBarWeightsTest {

    private fun seg(kind: Kind, minutes: Int) = RouteSegment(kind, minutes * 60, kind.name)

    @Test
    fun `대기 칸은 막대에서 뺀다`() {
        val bar = barSegments(
            RouteSegments(listOf(seg(Kind.WALK, 3), seg(Kind.WAIT, 5), seg(Kind.BUS, 13), seg(Kind.WALK, 6))),
        )
        assertEquals(listOf(Kind.WALK, Kind.BUS, Kind.WALK), bar.map { it.kind })
    }

    @Test
    fun `짧은 칸도 최소 폭을 받고 합은 1`() {
        val weights = labeledWeights(listOf(seg(Kind.WALK, 1), seg(Kind.SUBWAY, 30), seg(Kind.WALK, 1)))
        assertEquals(1f, weights.sum(), 0.001f)
        weights.forEach { assertTrue("칸이 너무 좁다: $it", it >= 0.14f - 0.001f) }
        assertTrue("긴 칸이 가장 넓어야 한다", weights[1] > weights[0])
    }

    @Test
    fun `칸이 많으면 최소 폭은 균등분까지 낮춘다`() {
        val weights = labeledWeights(List(10) { seg(Kind.WALK, if (it == 0) 40 else 1) })
        assertEquals(1f, weights.sum(), 0.001f)
        weights.forEach { assertTrue(it >= 0.1f - 0.001f) }
    }

    @Test
    fun `이미 충분히 넓으면 소요시간 비율 그대로`() {
        val weights = labeledWeights(listOf(seg(Kind.WALK, 10), seg(Kind.BUS, 10)))
        assertEquals(0.5f, weights[0], 0.001f)
        assertEquals(0.5f, weights[1], 0.001f)
    }
}
