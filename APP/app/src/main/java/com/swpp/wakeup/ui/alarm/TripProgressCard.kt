package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitTextStyle

/**
 * 개인 진행률 (Figma ④ `card-진행률`).
 *
 * ## 바 하나가 전부다
 *
 * 단계 점 네 개 · 진행 바 · 퍼센트를 따로 놓던 형태를 **한 줄**로 합쳤다. 나뉘어
 * 있으면 같은 사실을 세 번 말하면서도 정작 "지금 어떤 상태인가" 를 읽으려면 세
 * 군데를 봐야 한다. 지금은 바의 채움이 진행률이고, 이름 아래 한 줄이 상태다.
 *
 * ## 바가 시간이 아니라 거리인 이유
 *
 * 경과 시간으로 채우면 **가만히 있어도 늘어난다.** 지하철을 기다리는 8분 동안
 * 화면은 "가고 있다" 고 말하고, 사용자는 그것을 보고 안심한다. 분자는 경로를
 * 따라 실제로 이동한 거리다 — 근거는 [RouteProgress] 에 있다.
 *
 * ## 진행률이 없을 때
 *
 * 추적 전이거나 좌표를 못 받았으면 **채우지 않는다.** 0% 를 채운 것과 측정하지
 * 않는 것은 화면에서 같아 보이지만 뜻이 전혀 다르다. 그 차이는 바 아래 한 줄이
 * 말한다.
 */
@Composable
fun TripProgressCard(
    /** 진행 바에 쓸 이름. 이 화면의 주인이 누구인지 밝힌다 */
    nickname: String,
    /** 아바타에 넣을 두 글자. 닉네임 뒤 두 글자다 */
    initials: String,
    stage: TripStage,
    progress: RouteProgress?,
    /** "8:50". 도착 예정 시각. 계산 못 했으면 null */
    arrivalAt: String?,
    /** "3분 전 갱신". 위치가 낡았으면 사용자가 그것을 알아야 한다 */
    freshness: String?,
) {
    JitCard(padding = 14.dp, gap = 8.dp) {
        Text("개인 진행률", color = JitColor.TextSecondary, fontSize = 11.sp)

        ProgressRow(
            nickname = nickname,
            initials = initials,
            stage = stage,
            progress = progress,
            arrivalAt = arrivalAt,
        )

        // 바 아래는 **한 줄만** 쓴다. 경로 이탈이 가장 급하고, 그다음이 위치가
        // 얼마나 낡았는지, 마지막이 왜 진행률이 없는지다.
        val note = when {
            progress != null && !progress.onRoute ->
                "경로에서 ${progress.offRouteLabel} 떨어져 있음. 다른 길로 가는 중이면 " +
                    "진행률을 계산할 수 없음" to JitColor.Amber

            freshness != null -> freshness to JitColor.TextSecondary
            else -> stageHint(stage) to JitColor.TextSecondary
        }
        Text(text = note.first, color = note.second, fontSize = 10.sp)
    }
}

/**
 * 진행 바 한 줄.
 *
 * 왼쪽부터 아바타 · 이름 · 상태, 오른쪽에 도착 시각 · 남은 거리다. 바탕의 채움이
 * 진행률이라 **글씨가 채움 위에 얹힌다** — 그래서 채움을 불투명하게 두지 않는다.
 * 불투명하면 경계를 넘는 글자의 대비가 급변해 읽기 어려워진다.
 */
@Composable
private fun ProgressRow(
    nickname: String,
    initials: String,
    stage: TripStage,
    progress: RouteProgress?,
    arrivalAt: String?,
) {
    val accent = stageColorFor(stage)
    // 경로를 벗어났으면 채우지 않는다. 이탈한 위치를 경로에 투영한 비율은
    // 사용자가 실제로 온 만큼이 아니다 — 그 숫자로 여유를 판단하면 지각한다.
    val ratio = progress?.takeIf { it.onRoute }?.ratio ?: 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .border(1.5.dp, accent, RoundedCornerShape(12.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(accent.copy(alpha = 0.2f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(initials = initials, color = accent)
            Spacer(Modifier.width(9.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = nickname,
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLine(stage, progress),
                    color = if (accent == JitColor.Track) JitColor.TextSecondary else accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(6.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    // 도착 시각을 모르면 자리만 비운다. 지어낸 시각을 넣으면
                    // 사용자가 그 시각을 기준으로 움직인다.
                    text = arrivalAt ?: "—",
                    color = JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = trailingLine(stage, progress),
                    color = JitColor.TextSecondary,
                    fontSize = 9.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 진행 바 높이. 두 줄 글씨와 34dp 아바타가 들어가는 최소값이다 */
private val ROW_HEIGHT = 52.dp

@Composable
private fun Avatar(initials: String, color: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            // 밝은 원 위에는 어두운 글씨. Track 은 어두우므로 반대로 간다.
            color = if (color == JitColor.Track) JitColor.TextPrimary else JitColor.Bg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            style = JitTextStyle.TightCentered,
            maxLines = 1,
        )
    }
}

/**
 * 이름 아래 한 줄. 단계와 근거를 붙여 쓴다.
 *
 * 근거가 없을 때 억지로 붙이지 않는다. "이동 중 · 0km 이동" 은 측정하지 못한
 * 것을 측정해서 0 이 나온 것처럼 보이게 한다.
 */
internal fun statusLine(stage: TripStage, progress: RouteProgress?): String = when (stage) {
    TripStage.IN_TRANSIT -> when {
        progress == null -> stage.label
        progress.onRoute -> "${stage.label} · ${progress.movedLabel}"
        else -> "${stage.label} · 경로 이탈"
    }

    TripStage.ARRIVED -> "도착 완료"
    TripStage.PAST -> "이동 기록 없음"
    else -> stage.label
}

/** 오른쪽 아래 한 줄. 도착 시각이 무엇인지 또는 얼마나 남았는지를 말한다. */
internal fun trailingLine(stage: TripStage, progress: RouteProgress?): String = when {
    stage == TripStage.ARRIVED -> "도착"
    progress != null && progress.onRoute -> progress.remainingLabel
    else -> "도착 예정"
}

/** 진행률을 못 그리는 이유. 상태마다 다르다. */
private fun stageHint(stage: TripStage): String = when (stage) {
    TripStage.BEFORE_ALARM -> "알람이 울리면 추적이 시작됨"
    TripStage.PREPARING -> "집을 나서면 이동 거리가 표시됨"
    TripStage.IN_TRANSIT -> "위치를 아직 받지 못했음"
    TripStage.ARRIVED -> "도착함"
    // 지난 일정이다. "추적이 시작됨" 같은 앞날 이야기를 하면 안 된다.
    TripStage.PAST -> "일정 시각이 지났고 이동 기록이 없음"
}

/**
 * 단계 색. 아바타·테두리·채움·상태 글씨가 모두 이 색을 쓴다.
 *
 * [JitColor.Track] 은 "아직/이제 아님" 을 뜻한다. 알람 전과 지난 일정이 같은
 * 색인데, 둘 다 **지금 일어나는 일이 아니다** 는 점에서 같다.
 */
internal fun stageColorFor(stage: TripStage): Color = when (stage) {
    TripStage.BEFORE_ALARM -> JitColor.Track
    TripStage.PREPARING -> JitColor.Accent
    TripStage.IN_TRANSIT -> JitColor.Blue
    TripStage.ARRIVED -> JitColor.Green
    TripStage.PAST -> JitColor.Track
}
