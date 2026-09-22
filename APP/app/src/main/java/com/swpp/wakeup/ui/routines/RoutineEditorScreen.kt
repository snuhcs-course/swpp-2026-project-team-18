package com.swpp.wakeup.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.RoutineBlockView
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 아침 루틴 블록 편집기.
 *
 * **한 화면이 두 가지 일을 한다.** [RoutineEditorState.isEventMode] 로 갈린다.
 *
 * | 모드 | 무엇을 바꾸는가 | 저장하면 |
 * | --- | --- | --- |
 * | 정의 | "샤워는 12~18분" 같은 기본 설정 | 알람은 그대로. 다음 계산에 반영 |
 * | 일정별 | "이 아침에는 아침 식사를 건너뛴다" | **그 일정만 즉시 재계산** |
 *
 * 목록이 같으니 화면을 합치는 것이 맞다. 하지만 저장 동작이 다르므로 화면이
 * 그 차이를 반드시 밝혀야 한다 — 같은 화면인데 어떨 때는 알람이 바뀌고 어떨
 * 때는 안 바뀌면 사용자가 규칙을 찾을 수 없다.
 *
 * 일정별 모드는 **저장 버튼으로 한 번에 보낸다.** 체크마다 요청하면 서버가
 * 재계산을 그만큼 돌려 카카오 경로 쿼터(일 1,000건)를 먹는다.
 */
@Composable
fun RoutineEditorScreen(
    state: RoutineEditorState,
    onBack: () -> Unit,
    onToggleChecked: (Long) -> Unit,
    onToggleIncludedByDefault: (Long) -> Unit,
    onEditBlock: (Long) -> Unit,
    onNewBlock: () -> Unit,
    onSaveEventBlocks: () -> Unit,
    onDismissMessages: () -> Unit,
    modifier: Modifier = Modifier,
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
        ScreenHeader(
            title = if (state.isEventMode) "이 아침 할 일" else "아침 루틴",
            onBack = onBack,
            enabled = !state.saving,
        )

        Text(
            text = if (state.isEventMode) "이 아침에 무엇을 하는가?" else "아침에 무엇을 하는가?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (state.isEventMode) {
                state.eventTitle ?: "체크를 바꾸면 이 일정의 알람만 다시 계산됨"
            } else {
                "항목을 쪼개면 준비 시간의 근거가 생기고, 늦었을 때 무엇을 줄일지 고를 수 있음"
            },
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        if (state.loading) {
            LoadingBlock()
            return@Column
        }

        SummaryCard(state)

        if (state.outOfRangeCount > 0) {
            NoticeCard(
                dot = JitColor.Amber,
                title = "실측이 신고 범위를 벗어난 항목 ${state.outOfRangeCount}개",
                body = "아래 항목의 범위를 실제에 맞게 고치면 알람이 정확해짐",
            )
        }

        if (state.blocks.isEmpty()) {
            EmptyCard()
        } else {
            state.blocks.forEach { block ->
                BlockCard(
                    block = block,
                    eventMode = state.isEventMode,
                    enabled = !state.saving,
                    onToggle = {
                        if (state.isEventMode) onToggleChecked(block.id)
                        else onToggleIncludedByDefault(block.id)
                    },
                    onEdit = { onEditBlock(block.id) },
                )
            }
        }

        state.error?.let {
            MessageLine(text = it, color = JitColor.Red, onDismiss = onDismissMessages)
        }
        state.notice?.let {
            MessageLine(text = it, color = JitColor.Green, onDismiss = onDismissMessages)
        }

        Spacer(Modifier.height(4.dp))

        if (state.isEventMode) {
            JitPrimaryButton(
                label = if (state.dirty) "저장하고 알람 다시 계산" else "바뀐 항목 없음",
                onClick = onSaveEventBlocks,
                enabled = state.dirty && !state.saving,
                loading = state.saving,
            )
            Text(
                text = "체크는 이 일정에만 적용됨. 기본값을 바꾸려면 아침 루틴 설정에서 고침",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        } else {
            JitPrimaryButton(
                label = "항목 추가",
                onClick = onNewBlock,
                enabled = !state.saving,
                loading = state.saving,
            )
            Text(
                text = "정의를 고쳐도 이미 계산된 알람은 그대로임. 알람 화면의 \"다시 계산\" 으로 반영함",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * 합계 카드.
 *
 * **"단순 합" 이라고 분명히 쓴다.** 실제 준비 시간은 병렬 블록을 max 로 넣고
 * 선행 관계를 따지므로 이 값보다 짧을 수 있다. 라벨 없이 숫자만 두면 알람
 * 근거 카드의 값과 달라 보여 사용자가 둘 중 하나를 고장으로 여긴다.
 */
@Composable
private fun SummaryCard(state: RoutineEditorState) {
    JitCard(padding = 14.dp, gap = 7.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = "포함 ${state.includedCount} / ${state.blocks.size}개",
                dotColor = JitColor.Accent,
                fontSize = 13,
                dotSize = 7.dp,
            )
            state.simpleSumLabel?.let {
                Text(it, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = "합계는 단순 더하기임. 병렬 항목과 선행 관계는 알람 계산에서 반영되므로 " +
                "실제 준비 시간은 이보다 짧을 수 있음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun BlockCard(
    block: RoutineBlockView,
    eventMode: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    JitCard(padding = 14.dp, gap = 7.dp, accented = block.rangeMismatch) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(JitRadius.Hint))
                    // 정의 모드에서만 눌러서 수정한다. 일정별 모드에서 같은
                    // 자리를 누르면 "이 아침만" 바꾸려던 것이 기본값 수정으로
                    // 이어져 다른 날짜까지 바뀐다.
                    .clickable(enabled = enabled && !eventMode, onClick = onEdit)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (block.checked) JitColor.Accent else JitColor.Track)
                )
                Spacer(Modifier.width(9.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = block.name,
                            color = if (block.checked) JitColor.TextPrimary
                            else JitColor.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = block.rangeLabel,
                            color = JitColor.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (block.parallelizable) JitChip("병렬", JitColor.Blue, fontSize = 9)
                        if (block.dropCost == DropCost.IMPOSSIBLE) {
                            JitChip("필수", JitColor.Red, fontSize = 9)
                        } else if (block.dropCost == DropCost.NONE) {
                            JitChip("생략 가능", JitColor.TextSecondary, fontSize = 9)
                        }
                        // 이 일정에서 기본값을 덮어썼다는 표시. "기본은 포함인데
                        // 이번엔 뺐다" 를 사용자가 알아야 다음에 헷갈리지 않는다.
                        if (eventMode && block.explicit) {
                            JitChip("직접 바꿈", JitColor.Accent, fontSize = 9)
                        }
                    }
                }
            }

            Switch(
                checked = block.checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JitColor.Bg,
                    checkedTrackColor = JitColor.Accent,
                    uncheckedThumbColor = JitColor.TextSecondary,
                    uncheckedTrackColor = JitColor.Surface2,
                    uncheckedBorderColor = JitColor.Track,
                ),
            )
        }

        HorizontalDivider(color = JitColor.Track)

        Text(
            text = block.learningNote,
            color = if (block.rangeMismatch) JitColor.Amber else JitColor.TextSecondary,
            fontSize = 10.sp,
        )

        if (!eventMode) {
            Text(
                text = "눌러서 수정",
                color = JitColor.TextSecondary,
                fontSize = 9.sp,
            )
        }
    }
}

/**
 * 블록 추가·수정 폼.
 *
 * 별도 화면이 아니라 목록 위에 겹쳐 띄운다. 항목을 여러 개 연달아 고치는
 * 흐름이라 매번 화면을 오가면 맥이 끊긴다.
 */
@Composable
fun BlockDraftSheet(
    draft: BlockDraft,
    saving: Boolean,
    onName: (String) -> Unit,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onDropCost: (DropCost) -> Unit,
    onParallel: (Boolean) -> Unit,
    onIncluded: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
        ScreenHeader(title = draft.title, onBack = onDismiss, enabled = !saving)

        JitTextField(
            label = "이름",
            value = draft.name,
            onValueChange = onName,
            imeAction = ImeAction.Next,
            enabled = !saving,
        )
        draft.errors["name"]?.let { FieldError(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                JitTextField(
                    label = "최소 (분)",
                    value = draft.minText,
                    onValueChange = onMin,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    enabled = !saving,
                )
                draft.errors["min"]?.let { FieldError(it) }
            }
            Column(modifier = Modifier.weight(1f)) {
                JitTextField(
                    label = "최대 (분)",
                    value = draft.maxText,
                    onValueChange = onMax,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    enabled = !saving,
                )
                draft.errors["max"]?.let { FieldError(it) }
            }
        }

        // 범위가 확률 계산의 입구다. 한 점으로 두면 변동성이 없다고 신고한
        // 것이 되어 정시 도착 확률이 만들어지지 않는다. 그 사실을 여기서 알린다.
        NoticeCard(
            dot = if (draft.minText.isNotBlank() && draft.minText == draft.maxText) {
                JitColor.Amber
            } else {
                JitColor.Blue
            },
            title = "범위가 확률의 재료임",
            body = "최소와 최대를 다르게 주면 그 폭을 변동성으로 읽어 정시 도착 확률을 계산함. " +
                "같게 두면 변동성이 없다고 신고한 것이 되어 확률이 만들어지지 않음",
        )

        Text("포기 시 손실", color = JitColor.TextSecondary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropCost.entries.forEach { cost ->
                val selected = cost == draft.dropCost
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) JitColor.Accent else JitColor.Surface2)
                        .clickable(enabled = !saving) { onDropCost(cost) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = cost.label,
                        color = if (selected) JitColor.Bg else JitColor.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Text(draft.dropCost.hint, color = JitColor.TextSecondary, fontSize = 10.sp)

        ToggleRow(
            title = "병렬로 진행 가능",
            body = "다른 일과 겹쳐 할 수 있음. 총 준비 시간에 더하지 않고 겹쳐 계산함",
            checked = draft.parallelizable,
            enabled = !saving,
            onChange = onParallel,
        )

        ToggleRow(
            title = "새 일정에 기본 포함",
            body = "끄면 일정마다 직접 체크해야 포함됨",
            checked = draft.includedByDefault,
            enabled = !saving,
            onChange = onIncluded,
        )

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = "저장",
            onClick = onSave,
            enabled = !saving,
            loading = saving,
        )

        draft.id?.let { id ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "항목 삭제",
                    color = JitColor.Red,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !saving) { onDelete(id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "지금까지 쌓인 이 항목의 관측도 함께 사라짐",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    JitCard(padding = 14.dp, gap = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = JitColor.TextPrimary, fontSize = 13.sp)
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JitColor.Bg,
                    checkedTrackColor = JitColor.Accent,
                    uncheckedThumbColor = JitColor.TextSecondary,
                    uncheckedTrackColor = JitColor.Surface2,
                    uncheckedBorderColor = JitColor.Track,
                ),
            )
        }
        Text(body, color = JitColor.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun FieldError(text: String) {
    Text(
        text = text,
        color = JitColor.Red,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 4.dp, top = 3.dp),
    )
}

@Composable
private fun MessageLine(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun EmptyCard() {
    JitCard(padding = 16.dp, gap = 7.dp) {
        JitDotLabel(
            text = "등록된 항목이 없음",
            dotColor = JitColor.TextSecondary,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(
            text = "항목이 없으면 집 설정에서 답한 준비 시간을 한 덩어리로 씀. " +
                "쪼개면 왜 그 시각인지 설명되고 확률 계산도 시작됨",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun NoticeCard(
    dot: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    JitCard(padding = 14.dp, gap = 5.dp) {
        JitDotLabel(text = title, dotColor = dot, fontSize = 12, dotSize = 7.dp)
        Text(body, color = JitColor.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = JitColor.Accent,
            strokeWidth = 2.dp,
        )
    }
}

// ---------------------------------------------------------------------------

private fun previewBlocks() = listOf(
    RoutineBlockView(
        id = 1,
        name = "샤워",
        minMinutes = 12,
        maxMinutes = 18,
        rangeLabel = "12~18분",
        dropCost = DropCost.LARGE,
        parallelizable = false,
        includedByDefault = true,
        order = 1,
        observationCount = 7,
        observedMeanLabel = "실측 평균 14.2분",
        learningNote = "실측 평균 14.2분 · 관측 7회, 신고 범위 안에 있음",
        rangeMismatch = false,
        checked = true,
    ),
    RoutineBlockView(
        id = 2,
        name = "아침 식사",
        minMinutes = 8,
        maxMinutes = 15,
        rangeLabel = "8~15분",
        dropCost = DropCost.NONE,
        parallelizable = false,
        includedByDefault = true,
        order = 2,
        observationCount = 5,
        observedMeanLabel = "실측 평균 19분",
        learningNote = "실측 평균 19분 · 신고 최대보다 4분 길다. 범위를 늘리는 것을 권함",
        rangeMismatch = true,
        checked = false,
        explicit = true,
    ),
    RoutineBlockView(
        id = 3,
        name = "세탁기 돌리기",
        minMinutes = 5,
        maxMinutes = 5,
        rangeLabel = "5분",
        dropCost = DropCost.SMALL,
        parallelizable = true,
        includedByDefault = false,
        order = 3,
        observationCount = 0,
        observedMeanLabel = null,
        learningNote = "관측 없음 · 범위가 한 점이라 확률 계산에 쓰이지 않음",
        rangeMismatch = false,
        checked = false,
    ),
)

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun RoutineEditorPreview() {
    JitTheme {
        RoutineEditorScreen(
            state = RoutineEditorState(
                blocks = previewBlocks(),
                original = previewBlocks().associate { it.id to it.checked },
            ),
            onBack = {},
            onToggleChecked = {},
            onToggleIncludedByDefault = {},
            onEditBlock = {},
            onNewBlock = {},
            onSaveEventBlocks = {},
            onDismissMessages = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun EventBlocksPreview() {
    JitTheme {
        RoutineEditorScreen(
            state = RoutineEditorState(
                blocks = previewBlocks(),
                original = previewBlocks().associate { it.id to true },
                eventId = 3,
                eventTitle = "09:00 자료구조 및 알고리즘",
            ),
            onBack = {},
            onToggleChecked = {},
            onToggleIncludedByDefault = {},
            onEditBlock = {},
            onNewBlock = {},
            onSaveEventBlocks = {},
            onDismissMessages = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun BlockDraftPreview() {
    JitTheme {
        BlockDraftSheet(
            draft = BlockDraft(
                id = 1,
                name = "샤워",
                minText = "12",
                maxText = "18",
                dropCost = DropCost.LARGE,
            ),
            saving = false,
            onName = {},
            onMin = {},
            onMax = {},
            onDropCost = {},
            onParallel = {},
            onIncluded = {},
            onSave = {},
            onDelete = {},
            onDismiss = {},
        )
    }
}
