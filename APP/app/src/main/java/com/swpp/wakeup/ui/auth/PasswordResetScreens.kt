package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.R
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace

// Partly AI-generated (Claude)
/**
 * 비밀번호 재설정 두 화면(Figma F-38 `506:9`, F-39 `507:9`). 가입한 이메일로 6자리 코드를 받고,
 * 코드와 새 비밀번호를 보내면 로그인까지 된다. 서버 계약은 backend-requests.md R3-9(제안)다.
 * 서버가 아직 없으면 오류 줄에 "아직 서버에서 지원하지 않아요" 가 뜬다.
 *
 * 상태는 [AuthViewModel] 이 갖는다. 여기는 값과 콜백만 받는다.
 */
@Composable
fun PasswordResetRequestScreen(
    email: String,
    loading: Boolean,
    error: String?,
    canSubmit: Boolean,
    onEmailChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    waking: Boolean = false,
) {
    ResetColumn(modifier = modifier, scrollTag = "reset_request_scroll") {
        ResetHeader(onBackClick = onBackClick, enabled = !loading)

        Text(
            text = "비밀번호 재설정",
            color = JitColor.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "가입한 이메일로 6자리 확인 코드를 보냄",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        EmailField(
            label = stringResource(R.string.auth_email),
            value = email,
            onValueChange = onEmailChange,
            inputTag = "reset_email",
            errorTag = "reset_email_error",
            enabled = !loading,
            imeAction = ImeAction.Done,
            onImeAction = { if (canSubmit) onSubmitClick() },
        )

        Text(
            text = "카카오·Google로만 가입한 계정은 비밀번호가 없음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )

        if (error != null) ErrorText(error)
        WakingHint(waking)

        JitPrimaryButton(
            label = "확인 코드 받기",
            modifier = Modifier.testTag("reset_request_submit"),
            onClick = onSubmitClick,
            enabled = canSubmit,
            loading = loading,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onBackClick, enabled = !loading) {
                Text(
                    text = "로그인으로 돌아가기",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// Partly AI-generated (Claude)
@Composable
fun PasswordResetConfirmScreen(
    sentTo: String,
    code: String,
    password: String,
    passwordConfirm: String,
    passwordsMatch: Boolean,
    passwordCheck: PasswordCheckResponse?,
    passwordCheckError: String?,
    loading: Boolean,
    error: String?,
    info: String?,
    canSubmit: Boolean,
    onCodeChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    onResendClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    waking: Boolean = false,
) {
    ResetColumn(modifier = modifier, scrollTag = "reset_confirm_scroll") {
        ResetHeader(onBackClick = onBackClick, enabled = !loading)

        Text(
            text = "새 비밀번호 정하기",
            color = JitColor.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${sentTo}로 보낸 코드를 입력함 · 10분 동안 유효",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        JitTextField(
            label = "확인 코드",
            value = code,
            onValueChange = onCodeChange,
            keyboardType = KeyboardType.NumberPassword,
            inputTag = "reset_code",
            enabled = !loading,
        )

        JitTextField(
            label = "새 비밀번호",
            value = password,
            onValueChange = onPasswordChange,
            isPassword = true,
            passwordToggle = true,
            keyboardType = KeyboardType.Password,
            inputTag = "reset_new_password",
            enabled = !loading,
        )

        PasswordRuleCard(lengthPassed = passwordCheck?.minLength, mixPassed = passwordCheck?.lettersAndDigits)
        passwordCheckError?.let { ErrorText(it) }

        JitTextField(
            label = "새 비밀번호 확인",
            value = passwordConfirm,
            onValueChange = onPasswordConfirmChange,
            isPassword = true,
            passwordToggle = true,
            keyboardType = KeyboardType.Password,
            inputTag = "reset_new_password_confirm",
            imeAction = ImeAction.Done,
            onImeAction = { if (canSubmit) onSubmitClick() },
            enabled = !loading,
            trailing = if (passwordsMatch) {
                { MatchCheck() }
            } else null,
        )

        if (error != null) ErrorText(error)
        if (info != null && error == null) {
            Text(
                text = info,
                modifier = Modifier
                    .testTag("reset_info")
                    .fillMaxWidth(),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        WakingHint(waking)

        JitPrimaryButton(
            label = "비밀번호 바꾸고 로그인",
            modifier = Modifier.testTag("reset_confirm_submit"),
            onClick = onSubmitClick,
            enabled = canSubmit,
            loading = loading,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(
                onClick = onResendClick,
                enabled = !loading,
                modifier = Modifier.testTag("reset_resend"),
            ) {
                Text(
                    text = "코드 다시 받기",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// --- 공통 조각 --------------------------------------------------------------

@Composable
private fun ResetColumn(
    modifier: Modifier,
    scrollTag: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag(scrollTag)
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        content()
    }
}

/** 가입 화면 머리글과 같은 모양: "‹" + "비밀번호 찾기" */
@Composable
private fun ResetHeader(onBackClick: () -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBackClick, enabled = enabled, modifier = Modifier.testTag("reset_back")) {
            Text(
                text = "‹",
                color = JitColor.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "비밀번호 찾기",
                color = JitColor.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WakingHint(waking: Boolean) {
    if (!waking) return
    Text(
        text = stringResource(R.string.auth_waking_server),
        modifier = Modifier.fillMaxWidth(),
        color = JitColor.Amber,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun MatchCheck() {
    Text(
        text = "✓",
        color = JitColor.Green,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
    )
}
