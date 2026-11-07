// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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