// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 한 줄 위에 글자 묶음(타임라인 눈금 · 구간 이름)을 놓는 계산. 화면이 없는 순수 함수라 단위 테스트로 묶는다.
 *
 * 글자 폭은 **실제로 잰 값**을 받는다. 글자 크기 설정(fontScale)이 커지면 폭이 늘어나므로 고정 폭으로 가정하면
 * "9:4510:00" 처럼 겹친다(11-16 실기기). 한 줄에 다 들어가지 않으면 두 줄로 엇갈려 놓는다.
 */
object LabelLayout {

    /** @property x 왼쪽 위치 @property row 0 = 윗줄, 1 = 아랫줄 */
    data class Placement(val x: Float, val row: Int)

    /**
     * 순서를 지키며 서로 [gap] 이상 떨어지게 밀고 줄 밖으로 나가지 않게 한다. 그렇게 해도 겹치거나 넘치면 null.
     */
    fun push(anchors: List<Float>, widths: List<Float>, width: Float, gap: Float): List<Float>? {
        val n = anchors.size
        if (n == 0) return emptyList()
        if (widths.sum() + gap * (n - 1) > width + 0.5f) return null
        val x = anchors.mapIndexed { i, a -> a.coerceIn(0f, (width - widths[i]).coerceAtLeast(0f)) }.toMutableList()
        for (i in 1 until n) x[i] = maxOf(x[i], x[i - 1] + widths[i - 1] + gap)
        x[n - 1] = minOf(x[n - 1], width - widths[n - 1])
        for (i in n - 2 downTo 0) x[i] = minOf(x[i], x[i + 1] - widths[i] - gap)
        x[0] = maxOf(x[0], 0f)
        for (i in 1 until n) x[i] = maxOf(x[i], x[i - 1] + widths[i - 1] + gap)
        val ok = x[0] >= -0.5f && x[n - 1] + widths[n - 1] <= width + 0.5f &&
            (1 until n).all { x[it] >= x[it - 1] + widths[it - 1] + gap - 0.5f }
        return if (ok) x else null
    }

    /**
     * 한 줄에 놓아 보고, 안 되면 짝수 번째는 윗줄 · 홀수 번째는 아랫줄로 엇갈려 놓는다. 그래도 한 줄에 안 들어가면
     * 그 줄 안에서 고르게 나눈다(글자가 아주 클 때의 마지막 수단).
     */
    fun place(anchors: List<Float>, widths: List<Float>, width: Float, gap: Float): List<Placement> {
        push(anchors, widths, width, gap)?.let { xs -> return xs.map { Placement(it, 0) } }
        val result = MutableList(anchors.size) { Placement(0f, 0) }
        for (row in 0..1) {
            val idx = anchors.indices.filter { it % 2 == row }
            if (idx.isEmpty()) continue
            val xs = push(idx.map { anchors[it] }, idx.map { widths[it] }, width, gap)
                ?: spread(idx.map { widths[it] }, width)
            idx.forEachIndexed { k, i -> result[i] = Placement(xs[k], row) }
        }
        return result
    }

    private fun spread(widths: List<Float>, width: Float): List<Float> {
        val n = widths.size
        if (n == 1) return listOf(0f)
        return List(n) { i -> ((width - widths[i]) * i / (n - 1)).coerceAtLeast(0f) }
    }

    /** 가운데를 [centers] 에 맞춘 원하는 왼쪽 위치 */
    fun centered(centers: List<Float>, widths: List<Float>): List<Float> =
        centers.mapIndexed { i, c -> c - widths[i] / 2 }
}

/**
 * [count] 개의 글자 묶음을 재서 [LabelLayout.place] 대로 놓는다. 높이는 고정하지 않는다 — 내용 높이만큼이고,
 * 두 줄이 되면 그만큼 커진다. [anchors] 는 (재 본 폭 목록, 전체 폭) → 원하는 왼쪽 위치(px).
 */
@Composable
fun PlacedLabels(
    count: Int,
    anchors: (widths: List<Float>, width: Float) -> List<Float>,
    modifier: Modifier = Modifier,
    gap: Dp = 6.dp,
    rowGap: Dp = 4.dp,
    content: @Composable (index: Int) -> Unit,
) {
    Layout(
        content = { repeat(count) { content(it) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val widths = placeables.map { it.width.toFloat() }
        val placements = LabelLayout.place(anchors(widths, width.toFloat()), widths, width.toFloat(), gap.toPx())
        val rowHeights = (0..1).map { row ->
            placeables.filterIndexed { i, _ -> placements.getOrNull(i)?.row == row }.maxOfOrNull { it.height } ?: 0
        }
        val rowGapPx = rowGap.roundToPx()
        val height = rowHeights[0] + if (rowHeights[1] > 0) rowGapPx + rowHeights[1] else 0
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val pl = placements[i]
                val y = if (pl.row == 0) 0 else rowHeights[0] + rowGapPx
                p.placeRelative(pl.x.toInt(), y)
            }
        }
    }
}
