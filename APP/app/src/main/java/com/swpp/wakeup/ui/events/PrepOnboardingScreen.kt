package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitDurationWheelSheet
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.common.durationLabel
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 준비 시간. Figma ⑮ 다시 그림(iter4 H2-5 · H2-1, node `546:2098`). 가입 직후(온보딩)와 내 정보 › 준비 시간이 같이 쓴다.
 *
 * ## 왜 이 화면이 필요한가
 *
 * 준비 시간은 알람 시각을 거꾸로 계산하는 **시작값**이다. 지금까지는 전원에게
 * 30분을 썼는데, 그 30분은 누구의 실제 준비 시간도 아니다. 한 번 물어보면 그
 * 구간이 사라진다.
 *
 * ## 값을 고르는 세 길
 *
 * −/+ 5분 · 빠른 칩(20분 · 30분 · 45분 · 1시간) · 값 카드를 누르면 뜨는 휠(⑮-a, 1분 단위, [JitDurationWheelSheet]).
 * Figma ⑮-a 의 [휠로 고르기] 버튼 대신 카드 자체를 누르게 했다(사용자 결정 11-12).
 *
 * ## 값을 미리 채우지 않는다
 *
 * 값이 없으면 "—" 로 둔다. "30" 을 넣어 두면 대부분이 그대로 저장해서 묻는 의미가 없어진다.
 * 값이 없으면 −/+ 와 [저장하고 시작하기] 가 꺼져 있고, 칩이나 휠로 처음 값을 정한다.
 * 범위(5분~4시간) 끝에서는 그쪽 버튼이 꺼진다. 칩은 값이 칩과 같을 때만 선택돼 보인다.
 *
 * @param onSplit [항목으로 나눠 적기] → 아침 루틴(준비 항목) 편집
 */
@Composable
fun PrepOnboardingScreen(
    state: HomeViewModel.PrepOnboardingState,
    onMinutesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onSplit: (() -> Unit)? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.submitting
    val set: (Int) -> Unit = { onMinutesChange(it.toString()) }

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
        ScreenHeader(title = "준비 시간", onBack = onSkip, enabled = enabled)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
        ) {
            // 줄바꿈을 직접 넣는다. 맡겨 두면 낱말 중간에서 끊긴다 — 한글은 낱말 경계를 자동으로 지켜 주지 않는다.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "일어나서 집을 나서기까지\n얼마나 걸리나요?",
                    color = JitColor.TextPrimary,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "씻기, 옷 입기, 아침 먹기처럼 나가기 전에 하는 일을\n모두 합친 시간이에요",
                    color = JitColor.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }

            WakeToDoorStrip()

            PrepValueCard(
                minutes = state.parsed,
                range = state.range,
                enabled = enabled,
                onSet = set,
                onOpenWheel = { picking = true },
            )

            PrepHint()

            if (onSplit != null) SplitField(enabled = enabled, onClick = onSplit)

            state.error?.let { message ->
                Text(text = message, color = JitColor.Red, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = onSkip)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "나중에 입력",
                        color = JitColor.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (picking) {
        JitDurationWheelSheet(
            minutes = state.parsed,
            range = state.range,
            onConfirm = { total ->
                set(total)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

// AI-generated (Claude)
/** ⑮ 값 카드의 규칙. JVM 테스트로 고정한다(`PrepValueRulesTest`). */
internal object PrepValueRules {
    /** −/+ 한 번에 바뀌는 분 */
    const val STEP = 5

    /** 빠른 칩(Figma `quick chips`) */
    val CHIPS = listOf(20, 30, 45, 60)

    /** 값이 없으면 −/+ 둘 다 꺼짐. 범위 끝에서는 그쪽만 꺼짐 */
    fun canMinus(minutes: Int?, range: IntRange): Boolean = minutes != null && minutes > range.first
    fun canPlus(minutes: Int?, range: IntRange): Boolean = minutes != null && minutes < range.last

    /** 범위 밖으로 넘기지 않는다(7분 − 5 → 5분) */
    fun minus(minutes: Int, range: IntRange): Int = (minutes - STEP).coerceAtLeast(range.first)
    fun plus(minutes: Int, range: IntRange): Int = (minutes + STEP).coerceAtMost(range.last)

    /** 값 아래 작은 글. 카드를 누르면 휠이 뜬다는 것을 알린다 */
    fun caption(minutes: Int?): String = if (minutes == null) "아직 정하지 않았어요" else "${STEP}분씩"
}

/** 일어나기 → (준비 시간) → 집 나서기 (Figma `strip 일어나기 → 집 나서기`) */
@Composable
private fun WakeToDoorStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StripNode(LineIcons.sunrise, "일어나기")
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 15.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JitColor.Accent),
            )
            Text("준비 시간", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        StripNode(LineIcons.door, "집 나서기")
    }
}

@Composable
private fun StripNode(icon: ImageVector, label: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(JitColor.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(18.dp))
        }
        Text(label, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 값 카드(Figma `card 준비 시간 값`): −/값/+ · 빠른 칩. 카드에서 버튼 · 칩이 아닌 곳을 누르면 휠이 뜬다.
 *
 * 카드는 `clickable` 대신 탭 감지 + 의미(onClick)로 누른다. `clickable` 은 안의 글자를 한 덩어리로 합쳐
 * 값 글자(`prep_display`)를 따로 읽을 수 없게 한다.
 */
@Composable
private fun PrepValueCard(
    minutes: Int?,
    range: IntRange,
    enabled: Boolean,
    onSet: (Int) -> Unit,
    onOpenWheel: () -> Unit,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .testTag("prep_value")
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { onOpenWheel() }) }
            .semantics {
                role = Role.Button
                if (enabled) onClick(label = "준비 시간 휠로 고르기") { onOpenWheel(); true }
            }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(
                icon = LineIcons.minus,
                description = "${PrepValueRules.STEP}분 줄이기",
                tag = "prep_minus",
                enabled = enabled && PrepValueRules.canMinus(minutes, range),
                onClick = { minutes?.let { onSet(PrepValueRules.minus(it, range)) } },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = minutes?.let(::durationLabel) ?: "—",
                    color = JitColor.TextPrimary,
                    fontSize = 40.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.testTag("prep_display"),
                )
                Text(
                    text = PrepValueRules.caption(minutes),
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            StepButton(
                icon = LineIcons.plus,
                description = "${PrepValueRules.STEP}분 늘리기",
                tag = "prep_plus",
                enabled = enabled && PrepValueRules.canPlus(minutes, range),
                onClick = { minutes?.let { onSet(PrepValueRules.plus(it, range)) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            PrepValueRules.CHIPS.filter { it in range }.forEach { chip ->
                QuickChip(
                    label = durationLabel(chip),
                    selected = minutes == chip,
                    enabled = enabled,
                    tag = "prep_chip_$chip",
                    onClick = { onSet(chip) },
                )
            }
        }
    }
}

/** 둥근 −/+ 버튼(Figma `iconbtn`, 36 · 아이콘 18). 꺼지면 흐리게 */
@Composable
private fun StepButton(
    icon: ImageVector,
    description: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(18.dp))
    }
}

/** 빠른 칩(높이 32 · 알약). 값이 칩과 같을 때만 주황으로 */
@Composable
private fun QuickChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) JitColor.Accent else JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) JitColor.Bg else JitColor.TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 힌트 카드(Figma `card 처음엔 대략이면 충분해요`, Card HINT: Surface2 · 둥근 12 · 안 12 · 사이 5) */
@Composable
private fun PrepHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(JitColor.Accent),
            )
            Text("대략이면 충분해요", color = JitColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        // 알람을 끄고 집을 나선 시각으로 정한 값의 0.5~1.5배 안에서 맞춰 간다(서버 prep_observations).
        Text(
            text = "아침마다 실제로 걸린 시간이 쌓이면\n앱이 내 준비 속도에 맞춰 가요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 14.sp,
        )
    }
}

/** [항목으로 나눠 적기] (Figma `field 항목으로 나눠 적기`) → 아침 루틴 편집 */
@Composable
private fun SplitField(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("prep_split")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("항목으로 나눠 적기", color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                "예: 샤워 · 옷 입기 · 아침 식사",
                color = JitColor.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Icon(LineIcons.chevronRight, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(18.dp))
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
            onSplit = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun PrepOnboardingEmptyPreview() {
    JitTheme {
        PrepOnboardingScreen(
            state = HomeViewModel.PrepOnboardingState(),
            onMinutesChange = {},
            onSubmit = {},
            onSkip = {},
            onSplit = {},
        )
    }
}
