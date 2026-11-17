package com.swpp.wakeup.ui.common

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * Figma 의 기본 CTA 버튼. 강조색 배경 + 어두운 글자.
 *
 * [loading] 이면 라벨 자리에 진행 표시를 넣고 클릭을 막는다. 버튼이 사라지거나
 * 크기가 변하면 화면이 흔들리므로 높이를 유지한다.
 */
// Partly AI-generated (Claude)
/**
 * 회색 채움 · 기본 버튼(iter4 H2-6, Figma 버튼 4단계의 "다시 시도"). 둥근 알약에 되돌이 화살표 + 굵은 흰 글자.
 *
 * 오류 · 오프라인 안내 안의 [다시 시도] 에 쓴다. 예전에는 주황 글자 "눌러서 다시 시도" 한 줄에 카드 전체가
 * 눌려서 글자인지 버튼인지 구분되지 않았다(HE 2-6). 이제 이 버튼만 눌린다.
 *
 * 채움은 [JitColor.Track] 이다 — 안내 띠 바탕([JitColor.Surface2])보다 한 단계 밝아야 띠와 구분된다.
 * 누르는 영역은 48dp 이상.
 *
 * @param icon null 이면 아이콘 없이 글자만(예: "출발지·도착지 다시 고르기")
 */
@Composable
fun JitRetryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "다시 시도",
    icon: ImageVector? = LineIcons.rotateCcw,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(JitColor.Track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) JitColor.TextPrimary else JitColor.TextSecondary
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun JitPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    /** 진행 중에 돌림표 옆에 적을 글자. null 이면 돌림표만 보인다 */
    loadingLabel: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JitRadius.Button),
        colors = ButtonDefaults.buttonColors(
            containerColor = JitColor.Accent,
            contentColor = JitColor.Bg,
            disabledContainerColor = JitColor.Track,
            disabledContentColor = JitColor.TextSecondary,
        ),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = JitColor.Bg,
                strokeWidth = 2.dp
            )
            if (loadingLabel != null) {
                Spacer(Modifier.width(10.dp))
                Text(text = loadingLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Figma ⑦ 의 소셜 버튼(카카오·Google). 누르면 SDK 로 ID 토큰을 받아 서버로 보낸다
 * (docs/iter3/social-login-api.md, `ui/auth/SocialSignIn`).
 */
@Composable
fun JitSocialButton(
    label: String,
    container: Color,
    symbol: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 다른 버튼이 확인 중이면 막는다. 막힌 버튼은 흐리게 그린다(Figma ⑦ 변형 `496:9`) */
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JitRadius.Button),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = JitColor.Bg,
            disabledContainerColor = container.copy(alpha = 0.45f),
            disabledContentColor = JitColor.Bg.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(vertical = 15.dp)
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(symbol)
        )
        Spacer(Modifier.width(9.dp))
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
