package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.R
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 소셜로 처음 가입할 때의 약관 동의(Figma ⑦ 변형 `495:9`, docs/iter3/social-login-api.md §2).
 *
 * 서버가 400 `social_terms_required` 를 줬을 때만 온다. 이미 연결된 계정이면 이 화면 없이 바로
 * 들어간다. 닉네임은 ID 토큰의 이름으로 미리 채우고, 비우면 서버가 토큰의 이름을 쓴다.
 */
@Composable
fun SocialTermsScreen(
    provider: SocialProvider,
    nickname: String,
    agreed: Boolean,
    loading: Boolean,
    error: String?,
    canSubmit: Boolean,
    onNicknameChange: (String) -> Unit,
    onAgreedChange: (Boolean) -> Unit,
    onSubmitClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    waking: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("social_terms_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackClick, enabled = !loading) {
                Text(text = "‹", color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${provider.label}로 시작하기",
                    color = JitColor.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(text = "처음 오셨네요", color = JitColor.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "약관에 동의하면 이 ${provider.label} 계정으로 바로 시작함",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        JitTextField(
            label = "닉네임 · ${provider.label} 프로필에서 가져옴, 바꿀 수 있음",
            inputTag = "social_nickname",
            value = nickname,
            onValueChange = onNicknameChange,
            imeAction = ImeAction.Done,
            enabled = !loading,
        )

        TermsAgreementRow(
            checked = agreed,
            onCheckedChange = onAgreedChange,
            enabled = !loading,
            tag = "social_terms",
        )
        Text(
            text = "이미 가입한 ${provider.label} 계정이면 이 화면 없이 바로 들어감. 같은 이메일로 가입한 " +
                "계정이 있으면 합치지 않고 안내함",
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
        )

        error?.let { ErrorText(it) }
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
            label = "동의하고 시작하기",
            modifier = Modifier.testTag("social_terms_submit"),
            onClick = onSubmitClick,
            enabled = canSubmit,
            loading = loading,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "이메일로 가입했나요?", color = JitColor.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onBackClick, enabled = !loading) {
                Text(
                    text = "이메일로 로그인",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun SocialTermsScreenPreview() {
    JitTheme {
        SocialTermsScreen(
            provider = SocialProvider.KAKAO,
            nickname = "김진호",
            agreed = true,
            loading = false,
            error = null,
            canSubmit = true,
            onNicknameChange = {},
            onAgreedChange = {},
            onSubmitClick = {},
            onBackClick = {},
        )
    }
}
