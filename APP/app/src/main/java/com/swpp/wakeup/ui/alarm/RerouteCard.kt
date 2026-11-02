package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.swpp.wakeup.domain.model.RerouteBanner
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 이동 중 "지금 경로로는 늦어요"(todo §2 "이동 중 늦으면 경로 바꾸기", Figma ⑦ 변형 `491:9`).
 *
 * 개인 진행률 카드 바로 아래에 둔다. 바꿀 경로가 있으면 강조 테두리와 [이 경로로 바꾸기]
 * (`491:21`), 없으면 늦는다는 사실만 한 줄로 알린다(`491:28`). 바꾸기는 이번 이동만 바꾼다 —
 * 일정에 저장한 경로와 알람 계획은 그대로다(reroute-api.md).
 *
 * @param busy 바꾸는 중. 버튼 자리에 진행 표시를 넣고 다시 누르지 못하게 한다
 */
@Composable
fun RerouteCard(
    banner: RerouteBanner,
    onReroute: (String) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    val routeKey = banner.routeKey
    if (routeKey == null) {
        Column(
            modifier = modifier
                .testTag("reroute_late_only")
                .fillMaxWidth()
                .clip(shape)
                .background(JitColor.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(banner.headline, color = JitColor.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(banner.body, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
        return
    }

    Column(
        modifier = modifier
            .testTag("reroute_card")
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.5.dp, JitColor.Amber, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(banner.headline, color = JitColor.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(banner.body, color = JitColor.TextPrimary, fontSize = 11.sp)
        Box(
            modifier = Modifier
                .testTag("reroute_switch")
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(JitRadius.Button))
                .background(if (busy) JitColor.Track else JitColor.Accent)
                .clickable(enabled = !busy, role = Role.Button) { onReroute(routeKey) },
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
            text = "이번 이동만 바뀜. 일정에 저장한 경로는 그대로임",
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1320, widthDp = 360)
@Composable
private fun RerouteCardPreview() {
    JitTheme {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RerouteCard(
                banner = RerouteBanner(
                    headline = "지금 경로로는 6분 늦어요",
                    body = "9호선 → 2호선으로 가면 안 늦어요 · 8:57 도착 예상",
                    routeKey = "subway:9-2",
                ),
                onReroute = {},
                busy = false,
            )
            RerouteCard(
                banner = RerouteBanner(
                    headline = "지금 경로로 가면 6분 늦어요",
                    body = "더 일찍 닿는 경로가 없음",
                    routeKey = null,
                ),
                onReroute = {},
                busy = false,
            )
        }
    }
}
