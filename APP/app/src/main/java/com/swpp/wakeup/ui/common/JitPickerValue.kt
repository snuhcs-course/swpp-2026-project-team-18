// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 카드 가운데의 값. 누르면 피커 팝업(달력·시계 다이얼)을 띄운다.
 *
 * 밑줄은 "눌러서 바꿀 수 있음" 을 알리는 표시다. 일정 추가의 날짜·시각과 준비 시간
 * 화면이 같이 쓴다.
 *
 * @param onClickLabel 화면 낭독기가 읽을 동작 이름. 예 "준비 시간 고르기"
 */
@Composable
internal fun JitPickerValue(
    display: String,
    fontSize: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = display,
            color = JitColor.TextPrimary,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(3.dp))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(JitColor.Track)
        )
    }
}
