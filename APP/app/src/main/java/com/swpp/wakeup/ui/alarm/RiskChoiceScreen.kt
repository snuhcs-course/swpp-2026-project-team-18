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
import androidx.compose.material3.HorizontalDivider
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

/**
 * 리스크 선택. Figma "5. 리스크 선택" (node 1:53).
 *
 * 원래 의도는 **시각이 아니라 감수할 지각 위험을 정하는 것**이다. τ 를 0.97 로
 * 올리면 더 일찍 일어나고, 0.65 로 내리면 더 자되 지각 확률이 커진다.
 *
 * **그런데 지금은 선택지를 만들 수 없다.** τ 별 알람 시각을 계산하려면 소요시간
 * 분포가 있어야 한다. p97 과 p65 가 서로 다른 값이어야 의미가 있는데, 관측이
 * 없으면 둘이 같은 점추정치로 나온다. 카카오 응답에도 변동성 정보가 없다
 * (checklist "BE-P0-06 결론").
 *
 * 그래서 선택지는 **서버가 계산해 준 경우에만**([riskOptions], task.md B-5) 그린다.
 * 서버가 옵션을 주지 못하면 **왜 아직 선택할 수 없는지**와 무엇이 쌓이면
 * 가능해지는지를 설명하는 빈 상태를 보여준다. 가짜 97%/90%/65% 세 장을
 * 보여주는 것보다 정직하다.
 *
 * @param riskOptions 서버가 준 옵션·분포. null 이거나 옵션이 비면 빈 상태
 * @param onContinue 고른 옵션의 key 로 계속. ⏳ B-5 이후: 저장 API 를 부르고 ④로 돌아간다
 */
@Composable
fun RiskChoiceScreen(
    plan: AlarmPlanView?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    riskOptions: RiskOptionsView? = null,
    onContinue: (String) -> Unit = {},
) {
    if (riskOptions != null && riskOptions.options.isNotEmpty()) {
        RiskChooser(riskOptions, onBack, onContinue, modifier)
    } else {
        RiskEmptyState(plan, onBack, modifier)
    }
}

/** 서버가 옵션을 준 경우의 선택 화면(Figma ⑤). */
@Composable
private fun RiskChooser(
    riskOptions: RiskOptionsView,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 고른 옵션은 화면 상태다. 저장은 "계속하기" 를 눌렀을 때 한다(B-5).
    var selectedKey by rememberSaveable(riskOptions) {
        mutableStateOf(riskOptions.selectedKey ?: riskOptions.options.first().key)
    }
    val selected = riskOptions.options.firstOrNull { it.key == selectedKey }
        ?: riskOptions.options.first()

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
        ScreenHeader(title = "지각 위험", onBack = onBack)

        Text(
            text = "얼마나 안전하게 갈까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "지각하지 않을 확률 선택",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        riskOptions.options.forEach { option ->
            RiskOptionCard(
                option = option,
                selected = option.key == selected.key,
                onClick = { selectedKey = option.key },
            )
        }

        if (riskOptions.autoAdjusted) {
            AutoAdjustCard(riskOptions.autoReason)
        }

        riskOptions.distribution?.let { DistributionCard(it) }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = "${selected.label}${josaRo(selected.label)} 계속하기",
            onClick = { onContinue(selected.key) },
        )
    }
}

/**
 * 옵션 카드 한 장: 알람 시각 · 라벨 칩 · 확률 · 막대 · 수면 줄.
 * 고른 카드는 주황 테두리와 "선택됨" 으로 표시한다.
 */
@Composable
private fun RiskOptionCard(option: RiskOptionView, selected: Boolean, onClick: () -> Unit) {
    val color = optionColor(option.key)
    JitCard(
        modifier = Modifier.clickable(onClick = onClick),
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
            Text(
                text = option.probability?.let { "$it%" } ?: "학습 중",
                color = if (option.probability == null) JitColor.TextSecondary else color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        JitProgressBar(fraction = (option.probability ?: 0) / 100f, color = color)
        option.sleepLabel?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

/** "● 자동 조정: 시험·발표 일정은 기본적으로 안전 모드" */
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

/**
 * 총 소요시간 분포 히스토그램. 막대 높이는 구간별 기록 수에 비례한다.
 * 축은 처음 · 중앙값 · 끝 세 값만 적는다(Figma "62분 / 80분 / 98분").
 */
@Composable
private fun DistributionCard(distribution: DurationDistributionView) {
    JitCard(gap = 8.dp) {
        Text(
            text = "총 소요시간 분포 · 최근 ${distribution.windowDays}일",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        val maxCount = distribution.bins.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1
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
                        .fillMaxHeight(fraction = (bin.count.toFloat() / maxCount).coerceAtLeast(0.04f))
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
            text = "절반은 ${distribution.p50Minutes}분 이내 · 90%는 ${distribution.p90Minutes}분 이내",
            color = JitColor.TextPrimary,
            fontSize = 12.sp,
        )
    }
}

private val HISTOGRAM_HEIGHT = 64.dp

/** 옵션 key → 색. 라벨과 확률은 서버 값을 그대로 쓰고 색만 화면이 정한다. */
private fun optionColor(key: String): Color = when (key) {
    RiskOptionView.KEY_SAFE -> JitColor.Green
    RiskOptionView.KEY_GAMBLE -> JitColor.Red
    else -> JitColor.Accent
}

/** "안전" → "으로", "자동" → "으로", "느긋" → "으로", "여유" → "로". 받침(ㄹ 제외) 있으면 "으로". */
internal fun josaRo(word: String): String {
    val last = word.lastOrNull() ?: return "로"
    if (last !in '가'..'힣') return "로"
    val jong = (last - '가') % 28
    return if (jong == 0 || jong == 8) "로" else "으로"
}

/** 관측이 없어 서버가 옵션을 주지 못할 때. 왜 아직 고를 수 없는지 설명한다. */
@Composable
private fun RiskEmptyState(
    plan: AlarmPlanView?,
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
                    text = plan?.tauUsed?.let { "τ ${"%.2f".format(it)}" } ?: "τ —",
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
            plan?.sensitivityTag?.let {
                Text(
                    text = "$it 일정이라 τ 가 이렇게 정해졌음",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        // 왜 아직 선택지를 만들 수 없는지
        JitCard(gap = 9.dp) {
            JitDotLabel(
                text = "선택지를 만들려면 관측이 필요함",
                dotColor = JitColor.Amber,
                fontSize = 13,
                dotSize = 8.dp,
            )
            Text(
                text = "τ 를 바꿔 여러 알람 시각을 제시하려면 소요시간이 얼마나 " +
                    "흔들리는지 알아야 함. 지금은 경로 조회값 하나뿐이라 τ 를 " +
                    "0.97 로 올려도 0.65 로 내려도 같은 시각이 나옴.",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )

            HorizontalDivider(color = JitColor.Track)

            ProgressRow("실제 출발·도착 기록", 0f, "0일 / 14일")
            Text(
                text = "며칠 쓰면 준비 시간과 이동 시간이 쌓임. 그때부터 분포로 " +
                    "확률을 계산할 수 있음",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }

        // 그때 무엇이 생기는지
        JitCard(gap = 9.dp) {
            Text("관측이 쌓이면", color = JitColor.TextSecondary, fontSize = 11.sp)
            FutureRow("안전", "일찍 일어나고 지각 확률이 낮음", JitColor.Green)
            FutureRow("보통", "권장값. 수면과 정시 도착의 균형", JitColor.Accent)
            FutureRow("도박", "더 자되 지각 확률을 감수", JitColor.Red)
            HorizontalDivider(color = JitColor.Track)
            Text(
                text = "평균이 아니라 분포를 쓰기 때문에 확률로 말할 수 있음",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }

        // 지금도 동작하는 것
        JitCard(gap = 9.dp) {
            JitDotLabel(
                text = "지금도 자동으로 적용되는 것",
                dotColor = JitColor.Blue,
                fontSize = 12,
                dotSize = 6.dp,
            )
            Text(
                text = "시험·발표·기차 일정은 τ 를 높게 잡아 더 여유 있게 계산함. " +
                    "일정 종류만 골라 두면 알아서 반영됨",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ProgressRow(label: String, fraction: Float, right: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = JitColor.TextPrimary, fontSize = 12.sp)
            Text(right, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        JitProgressBar(fraction = fraction, color = JitColor.Accent)
    }
}

@Composable
private fun FutureRow(name: String, body: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
    }
}

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
                confidence = ConfidenceView(
                    percent = null,
                    headline = "정시 도착 확률 학습 중",
                    reason = "준비·이동 둘 다 단일 추정값이라 분포가 없음",
                    action = "루틴 블록에 최소~최대 범위를 넣으면 확률 계산이 시작됨",
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
                    RiskOptionView("safe", "안전", "7:20", "AM", 97, "수면 6시간 20분"),
                    RiskOptionView("normal", "보통", "7:40", "AM", 90, "수면 6시간 40분"),
                    RiskOptionView("gamble", "도박", "8:00", "AM", 65, "수면 7시간"),
                ),
                selectedKey = "normal",
                autoAdjusted = true,
                autoReason = "시험·발표 일정은 기본적으로 안전 모드",
                distribution = DurationDistributionView(
                    bins = listOf(
                        DurationDistributionView.Bin(62, 68, 2),
                        DurationDistributionView.Bin(68, 74, 5),
                        DurationDistributionView.Bin(74, 80, 9),
                        DurationDistributionView.Bin(80, 86, 7),
                        DurationDistributionView.Bin(86, 92, 4),
                        DurationDistributionView.Bin(92, 98, 2),
                    ),
                    p50Minutes = 80,
                    p90Minutes = 92,
                    windowDays = 30,
                ),
            ),
        )
    }
}
