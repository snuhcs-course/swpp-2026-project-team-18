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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * 쓰다 만 일정이 있을 때 "일정 추가" 를 누르면 뜨는 대화상자(Figma F-47 `526:73`).
 * [onResume] 은 이어 쓰기, [onDiscard] 는 새로 쓰기(쓰던 내용을 버림), [onDismiss] 는 닫기만 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddResumeDialog(
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag("add_resume_dialog")
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "작성 중인 일정이 있어요. 이어서 쓸까요?",
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDiscard, modifier = Modifier.testTag("add_resume_discard")) {
                    Text("새로 쓰기", color = JitColor.TextSecondary)
                }
                TextButton(onClick = onResume, modifier = Modifier.testTag("add_resume_continue")) {
                    Text("이어서 쓰기", color = JitColor.Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
