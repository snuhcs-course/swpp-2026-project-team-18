package com.swpp.wakeup.ui.alarm

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 알람 결정. Figma "4. 알람 결정" (node 1:2).
 *
 * **근거를 함께 보여주는 것이 이 화면의 목적이다.** "7:32" 만 주면 사용자가
 * 믿을 이유가 없다. 준비·이동·버퍼를 분해하고 각 값의 출처를 적는다.
 *
 * 확률은 서버가 null 로 줄 수 있다. 관측이 쌓이기 전에는 분포가 없어서다.
 * 그때는 "학습 중" 으로 적고 왜 그런지도 밝힌다.
 */
@Composable
fun AlarmDecisionScreen(
    plan: AlarmPlanView?,
    onBack: () -> Unit,
    onChangeRisk: () -> Unit,
    onDelete: () -> Unit,
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
        ScreenHeader(title = "알람 결정", onBack = onBack)

        if (plan == null) {
            LoadingBlock()
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(plan.whenLabel, color = JitColor.TextSecondary, fontSize = 15.sp)
            Text(plan.dateLabel, color = JitColor.TextSecondary, fontSize = 15.sp)
        }

        // 일정 요약
        JitCard(padding = 14.dp, gap = 9.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JitDotLabel(
                    text = "첫 일정",
                    dotColor = JitColor.Accent,
                    textColor = JitColor.TextSecondary,
                    fontSize = 11,
                    bold = false,
                    dotSize = 6.dp,
                )
                plan.sensitivityTag?.let { JitChip(it, JitColor.Accent) }
            }
            Text(
                text = plan.eventTitle,
                color = JitColor.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = plan.eventPlace, color = JitColor.TextSecondary, fontSize = 11.sp)
        }

        if (plan.isComputed) {
            RecommendedAlarmCard(plan)
            BreakdownCard(plan)
        } else {
            NotComputedCard(plan)
        }

        Spacer(Modifier.height(4.dp))

        if (plan.isComputed) {
            JitPrimaryButton(label = "이 알람으로 설정", onClick = onBack)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "지각 위험 바꾸기",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onChangeRisk)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "일정 삭제",
                color = JitColor.Red,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RecommendedAlarmCard(plan: AlarmPlanView) {
    JitCard(padding = 18.dp, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("추천 알람", color = JitColor.TextSecondary, fontSize = 11.sp)
            plan.remaining?.let {
                Text(it, color = JitColor.TextSecondary, fontSize = 11.sp)
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = plan.alarmAt ?: "—",
                color = JitColor.TextPrimary,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = plan.meridiem,
                color = JitColor.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        ProbabilityRow(plan)
        plan.arrivalLine?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

/**
 * 확률 한 줄.
 *
 * null 이면 왜 없는지까지 적는다. 사용자가 "고장인가?" 하고 의심하지 않게
 * 하려는 것이고, 나중에 값이 채워질 것임을 알려 준다.
 */
@Composable
private fun ProbabilityRow(plan: AlarmPlanView) {
    val probability = plan.onTimeProbability
    if (probability == null) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            JitDotLabel(
                text = "정시 도착 확률 학습 중",
                dotColor = JitColor.TextSecondary,
                textColor = JitColor.TextSecondary,
                fontSize = 13,
                dotSize = 8.dp,
            )
            Text(
                text = "실제 준비·이동 시간이 쌓이면 분포로 확률을 계산함. " +
                    "지금은 경로 조회값 하나만 있어서 확률을 만들 수 없음",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    } else {
        JitDotLabel(
            text = "정시 도착 확률 $probability%",
            dotColor = if (probability >= 90) JitColor.Green else JitColor.Amber,
            textColor = if (probability >= 90) JitColor.Green else JitColor.Amber,
            fontSize = 14,
            dotSize = 8.dp,
        )
    }
}

/** Figma "이렇게 계산했음" — 값마다 출처를 붙인다. */
@Composable
private fun BreakdownCard(plan: AlarmPlanView) {
    JitCard(padding = 14.dp, gap = 9.dp) {
        Text("이렇게 계산했음", color = JitColor.TextSecondary, fontSize = 11.sp)

        plan.breakdown.forEach { row ->
            BreakdownRow(row, plan.maxRowMinutes)
        }

        HorizontalDivider(color = JitColor.Track)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = plan.totalMinutes?.let { "합계 ${it}분" } ?: "합계 —",
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            plan.arrivalLine?.let {
                Text(it, color = JitColor.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        plan.tauUsed?.let {
            Text(
                text = "적용 τ ${"%.2f".format(it)} · 일정 종류에서 결정됨",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BreakdownRow(row: PlanRow, maxMinutes: Int) {
    val color = when (row.kind) {
        PlanRow.Kind.PREP -> JitColor.Accent
        PlanRow.Kind.TRAVEL -> JitColor.Blue
        PlanRow.Kind.BUFFER -> JitColor.Track
    }
    // 막대 길이를 최대값 기준 상대 비율로 그린다. 최대 폭은 58dp.
    val barWidth = (58f * row.minutes / maxMinutes).coerceAtLeast(8f)

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(barWidth.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
                Spacer(Modifier.width(9.dp))
                Text(row.label, color = JitColor.TextPrimary, fontSize = 12.sp)
            }
            Text(
                text = "${row.minutes}분",
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        row.note?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
    }
}

/** 알람을 계산할 수 없는 경우. 이유를 그대로 보여준다. */
@Composable
private fun NotComputedCard(plan: AlarmPlanView) {
    val (dot, body) = when (plan.status) {
        "no_home" -> JitColor.Amber to
            "이동 시간을 구하려면 출발지가 필요함. 홈 화면 안내에서 집 위치를 설정하면 바로 계산됨"

        "no_place" -> JitColor.Amber to
            "일정에 장소가 없어서 이동 시간을 구할 수 없음. 장소를 넣으면 계산됨"

        "route_failed" -> JitColor.Red to
            "경로 조회가 실패했음. 출발지와 목적지 주변에 정류장이 없거나 일시적 오류일 수 있음"

        else -> JitColor.TextSecondary to "알람이 아직 계산되지 않았음"
    }

    JitCard(padding = 14.dp, gap = 8.dp) {
        JitDotLabel(
            text = plan.statusLabel ?: "알람 미계산",
            dotColor = dot,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun LoadingBlock() {
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

@Preview(widthDp = 360, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun AlarmDecisionPreview() {
    JitTheme {
        AlarmDecisionScreen(
            plan = AlarmPlanView(
                eventId = 1,
                whenLabel = "내일 아침",
                dateLabel = "9월 18일 금",
                eventTitle = "09:00 자료구조 및 알고리즘",
                eventPlace = "서울대학교 302동 · 서울 관악구 관악로 1",
                sensitivityTag = "수업",
                alarmAt = "7:32",
                meridiem = "AM",
                remaining = "7시간 28분 남음",
                onTimeProbability = null,
                tauUsed = 0.90,
                breakdown = listOf(
                    PlanRow("준비 시간", 28, "온보딩에서 답한 값. 관측이 쌓이면 학습값으로 바뀜", PlanRow.Kind.PREP),
                    PlanRow("버스 이동", 20, "카카오 실측 경로 · 20분 · 4.6km · 환승 1회", PlanRow.Kind.TRAVEL),
                    PlanRow("안전 버퍼", 10, "문 앞에서 실제 출발까지의 여유", PlanRow.Kind.BUFFER),
                ),
                totalMinutes = 58,
                arrivalLine = "8:50 도착 예정",
                status = "ok",
                statusLabel = "계산 완료",
            ),
            onBack = {},
            onChangeRisk = {},
            onDelete = {},
        )
    }
}
