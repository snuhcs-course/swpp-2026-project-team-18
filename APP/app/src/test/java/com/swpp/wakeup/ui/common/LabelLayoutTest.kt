// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import com.swpp.wakeup.ui.alarm.AlarmDecisionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 타임라인 눈금 · 구간 이름 배치(11-16 실기기: "9:4510:00" 겹침 · 이름 줄 잘림) */
class LabelLayoutTest {

    private fun noOverlap(p: List<LabelLayout.Placement>, widths: List<Float>, width: Float, gap: Float) {
        for (row in 0..1) {
            val idx = p.indices.filter { p[it].row == row }.sortedBy { p[it].x }
            for (k in idx.indices) {
                val i = idx[k]
                assertTrue("넘침 $p", p[i].x >= -0.5f && p[i].x + widths[i] <= width + 0.5f)
                if (k > 0) {
                    val j = idx[k - 1]
                    assertTrue("겹침 $p", p[i].x >= p[j].x + widths[j] + gap - 0.5f)
                }
            }
        }
    }

    @Test
    fun `넉넉하면 한 줄 원하는 자리 그대로`() {
        val widths = listOf(40f, 40f, 40f, 40f)
        val p = LabelLayout.place(listOf(0f, 100f, 200f, 280f), widths, 320f, 4f)
        assertEquals(listOf(0f, 100f, 200f, 280f), p.map { it.x })
        assertTrue(p.all { it.row == 0 })
    }

    @Test
    fun `가까운 둘은 밀어서 한 줄에 둔다`() {
        // 도착 9:45 · 시작 10:00 이 붙은 경우(여유 15분 / 전체 107분)
        val widths = listOf(36f, 36f, 36f, 44f)
        val width = 320f
        val centers = listOf(0f, 194f, 275f, 320f)
        val p = LabelLayout.place(LabelLayout.centered(centers, widths), widths, width, 6f)
        noOverlap(p, widths, width, 6f)
        assertTrue(p.all { it.row == 0 })
    }

    @Test
    fun `큰 글자로 한 줄에 다 안 들어가면 두 줄로 엇갈린다`() {
        // fontScale 1.5 · 320dp 근처: 글자 폭 합이 줄 폭을 넘는다
        val widths = listOf(90f, 90f, 90f, 90f)
        val width = 320f
        assertNull(LabelLayout.push(listOf(0f, 100f, 200f, 230f), widths, width, 6f))
        val p = LabelLayout.place(LabelLayout.centered(listOf(0f, 190f, 300f, 320f), widths), widths, width, 6f)
        assertEquals(listOf(0, 1, 0, 1), p.map { it.row })
        noOverlap(p, widths, width, 6f)
    }

    @Test
    fun `여유 2분처럼 아주 짧은 구간의 이름도 겹치지 않는다`() {
        val widths = listOf(50f, 50f, 50f)
        val width = 300f
        val p = LabelLayout.place(LabelLayout.centered(listOf(10f, 150f, 296f), widths), widths, width, 6f)
        noOverlap(p, widths, width, 6f)
    }

    @Test
    fun `④ 눈금은 양끝 정렬과 경계 오른쪽 정렬을 원하는 자리로 쓴다`() {
        val widths = listOf(30f, 30f, 30f, 40f)
        val anchors = AlarmDecisionLogic.decisionAnchors(listOf(0f, .3f, .7f, 1f), widths, 300f)
        assertEquals(listOf(0f, 60f, 180f, 260f), anchors)
    }
}
