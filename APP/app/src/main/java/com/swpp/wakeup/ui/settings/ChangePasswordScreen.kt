package com.swpp.wakeup.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.auth.ErrorText
import com.swpp.wakeup.ui.auth.MatchCheck
import com.swpp.wakeup.ui.auth.PasswordRuleCard
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace

// Partly AI-generated (Claude)
/**
 * 설정 ⑯ 변형 "비밀번호 변경"(Figma F-41 `508:9`). 이메일 로그인에 쓰는 비밀번호를 바꾼다. 바꾸면
 * 서버가 다른 기기의 로그인을 모두 끝내고, 이 기기는 새 토큰으로 로그인된 채 남는다.
 * 서버 계약은 backend-requests.md R3-9(제안)이고, 아직 없으면 오류 줄에 그렇게 뜬다.
 */
@Composable
fun ChangePasswordScreen(
    state: PasswordChangeState,
    onCurrentChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("change_password_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "비밀번호 변경", onBack = onBack)

        Column {
            JitTextField(
                label = "지금 비밀번호",
                value = state.current,
                onValueChange = onCurrentChange,
                isPassword = true,
                passwordToggle = true,
                keyboardType = KeyboardType.Password,
                inputTag = "pw_current",
                enabled = !state.busy,
                isError = state.currentWrong,
            )
            if (state.currentWrong) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "지금 비밀번호가 맞지 않음",
                    modifier = Modifier.testTag("pw_current_error"),
                    color = JitColor.Red,
                    fontSize = 11.sp,
                )
            }
        }

        JitTextField(
            label = "새 비밀번호",
            value = state.newPassword,
            onValueChange = onNewChange,
            isPassword = true,
            passwordToggle = true,
            keyboardType = KeyboardType.Password,
            inputTag = "pw_new",
            enabled = !state.busy,
        )

        PasswordRuleCard(
            lengthPassed = state.passwordCheck?.minLength,
            mixPassed = state.passwordCheck?.lettersAndDigits,
        )
        state.passwordCheckError?.let { ErrorText(it) }

        JitTextField(
            label = "새 비밀번호 확인",
            value = state.confirm,
            onValueChange = onConfirmChange,
            isPassword = true,
            passwordToggle = true,
            keyboardType = KeyboardType.Password,
            inputTag = "pw_new_confirm",
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canSubmit) onSubmit() },
            enabled = !state.busy,
            trailing = if (state.passwordsMatch) {
                { MatchCheck() }
            } else null,
        )

        Text(
            text = "바꾸면 다른 기기의 로그인이 모두 끝남. 이 기기는 로그인된 채로 남음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
        )

        // 지금 비밀번호 칸 아래에 이미 그린 오류는 중복해 그리지 않는다.
        if (state.error != null && !state.currentWrong) {
            ErrorText(state.error, modifier = Modifier.testTag("pw_error"))
        }

        JitPrimaryButton(
            label = "비밀번호 바꾸기",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pw_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.busy,
        )
    }
}
