package com.swpp.wakeup.ui.events

import android.os.SystemClock
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.RouteArrival
import com.swpp.wakeup.domain.model.RouteCheckpoint
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.PlacePicker
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTextStyle
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.delay

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
    onOriginEditToggle: (Boolean) -> Unit = {},
    onOriginQueryChange: (String) -> Unit = {},
    onOriginSearch: () -> Unit = {},
    onOriginSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit = {},
    onUseCurrentLocation: () -> Unit = {},
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

        val choice = state?.choice
        Text(
            text = choice?.let { "${it.originLabel} → ${it.destination}" } ?: "경로를 불러오는 중",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        OriginPicker(
            state = state,
            onEditToggle = onOriginEditToggle,
            onQueryChange = onOriginQueryChange,
            onSearch = onOriginSearch,
            onSelect = onOriginSelect,
            onUseCurrentLocation = onUseCurrentLocation,
        )

        when {
            state == null || state.loading -> LoadingBlock()

            state.error != null -> ErrorBlock(message = state.error, onRetry = onRetry)

            choice != null -> {
                val now = rememberElapsedTicker()
                choice.options.forEach { option ->
                    RouteCard(
                        option = option,
                        selected = option.key == choice.selectedKey,
                        onClick = { onSelect(option.key) },
                        now = now,
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
 * 출발지 선택.
 *
 * **기본값은 현재 위치다.** 집을 기본으로 두면 대부분 맞지만, 틀렸을 때
 * 사용자가 알아채지 못한다 — 집이 기준이라는 걸 모르면 알람이 왜 그 시각인지
 * 설명되지 않는다. 현재 위치를 못 구하면 집으로 돌아가고 그 사실을 적는다.
 *
 * 접힌 상태에서는 출발지 한 줄과 "변경" 만 보인다. 경로 목록이 화면의 주인공이고
 * 출발지는 대개 손댈 필요가 없어서다. 펼치면 [PlacePicker] 를 그대로 쓴다 —
 * 목적지·집 설정과 같은 "좌표 있는 장소 하나 고르기" 이므로 화면을 따로 만들 이유가
 * 없다.
 */
@Composable
private fun OriginPicker(
    state: HomeViewModel.RouteState?,
    onEditToggle: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (PlaceSearchItem?) -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    if (state == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "출발지",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            if (!state.originEditing) {
                Text(
                    text = "변경",
                    color = JitColor.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEditToggle(true) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    text = "취소",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEditToggle(false) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        when {
            // 현재 위치를 재는 중. 좌표가 없으면 출발지를 정할 수 없으므로 기다린다.
            state.locating -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = JitColor.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "현재 위치를 확인하는 중",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            state.originEditing -> PlacePicker(
                label = "출발지 검색",
                query = state.originQuery,
                results = state.originResults,
                searching = state.originSearching,
                selected = null,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onSelect = onSelect,
            )

            else -> {
                val origin = state.origin
                Text(
                    text = origin?.name ?: state.choice?.originLabel ?: "집",
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                origin?.address?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
                }
            }
        }

        // 현재 위치를 못 구했으면 무엇을 기준으로 계산했는지 밝힌다. 조용히
        // 집으로 넘어가면 사용자는 알람 시각을 설명할 수 없다.
        state.originNotice?.let { notice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(JitColor.Amber)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = notice, color = JitColor.TextSecondary, fontSize = 11.sp)
            }
        }

        if (!state.locating) {
            Text(
                text = "현재 위치로 다시 잡기",
                color = JitColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onUseCurrentLocation)
                    .padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun RouteCard(
    option: RouteOption,
    selected: Boolean,
    onClick: () -> Unit,
    now: () -> Long = { 0L },
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = JitColor.SnuNavyBorder,
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
            Spacer(Modifier.width(9.dp))
            Text(
                text = option.mode,
                color = if (selected) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            option.badge?.let { badge ->
                Text(
                    text = badge,
                    color = JitColor.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(JitColor.Surface2)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        if (option.detailLine.isNotBlank()) {
            Text(text = option.detailLine, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        if (option.hasSegmentBar) {
            SegmentBar(option.segments)
        }
        // 상세 목록은 선택한 카드에만 펼친다. 모든 후보에 열면 같은 정류장이
        // 반복돼 비교가 어려워지고 한 카드가 화면 여러 장을 차지한다.
        if (selected && option.hasCheckpoints) {
            RouteCheckpointList(option.checkpoints, now)
        }
    }
}

/**
 * 1초마다 갱신되는 단조 시계를 읽는 함수를 돌려준다.
 *
 * ## 왜 값이 아니라 함수인가
 *
 * `Long` 을 파라미터로 내려보내면 1초마다 후보 카드 전체가 다시 그려진다.
 * 함수로 내려보내면 그 함수를 **호출하는 컴포저블만** 구독하므로, 실제로
 * 숫자가 바뀌는 도착정보 행만 다시 그려진다.
 *
 * ## 왜 화면에 머무는 동안만인가
 *
 * `repeatOnLifecycle(STARTED)` 로 묶어 화면이 가려지면 멈춘다. 남은 시간은
 * 기준 시점에서 매번 다시 계산하므로, 멈춘 동안 흐른 시간도 돌아올 때 한 번에
 * 반영된다 — 따로 보정할 것이 없다.
 */
@Composable
private fun rememberElapsedTicker(): () -> Long {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
    }
    return remember { { now.longValue } }
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
 * ## 폭과 색
 *
 * 폭은 [RouteSegments.weights] 가 계산한다. 합이 정확히 1.0 이라 오른쪽에
 * 바탕색이 남지 않는다. 색은 [SegmentPalette] 가 고르고 지하철 노선색·버스
 * 종류색을 따른다 — 사용자가 이미 아는 색이라야 막대가 한 번에 읽힌다.
 *
 * 좁은 칸에는 글자를 넣지 않는다. 잘린 글자는 없는 것보다 읽기 어렵다.
 */
@Composable
private fun SegmentBar(segments: RouteSegments) {
    val weights = segments.weights()
    if (weights.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(SegmentPalette.TRACK)),
    ) {
        segments.items.forEachIndexed { index, segment ->
            Box(
                modifier = Modifier
                    .weight(weights[index])
                    .fillMaxHeight()
                    .background(Color(SegmentPalette.fill(segment))),
                contentAlignment = Alignment.Center,
            ) {
                // 비율이 이만큼은 돼야 "12분" 이 잘리지 않는다. 화면 폭이
                // 달라도 비율로 판단하므로 기기마다 같게 동작한다.
                if (weights[index] >= SEGMENT_LABEL_MIN_WEIGHT) {
                    Text(
                        text = segment.minutesLabel,
                        color = Color(SegmentPalette.onFill(segment)),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        // Box 의 Alignment.Center 만으로는 부족하다. 기본 Text 는
                        // 폰트 여백을 줄 상자에 넣어 글리프를 아래로 밀어낸다.
                        style = JitTextStyle.TightCentered,
                    )
                }
            }
        }
    }
}

/** 이 비율보다 좁은 칸에는 분을 쓰지 않는다. 9sp 로 "12분" 이 들어갈 최소치다. */
private const val SEGMENT_LABEL_MIN_WEIGHT = 0.11f

/**
 * 선택한 경로의 출발·승차·하차·도착과 실시간 차량 도착정보.
 *
 * 승차 행에는 장소와 노선만 쓴다. 바로 다음 차량과 그다음 차량은 그 아래
 * 별도 행에 둔다. 승차 행 오른쪽에 "3분 20초 뒤" 를 쓰면 사용자가 그 지점에
 * 3분 뒤 도착한다는 뜻으로 오해하기 때문이다.
 */
@Composable
private fun RouteCheckpointList(
    checkpoints: List<RouteCheckpoint>,
    now: () -> Long = { 0L },
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        checkpoints.forEach { checkpoint ->
            when (checkpoint) {
                is RouteCheckpoint.Stop -> CheckpointStopRow(checkpoint)
                is RouteCheckpoint.VehicleArrival -> CheckpointArrivalRow(checkpoint, now)
            }
        }
    }
}

@Composable
private fun CheckpointStopRow(checkpoint: RouteCheckpoint.Stop) {
    val segment = checkpoint.segment
    val dotColor = segment?.let { Color(SegmentPalette.fill(it)) } ?: JitColor.TextSecondary
    val strong = checkpoint.role == RouteCheckpoint.Stop.Role.START ||
        checkpoint.role == RouteCheckpoint.Stop.Role.END

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Spacer(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = checkpoint.label,
            color = JitColor.TextPrimary,
            fontSize = 11.sp,
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
        )
        checkpoint.lineLabel.takeIf(String::isNotBlank)?.let { line ->
            Text(
                text = line,
                color = Color(SegmentPalette.ON_VEHICLE),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(dotColor)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 차량이 승차 지점에 들어오기까지 남은 시간.
 *
 * [RouteCheckpoint.VehicleArrival.primary] 이면 다음 차량이라 노랑, 아니면
 * 그다음 차량이라 회색이다. 둘을 같은 열에 세로로 놓아 순서를 한눈에 읽는다.
 */
@Composable
private fun CheckpointArrivalRow(
    checkpoint: RouteCheckpoint.VehicleArrival,
    now: () -> Long = { 0L },
) {
    val color = if (checkpoint.primary) JitColor.ArrivalNext else JitColor.ArrivalLater
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = checkpoint.vehicleLabel,
            color = color,
            fontSize = 10.sp,
        )
        Spacer(Modifier.weight(1f))
        // now() 를 여기서 읽는다. 이 행만 1초마다 다시 그려지고 카드는 그대로다.
        Text(
            text = checkpoint.arrival.displayTextAt(now()),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
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
