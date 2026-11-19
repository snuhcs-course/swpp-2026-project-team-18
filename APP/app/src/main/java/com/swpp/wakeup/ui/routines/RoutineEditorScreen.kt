package com.swpp.wakeup.ui.routines

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import com.swpp.wakeup.ui.common.JitSectionTitle
import com.swpp.wakeup.ui.common.JitPillChip
import com.swpp.wakeup.ui.common.LineIcons
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
import androidx.compose.ui.platform.testTag
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
    /** 항목이 없는 "이 아침 할 일" 의 [아침 루틴 만들러 가기](내 정보 › 아침 루틴) */
    onOpenRoutine: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("routine_scroll")
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

        // 11-17: 지금 UI 기준(제목 20sp · 해요체)
        Text(
            text = if (state.isEventMode) "이 아침에 무엇을 하나요?" else "아침에 무엇을 하나요?",
            color = JitColor.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (state.isEventMode) {
                state.eventTitle ?: "체크를 바꾸면 이 일정의 알람만 다시 계산해요"
            } else {
                "항목을 쪼개면 준비 시간의 근거가 생기고, 늦었을 때 무엇을 줄일지 고를 수 있어요"
            },
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )

        if (state.loading) {
            LoadingBlock()
            return@Column
        }

        // 항목이 없으면 "0개 중 0개 할게요 · 더하면 0분" 은 뜻이 없다 — 빈 안내만 둔다(11-17 사용자)
        if (state.blocks.isNotEmpty()) SummaryCard(state)

        if (state.outOfRangeCount > 0) {
            NoticeCard(
                dot = JitColor.Amber,
                title = "실제 시간이 적어 둔 범위를 벗어난 항목 ${state.outOfRangeCount}개",
                body = "아래 항목의 범위를 실제에 맞게 고치면 알람이 정확해져요",
            )
        }

        if (state.blocks.isEmpty()) {
            EmptyCard(onOpenRoutine = onOpenRoutine.takeIf { state.isEventMode })
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

        if (state.isEventMode && state.blocks.isEmpty()) {
            // 고를 항목이 없으니 저장 버튼도 없다. 바로가기는 빈 안내 카드 안에 있다.
        } else if (state.isEventMode) {
            JitPrimaryButton(
                label = if (state.dirty) "저장하고 알람에 반영" else "바뀐 항목 없음",
                onClick = onSaveEventBlocks,
                enabled = state.dirty && !state.saving,
                loading = state.saving,
            )
            Text(
                text = "켜고 끈 것은 이 일정에만 적용돼요. 기본값을 바꾸려면 내 정보 › 아침 루틴에서 고쳐 주세요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        } else {
            JitPrimaryButton(
                label = "항목 추가",
                modifier = Modifier.testTag("routine_new"),
                onClick = onNewBlock,
                enabled = !state.saving,
                loading = state.saving,
            )
            Text(
                text = "항목을 고치면 앞으로의 알람에 자동으로 반영돼요. 이미 울린 알람은 그대로예요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
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
    JitCard(padding = 16.dp, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = "${state.blocks.size}개 중 ${state.includedCount}개 할게요",
                dotColor = JitColor.Accent,
                fontSize = 13,
                dotSize = 7.dp,
            )
            state.simpleSumLabel?.let {
                Text(it, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = "그냥 더한 시간이에요. 함께 할 수 있는 항목은 겹쳐 계산하므로 실제 준비 시간은 이보다 짧을 수 있어요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
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
    JitCard(padding = 16.dp, gap = 10.dp, accented = block.rangeMismatch) {
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = block.rangeLabel,
                            color = JitColor.TextSecondary,
                            fontSize = 13.sp,
                        )
                        // 기본 루틴에서는 눌러서 고친다 — "눌러서 수정" 글 대신 연필 아이콘(11-17)
                        if (!eventMode) {
                            Spacer(Modifier.width(6.dp))
                            Icon(LineIcons.pencil, contentDescription = "수정", tint = JitColor.TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (block.parallelizable) JitChip("다른 일과 함께", JitColor.Blue, fontSize = 11)
                        if (block.dropCost == DropCost.IMPOSSIBLE) {
                            JitChip("꼭 해요", JitColor.Red, fontSize = 11)
                        } else if (block.dropCost == DropCost.NONE) {
                            JitChip("건너뛰어도 돼요", JitColor.TextSecondary, fontSize = 11)
                        }
                        // 이 일정에서 기본값을 덮어썼다는 표시. "기본은 포함인데
                        // 이번엔 뺐다" 를 사용자가 알아야 다음에 헷갈리지 않는다.
                        if (eventMode && block.explicit) {
                            JitChip("이번만 바꿈", JitColor.Accent, fontSize = 11)
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
            fontSize = 12.sp,
        )
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
    /**
     * 칸에 붙지 않는 저장 · 삭제 실패(통신 · 5xx · 다른 기기에서 지운 항목). 폼이 열려 있는 동안에는 목록이 그려지지 않아
     * 예전에는 이 실패가 어디에도 보이지 않았다. 저장 버튼 위에 한 줄로 둔다(fix/iter4-he-bugs 이식).
     */
    formError: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("block_draft_scroll")
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
            inputTag = "block_name",
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
                    inputTag = "block_min",
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
                    inputTag = "block_max",
                    value = draft.maxText,
                    onValueChange = onMax,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    enabled = !saving,
                )
                draft.errors["max"]?.let { FieldError(it) }
            }
        }

        // 범위가 준비 시간 변동 폭의 재료다. 한 점으로 두면 변동이 없다고 신고한
        // 것이 된다. 정시 도착 확률은 이동 변동 폭까지 있어야 나오므로(서버
        // compute_alarm_math) 준비 범위만으로 확률이 생긴다고 약속하지 않는다.
        NoticeCard(
            dot = if (draft.minText.isNotBlank() && draft.minText == draft.maxText) {
                JitColor.Amber
            } else {
                JitColor.Blue
            },
            title = "최소와 최대를 다르게 넣어 주세요",
            body = "그 차이만큼 아침마다 준비 시간이 달라질 수 있다고 보고 계산해요. " +
                "정시 도착 확률은 이동 기록도 쌓여야 나와요",
        )

        JitSectionTitle("늦을 때 건너뛰면 얼마나 아쉬워요?")
        // 높이 32 알약 칩(알람 추가 시트와 같은 부품)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropCost.entries.forEach { cost ->
                JitPillChip(
                    text = cost.label,
                    selected = cost == draft.dropCost,
                    onClick = { onDropCost(cost) },
                    enabled = !saving,
                    fontSize = 13,
                    tag = "block_drop_${cost.name.lowercase()}",
                )
            }
        }
        Text(draft.dropCost.hint, color = JitColor.TextSecondary, fontSize = 12.sp)

        ToggleRow(
            title = "다른 일과 함께 할 수 있어요",
            body = "다른 일과 겹쳐 할 수 있어요. 총 준비 시간에 더하지 않고 겹쳐 계산해요",
            checked = draft.parallelizable,
            enabled = !saving,
            onChange = onParallel,
        )

        ToggleRow(
            title = "새 일정에 기본 포함",
            body = "끄면 일정마다 직접 체크해야 포함돼요",
            checked = draft.includedByDefault,
            enabled = !saving,
            onChange = onIncluded,
        )

        Spacer(Modifier.height(4.dp))

        formError?.let {
            Text(text = it, color = JitColor.Red, fontSize = 12.sp, modifier = Modifier.testTag("block_form_error"))
        }

        JitPrimaryButton(
            label = "저장",
            modifier = Modifier.testTag("block_save"),
            onClick = onSave,
            enabled = !saving,
            loading = saving,
        )

        draft.id?.let { id ->
            // 삭제는 테두리 버튼(빨간 글자 · 휴지통). 꽉 찬 버튼은 [저장] 하나뿐이다.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, JitColor.Track, RoundedCornerShape(12.dp))
                    .clickable(enabled = !saving, role = Role.Button) { onDelete(id) }
                    .testTag("block_delete"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LineIcons.trash, contentDescription = null, tint = JitColor.Red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("항목 삭제", color = JitColor.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "지금까지 쌓인 이 항목의 기록도 함께 사라져요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
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
    JitCard(padding = 16.dp, gap = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        Text(body, color = JitColor.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun FieldError(text: String) {
    Text(
        text = text,
        color = JitColor.Red,
        fontSize = 12.sp,
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
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun EmptyCard(onOpenRoutine: (() -> Unit)? = null) {
    JitCard(padding = 16.dp, gap = 10.dp) {
        JitDotLabel(
            text = "등록된 항목이 없어요",
            dotColor = JitColor.TextSecondary,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(
            // 준비 시간은 ⑮ 에서 받고, 답하지 않았으면 서버 기본값을 쓴다. 그 숫자는
            // 서버 소유라 앱에 적지 않는다.
            text = "항목이 없으면 평소 준비 시간 하나를 한 덩어리로 써요. " +
                "나눠 두면 왜 그 시각인지 설명할 수 있어요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        // 이 아침 할 일에서만: 항목은 내 정보 › 아침 루틴에서 만든다. 회색 채움(가벼운 이동, HE 2-6)
        onOpenRoutine?.let { open ->
            Row(
                modifier = Modifier
                    .testTag("routine_open_from_event")
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(JitColor.Surface2)
                    .clickable(role = Role.Button, onClick = open),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LineIcons.plus, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("아침 루틴 만들러 가기", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NoticeCard(
    dot: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    JitCard(padding = 16.dp, gap = 6.dp) {
        JitDotLabel(text = title, dotColor = dot, fontSize = 13, dotSize = 7.dp)
        Text(body, color = JitColor.TextSecondary, fontSize = 12.sp)
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

internal fun previewBlocks() = listOf(
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
        observedMeanLabel = "실제 평균 14.2분",
        learningNote = "실제 평균 14.2분 · 7번 기록, 적어 둔 범위 안이에요",
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
        observedMeanLabel = "실제 평균 19분",
        learningNote = "실제 평균 19분 · 적어 둔 최대보다 4분 길어요. 범위를 늘려 보세요",
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
        learningNote = "기록 없음 · 범위가 한 점이라 달라질 폭이 없어요",
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
