// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.Tone
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.theme.JitColor

/*
 * ⑧ 주간 리포트와 ⑨ 상세 리포트가 함께 쓰는 부품.
 */

/** 값의 뜻 → 색. 매핑이 뜻(Tone)을 정하고 화면은 색만 고른다. */
internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.GOOD -> JitColor.Green
    Tone.WARN -> JitColor.Amber
    Tone.BAD -> JitColor.Red
    Tone.NEUTRAL -> JitColor.TextPrimary
    Tone.UNKNOWN -> JitColor.TextSecondary
}

@Composable
internal fun ToneText(
    text: String,
    tone: Tone,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    bold: Boolean = true,
) {
    Text(
        text = text,
        color = toneColor(tone),
        fontSize = fontSize,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier,
    )
}

/** 판정 칩 색. 표본 부족은 흐린 글자다. */
internal fun verdictColor(verdict: String): Color = when (verdict) {
    "calibrated" -> JitColor.Green
    "overconfident" -> JitColor.Red
    "conservative" -> JitColor.Amber
    else -> JitColor.TextSecondary
}

/**
 * 예측 검증 표(⑨). 버킷마다 "약속 구간 · 실제 정시율 · 표본 수" 한 줄.
 *
 * 이 일정의 τ 구간([highlightLower])은 점을 Accent 로, 구간 글자를 굵게 한다. 서버가
 * 준 구간 아래 끝끼리 비교한다 — 앱이 τ 로 구간을 다시 계산하지 않는다.
 */
@Composable
internal fun CalibrationTable(view: CalibrationView, highlightLower: Double?) {
    JitCard(padding = 14.dp, gap = 8.dp, accented = view.verdict == CalibrationView.WIRE_OVERCONFIDENT) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("예측 검증 · 최근 90일", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text("약속 → 실제", color = JitColor.TextSecondary, fontSize = 10.sp)
        }

        if (!view.hasData) {
            Text("아직 채점된 아침이 없음", color = JitColor.TextSecondary, fontSize = 11.sp)
        }

        view.points.forEach { point ->
            val mine = point.matches(highlightLower)
            val actualColor = when {
                !point.reliable -> JitColor.TextSecondary
                point.overconfident -> JitColor.Red
                else -> JitColor.Green
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (mine) JitColor.Accent else JitColor.Track)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = point.rangeLabel,
                    color = if (mine || point.reliable) JitColor.TextPrimary else JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = point.actualLabel,
                    color = actualColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(text = view.verdictNote, color = JitColor.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
internal fun NoticeCard(dot: Color, title: String, body: String, caption: String? = null) {
    JitCard(padding = 14.dp, gap = 5.dp) {
        JitDotLabel(text = title, dotColor = dot, fontSize = 12, dotSize = 7.dp)
        Text(body, color = JitColor.TextSecondary, fontSize = 10.sp)
        caption?.let { Text(it, color = JitColor.TextSecondary, fontSize = 10.sp) }
    }
}

@Composable
internal fun ErrorCard(message: String, onRetry: () -> Unit) {
    JitCard(
        modifier = Modifier.clickable(role = Role.Button, onClick = onRetry),
        padding = 14.dp,
        gap = 6.dp,
    ) {
        JitDotLabel(
            text = "불러오지 못했음",
            dotColor = JitColor.Red,
            fontSize = 12,
            dotSize = 7.dp,
        )
        Text(text = message, color = JitColor.TextSecondary, fontSize = 11.sp)
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = JitColor.Accent,
            strokeWidth = 2.dp,
        )
    }
}
