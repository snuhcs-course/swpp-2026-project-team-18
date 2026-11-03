package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.FasterRoute
import com.swpp.wakeup.domain.model.FasterRouteOffer
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 홈 "다음 알람" 카드 안의 한 줄(Figma ③ 변형 `512:10`): "9호선 → 2호선으로 가면 6분 빨라요"와 작은
 * [이 경로로 바꾸기]. 카드 전체 클릭(근거 화면 열기)과 별개로 버튼만 누른다.
 */
@Composable
fun FasterRouteRow(
    offer: FasterRouteOffer,
    busy: Boolean,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .testTag("next_alarm_faster_route")
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = FasterRoute.line(offer),
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = JitColor.TextSecondary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = "이 경로로 바꾸기",
                color = JitColor.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag("next_alarm_switch_route")
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onSwitch)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

// Partly AI-generated (Claude)
/** 알람 결정 화면(④)의 카드(Figma `512:30`). 알람이 울리기 전에만 부른다. */
@Composable
fun FasterRouteCard(
    offer: FasterRouteOffer,
    busy: Boolean,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    JitCard(modifier = modifier.testTag("plan_faster_route"), padding = 14.dp, gap = 8.dp) {
        Text(
            text = FasterRoute.line(offer),
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .testTag("plan_faster_route_switch")
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(JitRadius.Button))
                .background(if (busy) JitColor.Track else JitColor.Accent)
                .clickable(enabled = !busy, role = Role.Button, onClick = onSwitch),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = JitColor.TextSecondary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("이 경로로 바꾸기", color = JitColor.Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = "일정에 저장한 경로가 바뀌고 알람을 다시 계산함",
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1320, widthDp = 360)
@Composable
private fun FasterRoutePreview() {
    JitTheme {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val offer = FasterRouteOffer("transit:2호선", "9호선 → 2호선", 6, 0L)
            FasterRouteRow(offer, busy = false, onSwitch = {})
            FasterRouteCard(offer, busy = false, onSwitch = {})
        }
    }
}
