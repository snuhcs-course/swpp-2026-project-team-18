package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.DurationDistributionView
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.domain.model.RiskOptionsView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitProgressBar
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/** 서버가 계산한 위험별 선택지. 저장은 사용자가 계속하기를 눌렀을 때만 한다. */
@Composable
fun RiskChoiceScreen(
    plan: AlarmPlanView?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    riskOptions: RiskOptionsView? = null,
    onContinue: (String) -> Unit = {},
    loading: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    if (!loading && riskOptions != null && riskOptions.options.isNotEmpty()) {
        RiskChooser(riskOptions, onBack, onContinue, modifier, saving, error, plan?.tauUsed)
    } else {
        RiskEmptyState(plan, onBack, modifier, loading, error, riskOptions?.unavailableReason, onRetry)
    }
}

// Partly AI-generated (Claude)
/** 서버가 옵션을 준 경우의 선택 화면(Figma ⑤). */
@Composable
private fun RiskChooser(
    riskOptions: RiskOptionsView,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    error: String? = null,
    currentTau: Double? = null,
) {
    // 고른 옵션은 화면 상태다. 저장은 "계속하기" 를 눌렀을 때 한다(B-5).
    var selectedKey by rememberSaveable(riskOptions) {
        mutableStateOf(riskOptions.selectedKey)
    }
    val selected = riskOptions.option(selectedKey)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .testTag("risk_scroll")
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "지각 위험", onBack = onBack)

        Text(
            text = "얼마나 안전하게 갈까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (riskOptions.options.all { it.probability == null })
                "일부 소요시간의 변동 폭만 알려져 정시 도착 확률은 아직 계산할 수 없음"
            else "지각하지 않을 확률 선택",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        if (riskOptions.selectedKey == null) {
            Text(
                text = currentTau?.let { "현재 적용 τ $it · 바꾸려면 옵션을 골라 주세요" }
                    ?: "바꾸려면 옵션을 골라 주세요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        }

        riskOptions.options.forEach { option ->
            RiskOptionCard(
                option = option,
                selected = option.key == selected?.key,
                onClick = { selectedKey = option.key },
                enabled = !saving,
            )
        }

        if (riskOptions.autoAdjusted) {
            AutoAdjustCard(riskOptions.autoReason)
        }

        riskOptions.distribution?.let { DistributionCard(it) }

        Spacer(Modifier.height(4.dp))

        error?.let { Text(it, color = JitColor.Red, fontSize = 12.sp) }
        JitPrimaryButton(
            label = selected?.let { "${it.label}${josaRo(it.label)} 계속하기" } ?: "옵션을 선택해 주세요",
            onClick = { selected?.let { onContinue(it.key) } },
            enabled = selected != null,
            loading = saving,
            modifier = Modifier.testTag("risk_continue"),
        )
    }
}

// Partly AI-generated (Claude)
/**
 * 옵션 카드 한 장: 알람 시각 · 라벨 칩 · 확률 · 막대 · 수면 줄.
 * 고른 카드는 주황 테두리와 "선택됨" 으로 표시한다.
 */
@Composable
private fun RiskOptionCard(
    option: RiskOptionView,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val color = optionColor(option.key)
    JitCard(
        modifier = Modifier.testTag("risk_option_${option.key}").clickable(enabled = enabled, onClick = onClick),
        accented = selected,
        gap = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitChip(option.label, color)
            if (selected) {
                Text(
                    text = "선택됨",
                    color = JitColor.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = option.alarmAt,
                    color = JitColor.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = option.meridiem,
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // 확률이 없으면 글자와 막대를 둘 다 숨긴다. 빈 막대(0%)도 숫자처럼 읽힌다.
            option.probability?.let {
                Text(
                    text = "$it%",
                    color = color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        option.probability?.let { JitProgressBar(fraction = it / 100f, color = color) }
        option.sleepLabel?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

// Partly AI-generated (Claude)
/** 일정 종류 기본 τ가 프로필 기본값 대신 적용된 실제 이유. */
@Composable
private fun AutoAdjustCard(reason: String?) {
    JitCard(gap = 6.dp) {
        JitDotLabel(
            text = "자동 조정",
            dotColor = JitColor.Blue,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = reason ?: "일정 종류에 맞춰 옵션이 자동으로 조정됨",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

// AI-generated (Claude)
/**
 * 총 소요시간 모델 분포. 막대 높이는 구간별 예상 비율에 비례한다.
 * 축은 처음 · 중앙값 · 끝 세 값만 적는다(Figma "62분 / 80분 / 98분").
 */
@Composable
private fun DistributionCard(distribution: DurationDistributionView) {
    JitCard(gap = 8.dp) {
        Text(
            text = "예상 총 소요시간 분포 · 모형 계산",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        val maxMass = distribution.bins.maxOfOrNull { it.mass }?.takeIf { it > 0 } ?: 1.0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HISTOGRAM_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            distribution.bins.forEach { bin ->
                // 90% 값 안쪽 구간은 주황, 그 바깥 꼬리는 흐리게.
                val inside = bin.minMinutes < distribution.p90Minutes
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction = (bin.mass / maxMass).toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (inside) JitColor.Accent else JitColor.Track),
                )
            }
        }
        val first = distribution.bins.firstOrNull()
        val last = distribution.bins.lastOrNull()
        if (first != null && last != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${first.minMinutes}분", color = JitColor.TextSecondary, fontSize = 10.sp)
                Text("${distribution.p50Minutes}분", color = JitColor.TextSecondary, fontSize = 10.sp)
                Text("${last.maxMinutes}분", color = JitColor.TextSecondary, fontSize = 10.sp)
            }
        }
        Text(
            text = "모형 기준 절반은 ${distribution.p50Minutes}분 이내 · 90%는 ${distribution.p90Minutes}분 이내",
            color = JitColor.TextPrimary,
            fontSize = 12.sp,
        )
    }
}

// AI-generated (Claude)
private val HISTOGRAM_HEIGHT = 64.dp

// AI-generated (Claude)
/** 옵션 key → 색. 라벨과 확률은 서버 값을 그대로 쓰고 색만 화면이 정한다. */
private fun optionColor(key: String): Color = when (key) {
    RiskOptionView.KEY_SAFE -> JitColor.Green
    RiskOptionView.KEY_GAMBLE -> JitColor.Red
    else -> JitColor.Accent
}

// AI-generated (Claude)
/** "안전" → "으로", "자동" → "으로", "느긋" → "으로", "여유" → "로". 받침(ㄹ 제외) 있으면 "으로". */
internal fun josaRo(word: String): String {
    val last = word.lastOrNull() ?: return "로"
    if (last !in '가'..'힣') return "로"
    val jong = (last - '가') % 28
    return if (jong == 0 || jong == 8) "로" else "으로"
}

// Partly AI-generated (Claude)
/** 관측이 없어 서버가 옵션을 주지 못할 때. 왜 아직 고를 수 없는지 설명한다. */
@Composable
private fun RiskEmptyState(
    plan: AlarmPlanView?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean,
    error: String?,
    unavailableReason: String?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .testTag("risk_scroll")
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "지각 위험", onBack = onBack)

        Text(
            text = "얼마나 안전하게 갈까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "시각을 정하지 말고, 감수할 지각 위험을 정하면 됨",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        // 현재 적용된 값
        JitCard(gap = 9.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("지금 적용된 값", color = JitColor.TextSecondary, fontSize = 11.sp)
                Text(
                    text = plan?.tauUsed?.let { "τ $it" } ?: "τ —",
                    color = JitColor.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = plan?.alarmAt ?: "—",
                    color = JitColor.TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = plan?.meridiem ?: "",
                    color = JitColor.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // τ 의 출처. 종류가 없는 일정은 프로필 기본값이라 "{종류} 일정이라" 고 쓰면 틀린다.
            plan?.tauSourceLabel?.let {
                Text(
                    text = "τ 출처 · $it",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        JitCard(gap = 9.dp, modifier = Modifier.testTag("risk_unavailable")) {
            JitDotLabel(
                text = if (loading) "선택지 불러오는 중" else "선택지를 표시할 수 없음",
                dotColor = JitColor.Amber,
                fontSize = 13,
                dotSize = 8.dp,
            )
            Text(
                text = when {
                    loading -> "서버에서 알람 선택지를 계산하고 있습니다."
                    error != null -> error
                    else -> unavailableReason ?: "소요시간 정보가 부족해 선택지를 계산할 수 없습니다."
                },
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
        if (!loading) {
            JitPrimaryButton(
                label = "다시 시도",
                onClick = onRetry,
                modifier = Modifier.testTag("risk_retry"),
            )
        }
    }
}

// Partly AI-generated (Claude)
@Preview(widthDp = 360, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun RiskChoicePreview() {
    JitTheme {
        RiskChoiceScreen(
            plan = AlarmPlanView(
                eventId = 1,
                whenLabel = "내일 아침",
                dateLabel = "9월 18일 금",
                eventTitle = "09:00 자료구조 및 알고리즘",
                eventPlace = "서울대학교 302동",
                sensitivityTag = "수업",
                alarmAt = "7:32",
                meridiem = "AM",
                remaining = null,
                onTimeProbability = null,
                tauUsed = 0.90,
                tauSourceLabel = "수업 일정 기본값",
                confidence = ConfidenceView(
                    percent = null,
                    headline = "정시 도착 확률 · 아직 계산할 수 없음",
                    reason = "준비·이동 둘 다 단일 추정값이라 분포가 없음",
                    action = "루틴 블록에 최소~최대 범위를 넣으면 준비 시간 변동이 반영됨",
                ),
                breakdown = emptyList(),
                totalMinutes = 58,
                arrivalLine = "8:50 도착 예정",
                status = "ok",
                statusLabel = "계산 완료",
            ),
            onBack = {},
        )
    }
}

// AI-generated (Claude)
/** 서버(B-5)가 옵션을 준 경우의 모습. 값은 Figma 예시다. */
@Preview(widthDp = 360, heightDp = 1100, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun RiskChooserPreview() {
    JitTheme {
        RiskChoiceScreen(
            plan = null,
            onBack = {},
            riskOptions = RiskOptionsView(
                options = listOf(
                    RiskOptionView("safe", "안전", "7:20", "AM", 97, null, .97),
                    RiskOptionView("normal", "보통", "7:40", "AM", 90, null, .90),
                    RiskOptionView("gamble", "도박", "8:00", "AM", 65, null, .65),
                ),
                selectedKey = "normal",
                autoAdjusted = false,
                autoReason = null,
                distribution = DurationDistributionView(
                    bins = listOf(
                        DurationDistributionView.Bin(62, 68, .07),
                        DurationDistributionView.Bin(68, 74, .17),
                        DurationDistributionView.Bin(74, 80, .31),
                        DurationDistributionView.Bin(80, 86, .24),
                        DurationDistributionView.Bin(86, 92, .14),
                        DurationDistributionView.Bin(92, 98, .07),
                    ),
                    p50Minutes = 80,
                    p90Minutes = 92,
                    windowDays = null,
                    source = "model",
                ),
            ),
        )
    }
}
