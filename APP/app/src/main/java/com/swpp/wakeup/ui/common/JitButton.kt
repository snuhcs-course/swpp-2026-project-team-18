package com.swpp.wakeup.ui.common

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
@Composable
fun JitPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
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
        } else {
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Figma ⑦ 의 소셜 버튼.
 *
 * 아직 연동이 없다. back-spec 5.1 이 이메일·비밀번호로 확정됐으므로 소셜은
 * 후속 과제이며, 지금은 눌렀을 때 미구현임을 알리는 역할만 한다.
 */
@Composable
fun JitSocialButton(
    label: String,
    container: Color,
    symbol: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JitRadius.Button),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = JitColor.Bg
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
