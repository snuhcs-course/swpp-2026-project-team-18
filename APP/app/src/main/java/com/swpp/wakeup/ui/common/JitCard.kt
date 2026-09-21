package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * Figma 전반에서 반복되는 카드. 배경 [JitColor.Surface] + 반경 16 + 패딩 14.
 *
 * [accented] 면 강조 테두리를 두른다. Figma 는 "지금 주목해야 하는 카드" 에만
 * 이 테두리를 쓴다(다음 알람, 선택된 리스크 옵션).
 */
@Composable
fun JitCard(
    modifier: Modifier = Modifier,
    accented: Boolean = false,
    padding: Dp = JitSpace.CardPadding,
    gap: Dp = JitSpace.CardGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .then(if (accented) Modifier.border(1.dp, JitColor.Accent, shape) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** 점 + 라벨. Figma 가 상태 표시에 거의 항상 쓰는 조합이다. */
@Composable
fun JitDotLabel(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    textColor: Color = JitColor.TextPrimary,
    fontSize: Int = 12,
    bold: Boolean = true,
    dotSize: Dp = 7.dp,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 작은 알약형 칩. 태그·등급 표시에 쓴다. */
@Composable
fun JitChip(
    label: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    container: Color = JitColor.Surface2,
    fontSize: Int = 10,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // 알약형이 전제다. 폭이 좁은 칸에 들어가도 글자가 세로로 접히면 안 된다.
        // (좁은 Row 안에서 "수업" 이 "수"/"업" 으로 쌓이던 버그)
        Text(
            text = label,
            color = contentColor,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 진행 바. 트랙 위에 채운 부분을 얹는다.
 *
 * [fraction] 이 0~1 밖으로 나가면 잘라낸다. 확률 값이 반올림으로 100을 넘는
 * 경우를 화면에서 방어한다.
 */
@Composable
fun JitProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = JitColor.Track,
) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(safe)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}
