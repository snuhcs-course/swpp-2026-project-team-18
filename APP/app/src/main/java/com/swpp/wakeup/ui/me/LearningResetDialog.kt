// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.LearningReset
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * "기록 초기화" 확인 창. 되돌릴 수 없는 일이라 무엇이 지워지고 무엇이 남는지 먼저 말하고, 지우는 버튼은 빨갛게 둔다.
 * 지우는 동안([busy])에는 두 버튼과 바깥 누르기를 막는다 — 요청이 끝나기 전에 창이 닫히면 결과를 모른다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearningResetDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .testTag("learning_reset_dialog")
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = LearningReset.DIALOG_TITLE,
                color = JitColor.TextPrimary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(LearningReset.DIALOG_ERASED, color = JitColor.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            Text(LearningReset.DIALOG_KEPT, color = JitColor.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            Text(
                text = LearningReset.DIALOG_WARNING,
                color = JitColor.Red,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogButton(
                    label = LearningReset.CANCEL,
                    tag = "learning_reset_cancel",
                    container = JitColor.Surface2,
                    content = JitColor.TextPrimary,
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    label = if (busy) LearningReset.CONFIRM_BUSY else LearningReset.CONFIRM,
                    tag = "learning_reset_confirm",
                    container = JitColor.Red,
                    content = JitColor.Bg,
                    enabled = !busy,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    tag: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .testTag(tag)
            .height(46.dp)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(if (enabled) container else JitColor.Track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) content else JitColor.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
