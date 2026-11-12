// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.events.SegmentPalette
import com.swpp.wakeup.ui.home.HomeLabels
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.home.riskColor
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 하단 탭 "이동"의 첫 화면(Figma "HE 2-2 · 이동 탭 · 출발 전" `545:1080`, iter4 H2-2).
 *
 * 맨 위 카드에 다음 이동을 가장 크게(출발 시각 → 몇 분 뒤 · 도착 예정 → 어디로) 두고, "가는 길" 카드에
 * 출발부터 도착까지 할 일을 시각 순서로 세로 선에 잇는다. 지하철·버스는 노선색 칩과 색 선, 걷는 구간은
 * 회색 선이다. 보여 줄 일정은 지금부터 가장 가까운, 경로가 정해진 일정이다([TripTabLogic.pick]).
 *
 * 이동 중이면 맨 위에 "이동 중" 카드를 두고 ④ 의 진행 카드로 보낸다. 이 탭 안에 진행 카드를 옮겨 그리는
 * 것은 다음 단계다(Figma 캡션: 기존 ④-c `471:9` · `510:9`, 더 빠른 길 `512:9`).
 */
@Composable
fun TripTabScreen(
    /** 보여 줄 일정([TripTabLogic.pick]). 없으면 빈 상태 */
    event: UpcomingEvent?,
    /** 목록에 일정이 하나라도 있는가. 빈 상태 문구를 가른다 */
    hasEvents: Boolean,
    today: LocalDate,
    trip: HomeViewModel.TripTabState,
    /** "가는 길" 정류장 구간. 이 일정·경로 것만 넘긴다. 아직 없거나 대중교통이 아니면 null */
    segments: RouteSegments?,
    /** 이 일정이 지금 이동 중인가([com.swpp.wakeup.domain.model.TripStage.IN_TRANSIT]) */
    inTransit: Boolean,
    onOpenEvent: (Long) -> Unit,
    onChangeRoute: (Long) -> Unit,
    onAddEvent: () -> Unit,
    /** 정류장 구간이 필요하다(카드가 보일 때 한 번). [HomeViewModel.loadCheckpointsIfNeeded] */
    onNeedSegments: (eventId: Long, routeKey: String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 오른쪽 위 ?(도움말, H2-4). null 이면 그리지 않는다 */
    onHelpClick: (() -> Unit)? = null,
) {
    // 오늘 갈 곳이 없을 때 [내일 가는 길 보기] 를 누르면 그 일정을 펼친다. 일정이 바뀌면 다시 접는다.
    var showLater by rememberSaveable(event?.id) { mutableStateOf(false) }
    val empty = TripTabLogic.empty(event, hasEvents, today)?.takeUnless { showLater && event != null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("trip_tab")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        TripHeader(onHelpClick)

        if (empty != null || event == null) {
            empty?.let {
                EmptyCard(
                    empty = it,
                    onAction = {
                        if (it.kind == TripTabLogic.Empty.Kind.LATER) showLater = true else onAddEvent()
                    },
                )
            }
            return@Column
        }

        if (inTransit) InTransitCard(onClick = { onOpenEvent(event.id) })

        val plan = trip.plan?.takeIf { it.eventId == event.id }
        when {
            plan != null -> {
                NextTripCard(event = event, plan = plan, today = today, onClick = { onOpenEvent(event.id) })
                RouteCard(
                    plan = plan,
                    segments = segments,
                    onNeedSegments = onNeedSegments,
                    onMap = { onOpenEvent(event.id) },
                    onChangeRoute = { onChangeRoute(event.id) },
                )
                Hint(alarmOn = plan.alarmOn)
            }

            trip.failed && trip.eventId == event.id -> FailedCard(onRetry)

            else -> LoadingCard()
        }
    }
}

@Composable
private fun TripHeader(onHelpClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "이동", color = JitColor.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        onHelpClick?.let { help ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(JitColor.Surface2)
                    .clickable(role = Role.Button, onClick = help),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LineIcons.help, contentDescription = "도움말", tint = JitColor.TextPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 다음 이동(Figma `hero 다음 이동`). "다음 이동 · 오늘" + 정시 도착 확률 칩 / "오전 8:08 출발" /
 * "1시간 56분 뒤 · 8:50 도착 예정" / 핀 + "302동 105호 · 9:00 자료구조 및 알고리즘". 누르면 ④ 로 간다.
 */
@Composable
private fun NextTripCard(event: UpcomingEvent, plan: AlarmPlanView, today: LocalDate, onClick: () -> Unit) {
    val now by rememberMinuteClock()
    val shape = RoundedCornerShape(JitRadius.Card)
    val depart = plan.departByMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    val departMeridiem = depart?.let { if (it.hour < 12) "AM" else "PM" }
    val start = HomeLabels.heroStartClock(event.startClock, departMeridiem)

    Column(
        modifier = Modifier
            .testTag("trip_next")
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            JitDotLabel(
                text = "다음 이동 · ${HomeLabels.alarmDayWord(depart?.toLocalDate() ?: event.startDate, today)}",
                dotColor = JitColor.Accent,
                modifier = Modifier.weight(1f),
            )
            (plan.onTimeProbability ?: event.onTimeProbability)?.let { probability ->
                val tone = riskColor(event.risk)
                JitChip(
                    label = "정시 도착 확률 $probability%",
                    contentColor = tone,
                    container = tone.copy(alpha = 0.15f),
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            if (depart != null) {
                HomeLabels.koreanMeridiem(departMeridiem)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.alignByBaseline(),
                        color = JitColor.Accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = depart.format(CLOCK),
                    modifier = Modifier.alignByBaseline(),
                    color = JitColor.Accent,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "출발",
                    modifier = Modifier.alignByBaseline(),
                    color = JitColor.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                // 출발 시각을 서버가 주지 않았다. 숫자를 만들지 않고 알람 시각으로 대신한다.
                Text(
                    text = "알람 ${event.alarmClock ?: event.alarmAt ?: "—"}",
                    color = JitColor.Accent,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TripTabLogic.whenLine(plan.departByMillis, now, plan.arrivalLine)?.let {
                Text(text = it, color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.placeName != null) {
                    Icon(LineIcons.mapPin, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = HomeLabels.heroPlaceLine(event.placeName, start, event.title),
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Partly AI-generated (Claude)
/** "가는 길" 카드(Figma `card 가는 길`). 시각 · 점과 선 · 할 일, 아래에 [지도로 보기] [경로 바꾸기]. */
@Composable
private fun RouteCard(
    plan: AlarmPlanView,
    segments: RouteSegments?,
    onNeedSegments: (Long, String?) -> Unit,
    onMap: () -> Unit,
    onChangeRoute: () -> Unit,
) {
    LaunchedEffect(plan.eventId, plan.routeKey) { onNeedSegments(plan.eventId, plan.routeKey) }

    val zone = ZoneId.systemDefault()
    val departAt = plan.departByMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    val arriveAt = plan.arriveAtMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    val destination = plan.eventPlace.substringBefore(" · ").ifBlank { "도착지" }
    // 집에서 나서는 일정만 준비 시간이 있다(AlarmPlanView.prepApplies). 아니면 일정에 고른 출발지다.
    val origin = if (plan.prepApplies) "집에서 출발" else "출발지에서 출발"
    val endSub = TripTabLogic.endSub(plan.sensitivityTag, plan.startAtMillis, plan.arriveAtMillis)
    val steps = segments?.items
        ?.let { TripTabLogic.steps(it, departAt, origin, destination, endSub) }
        ?.takeIf { it.isNotEmpty() }
        ?: TripTabLogic.simpleSteps(plan.routeKey, plan.travelMinutes, departAt, arriveAt, origin, destination, endSub)
    val totalMinutes = if (plan.departByMillis != null && plan.arriveAtMillis != null) {
        ((plan.arriveAtMillis - plan.departByMillis) / 60_000L).toInt()
    } else {
        plan.travelMinutes
    }

    Column(
        modifier = Modifier
            .testTag("trip_route")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "가는 길", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            TripTabLogic.summary(totalMinutes, segments?.items)?.let {
                Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }

        Column {
            steps.forEachIndexed { index, step ->
                StepRow(step = step, last = index == steps.lastIndex)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "지도로 보기",
                icon = LineIcons.mapPin,
                onClick = onMap,
                modifier = Modifier.weight(1f).testTag("trip_open_map"),
            )
            SecondaryButton(
                text = "경로 바꾸기",
                icon = LineIcons.route,
                onClick = onChangeRoute,
                modifier = Modifier.weight(1f).testTag("trip_change_route"),
            )
        }
    }
}

/** 걷는 구간·모르는 구간의 선 색(Figma `line`) */
private val WalkLine = Color(0xFF48526A)

// Partly AI-generated (Claude)
/** "가는 길" 한 줄. 왼쪽 시각, 가운데 점(아래로 선), 오른쪽 할 일과 아랫글. */
@Composable
private fun StepRow(step: TripTabLogic.Step, last: Boolean) {
    val lineColor = step.line?.let { Color(SegmentPalette.fill(it)) }
    val (nodeColor, icon, iconTint) = when (step.kind) {
        TripTabLogic.StepKind.START -> Triple(JitColor.Surface2, LineIcons.door, JitColor.TextPrimary)
        TripTabLogic.StepKind.BOARD -> Triple(
            lineColor ?: JitColor.Surface2,
            if (step.line?.kind == RouteSegment.Kind.BUS) LineIcons.bus else LineIcons.train,
            step.line?.let { Color(SegmentPalette.onFill(it)) } ?: JitColor.TextPrimary,
        )
        TripTabLogic.StepKind.ALIGHT -> Triple(JitColor.Surface2, LineIcons.walk, JitColor.TextPrimary)
        TripTabLogic.StepKind.END -> Triple(JitColor.Accent, LineIcons.flag, JitColor.Bg)
    }
    val below = when (step.below) {
        null -> null
        RouteSegment.Kind.BUS, RouteSegment.Kind.SUBWAY -> lineColor ?: WalkLine
        else -> WalkLine
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = if (last) 34.dp else 48.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = step.time?.format(CLOCK) ?: "",
            modifier = Modifier.width(34.dp).padding(top = 4.dp),
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        Column(
            modifier = Modifier.width(24.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(nodeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            }
            if (!last && below != null) {
                Box(Modifier.width(2.dp).weight(1f).background(below))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp, bottom = if (last) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                step.line?.let {
                    LineChip(it)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = step.title,
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.sub.isNotEmpty()) {
                Text(text = step.sub, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 노선 칩("2호선", "5513"). 노선색 알약(Figma `line 2호선`) */
@Composable
private fun LineChip(segment: RouteSegment) {
    Text(
        text = segment.label,
        color = Color(SegmentPalette.onFill(segment)),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(SegmentPalette.fill(segment)))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SecondaryButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
        Text(text = text, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 카드 아래 안내 한 줄. 이동 기록은 알람을 끌 때 시작하므로 알람이 꺼져 있으면 다르게 쓴다. */
@Composable
private fun Hint(alarmOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LineIcons.info, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(14.dp))
        Text(
            text = if (alarmOn) "집을 나서면 이동 기록이 저절로 시작돼요" else "알람이 꺼져 있어 이동 기록은 시작되지 않아요",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 이동 중이면 맨 위에 둔다. 진행 · 늦을 때 더 빠른 길은 ④ 에 있다. */
@Composable
private fun InTransitCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag("trip_in_transit")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface2)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            JitDotLabel(text = "이동 중이에요", dotColor = JitColor.Green)
            Text(
                text = "지금 어디쯤인지, 늦을 것 같으면 더 빠른 길을 보여 드려요",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Text(text = "›", color = JitColor.TextSecondary, fontSize = 18.sp)
    }
}

/** 빈 상태(Figma 캡션 `545:1304`). 오늘 갈 곳이 없으면 [내일 가는 길 보기], 일정이 없으면 [일정 추가]. */
@Composable
private fun EmptyCard(empty: TripTabLogic.Empty, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .testTag("trip_empty")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = empty.title, color = JitColor.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(text = empty.body, color = JitColor.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        SecondaryButton(
            text = empty.action,
            icon = if (empty.kind == TripTabLogic.Empty.Kind.LATER) LineIcons.route else LineIcons.plus,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth().testTag("trip_empty_action"),
        )
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = JitColor.Accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun FailedCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(onClick = onRetry)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(text = "가는 길을 불러오지 못했어요", dotColor = JitColor.Red, fontSize = 12, dotSize = 6.dp)
        Text(text = "눌러서 다시 시도", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** 분이 바뀔 때마다 새 값을 내는 지금 시각(epoch ms). "N시간 M분 뒤" 를 맞춘다. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        val now = System.currentTimeMillis()
        delay(60_000L - now % 60_000L + 50L)
        value = System.currentTimeMillis()
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")

// Partly AI-generated (Claude)
@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun TripEmptyPreview() {
    JitTheme {
        TripTabScreen(
            event = null,
            hasEvents = false,
            today = LocalDate.now(),
            trip = HomeViewModel.TripTabState(),
            segments = null,
            inTransit = false,
            onOpenEvent = {},
            onChangeRoute = {},
            onAddEvent = {},
            onNeedSegments = { _, _ -> },
            onRetry = {},
        )
    }
}
