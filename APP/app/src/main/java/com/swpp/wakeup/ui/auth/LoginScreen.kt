package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import com.swpp.wakeup.ui.common.JitSocialButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 로그인 화면. Figma "7. 로그인" (node 23:2).
 *
 * 이메일·비밀번호가 기본 경로다. back-spec.md 5.1 이 SimpleJWT 로 확정돼 있고,
 * 소셜 로그인은 OAuth 심사 부담 때문에 제외됐다. 소셜 버튼은 디자인에 남겨
 * 두었으나 아직 동작하지 않는다.
 *
 * 상태를 갖지 않는다. 값과 콜백을 모두 호출자가 넘긴다. [AuthViewModel] 이
 * 상태를 소유하고 [LoginActivity] 가 연결한다.
 *
 * 가치 제안 3줄("캘린더를 읽어…" 카드)은 **뺐다.** 로그인 화면이 설명을 읽는
 * 자리가 아니고, 그 카드가 입력칸을 화면 아래로 밀어냈다. Figma 쪽에서도 지웠다
 * (node 23:12 삭제).
 *
 * Figma 와 의도적으로 다른 점.
 *  1) 목업 상단의 "6:12 / LTE 87%" 줄은 넣지 않았다. 실제 기기에서는 시스템
 *     상태바가 그 자리를 차지하므로 가짜 상태바는 중복이다.
 *  2) 약관 문구를 9sp 대신 11sp 로 올렸다. Figma 쪽도 11 로 맞춰 두었다.
 *  3) 키보드가 올라오면 화면이 좁아지므로 스크롤을 붙였다. 목업은 고정 800dp
 *     기준이라 이 상황이 없다.
 *
 * 목업의 스페이서(node 23:6)는 원래 남는 공간을 채우도록(`layoutGrow=1`) 돼 있어서
 * 카드를 지우면 **로고가 아래로 내려갔다.** 여기 `Spacer(28.dp)` 는 고정이라 앱은
 * 반대로 아래 요소가 위로 올라온다. 목업이 앱과 어긋나므로 Figma 쪽을 28 고정으로
 * 바꿨다 — 상태바 아래 여백이 양쪽 모두 52 가 된다(12 + 28 + 12).
 */
@Composable
fun LoginScreen(
    email: String,
    password: String,
    loading: Boolean,
    error: String?,
    canSubmit: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onSignupClick: () -> Unit,
    onGoogleClick: () -> Unit,
    onKakaoClick: () -> Unit,
    onBrowseClick: () -> Unit,
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
        Spacer(Modifier.height(28.dp))

        BrandMark(Modifier.align(Alignment.CenterHorizontally))

        Text(
            text = stringResource(R.string.login_brand),
            modifier = Modifier.fillMaxWidth(),
            color = JitColor.TextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.login_tagline),
            modifier = Modifier.fillMaxWidth(),
            color = JitColor.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
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
            imeAction = ImeAction.Done,
            enabled = !loading
        )

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

        JitPrimaryButton(
            label = stringResource(R.string.auth_login),
            onClick = onLoginClick,
            enabled = canSubmit,
            loading = loading
        )

        // Figma 59:12 — "계정이 없으신가요? 회원가입"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.auth_no_account),
                color = JitColor.TextSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onSignupClick, enabled = !loading) {
                Text(
                    text = stringResource(R.string.auth_signup),
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        DividerWithLabel(stringResource(R.string.auth_or))

        JitSocialButton(
            label = stringResource(R.string.login_google),
            container = Color.White,
            symbol = JitColor.GoogleSymbol,
            onClick = onGoogleClick
        )

        JitSocialButton(
            label = stringResource(R.string.login_kakao),
            container = JitColor.Kakao,
            symbol = JitColor.KakaoSymbol,
            onClick = onKakaoClick
        )

        Text(
            text = stringResource(R.string.login_terms),
            modifier = Modifier.fillMaxWidth(),
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        TextButton(
            onClick = onBrowseClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.login_browse),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Figma 23:8 — 66dp 링 + 중앙 14dp 점 */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(66.dp)
            .border(width = 3.dp, color = JitColor.Accent, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(JitColor.Accent)
        )
    }
}

/** Figma 59:15 — 좌우 선 사이에 "또는" */
@Composable
private fun DividerWithLabel(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = JitColor.Track)
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp),
            color = JitColor.TextSecondary,
            fontSize = 11.sp
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = JitColor.Track)
    }
}

/** 서버가 준 메시지를 그대로 보여준다. */
@Composable
internal fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.fillMaxWidth(),
        color = JitColor.Red,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}

@Preview(widthDp = 360, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun LoginScreenPreview() {
    JitTheme {
        LoginScreen(
            email = "jinho@snu.ac.kr",
            password = "password",
            loading = false,
            error = null,
            canSubmit = true,
            onEmailChange = {},
            onPasswordChange = {},
            onLoginClick = {},
            onSignupClick = {},
            onGoogleClick = {},
            onKakaoClick = {},
            onBrowseClick = {},
        )
    }
}
