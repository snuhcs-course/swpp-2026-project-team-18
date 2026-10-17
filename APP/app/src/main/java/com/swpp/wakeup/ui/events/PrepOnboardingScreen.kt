package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDurationPickerDialog
import com.swpp.wakeup.ui.common.JitPickerValue
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.durationLabel
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 준비 시간. Figma ⑮ (node 134:2). 가입 직후(온보딩)와 설정(⑯ "변경")이 같이 쓴다.
 *
 * ## 왜 이 화면이 필요한가
 *
 * 준비 시간은 알람 시각을 거꾸로 계산하는 **시작값**이다. 지금까지는 전원에게
 * 30분을 썼는데, 그 30분은 누구의 실제 준비 시간도 아니다. 한 번 물어보면 그
 * 구간이 사라진다.
 *
 * ## 이 값은 저절로 바뀌지 않는다
 *
 * 루틴 블록이 없으면 이 값을 그대로 쓴다(서버 `estimate_prep`, 변동 폭 0). 학습
 * 재료인 블록 관측이 없어서다. 그래서 "기록이 쌓이면 조정됨" 같은 말은 쓰지 않는다.
 * 바꾸려면 설정에서 다시 고르거나 루틴 블록을 등록한다.
 *
 * ## 값을 미리 채우지 않는다
 *
 * 값이 없으면 "—" 로 둔다. "30" 을 넣어 두면 대부분이 그대로 저장해서 묻는 의미가
 * 없어진다. 시계 다이얼(24시간제, 시:분 = 걸리는 시간)은 0:00 에서 열리고, 범위
 * (5분~4시간) 밖이라 그대로 확인할 수 없다. 예전의 ±5분 버튼·숫자칸·프리셋은
 * 정보가 많아 보여 뺐다(PM 지시).
 */
@Composable
fun PrepOnboardingScreen(
    state: HomeViewModel.PrepOnboardingState,
    onMinutesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }

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
        // 뒤로 가기는 "나중에 입력" 과 같은 동작이다(설정에서 열었으면 저장 안 하고 닫기).
        // 두 경로가 다르게 끝나면 한쪽만 상태를 정리해서, 다음에 열 때 앞서 고른 값이 남는다.
        ScreenHeader(title = "준비 시간", onBack = onSkip, enabled = !state.submitting)

        // 줄바꿈을 직접 넣는다. 맡겨 두면 "걸리 / 나요?" 처럼 낱말 중간에서
        // 끊긴다 — 한글은 낱말 경계를 자동으로 지켜 주지 않는다.
        Text(
            text = "평소 준비하는데\n얼마나 걸리나요?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "기상 후 문을 나서기까지 걸리는 시간",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        PrepInputCard(state = state, onOpen = { picking = true })

        state.error?.let { message ->
            Text(text = message, color = JitColor.Red, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))

        JitPrimaryButton(
            label = if (state.fromSettings) "저장" else "저장하고 시작하기",
            modifier = Modifier.testTag("prep_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )

        // 네트워크가 죽었을 때 이 화면이 앱의 입구를 막지 않게 하는 탈출구다.
        // 건너뛰면 다음에 열 때 다시 묻는다 — 프로필이 여전히 비어 있으므로.
        // 설정에서 들어왔으면 건너뛸 것이 없다. 헤더의 뒤로가 그 역할을 한다.
        if (!state.fromSettings) {
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

    if (picking) {
        JitDurationPickerDialog(
            minutes = state.parsed,
            range = state.range,
            onConfirm = { total ->
                onMinutesChange(total.toString())
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

// Partly AI-generated (Claude)
/** 값 칸. 누르면 시계 다이얼이 뜬다. 일정 추가의 시작 시각 칸과 같은 모양이다. */
@Composable
private fun PrepInputCard(
    state: HomeViewModel.PrepOnboardingState,
    onOpen: () -> Unit,
) {
    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "평소 준비 시간", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                text = "눌러서 선택",
                color = JitColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        JitPickerValue(
            display = state.parsed?.let(::durationLabel) ?: "—",
            fontSize = 24,
            enabled = !state.submitting,
            onClick = onOpen,
            modifier = Modifier.testTag("prep_value"),
            onClickLabel = "준비 시간 고르기",
        )
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun PrepOnboardingPreview() {
    JitTheme {
        PrepOnboardingScreen(
            state = HomeViewModel.PrepOnboardingState(minutes = "35"),
            onMinutesChange = {},
            onSubmit = {},
            onSkip = {},
        )
    }
}
