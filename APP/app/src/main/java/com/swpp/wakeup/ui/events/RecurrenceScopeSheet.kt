// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.repository.EventScope
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/** 시트가 묻는 일. 줄 이름과 색이 달라진다 */
internal enum class ScopeAction { SAVE, DELETE }

// Partly AI-generated (Claude)
/** 시트 줄 이름. "이 일정만 저장" / "이후 일정 모두 삭제" */
internal fun scopeLabel(scope: EventScope, action: ScopeAction): String {
    val target = if (scope == EventScope.THIS) "이 일정만" else "이후 일정 모두"
    val verb = if (action == ScopeAction.SAVE) "저장" else "삭제"
    return "$target $verb"
}

// Partly AI-generated (Claude)
/**
 * 반복 일정을 고치거나 지울 때 범위를 묻는 바텀시트(iter4 A2, 아이폰 캘린더와 같은 모양).
 *
 * 줄은 [choices] 순서대로 그리고 맨 아래에 [취소]. 요일을 바꿔 저장할 때는 [choices] 에
 * "이후 일정 모두" 하나만 온다. 바깥을 누르거나 아래로 밀어도 취소다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurrenceScopeSheet(
    action: ScopeAction,
    choices: List<EventScope>,
    onChoose: (EventScope) -> Unit,
    onDismiss: () -> Unit,
    /** 요일을 바꿔 "이 일정만" 을 뺐을 때 그 이유를 한 줄 적는다 */
    weekdaysChanged: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = JitColor.Surface,
        modifier = Modifier.testTag("scope_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (action == ScopeAction.SAVE) "반복 일정을 수정해요" else "반복 일정을 삭제해요",
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    action == ScopeAction.DELETE -> "이 일정만 지울지, 이 일정부터 반복을 끝낼지 골라 주세요."
                    weekdaysChanged -> "요일을 바꾸면 이 일정부터 앞으로의 반복 일정에 적용돼요."
                    else -> "이 일정만 바꿀지, 이 일정부터 앞으로의 반복 일정을 모두 바꿀지 골라 주세요."
                },
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.testTag("scope_sheet_note"),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JitRadius.Card))
                    .background(JitColor.Surface2),
            ) {
                choices.forEachIndexed { index, scope ->
                    if (index > 0) HorizontalDivider(color = JitColor.Bg)
                    ScopeRow(
                        text = scopeLabel(scope, action),
                        color = if (action == ScopeAction.DELETE) JitColor.Red else JitColor.Accent,
                        tag = "scope_${scope.wire}",
                        onClick = { onChoose(scope) },
                    )
                }
            }
            ScopeRow(
                text = "취소",
                color = JitColor.TextSecondary,
                tag = "scope_cancel",
                onClick = onDismiss,
                modifier = Modifier
                    .clip(RoundedCornerShape(JitRadius.Card))
                    .background(JitColor.Surface2),
            )
        }
    }
}

@Composable
private fun ScopeRow(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
