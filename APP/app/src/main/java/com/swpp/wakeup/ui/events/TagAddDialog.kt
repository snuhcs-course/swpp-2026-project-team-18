// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * 종류 "+ 추가" 대화상자(B1, Figma F-49b `528:78` · 20개 초과 F-49c `528:91`).
 * 서버가 거절하면 [HomeViewModel.TagAddState.error] 를 빨간 줄로 보이고 입력은 그대로 둔다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagAddDialog(
    state: HomeViewModel.TagAddState,
    onLabelChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag("tag_add_dialog")
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "종류 추가",
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            JitTextField(
                label = "종류 이름",
                inputTag = "tag_add_label",
                value = state.label,
                onValueChange = onLabelChange,
                imeAction = ImeAction.Done,
                enabled = !state.busy,
                onImeAction = onSubmit,
            )
            Text(
                text = "20자까지 · 이 계정의 다른 기기에서도 보임 · τ 는 프로필 기본을 씀",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
            state.error?.let {
                Text(
                    text = it,
                    color = JitColor.Red,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().testTag("tag_add_error"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tag_add_cancel"),
                ) {
                    Text("취소", color = JitColor.TextSecondary)
                }
                TextButton(
                    onClick = onSubmit,
                    enabled = !state.busy && state.label.isNotBlank(),
                    modifier = Modifier.testTag("tag_add_confirm"),
                ) {
                    Text(
                        text = if (state.busy) "추가하는 중…" else "추가",
                        color = JitColor.Accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
// Partly AI-generated (Claude)
/**
 * 내 종류 이름 바꾸기·지우기 대화상자(event-tags-api.md §5-5). 지금 앱 모양(종류 드롭다운)에 맞춘 임시
 * 입구이고, Figma 에 관리 화면이 생기면 그쪽으로 옮긴다.
 *
 * [삭제] 는 바로 지우지 않고 같은 대화상자 안에서 "지울까요?" 를 한 번 더 묻는다. 서버가 거절하면
 * [HomeViewModel.TagEditState.error] 를 빨간 줄로 보이고 입력은 그대로 둔다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagEditDialog(
    state: HomeViewModel.TagEditState,
    onLabelChange: (String) -> Unit,
    onRename: () -> Unit,
    /** true 면 "지울까요?" 로, false 면 이름 입력으로 */
    onAskDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag("tag_edit_dialog")
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.confirmDelete) "‘${state.original}’ 종류를 지울까요?" else "종류 수정",
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (state.confirmDelete) {
                Text(
                    text = "이 종류를 단 일정은 ‘선택 안 함’이 되고, 알람은 기본 여유로 다시 맞춰져요.",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                )
            } else {
                JitTextField(
                    label = "종류 이름",
                    inputTag = "tag_edit_label",
                    value = state.label,
                    onValueChange = onLabelChange,
                    imeAction = ImeAction.Done,
                    enabled = !state.busy,
                    onImeAction = onRename,
                )
                Text(
                    text = "20자까지 · 이 종류를 단 일정에도 바뀐 이름이 보여요",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            state.error?.let {
                Text(
                    text = it,
                    color = JitColor.Red,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().testTag("tag_edit_error"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.confirmDelete) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onAskDelete(false) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("tag_delete_cancel"),
                    ) {
                        Text("취소", color = JitColor.TextSecondary)
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("tag_delete_confirm"),
                    ) {
                        Text(
                            text = if (state.busy) "지우는 중…" else "지우기",
                            color = JitColor.Red,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    TextButton(
                        onClick = { onAskDelete(true) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("tag_edit_delete"),
                    ) {
                        Text("삭제", color = JitColor.Red)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("tag_edit_cancel"),
                    ) {
                        Text("취소", color = JitColor.TextSecondary)
                    }
                    TextButton(
                        onClick = onRename,
                        enabled = !state.busy && state.label.isNotBlank(),
                        modifier = Modifier.testTag("tag_edit_confirm"),
                    ) {
                        Text(
                            text = if (state.busy) "저장하는 중…" else "저장",
                            color = JitColor.Accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
