package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteArrival
import androidx.compose.ui.text.style.TextOverflow
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteMode
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTextStyle
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 경로 선택. Figma "⑬ 경로 선택" (node 77:2).
 *
 * **왜 고르게 하는가.** 카카오는 대중교통 대안을 15개 준다. 서버가 축별
 * 대표만 추려 6개 이하로 내리는데, 그 중 무엇이 나은지는 사람마다 다르다.
 * 신림역 → 서울대는 23분(환승 1회)과 27분(환승 없음)이 함께 나오고, 4분을
 * 더 쓰더라도 환승을 피하는 사람이 있다. 서버가 최단 시간으로 정해 버리면
 * 그 선택을 빼앗는다.
 *
 * **소요시간은 서버가 카카오에서 실측한 값이다.** 앱이 계산하지 않는다.
 * 고른 결과도 소요시간이 아니라 [RouteOption.key] 만 서버로 돌려보내고,
 * 알람을 계산할 때 서버가 그 수단을 다시 조회한다. 배차가 바뀌면 값도 바뀌어야
 * 하기 때문이다.
 */
@Composable
fun RouteChoiceScreen(
    state: HomeViewModel.RouteState?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 도착 목표 = 일정 시작 시각. 카드의 출발~도착 시각과 정류장 시각을 만든다.
     * ⏳ B-4 이후에는 서버가 준 시각을 쓴다(지금은 임시 계산, `RouteTimeline.kt`).
     */
    arriveBy: java.time.LocalDateTime? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "경로 선택", onBack = onBack)

        Text(
            text = "어떻게 갈까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        // 출발지는 일정 추가 화면에서 고른다. 여기서는 무엇 기준인지만 밝힌다.
        val choice = state?.choice
        Text(
            text = choice?.let { "${it.originLabel} → ${it.destination}" } ?: "경로를 불러오는 중",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        when {
            state == null || state.loading -> LoadingBlock()

            state.error != null -> ErrorBlock(message = state.error, onRetry = onRetry)

            choice != null -> {

                // 처음 열 탭: 고른 후보의 수단, 없으면 가장 빠른 후보의 수단.
                var mode by rememberSaveable(choice.options.map { it.key }) {
                    mutableStateOf(choice.defaultMode())
                }
                ModeTabs(
                    selected = mode,
                    counts = RouteMode.entries.associateWith { choice.optionsOf(it).size },
                    onSelect = { next ->
                        mode = next
                        // 탭을 바꾸면 그 탭의 첫 후보(가장 빠른 것)를 고른다. 보이지 않는
                        // 탭의 후보가 선택된 채로 "이 경로로 계산" 을 누르면 무엇으로
                        // 계산했는지 화면에서 알 수 없다.
                        val inTab = choice.optionsOf(next)
                        if (inTab.none { it.key == choice.selectedKey }) {
                            inTab.firstOrNull()?.let { onSelect(it.key) }
                        }
                    },
                )

                val inTab = choice.optionsOf(mode)
                emptyTabNotice(choice)?.let {
                    Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
                }
                inTab.forEach { option ->
                    RouteCard(
                        option = option,
                        selected = option.key == choice.selectedKey,
                        onClick = { onSelect(option.key) },
                        depart = departAt(arriveBy, option.minutes),
                        origin = choice.originLabel,
                        destination = choice.destination,
                    )
                }

                Spacer(Modifier.height(4.dp))

                JitPrimaryButton(
                    label = "이 경로로 계산",
                    onClick = onConfirm,
                    enabled = choice.selectedKey != null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * 수단 탭 4개(Figma 13-a ~ 13-d, node 77:2).
 *
 * 고른 탭은 한 단계 밝은 칸 + 주황 글씨 + 아래 주황 밑줄. 후보가 없는 탭은
 * 흐리게 두고 누를 수 없다(왜 비었는지는 탭 아래 안내가 말한다).
 */
@Composable
private fun ModeTabs(
    selected: RouteMode,
    counts: Map<RouteMode, Int>,
    onSelect: (RouteMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RouteMode.entries.forEach { mode ->
            val on = mode == selected
            val available = (counts[mode] ?: 0) > 0
            val tint = when {
                on -> JitColor.Accent
                available -> JitColor.TextSecondary
                else -> JitColor.Track
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Figma: 아이콘 위, 이름 아래.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .background(if (on) JitColor.Surface2 else JitColor.Surface)
                        .clickable(enabled = available && !on) { onSelect(mode) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = ModeIcons.of(mode),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = mode.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) JitColor.Accent else JitColor.Surface)
                )
            }
        }
    }
}

/**
 * 빈 탭이 있을 때의 안내. 왜 비었는지 모르면 고장으로 읽는다.
 *
 * 짧은 거리(1.2km 이하)는 서버가 도보만 조회한다. 그 경우를 따로 말한다.
 */
private fun emptyTabNotice(choice: RouteChoice): String? {
    val empty = RouteMode.entries.filter { choice.optionsOf(it).isEmpty() }
    if (empty.isEmpty()) return null
    val onlyWalk = choice.options.isNotEmpty() && choice.options.all { it.travelMode == RouteMode.WALK }
    return if (onlyWalk) {
        "가까운 거리라 도보 경로만 받아왔음"
    } else {
        "${empty.joinToString("·") { it.label }} 는 이 구간에 경로가 없음"
    }
}

@Composable
private fun RouteCard(
    option: RouteOption,
    selected: Boolean,
    onClick: () -> Unit,
    /** 일정 시작에 맞춘 출발 시각(임시 계산). null 이면 시각을 쓰지 않는다 */
    depart: java.time.LocalDateTime? = null,
    origin: String = "출발",
    destination: String = "도착",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .then(
                // Figma: 고른 카드는 주황 테두리.
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = JitColor.Accent,
                        shape = RoundedCornerShape(JitRadius.Card),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.minutesLabel,
                color = JitColor.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            // "지하철+도보+버스" 같은 수단 이름은 쓰지 않는다. 탭과 구간 막대가 이미 말한다.
            // 대신 일정 시작에 맞춘 출발~도착 시각을 총 시간 오른쪽에 쓴다(Figma 13-b).
            depart?.let {
                Spacer(Modifier.width(9.dp))
                Text(
                    text = timeRangeLabel(
                        it.toLocalTime(),
                        it.plusMinutes(option.minutes.toLong()).toLocalTime(),
                    ),
                    color = if (selected) JitColor.TextPrimary else JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            // 배지는 여러 개일 수 있다("최단 시간", "최소 비용", "최소 환승"). 서버 문구 그대로.
            option.badges.forEachIndexed { index, badge ->
                if (index > 0) Spacer(Modifier.width(4.dp))
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
        if (option.travelMode == RouteMode.BICYCLE) {
            // Figma 13-d: 자전거는 요약 줄 대신 지표 칸. 서버가 주는 값만 칸으로 그리고
            // 없는 칸은 그리지 않는다. 경사도 칸은 없다(카카오가 주지 않음).
            // 거리·자전거도로 두 칸을 1:1 로 가로를 채운다. 자전거도로는 카카오가 정확한
            // 값을 주지 않아 서버 값이 없으면 칸만 두고 비워 둔다(사용자 결정 10-05).
            MetricCells(
                listOf(
                    "거리" to option.distanceM?.let { "%.1fkm".format(it / 1000.0) }.orEmpty(),
                    "자전거도로" to option.bikeRoadPercent?.let { "$it%" }.orEmpty(),
                ),
            )
        } else if (option.detailLine.isNotBlank()) {
            // 요약 한 줄은 서버 문구 그대로("2호선 → 5513 · 5.1km · 환승 1회 · 1,550원").
            // 도보는 "1.2km" 처럼 거리만 온다. 계단·경사는 쓰지 않는다(task.md 13-0).
            Text(text = option.detailLine, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        // 막대는 대중교통의 구간 막대(노선·버스 색 + 구간별 분)만 그린다. 자동차·도보·
        // 자전거의 빠르기 막대는 전하는 정보가 없어 뺐다(사용자 결정 10-05).
        if (option.hasSegmentBar) {
            SegmentBar(option.segments)
        }
        // 상세 목록은 선택한 카드에만 펼친다. 모든 후보에 열면 같은 정류장이
        // 반복돼 비교가 어려워지고 한 카드가 화면 여러 장을 차지한다.
        if (selected) {
            val rows = timelineRows(option.segments, origin, destination, depart)
            if (rows.isNotEmpty()) {
                // Figma 13-b: 막대와 정류장 타임라인 사이 구분선.
                HorizontalDivider(color = JitColor.Track, modifier = Modifier.padding(vertical = 2.dp))
                RouteTimeline(rows)
            }
        }
    }
}

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
private fun SegmentBar(segments: RouteSegments) {
    val items = barSegments(segments)
    val weights = labeledWeights(items)
    if (weights.isEmpty()) return

    // Figma 13-b: 구간마다 따로 둥근 칸, 칸 사이에 틈.
    Row(
        modifier = Modifier
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

/** 막대에 그릴 구간. 대기(차 기다리는 시간)는 빼고 움직이는 구간만. */
internal fun barSegments(segments: RouteSegments): List<RouteSegment> =
    segments.items.filter { it.kind != RouteSegment.Kind.WAIT }

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

/**
 * 고른 카드의 정류장 타임라인(Figma 13-b, node 232:265).
 *
 * 한 행: [점] 이름 [노선 칩] / 아래 작은 글자("승차"·"환승"·"하차") ··· 오른쪽 시각 / "○○ 도착".
 * 점 색은 그 지점에서 타는 노선색이고, 출발·도착은 회색이다. 노선 번호는 이름 오른쪽 칩으로 둔다.
 *
 * 다음 차량·그다음 차량 "N분 뒤 도착" 은 보여 주지 않는다. 경로를 고르는 단계에서는
 * 필요 없고 화면만 길어진다.
 */
@Composable
private fun RouteTimeline(rows: List<TimelineRow>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { TimelineRowView(it) }
    }
}

/**
 * 자전거 카드의 지표 칸(Figma 13-d). "거리 / 4.8km" 처럼 이름 위, 값 아래.
 * 칸들이 같은 폭으로 가로를 꽉 채운다. 값이 빈 칸도 높이는 같게 둔다.
 */
@Composable
private fun MetricCells(cells: List<Pair<String, String>>) {
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

/** 이름 옆 노선 칩을 몇 개까지 그릴지. 넘치면 "+N" 으로 줄인다. */
private const val MAX_LINE_CHIPS = 4

/**
 * 노선 칩 하나("5519", "2호선"). 색은 노선색·버스 종류색.
 *
 * 기본 Text 는 폰트 위아래 여백 때문에 칩이 세로로 길어진다. TightCentered 로
 * 여백을 걷어 칩을 글자 높이에 맞춘다.
 */
@Composable
private fun LineChip(segment: RouteSegment) {
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

@Composable
private fun TimelineRowView(row: TimelineRow) {
    val lineColor = row.line?.let { Color(SegmentPalette.fill(it)) }
    val dotColor = lineColor ?: JitColor.Track
    val end = row.kind == TimelineRow.Kind.END

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 이름과 아래 "승차" 사이가 벌어지지 않게 폰트 여백을 걷는다.
                    style = JitTextStyle.TightCentered,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 노선 번호는 이름 오른쪽 칩. 색은 노선색 그대로. 같은 구간을 가는
                // 대체 버스가 있으면 그 칩도 나란히 붙인다(많으면 "+N").
                row.line?.let { segment ->
                    val chips = listOf(segment) + segment.alternatives
                    val shown = chips.take(MAX_LINE_CHIPS)
                    shown.forEach { chip ->
                        Spacer(Modifier.width(4.dp))
                        LineChip(chip)
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.sub,
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
                style = JitTextStyle.TightCentered,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.time?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) ?: "",
                // 시각 아래 "출발 / ○○ 도착" 글자는 뺐다. 마지막 도착 시각만 초록으로 구분한다.
                color = if (end) JitColor.Green else JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = JitColor.Accent, strokeWidth = 2.dp)
        Text(
            text = "카카오에서 경로를 받아오는 중",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(JitColor.Red)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "경로를 불러오지 못했음",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = message, color = JitColor.TextSecondary, fontSize = 12.sp)
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun RouteChoicePreview() {
    JitTheme {
        RouteChoiceScreen(
            state = HomeViewModel.RouteState(
                loading = false,
                choice = RouteChoice(
                    originLabel = "신림역",
                    destination = "서울대학교 관악캠퍼스",
                    selectedKey = "transit:2호선>5513",
                    options = listOf(
                        RouteOption("car", "자동차", 14, "14분", "택시 예상액 · 4.3km · 7,600원", "가장 빠름"),
                        RouteOption(
                            "transit:2호선>5513", "지하철+도보+버스", 23, "23분",
                            "2호선 → 5513 · 5.1km · 환승 1회 · 1,550원", null,
                            // 지하철 노선색과 버스 종류색이 같이 나오는 조합.
                            // 프리뷰에서 색이 섞이는 모양을 보려고 이걸 골랐다.
                            RouteSegments(
                                listOf(
                                    RouteSegment(RouteSegment.Kind.WALK, 240, "도보"),
                                    RouteSegment(
                                        RouteSegment.Kind.SUBWAY,
                                        420,
                                        "2호선",
                                        lineName = "2호선",
                                        region = "metro_seoul",
                                        stops = listOf("신림", "봉천", "서울대입구(관악구청)"),
                                        arrivals = listOf(
                                            RouteArrival(200, "3분 20초 뒤 도착"),
                                            RouteArrival(580, "9분 40초 뒤 도착"),
                                        ),
                                    ),
                                    RouteSegment(RouteSegment.Kind.WALK, 120, "도보"),
                                    RouteSegment(
                                        RouteSegment.Kind.BUS,
                                        480,
                                        "5513",
                                        busType = "지선",
                                        region = "metro_seoul",
                                        stops = listOf("봉천", "관악구청"),
                                        arrivals = listOf(
                                            RouteArrival(70, "1분 10초 뒤 도착", crowding = "여유"),
                                            RouteArrival(810, "13분 30초 뒤 도착"),
                                        ),
                                    ),
                                    RouteSegment(RouteSegment.Kind.WALK, 120, "도보"),
                                )
                            ),
                        ),
                        RouteOption("bicycle", "자전거", 24, "24분", "5.0km", null),
                        RouteOption(
                            "transit:5516", "버스", 27, "27분", "5516 · 4.6km · 1,500원", "환승 없음",
                            // 대기가 보이는 경우. 버스를 기다리는 시간이 도보와
                            // 다른 색이라야 "걸어서 6분" 과 구분된다.
                            RouteSegments(
                                listOf(
                                    RouteSegment(RouteSegment.Kind.WALK, 180, "도보"),
                                    RouteSegment(RouteSegment.Kind.WAIT, 300, "대기"),
                                    RouteSegment(
                                        RouteSegment.Kind.BUS,
                                        780,
                                        "5516",
                                        busType = "간선",
                                        region = "metro_seoul",
                                        stops = listOf("제2공학관", "서울대정문"),
                                    ),
                                    RouteSegment(RouteSegment.Kind.WALK, 360, "도보"),
                                )
                            ),
                        ),
                    ),
                ),
            ),
            onSelect = {},
            onConfirm = {},
            onRetry = {},
            onBack = {},
        )
    }
}
