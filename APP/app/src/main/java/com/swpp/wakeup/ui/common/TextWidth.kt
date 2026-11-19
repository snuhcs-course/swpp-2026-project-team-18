// AI-generated (Claude) — 홈 일정 줄의 "오전 11:00" 이 잘리던 것(11-19 사용자 스크린샷)
package com.swpp.wakeup.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * [sample] 을 이 글자 크기 · 굵기로 그렸을 때의 너비. 시각 칸처럼 줄마다 폭을 맞춰야 하는 칸의 최소 너비로 쓴다.
 *
 * 고정 dp 로 두면 기기의 글자 크기 설정(fontScale)이 크거나 글꼴이 넓을 때 "오전 11:00" 의 끝이 잘렸다. 가장 넓은
 * 값(예: "오후 12:00")을 지금 기기 설정으로 재서 쓰면 모든 줄이 같은 폭을 갖고 잘리지 않는다.
 */
@Composable
fun rememberTextWidth(sample: String, fontSize: TextUnit, fontWeight: FontWeight): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, fontSize, fontWeight, density) {
        val px = measurer.measure(sample, TextStyle(fontSize = fontSize, fontWeight = fontWeight), softWrap = false).size.width
        with(density) { px.toDp() }
    }
}
