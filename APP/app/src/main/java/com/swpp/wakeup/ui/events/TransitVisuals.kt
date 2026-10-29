// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitTextStyle

/*
 * 수단별 색을 그리는 공용 부품(구간 막대·노선 칩·지표 칸·배지).
 *
 * 색 규칙 자체는 [SegmentPalette] 한 곳에 있다(ARGB Long, 기기 없이 테스트). 여기는
 * 그 색으로 **그리는** 부품만 둔다. 예전에는 경로 선택 화면 안에 private 으로
 * 흩어져 있어서 미리보기 화면이나 다른 화면이 같은 막대를 쓸 수 없었다.
 *
 * - 도보는 카카오맵처럼 회색, 대기는 막대에서 뺀다.
 * - 버스는 종류색(간선·지선·마을·광역·순환·심야·공항), 모르는 종류는 중립색.
 * - 지하철은 권역별 노선색. 권역을 모르면 추측하지 않고 중립색.
 */

/**
 * 구간 막대. "도보 4분 | 2호선 7분 | 도보 2분 | 5511 8분 | 도보 2분"
 *
 * ## 무엇을 해결하는가
 *
 * "25분" 만 보면 그 25분의 생김새를 알 수 없다. 18분을 버스에 앉아 있는
 * 경로와 12분을 걷는 경로는 같은 25분이지만 비 오는 날의 선택이 다르다.
 * 카카오맵이 같은 것을 가로 막대로 보여 주고, 그게 실제로 읽기 쉽다.
 *
 * ## 무엇을 그리는가
 *
 * 도보·버스·지하철처럼 **실제로 움직이는 구간만** 그린다. 차를 기다리는 대기
 * 칸은 뺀다([barSegments]). 모든 칸에 "N분" 을 쓰므로, 짧은 구간도 글자가
 * 들어갈 만큼은 넓혀 그린다([labeledWeights]) — 비율이 정확할 필요는 없다.
 *
 * 색은 [SegmentPalette] 가 고르고 지하철 노선색·버스 종류색을 따른다 —
 * 사용자가 이미 아는 색이라야 막대가 한 번에 읽힌다.
 */
@Composable
internal fun TransitSegmentBar(segments: RouteSegments, modifier: Modifier = Modifier) {
    val items = barSegments(segments)
    val weights = labeledWeights(items)
    if (weights.isEmpty()) return

    // Figma 13-b: 구간마다 따로 둥근 칸, 칸 사이에 틈.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, segment ->
            Box(
                modifier = Modifier
                    .weight(weights[index])
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(SegmentPalette.fill(segment))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = segment.minutesLabel,
                    color = Color(SegmentPalette.onFill(segment)),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    // Box 의 Alignment.Center 만으로는 부족하다. 기본 Text 는
                    // 폰트 여백을 줄 상자에 넣어 글리프를 아래로 밀어낸다.
                    style = JitTextStyle.TightCentered,
                )
            }
        }
    }
}

// AI-generated (Claude)
/** 막대에 그릴 구간. 대기(차 기다리는 시간)는 빼고 움직이는 구간만. */
internal fun barSegments(segments: RouteSegments): List<RouteSegment> =
    segments.items.filter { it.kind != RouteSegment.Kind.WAIT }

// AI-generated (Claude)
/**
 * 칸 폭 비율. 소요시간 비례가 기본이지만 모든 칸이 [minShare] 이상이 되게
 * 넓힌다. 9sp "12분" 이 들어갈 최소치다. 칸이 많으면 1/n 까지 낮춘다.
 * 합은 1 이다.
 */
internal fun labeledWeights(segments: List<RouteSegment>, minShare: Float = 0.14f): List<Float> {
    if (segments.isEmpty()) return emptyList()
    val total = segments.sumOf { it.seconds.coerceAtLeast(1) }.toFloat()
    val raw = segments.map { it.seconds.coerceAtLeast(1) / total }
    val floor = minOf(minShare, 1f / segments.size)

    // 작은 칸을 floor 로 올리고 남은 폭을 나머지에 비율대로 나눈다. 나눈 뒤 다시
    // floor 아래로 떨어진 칸이 생길 수 있어 고정될 때까지 반복한다(칸 수만큼이면 충분).
    val pinned = BooleanArray(raw.size)
    var result = raw
    repeat(raw.size) {
        raw.indices.forEach { if (result[it] < floor) pinned[it] = true }
        val free = 1f - floor * pinned.count { p -> p }
        val freeRaw = raw.indices.filter { !pinned[it] }.sumOf { raw[it].toDouble() }.toFloat()
        result = raw.indices.map { i ->
            if (pinned[i]) floor else if (freeRaw > 0f) raw[i] / freeRaw * free else floor
        }
    }
    return result
}

// AI-generated (Claude)
/** 이름 옆 노선 칩을 몇 개까지 그릴지. 넘치면 "+N" 으로 줄인다. */
private const val MAX_LINE_CHIPS = 4

// Partly AI-generated (Claude)
/**
 * 노선 칩 하나("5519", "2호선"). 색은 노선색·버스 종류색.
 *
 * 기본 Text 는 폰트 위아래 여백 때문에 칩이 세로로 길어진다. TightCentered 로
 * 여백을 걷어 칩을 글자 높이에 맞춘다.
 */
@Composable
internal fun TransitLineChip(segment: RouteSegment) {
    Text(
        text = segment.label,
        color = Color(SegmentPalette.onFill(segment)),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        style = JitTextStyle.TightCentered,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(SegmentPalette.fill(segment)))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// Partly AI-generated (Claude)
/**
 * 대표 노선 + 같은 구간을 가는 대체 노선 칩. 많으면 [max] 개까지 그리고 "+N".
 *
 * 칩마다 앞에 4dp 틈을 둔다. 이름 바로 오른쪽에 붙여 쓰는 자리라서다.
 */
@Composable
internal fun TransitLineChips(primary: RouteSegment, max: Int = MAX_LINE_CHIPS) {
    val chips = listOf(primary) + primary.alternatives
    val shown = chips.take(max)
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { chip ->
            Spacer(Modifier.width(4.dp))
            TransitLineChip(chip)
        }
        if (chips.size > shown.size) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+${chips.size - shown.size}",
                color = JitColor.TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                style = JitTextStyle.TightCentered,
            )
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 자전거 카드의 지표 칸(Figma 13-d). "거리 / 4.8km" 처럼 이름 위, 값 아래.
 * 칸들이 같은 폭으로 가로를 꽉 채운다. 값이 빈 칸도 높이는 같게 둔다.
 */
@Composable
internal fun MetricCells(cells: List<Pair<String, String>>) {
    if (cells.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cells.forEach { (name, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(JitRadius.Hint))
                    .background(JitColor.Surface2)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                Text(name, color = JitColor.TextSecondary, fontSize = 10.sp)
                // 빈 값이어도 줄 높이를 지키려고 공백 한 칸을 쓴다.
                Text(value.ifEmpty { " " }, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// AI-generated (Claude)
/**
 * 자전거 지표 칸의 내용. 서버가 주는 값만 쓰고 경사도 칸은 없다(카카오가 주지 않음).
 * 거리·자전거도로 두 칸을 1:1 로 둔다. 자전거도로는 카카오가 정확한 값을 주지 않아
 * 서버 값이 없으면 칸만 두고 비워 둔다(사용자 결정 10-05).
 */
internal fun bicycleMetrics(option: RouteOption): List<Pair<String, String>> = listOf(
    "거리" to option.distanceM?.let { "%.1fkm".format(it / 1000.0) }.orEmpty(),
    "자전거도로" to option.bikeRoadPercent?.let { "$it%" }.orEmpty(),
)

// Partly AI-generated (Claude)
/**
 * 카드 오른쪽 위 배지들("최단 시간", "최소 비용", "최소 환승", "최소 도보" 중 최대 3개). 서버 문구 그대로.
 * 목록 카드와 지도 미리보기가 같이 쓴다.
 */
@Composable
internal fun RouteBadges(badges: List<String>) {
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { badge ->
            Text(
                text = badge,
                color = JitColor.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(JitColor.Surface2)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}
