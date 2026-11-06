package com.swpp.wakeup.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.auth.ErrorText
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace

// Partly AI-generated (Claude)
/**
 * 설정 ⑯ 변형 "이메일 인증"(Figma F-53 `530:9`, 오류 F-53b `530:47`). 메일로 받은 6자리 코드를
 * 넣는다. 인증하지 않아도 앱은 그대로 쓸 수 있다(R3-10) — 로그인을 막는 곳이 없다.
 */
@Composable
fun EmailVerifyScreen(
    state: EmailVerifyState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("email_verify_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "이메일 인증", onBack = onBack)
        Text(
            text = "${state.sentTo ?: "내 이메일"}로 보낸 6자리 코드를 입력함 · 10분 동안 유효",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.testTag("email_verify_intro"),
        )
        JitTextField(
            label = "확인 코드",
            value = state.code,
            onValueChange = onCodeChange,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canSubmit) onSubmit() },
            inputTag = "email_verify_code",
            enabled = !state.busy,
            isError = state.error != null,
        )
        state.error?.let { ErrorText(it, modifier = Modifier.testTag("email_verify_error")) }
        if (state.info != null && state.error == null) {
            Text(
                text = state.info,
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.testTag("email_verify_info"),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(
                onClick = onResend,
                enabled = !state.busy && !state.sending,
                modifier = Modifier.testTag("email_verify_resend"),
            ) {
                Text(
                    text = if (state.sending) "보내는 중…" else "코드 다시 받기",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = "인증하지 않아도 앱은 그대로 쓸 수 있음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
        JitPrimaryButton(
            label = "인증하기",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("email_verify_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.busy,
        )
    }
}