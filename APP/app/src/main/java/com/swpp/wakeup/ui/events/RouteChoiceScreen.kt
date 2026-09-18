package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 경로 선택. Figma "⑬ 경로 선택" (node 77:2).
 *
 * **왜 고르게 하는가.** 카카오는 대중교통 대안을 15개 준다. 서버가 축별
 * 대표만 추려 6개 이하로 내리는데, 그 중 무엇이 나은지는 사람마다 다르다.
 * 신림역 → 서울대는 23분(환승 1회)과 27분(환승 없음)이 함께 나오고, 4분을
 * 더 쓰더라도 환승을 피하는 사람이 있다. 서버가 최단 시간으로 정해 버리면
 * 그 선택을 빼앗는다.
 *
 * **소요시간은 서버가 카카오에서 실측한 값이다.** 앱이 계산하지 않는다.
 * 고른 결과도 소요시간이 아니라 [RouteOption.key] 만 서버로 돌려보내고,
 * 알람을 계산할 때 서버가 그 수단을 다시 조회한다. 배차가 바뀌면 값도 바뀌어야
 * 하기 때문이다.
 */
@Composable
fun RouteChoiceScreen(
    state: HomeViewModel.RouteState?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "경로 선택", onBack = onBack)

        Text(
            text = "어떻게 갈까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        val choice = state?.choice
        Text(
            text = choice?.let { "${it.origin} → ${it.destination}" } ?: "경로를 불러오는 중",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        when {
            state == null || state.loading -> LoadingBlock()

            state.error != null -> ErrorBlock(message = state.error, onRetry = onRetry)

            choice != null -> {
                choice.options.forEach { option ->
                    RouteCard(
                        option = option,
                        selected = option.key == choice.selectedKey,
                        onClick = { onSelect(option.key) },
                    )
                }

                SourceNote()

                Spacer(Modifier.height(4.dp))

                JitPrimaryButton(
                    label = "이 경로로 계산",
                    onClick = onConfirm,
                    enabled = choice.selectedKey != null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun RouteCard(option: RouteOption, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = JitColor.Accent,
                        shape = RoundedCornerShape(JitRadius.Card),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Spacer(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(JitColor.Accent)
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = option.minutesLabel,
                color = JitColor.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = option.mode,
                color = if (selected) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            option.badge?.let { badge ->
                Text(
                    text = badge,
                    color = JitColor.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(JitColor.Surface2)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        if (option.detailLine.isNotBlank()) {
            Text(text = option.detailLine, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

/**
 * 무엇이 실측이고 무엇이 아직 고정값인지 밝힌다.
 *
 * 이동 시간만 카카오 실측이다. 준비 시간과 버퍼는 고정값인데, 세 값이 같은
 * 표에 나란히 놓이면 전부 계산된 값처럼 읽힌다.
 */
@Composable
private fun SourceNote() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(JitColor.Blue)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "이동 시간만 실측값",
                color = JitColor.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = "준비 시간과 안전 버퍼는 아직 고정값임. 관측이 쌓이면 학습값으로 바뀜",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = JitColor.Accent, strokeWidth = 2.dp)
        Text(
            text = "카카오에서 경로를 받아오는 중",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(JitColor.Red)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "경로를 불러오지 못했음",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = message, color = JitColor.TextSecondary, fontSize = 12.sp)
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun RouteChoicePreview() {
    JitTheme {
        RouteChoiceScreen(
            state = HomeViewModel.RouteState(
                loading = false,
                choice = RouteChoice(
                    origin = "신림역",
                    destination = "서울대학교 관악캠퍼스",
                    selectedKey = "transit:2호선>5513",
                    options = listOf(
                        RouteOption("car", "자동차", 14, "14분", "택시 예상액 · 4.3km · 7,600원", "가장 빠름"),
                        RouteOption("transit:2호선>5513", "지하철+도보+버스", 23, "23분", "2호선 → 5513 · 5.1km · 환승 1회 · 1,550원", null),
                        RouteOption("bicycle", "자전거", 24, "24분", "5.0km", null),
                        RouteOption("transit:5516", "버스", 27, "27분", "5516 · 4.6km · 1,500원", "환승 없음"),
                    ),
                ),
            ),
            onSelect = {},
            onConfirm = {},
            onRetry = {},
            onBack = {},
        )
    }
}
