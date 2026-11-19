package com.swpp.wakeup.ui.events

import com.swpp.wakeup.ui.common.LineIcons
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import com.swpp.wakeup.ui.common.JitRetryButton
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTextStyle
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 경로 선택. Figma "⑬ 경로 선택" (node 77:2).
 *
 * **왜 고르게 하는가.** 카카오는 대중교통 대안을 15개 준다. 서버가 축별
 * 대표를 수단별로 추려 내리는데, 그 중 무엇이 나은지는 사람마다 다르다.
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
    loadMap: (suspend (GeoPoint, Int, Int, Int) -> EventRepository.Result<Bitmap>)? = null,
    /**
     * 좌표가 있는 카드의 지도 미리보기를 연다(같은 경로 안에서). 자동차·도보·자전거는
     * 카드를 누를 때, 대중교통은 카드의 "지도로 보기" 를 누를 때 부른다
     */
    onPreview: (String) -> Unit = {},
    /** 미리보기의 뒤로. 목록으로 돌아간다(선택은 그대로) */
    onClosePreview: () -> Unit = {},
) {
    // 지도 미리보기(⑬-e). 새 화면 경로를 쌓지 않고 같은 경로 안에서 바꿔 그린다 —
    // "이 경로로 계산" 이 일정 추가로 바로 돌아가야 해서다(RoutineHost 와 같은 방식).
    val routes = state?.choice
    val previewOption = state?.previewKey
        ?.let { key -> routes?.options?.firstOrNull { it.key == key } }
        ?.takeIf { it.canPreviewMap }
    if (routes != null && previewOption != null && loadMap != null) {
        RoutePreviewScreen(
            option = previewOption,
            originLabel = routes.originLabel,
            destination = routes.destination,
            loadMap = loadMap,
            onClose = onClosePreview,
            onConfirm = {
                onSelect(previewOption.key)
                onConfirm()
            },
            modifier = modifier,
        )
        return
    }

    // Figma 시안(11-18): 머리 · 수단 탭 · 묶음 탭 · 경로 목록(구분선) · 아래 고정 [이 경로로 정하기].
    val choice = state?.choice
    Column(modifier = modifier.fillMaxSize().background(JitColor.Bg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("route_scroll")
                .padding(
                    start = JitSpace.ScreenHorizontal,
                    end = JitSpace.ScreenHorizontal,
                    top = JitSpace.ScreenTop,
                    bottom = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader(title = "경로 선택", onBack = onBack)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "어떻게 갈까요?",
                    color = JitColor.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                // 출발지는 일정 추가 화면에서 고른다. 여기서는 무엇 기준인지만 밝힌다.
                Text(
                    text = choice?.let { RouteChoiceLogic.header(it.originLabel, it.destination, arriveBy) } ?: "경로를 불러오는 중",
                    color = JitColor.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("route_header"),
                )
            }

            when {
                state == null || state.loading -> LoadingBlock()

                state.error != null -> ErrorBlock(
                    message = state.error,
                    retryable = state.retryable,
                    onRetry = onRetry,
                    onBack = onBack,
                )

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
                            // 탭의 후보가 선택된 채로 정하면 무엇으로 계산했는지 화면에서 알 수 없다.
                            val inTab = choice.optionsOf(next)
                            if (inTab.none { it.key == choice.selectedKey }) {
                                inTab.firstOrNull()?.let { onSelect(it.key) }
                            }
                        },
                    )

                    val inMode = choice.optionsOf(mode)
                    // 대중교통은 묶음 탭(전체 · 버스 · 지하철 · 버스+지하철)으로 한 번 더 나눈다
                    var group by rememberSaveable(mode, choice.options.map { it.key }) {
                        mutableStateOf(RouteChoiceLogic.Group.ALL)
                    }
                    if (mode == RouteMode.TRANSIT && inMode.isNotEmpty()) {
                        GroupTabs(
                            groups = RouteChoiceLogic.groups(inMode),
                            selected = group,
                            onSelect = { g ->
                                group = g
                                val shown = RouteChoiceLogic.filter(inMode, g)
                                if (shown.none { it.key == choice.selectedKey }) {
                                    shown.firstOrNull()?.let { onSelect(it.key) }
                                }
                            },
                        )
                    }
                    emptyTabNotice(choice)?.let {
                        Text(text = it, color = JitColor.TextSecondary, fontSize = 12.sp)
                    }

                    val shown = if (mode == RouteMode.TRANSIT) RouteChoiceLogic.filter(inMode, group) else inMode
                    Column {
                        shown.forEachIndexed { index, option ->
                            if (index > 0) HorizontalDivider(color = JitColor.Track, modifier = Modifier.padding(vertical = 6.dp))
                            // 지도는 목록 안에 붙이지 않는다(세로 스크롤과 핀치가 겹친다). 좌표가 있으면 전체 화면 미리보기로 간다.
                            val previewable = option.canPreviewMap && loadMap != null
                            val selected = option.key == choice.selectedKey
                            RouteCard(
                                option = option,
                                selected = selected,
                                onClick = {
                                    if (previewable && option.opensPreviewOnTap) onPreview(option.key) else onSelect(option.key)
                                },
                                arriveBy = arriveBy,
                                destination = choice.destination,
                                onPreview = if (previewable) ({ onPreview(option.key) }) else null,
                                caption = choice.preferredCaption?.takeIf { option.key == choice.preferredKey },
                            )
                            if (selected) RouteGuide(option.guide)
                        }
                    }
                }
            }
        }

        // 아래 고정. 화면의 꽉 찬 주황 버튼은 이것 하나다.
        if (choice != null && state.error == null && !state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JitColor.Bg)
                    .padding(start = JitSpace.ScreenHorizontal, end = JitSpace.ScreenHorizontal, top = 8.dp, bottom = 16.dp),
            ) {
                JitPrimaryButton(
                    label = "이 경로로 정하기",
                    modifier = Modifier.testTag("route_confirm"),
                    onClick = onConfirm,
                    enabled = choice.selectedKey != null,
                )
            }
        }
    }
}

// Partly AI-generated (Claude)
/** 묶음 탭: "전체 3 · 버스 1 · 지하철 1 · 버스+지하철 1". 고른 것만 흰 글씨 + 주황 밑줄. 넘치면 가로로 민다 */
@Composable
private fun GroupTabs(
    groups: List<Pair<RouteChoiceLogic.Group, Int>>,
    selected: RouteChoiceLogic.Group,
    onSelect: (RouteChoiceLogic.Group) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("route_groups"),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            groups.forEach { (g, count) ->
                val on = g == selected
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Tab, onClick = { onSelect(g) })
                        .testTag("route_group_${g.name.lowercase()}")
                        .padding(top = 8.dp, start = 2.dp, end = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "${g.label} $count",
                        color = if (on) JitColor.TextPrimary else JitColor.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (on) JitColor.Accent else JitColor.Bg)
                    )
                }
            }
        }
        HorizontalDivider(color = JitColor.Track)
    }
}

// ---------------------------------------------------------------------------

@Composable
internal fun RouteGuide(guide: String?) {
    if (guide.isNullOrBlank()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "● 최단 경로 안내", color = JitColor.Accent,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            text = guide, color = JitColor.TextSecondary, fontSize = 12.sp,
            modifier = Modifier.testTag("route_guide"),
        )
    }
}

// Partly AI-generated (Claude)
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
                        // 선 아이콘(LineIcons)으로 통일(11-18 시안)
                        imageVector = when (mode) {
                            RouteMode.CAR -> LineIcons.car
                            RouteMode.TRANSIT -> LineIcons.bus
                            RouteMode.WALK -> LineIcons.walk
                            RouteMode.BICYCLE -> LineIcons.bike
                        },
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

// AI-generated (Claude)
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
        "가까운 거리라 도보 경로만 찾았어요"
    } else {
        "${empty.joinToString("·") { it.label }}는 이 구간에 경로가 없어요"
    }
}

// Partly AI-generated (Claude)
/**
 * 경로 한 줄(Figma 시안 11-18). 카드 테두리 없이 목록 사이 구분선으로 나눈다. 고른 줄은 오른쪽 끝 주황 "선택 ✓".
 *
 * 위에서부터: 배지(지난번에 고른 길 · 서버 배지) / 큰 소요 시간 + "환승 1회" · "버스" / "오전 8:08 - 8:50 · 1,550원" /
 * 구간 막대(도보 합계 · 탄 구간 · 대기 합계) + "도보 14분 · 대기 4분은 전체 합계" / 탄 구간 목록 + "○○ 도착".
 * 자동차 · 도보 · 자전거는 막대와 목록 대신 서버 요약(자전거는 지표 칸).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(
    option: RouteOption,
    selected: Boolean,
    onClick: () -> Unit,
    arriveBy: java.time.LocalDateTime?,
    destination: String,
    /** 지도 미리보기를 연다. null 이면 좌표가 없어 "지도로 보기" 를 달지 않는다 */
    onPreview: (() -> Unit)? = null,
    /** 기본으로 고른 이유. "지난번에 고른 경로". 없으면 그리지 않는다 */
    caption: String? = null,
) {
    val cardOpensMap = onPreview != null && option.opensPreviewOnTap
    Column(
        modifier = Modifier
            .testTag("route_${option.key}")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .clickable(onClickLabel = if (cardOpensMap) "지도로 보기" else "이 경로 고르기", onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 11-18 사용자: 글씨를 한 단계 줄이고, 배지는 소요 시간 · 설명 오른쪽에 작게, 예전 색(주황 글자)으로.
        // 한 줄에 다 안 들어가면 다음 줄로 넘긴다(FlowRow) — 자르지 않는다.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = option.minutesLabel,
                    color = JitColor.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                RouteChoiceLogic.subtitle(option)?.let {
                    Text(
                        it,
                        color = JitColor.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                // "지난번에 고른 경로" — 예전처럼 주황 굵은 글자(배경 없음)
                caption?.let {
                    Text(
                        text = it,
                        color = JitColor.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterVertically).testTag("route_preferred"),
                    )
                }
                // 서버 배지("최단 시간" 등, 문구 그대로) — 예전 [RouteBadges] 와 같은 주황 글자 · 회색 알약
                option.badges.forEach { badge ->
                    Text(
                        text = badge,
                        color = JitColor.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(999.dp))
                            .background(JitColor.Surface2)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (selected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp).testTag("route_selected"),
                ) {
                    Text("선택", color = JitColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(LineIcons.check, contentDescription = null, tint = JitColor.Accent, modifier = Modifier.size(16.dp))
                }
            }
        }
        RouteChoiceLogic.timeLine(option, arriveBy)?.let {
            Text(it, color = JitColor.TextSecondary, fontSize = 12.sp)
        }
        if (option.travelMode == RouteMode.BICYCLE) {
            MetricCells(bicycleMetrics(option))
        } else if (!RouteChoiceLogic.isTransit(option) && option.detailLine.isNotBlank()) {
            // 자동차 · 도보는 서버 요약 한 줄 그대로("택시 예상액 · 4.3km · 7,600원", "1.2km")
            Text(text = option.detailLine, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        if (RouteChoiceLogic.isTransit(option)) {
            val cells = RouteChoiceLogic.barCells(option)
            if (cells.isNotEmpty()) {
                SegmentBar(cells)
                RouteChoiceLogic.totalsNote(option)?.let {
                    Text(it, color = JitColor.TextSecondary, fontSize = 11.sp)
                }
            }
            val steps = RouteChoiceLogic.steps(option)
            if (steps.isNotEmpty()) StepList(steps, destination)
        }
        onPreview?.let { open ->
            Text(
                text = "지도로 보기 ›",
                color = JitColor.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("route_preview_${option.key}")
                    .then(
                        if (cardOpensMap) Modifier
                        else Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = open)
                            .padding(horizontal = 4.dp)
                    ),
            )
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 구간 막대(시안): 칸 폭은 시간 비례(작은 칸은 최소 폭), 칸 안에 수단 아이콘 + "14분". 글자가 안 들어갈 만큼 좁으면
 * 글자를 빼고 아이콘만, 더 좁으면 색만. 지하철 · 버스 색은 노선색([SegmentPalette]), 도보 · 대기는 회색.
 */
@Composable
private fun SegmentBar(cells: List<RouteChoiceLogic.BarCell>) {
    val total = cells.sumOf { it.minutes }.coerceAtLeast(1).toFloat()
    val weights = labeledWeights(cells.map { RouteSegment(it.kind, it.minutes * 60, "") }, minShare = 0.12f)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).clip(RoundedCornerShape(6.dp)).testTag("route_bar"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cells.forEachIndexed { i, cell ->
            val seg = cell.ride ?: RouteSegment(cell.kind, cell.minutes * 60, "")
            val fill = when (cell.kind) {
                RouteSegment.Kind.WALK, RouteSegment.Kind.WAIT -> JitColor.Surface2
                else -> Color(SegmentPalette.fill(seg))
            }
            val on = when (cell.kind) {
                RouteSegment.Kind.WALK, RouteSegment.Kind.WAIT -> JitColor.TextPrimary
                else -> Color(SegmentPalette.onFill(seg))
            }
            val share = weights.getOrElse(i) { cell.minutes / total }
            Row(
                modifier = Modifier.weight(share).heightIn(min = 24.dp).background(fill).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
            ) {
                val icon = when (cell.kind) {
                    RouteSegment.Kind.WALK -> LineIcons.walk
                    RouteSegment.Kind.SUBWAY -> LineIcons.train
                    RouteSegment.Kind.BUS -> LineIcons.bus
                    else -> null
                }
                if (icon != null && share >= 0.14f) Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(12.dp))
                if (share >= 0.10f) {
                    Text(
                        text = "${cell.minutes}분",
                        color = on,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

// Partly AI-generated (Claude)
/** 탄 구간 목록(시안): 둥근 수단 아이콘 + 굵은 노선 이름 / 회색 "지하철 · 7분 탑승", 줄 사이 세로선, 끝은 점 + "○○ 도착" */
@Composable
private fun StepList(steps: List<RouteChoiceLogic.Step>, destination: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("route_steps")) {
        steps.forEach { step ->
            val line = Color(SegmentPalette.fill(step.ride))
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.5.dp, line, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (step.ride.kind == RouteSegment.Kind.BUS) LineIcons.bus else LineIcons.train,
                            contentDescription = null,
                            tint = line,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Box(Modifier.width(2.dp).height(10.dp).background(JitColor.Track))
                }
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.name, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(step.sub, color = JitColor.TextSecondary, fontSize = 11.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(JitColor.TextSecondary))
            }
            Spacer(Modifier.width(10.dp))
            Text("$destination 도착", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            text = "경로를 찾는 중이에요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

// Partly AI-generated (Claude)
/**
 * 후보를 못 받았을 때. [retryable] 이 false 면(가까운 출발·도착 400) 다시 시도해도
 * 같은 400 이 나오므로 "다시 시도" 대신 장소를 고치러 돌아가게 한다.
 */
@Composable
private fun ErrorBlock(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
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
                text = "경로를 불러오지 못했어요",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = message, color = JitColor.TextSecondary, fontSize = 12.sp)
        // 다시 시도할 수 있으면 [다시 시도], 못 하면 같은 회색 버튼에 아이콘 없이 "출발지·도착지 다시 고르기"(HE 2-6)
        if (retryable) {
            JitRetryButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp).testTag("route_retry"))
        } else {
            JitRetryButton(
                onClick = onBack,
                label = "출발지·도착지 다시 고르기",
                icon = null,
                modifier = Modifier.padding(top = 2.dp).testTag("route_reselect"),
            )
        }
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
