package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTextStyle
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 준비 시간 온보딩. Figma ⑭ (node 134:2).
 *
 * ## 왜 이 화면이 필요한가
 *
 * 준비 시간은 알람 시각을 거꾸로 계산하는 **시작값**이다. 지금까지는 전원에게
 * 30분을 썼는데, 그 30분은 누구의 실제 준비 시간도 아니다. 관측이 쌓이기까지
 * 며칠 동안 알람이 어떤 사용자에게는 너무 이르고 어떤 사용자에게는 너무 늦는다.
 * 한 번 물어보면 그 구간이 사라진다.
 *
 * ## 이 값은 고정값이 아니다
 *
 * 아침 기록이 쌓이면 학습값이 이 값을 대체한다. 화면이 그 사실을 분명히 말해야
 * 한다 — 말하지 않으면 사용자가 "한 번 잘못 답하면 끝" 이라고 생각해서 과하게
 * 크게 적고, 그러면 알람이 영원히 이르다.
 *
 * ## 값을 미리 채우지 않는다
 *
 * 입력란을 비워 둔다. "30" 을 넣어 두면 대부분이 그대로 저장해서 묻는 의미가
 * 없어진다. 대신 자주 쓰는 값 버튼으로 손을 덜어 준다.
 */
@Composable
fun PrepOnboardingScreen(
    state: HomeViewModel.PrepOnboardingState,
    onMinutesChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        Text(
            text = "준비 시간",
            color = JitColor.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "평소 준비에 얼마나 걸림?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "깨서부터 문을 나서기까지 걸리는 시간",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        PrepInputCard(
            state = state,
            onMinutesChange = onMinutesChange,
            onStep = onStep,
        )

        NoticeCard(
            dot = JitColor.Accent,
            title = "이 값에서 거꾸로 계산함",
            body = "알람은 이 시간을 빼서 정함. 며칠 쓰면 실제 기록이 쌓여 이 값을 대체함",
        )

        NoticeCard(
            dot = JitColor.Blue,
            title = "나중에 항목으로 쪼갤 수 있음",
            body = "샤워·아침식사로 나누면 늦었을 때 무엇을 줄일지 고를 수 있음",
        )

        state.error?.let { message ->
            Text(text = message, color = JitColor.Red, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))

        JitPrimaryButton(
            label = "저장하고 시작하기",
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )

        // 네트워크가 죽었을 때 이 화면이 앱의 입구를 막지 않게 하는 탈출구다.
        // 건너뛰면 다음에 열 때 다시 묻는다 — 프로필이 여전히 비어 있으므로.
        Text(
            text = "나중에 입력",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !state.submitting, onClick = onSkip)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun PrepInputCard(
    state: HomeViewModel.PrepOnboardingState,
    onMinutesChange: (String) -> Unit,
    onStep: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(JitSpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(JitSpace.CardGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "평소 준비 시간", color = JitColor.TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = "분 단위로 입력",
                color = JitColor.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StepButton(label = "−5분", enabled = !state.submitting) { onStep(-5) }
            Box(modifier = Modifier.weight(1f)) {
                JitTextField(
                    label = "분",
                    value = state.minutes,
                    onValueChange = onMinutesChange,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    enabled = !state.submitting,
                )
            }
            StepButton(label = "+5분", enabled = !state.submitting) { onStep(5) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 자주 쓰는 값. 선택 상태를 두지 않는다 — 값은 자유 입력이고
            // 이것은 지름길일 뿐이다.
            listOf(20, 30, 45, 60).forEach { preset ->
                Text(
                    text = "${preset}분",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    style = JitTextStyle.TightCentered,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(JitColor.Surface2)
                        .clickable(enabled = !state.submitting) {
                            onMinutesChange(preset.toString())
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            style = JitTextStyle.TightCentered,
        )
    }
}

@Composable
private fun NoticeCard(dot: Color, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(13.dp))
            Text(
                text = title,
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun PrepOnboardingPreview() {
    JitTheme {
        PrepOnboardingScreen(
            state = HomeViewModel.PrepOnboardingState(minutes = "35"),
            onMinutesChange = {},
            onStep = {},
            onSubmit = {},
            onSkip = {},
        )
    }
}
