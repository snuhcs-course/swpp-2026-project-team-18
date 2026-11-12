// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 시각 휠(iter4 H2-5, Figma `545:670` · 부품 `539:132`)의 계산. 화면과 떼어 JVM 테스트로 고정한다(`TimeWheelTest`).
 *
 * 시각은 0~23 시 그대로 주고받고, 휠 위에서만 오전/오후 · 1~12 시로 나눈다. 열끼리는 서로 넘기지 않는다 —
 * 분을 59 → 00 으로 굴려도 시는 그대로다(아이폰과 같다).
 */
internal object TimeWheelMath {

    /** 휠에 보이는 줄 수와 가운데 줄의 자리. 가운데 위아래로 두 줄씩 흐리게 보인다 */
    const val VISIBLE_ROWS = 5
    const val CENTER_ROW = VISIBLE_ROWS / 2

    /** 끝없이 도는 열은 값 목록을 이만큼 이어 붙이고 가운데에서 시작한다. 손으로 끝까지 굴릴 수 없는 길이다 */
    const val LOOP_CYCLES = 1_000

    val MERIDIEMS = listOf("오전", "오후")
    val HOURS = (1..12).map(Int::toString)

    /** 분 열의 값. [step] 분 간격(1 · 5 …), 두 자리 */
    fun minutes(step: Int): List<String> = (0 until 60 step step.coerceIn(1, 30)).map { "%02d".format(it) }

    fun isPm(hour24: Int): Boolean = hour24 >= 12

    /** 0 → 12(오전 12시), 13 → 1 */
    fun hour12(hour24: Int): Int = (hour24 % 12).let { if (it == 0) 12 else it }

    /** 오전 12시 → 0, 오후 12시 → 12, 오후 1시 → 13 */
    fun hour24(pm: Boolean, hour12: Int): Int = hour12 % 12 + if (pm) 12 else 0

    /** 간격에 맞지 않는 분은 앞쪽 칸으로 내린다(7:43, 5분 간격 → 7:40). 알람을 늦추지 않는 쪽이다 */
    fun snapMinute(minute: Int, step: Int): Int {
        val safe = step.coerceIn(1, 30)
        return (minute.coerceIn(0, 59) / safe) * safe
    }

    /** 맨 위 큰 글자. ("오전", "9:00") */
    fun readout(hour24: Int, minute: Int): Pair<String, String> =
        (if (isPm(hour24)) "오후" else "오전") to "${hour12(hour24)}:%02d".format(minute)

    fun guide(step: Int): String = "위아래로 밀어서 골라요 · ${step}분 단위"

    // --- 열 하나의 줄 번호 ↔ 값 번호 --------------------------------------------------------

    /** 열의 줄 수. 끝이 있는 열(오전/오후)은 위아래에 빈 줄 둘씩을 더해 첫 값과 끝 값도 가운데로 올 수 있게 한다 */
    fun rowCount(size: Int, loop: Boolean): Int = if (loop) size * LOOP_CYCLES else size + CENTER_ROW * 2

    /** 줄 번호의 값 번호. 빈 줄이면 null */
    fun valueAt(row: Int, size: Int, loop: Boolean): Int? =
        if (loop) row.mod(size) else (row - CENTER_ROW).takeIf { it in 0 until size }

    /** [value] 가 가운데 오게 하려면 맨 위에 둘 줄 번호 */
    fun firstRowFor(value: Int, size: Int, loop: Boolean): Int =
        if (loop) (LOOP_CYCLES / 2) * size + value - CENTER_ROW else value

    /** 가운데 줄의 값. 끝이 있는 열은 끝 값으로 붙잡는다(밀어 올리는 동안 잠깐 빈 줄이 가운데 와도) */
    fun centeredValue(centerRow: Int, size: Int, loop: Boolean): Int =
        if (loop) centerRow.mod(size) else (centerRow - CENTER_ROW).coerceIn(0, size - 1)
}

private val ROW_HEIGHT = 36.dp
private val COLUMN_WIDTH = 72.dp

/**
 * 시각 휠: 오전/오후 · 시(1~12, 끝없이 돎) · 분([minuteStep] 간격, 끝없이 돎) 세 열. 가운데 띠에 걸린 값이 고른 값이다.
 * 열마다 따로 구르고 놓으면 가까운 줄에 붙는다. 흐린 위아래 줄을 누르면 그 값으로 굴러간다.
 *
 * @param onChange 가운데 값이 바뀔 때마다 (0~23 시, 분)
 */
@Composable
internal fun JitTimeWheel(
    hour24: Int,
    minute: Int,
    minuteStep: Int,
    onChange: (hour24: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = remember(minuteStep) { TimeWheelMath.minutes(minuteStep) }
    val pm = TimeWheelMath.isPm(hour24)
    val hour12 = TimeWheelMath.hour12(hour24)
    val minuteIndex = minutes.indexOf("%02d".format(TimeWheelMath.snapMinute(minute, minuteStep))).coerceAtLeast(0)

    // 굴러가는 동안 남은 끌기가 바텀시트로 넘어가 시트가 내려가지 않게 휠 안에서 끝낸다.
    val keepScrollInside = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT * TimeWheelMath.VISIBLE_ROWS)
            .nestedScroll(keepScrollInside),
        contentAlignment = Alignment.Center,
    ) {
        // 가운데 띠(Figma `band`, 높이 40 · 둥근 12)
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JitColor.Surface2),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                values = TimeWheelMath.MERIDIEMS,
                selected = if (pm) 1 else 0,
                loop = false,
                label = "오전 오후",
                tag = "wheel_ampm",
                onSelect = { index -> onChange(TimeWheelMath.hour24(index == 1, hour12), minute) },
            )
            WheelColumn(
                values = TimeWheelMath.HOURS,
                selected = hour12 - 1,
                loop = true,
                label = "시",
                tag = "wheel_hour",
                onSelect = { index -> onChange(TimeWheelMath.hour24(pm, index + 1), minute) },
            )
            Text(":", color = JitColor.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            WheelColumn(
                values = minutes,
                selected = minuteIndex,
                loop = true,
                label = "분",
                tag = "wheel_minute",
                onSelect = { index -> onChange(hour24, minutes[index].toInt()) },
            )
        }
    }
}

/**
 * 휠 한 열. 줄 높이 36 · 다섯 줄. 가운데에서 멀수록 작고 흐리다(Figma `cell 값` 22 Bold, `위1·아래1` 16 · 70%,
 * `위2·아래2` 13 · 35%).
 *
 * 열의 설명은 "시 9" 처럼 이름과 지금 값이다 — 화면 읽기와 UI Automator 테스트(`DemoUi.pickTime`)가 같이 쓴다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(
    values: List<String>,
    selected: Int,
    loop: Boolean,
    label: String,
    tag: String,
    onSelect: (Int) -> Unit,
    width: Dp = COLUMN_WIDTH,
) {
    val size = values.size
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = TimeWheelMath.firstRowFor(selected, size, loop),
    )
    val scope = rememberCoroutineScope()
    val centerRow by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
                ?: (state.firstVisibleItemIndex + TimeWheelMath.CENTER_ROW)
        }
    }
    val centered = TimeWheelMath.centeredValue(centerRow, size, loop)

    // 가운데 값이 바뀌면 바로 알린다(위 큰 글자가 굴리는 동안 따라 바뀐다). 처음 값은 부르는 쪽이 이미 안다.
    // 효과는 한 번만 띄우므로 지금 값과 콜백은 최신 것을 읽는다 — 다른 열에서 바꾼 값을 되돌리지 않게.
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(state, size, loop) {
        snapshotFlow { TimeWheelMath.centeredValue(centerRow, size, loop) }
            .distinctUntilChanged()
            .collect { if (it != latestSelected) latestOnSelect(it) }
    }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .height(ROW_HEIGHT * TimeWheelMath.VISIBLE_ROWS)
            .testTag(tag)
            .semantics { contentDescription = "$label ${values[centered]}" },
    ) {
        items(count = TimeWheelMath.rowCount(size, loop)) { row ->
            val value = TimeWheelMath.valueAt(row, size, loop)
            val distance = abs(row - centerRow)
            val (fontSize, weight, color, alpha) = when (distance) {
                0 -> CellStyle(22, FontWeight.Bold, JitColor.TextPrimary, 1f)
                1 -> CellStyle(16, FontWeight.Medium, JitColor.TextSecondary, 0.7f)
                else -> CellStyle(13, FontWeight.Normal, JitColor.TextSecondary, 0.35f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .then(
                        if (value != null && distance != 0) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                            ) { scope.launch { state.animateScrollToItem(row - TimeWheelMath.CENTER_ROW) } }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (value != null) {
                    Text(
                        text = values[value],
                        color = color,
                        fontSize = fontSize.sp,
                        fontWeight = weight,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.alpha(alpha),
                    )
                }
            }
        }
    }
}

private data class CellStyle(val fontSize: Int, val weight: FontWeight, val color: Color, val alpha: Float)

/**
 * 시각 고르기 바텀시트(iter4 H2-5, Figma `545:670` `sheet 시작 시각`). 손잡이 · 제목 · 고른 값 큰 글자 · 휠 ·
 * 안내 한 줄 · [취소] [확인].
 *
 * [확인] 만 값을 넘긴다. [취소] · 바깥 누르기 · 아래로 밀기 · 뒤로 가기는 [onDismiss] 만 부른다(값은 그대로).
 * 직접 입력 모드는 만들지 않는다(사용자 결정 11-12) — Figma 의 [직접 입력] 버튼은 두지 않았다.
 *
 * @param minuteStep 분 간격. 일정 시작 시각은 1, 알람 시각 바꾸기(H2-3)는 5
 * @param onConfirm (0~23 시, 분)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JitTimeWheelSheet(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    minuteStep: Int = 1,
) {
    var hour by rememberSaveable { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by rememberSaveable { mutableIntStateOf(TimeWheelMath.snapMinute(initialMinute, minuteStep)) }
    val (meridiem, clock) = TimeWheelMath.readout(hour, minute)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JitColor.Track),
            )
        },
        modifier = Modifier.testTag("time_wheel_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                color = JitColor.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.height(36.dp).padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wheel_readout")
                    .semantics(mergeDescendants = true) { contentDescription = "$meridiem $clock" },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    meridiem,
                    Modifier.alignByBaseline(),
                    color = JitColor.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    clock,
                    Modifier.alignByBaseline(),
                    color = JitColor.Accent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            JitTimeWheel(
                hour24 = hour,
                minute = minute,
                minuteStep = minuteStep,
                onChange = { h, m ->
                    hour = h
                    minute = m
                },
            )
            Text(
                text = TimeWheelMath.guide(minuteStep),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(
                    label = "취소",
                    container = JitColor.Surface2,
                    content = JitColor.TextPrimary,
                    tag = "wheel_cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    label = "확인",
                    container = JitColor.Accent,
                    content = JitColor.Bg,
                    tag = "wheel_confirm",
                    onClick = { onConfirm(hour, minute) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 시트 아래 두 버튼(Figma `cta 취소` · `cta 확인`, 높이 50 · 둥근 14) */
@Composable
private fun SheetButton(
    label: String,
    container: Color,
    content: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .testTag(tag)
            .height(50.dp)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
