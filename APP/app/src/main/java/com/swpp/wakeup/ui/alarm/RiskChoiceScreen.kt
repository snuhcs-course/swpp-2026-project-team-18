package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
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
 * 그래서 이 화면은 **왜 아직 선택할 수 없는지**와 무엇이 쌓이면 가능해지는지를
 * 설명한다. 가짜 97%/90%/65% 세 장을 보여주는 것보다 정직하고, 데모에서도
 * 이 앱의 원리를 더 잘 전달한다.
 */
@Composable
fun RiskChoiceScreen(
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
private fun FutureRow(name: String, body: String, color: androidx.compose.ui.graphics.Color) {
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
