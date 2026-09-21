package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.R
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 회원가입 화면. Figma "10. 회원가입" (node 61:2).
 *
 * 비밀번호 규칙 표시는 **클라이언트 판단이 아니다.** 서버의 Django 검증기
 * (`AUTH_PASSWORD_VALIDATORS`)가 최종 판정을 하고, 여기서는 규칙을 미리
 * 알려주는 안내로만 쓴다. 두 곳에 검증 로직을 두면 반드시 어긋난다.
 * 다만 "비밀번호 확인 일치"만은 왕복 없이 즉시 알 수 있어 클라이언트에서도 본다.
 */
@Composable
fun SignupScreen(
    email: String,
    nickname: String,
    password: String,
    passwordConfirm: String,
    loading: Boolean,
    error: String?,
    canSubmit: Boolean,
    onEmailChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    onBackToLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 잠든 공용 서버를 깨우는 중. 오래 걸릴 때만 true 가 된다. */
    waking: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section)
    ) {
        // Figma header — 뒤로가기 + "회원가입"
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToLoginClick, enabled = !loading) {
                Text(
                    text = "‹",
                    color = JitColor.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.auth_signup),
                    color = JitColor.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = stringResource(R.string.signup_heading),
            color = JitColor.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.signup_sub),
            color = JitColor.TextSecondary,
            fontSize = 13.sp
        )

        JitTextField(
            label = stringResource(R.string.auth_nickname),
            value = nickname,
            onValueChange = onNicknameChange,
            enabled = !loading
        )

        JitTextField(
            label = stringResource(R.string.auth_email),
            value = email,
            onValueChange = onEmailChange,
            keyboardType = KeyboardType.Email,
            enabled = !loading
        )

        JitTextField(
            label = stringResource(R.string.auth_password),
            value = password,
            onValueChange = onPasswordChange,
            isPassword = true,
            keyboardType = KeyboardType.Password,
            enabled = !loading
        )

        JitTextField(
            label = stringResource(R.string.auth_password_confirm),
            value = passwordConfirm,
            onValueChange = onPasswordConfirmChange,
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            enabled = !loading
        )

        PasswordRuleCard()

        StoredInfoCard()

        if (error != null) {
            ErrorText(error)
        }

        // 잠든 서버를 깨우는 동안. 버튼만 돌고 있으면 앱이 멈춘 줄 안다.
        if (waking) {
            Text(
                text = stringResource(R.string.auth_waking_server),
                modifier = Modifier.fillMaxWidth(),
                color = JitColor.Amber,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = stringResource(R.string.signup_submit),
            onClick = onSubmitClick,
            enabled = canSubmit,
            loading = loading
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.signup_have_account),
                color = JitColor.TextSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onBackToLoginClick, enabled = !loading) {
                Text(
                    text = stringResource(R.string.auth_login),
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Figma card-비밀번호규칙 — 서버 검증기 규칙을 미리 알려준다. */
@Composable
private fun PasswordRuleCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(JitSpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(JitSpace.CardGap)
    ) {
        RuleRow(stringResource(R.string.signup_rule_length))
        RuleRow(stringResource(R.string.signup_rule_mix))
        RuleRow(stringResource(R.string.signup_rule_similar))
    }
}

@Composable
private fun RuleRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(JitColor.Green)
        )
        Spacer(Modifier.width(9.dp))
        Text(text = text, color = JitColor.TextPrimary, fontSize = 12.sp)
    }
}

/** Figma card-저장정보 — 무엇을 저장하는지 미리 밝힌다. */
@Composable
private fun StoredInfoCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(JitColor.Blue)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.signup_stored_title),
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = stringResource(R.string.signup_stored_body),
            color = JitColor.TextSecondary,
            fontSize = 11.sp
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun SignupScreenPreview() {
    JitTheme {
        SignupScreen(
            email = "jinho@snu.ac.kr",
            nickname = "김진호",
            password = "swpp2026Alarm",
            passwordConfirm = "swpp2026Alarm",
            loading = false,
            error = null,
            canSubmit = true,
            onEmailChange = {},
            onNicknameChange = {},
            onPasswordChange = {},
            onPasswordConfirmChange = {},
            onSubmitClick = {},
            onBackToLoginClick = {},
        )
    }
}
