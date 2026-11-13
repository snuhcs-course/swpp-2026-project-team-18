// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.Inquiry
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.theme.JitColor
import kotlinx.coroutines.launch

/**
 * 도움말 › [문의하기] 시트. 글(10~1000자)과 답 받을 이메일을 적어 보낸다([Inquiry]).
 *
 * - [보내기] 는 늘 눌린다. 모자란 것이 있으면 보내지 않고 그 칸 아래에 이유를 빨갛게 보인다(왜 안 되는지 알 수
 *   있게 — 꺼진 버튼만 두지 않는다).
 * - 보내는 동안에는 입력과 닫기를 막는다. 성공하면 닫고, 실패하면 적은 글을 그대로 둔 채 버튼 위에 이유를 보인다.
 *
 * @param defaultEmail 미리 채울 이메일(로그인 이메일). 없으면 빈 칸
 * @param onSend 서버로 보낸다. 결과에 따라 시트가 닫히거나 남는다
 * @param onSent 보낸 뒤 화면이 할 일(스낵바)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactSheet(
    defaultEmail: String?,
    onSend: suspend (message: String, email: String) -> Inquiry.Outcome,
    onSent: () -> Unit,
    onDismiss: () -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(defaultEmail.orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val messageError = Inquiry.messageError(message, tried)
    val emailError = Inquiry.emailError(email, tried)

    ModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !sending }),
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
        modifier = Modifier.testTag("contact_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Inquiry.TITLE, color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(Inquiry.SUBTITLE, color = JitColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }

            Field(
                label = Inquiry.MESSAGE_LABEL,
                trailing = Inquiry.counter(message),
                trailingWarn = Inquiry.length(message) > Inquiry.MAX,
                error = messageError,
                errorTag = "contact_message_error",
            ) {
                Input(
                    value = message,
                    onValueChange = { message = it; failure = null },
                    hint = Inquiry.MESSAGE_HINT,
                    tag = "contact_message",
                    enabled = !sending,
                    singleLine = false,
                    modifier = Modifier.heightIn(min = 120.dp, max = 220.dp),
                )
            }

            Field(label = Inquiry.EMAIL_LABEL, error = emailError, errorTag = "contact_email_error") {
                Input(
                    value = email,
                    onValueChange = { email = it; failure = null },
                    hint = Inquiry.EMAIL_HINT,
                    tag = "contact_email",
                    enabled = !sending,
                    singleLine = true,
                    keyboardType = KeyboardType.Email,
                )
            }

            Text(Inquiry.INFO, color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)

            failure?.let {
                Text(
                    text = it,
                    color = JitColor.Red,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(JitColor.SurfaceDanger)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("contact_failure"),
                )
            }

            JitPrimaryButton(
                label = if (sending) Inquiry.SENDING else Inquiry.SEND,
                modifier = Modifier.testTag("contact_send"),
                enabled = !sending,
                loading = sending,
                onClick = {
                    tried = true
                    if (!Inquiry.canSend(message, email)) return@JitPrimaryButton
                    sending = true
                    failure = null
                    scope.launch {
                        val outcome = onSend(message.trim(), email.trim())
                        sending = false
                        if (outcome == Inquiry.Outcome.DONE) onSent() else failure = Inquiry.message(outcome)
                    }
                },
            )
        }
    }
}

/** 칸 이름(왼쪽) · 글자 수(오른쪽) · 칸 · 오류 한 줄 */
@Composable
private fun Field(
    label: String,
    error: String?,
    errorTag: String,
    trailing: String? = null,
    trailingWarn: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            trailing?.let {
                Text(
                    text = it,
                    color = if (trailingWarn) JitColor.Red else JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("contact_count"),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(JitColor.Surface2)
                .then(if (error != null) Modifier.border(1.dp, JitColor.Red, RoundedCornerShape(12.dp)) else Modifier),
        ) { content() }
        error?.let {
            Text(it, color = JitColor.Red, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(errorTag))
        }
    }
}

@Composable
private fun Input(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    tag: String,
    enabled: Boolean,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = JitColor.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(color = JitColor.TextPrimary, fontSize = 13.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(JitColor.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
