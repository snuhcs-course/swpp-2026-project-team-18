package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 개인 진행률 (Figma ④ `card-진행률`).
 *
 * ## 무엇을 보여 주는가
 *
 * 지금 어느 단계인지(알람 전 / 준비 중 / 이동 중 / 도착)와, 이동 중이면 경로를
 * 얼마나 왔는지다.
 *
 * ## 바가 시간이 아니라 거리인 이유
 *
 * 경과 시간으로 채우면 **가만히 있어도 늘어난다.** 지하철을 기다리는 8분 동안
 * 화면은 "가고 있다" 고 말하고, 사용자는 그것을 보고 안심한다. 분자는 경로를
 * 따라 실제로 이동한 거리다 — 근거는 [RouteProgress] 에 있다.
 *
 * ## 진행률이 없을 때
 *
 * 추적 전이거나 좌표를 못 받았으면 **바를 그리지 않는다.** 0% 바를 띄우면
 * "아직 한 걸음도 못 갔다" 로 읽히는데, 사실은 측정하지 않는 상태다. 둘은
 * 다른 뜻이다.
 */
@Composable
fun TripProgressCard(
    stage: TripStage,
    progress: RouteProgress?,
    /** "3분 전 갱신". 위치가 낡았으면 사용자가 그것을 알아야 한다 */
    freshness: String?,
) {
    JitCard(padding = 14.dp, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("개인 진행률", color = JitColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            StageChip(stage)
        }

        StageTrack(stage)

        if (progress != null && progress.onRoute) {
            ProgressBar(progress.ratio)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(progress.label, color = JitColor.TextSecondary, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${progress.percent}%",
                    color = JitColor.Blue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else if (progress != null) {
            // 경로에서 벗어났다. 진행률을 보여 주면 틀린 정보이고, 그 숫자로
            // 여유가 있다고 판단하면 지각한다. 벗어난 사실만 말한다.
            Text(
                text = "경로에서 ${formatDistance(progress.offRouteM)} 떨어져 있음. " +
                    "다른 길로 가는 중이면 진행률을 계산할 수 없음",
                color = JitColor.Amber,
                fontSize = 10.sp,
            )
        } else {
            Text(
                text = stageHint(stage),
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }

        freshness?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
    }
}

/** 진행률을 못 그리는 이유. 상태마다 다르다. */
private fun stageHint(stage: TripStage): String = when (stage) {
    TripStage.BEFORE_ALARM -> "알람이 울리면 추적이 시작됨"
    TripStage.PREPARING -> "집을 나서면 이동 거리가 표시됨"
    TripStage.IN_TRANSIT -> "위치를 아직 받지 못했음"
    TripStage.ARRIVED -> "도착함"
}

private fun formatDistance(meters: Int): String =
    if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0)

/** 현재 단계를 색 칩으로. 한눈에 "지금 무엇" 을 읽는 자리다. */
@Composable
private fun StageChip(stage: TripStage) {
    val color = when (stage) {
        TripStage.BEFORE_ALARM -> JitColor.Track
        TripStage.PREPARING -> JitColor.Accent
        TripStage.IN_TRANSIT -> JitColor.Blue
        TripStage.ARRIVED -> JitColor.Green
    }
    Text(
        text = stage.label,
        // 밝은 바탕에는 어두운 글씨. 흰 글씨를 얹으면 10sp 에서 대비가 모자라다.
        color = if (stage == TripStage.BEFORE_ALARM) JitColor.TextSecondary else JitColor.Bg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 네 단계를 선으로 이어 그린다.
 *
 * 지난 단계는 채우고 앞으로 올 단계는 테두리만 둔다. 연결선도 지난 구간만
 * 밝게 해서 **선만 봐도 어디까지 왔는지** 읽히게 한다.
 */
@Composable
private fun StageTrack(current: TripStage) {
    val currentIndex = TripStage.ordered.indexOf(current)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TripStage.ordered.forEachIndexed { index, stage ->
            if (index > 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(if (index <= currentIndex) JitColor.Blue else JitColor.Track)
                )
            }
            StageDot(
                label = stage.label,
                state = when {
                    index < currentIndex -> DotState.DONE
                    index == currentIndex -> DotState.NOW
                    else -> DotState.TODO
                },
            )
        }
    }
}

private enum class DotState { DONE, NOW, TODO }

@Composable
private fun StageDot(label: String, state: DotState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state) {
            DotState.TODO -> Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    // 채우지 않고 테두리만. 채우면 지나온 단계와 구별되지 않는다.
                    .border(1.5.dp, JitColor.Track, CircleShape)
            )

            DotState.NOW -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(JitColor.Accent)
            )

            DotState.DONE -> Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(JitColor.Blue)
            )
        }
        Text(
            text = label,
            color = if (state == DotState.TODO) JitColor.TextSecondary else JitColor.TextPrimary,
            fontSize = 9.sp,
            fontWeight = if (state == DotState.NOW) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 거리 기준 진행 바. */
@Composable
private fun ProgressBar(ratio: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(JitColor.Track),
    ) {
        // 0 이면 `fillMaxWidth(0f)` 가 되어 아무것도 안 그려진다. 그것이 맞다 —
        // 한 걸음도 안 갔으면 채울 것이 없다.
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(JitColor.Blue)
        )
    }
}

/** 프리뷰·테스트에서 색을 직접 확인할 때만 쓴다. */
internal fun stageColorFor(stage: TripStage): Color = when (stage) {
    TripStage.BEFORE_ALARM -> JitColor.Track
    TripStage.PREPARING -> JitColor.Accent
    TripStage.IN_TRANSIT -> JitColor.Blue
    TripStage.ARRIVED -> JitColor.Green
}
