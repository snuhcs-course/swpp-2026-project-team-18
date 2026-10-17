// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.CauseExplanation
import com.swpp.wakeup.domain.model.MorningOutcome
import com.swpp.wakeup.domain.model.OutcomeResult
import com.swpp.wakeup.domain.model.PlanActualRow
import com.swpp.wakeup.domain.model.RowKind
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitProgressBar
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HEADER_DATE = DateTimeFormatter.ofPattern("M월 d일")
private val WEEKDAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/**
 * 상세 리포트. Figma ⑨(`16:2`). 하루 단위이고, 그날 일정이 둘 이상이면 칩으로 고른다.
 *
 * 그날 일정 하나의 계획 대비 실제(준비·출발·이동·도착), 지각이면 원인, 90일 예측
 * 검증 표를 보여준다. 값은 ⑧ 이 받은 주간 응답에서 찾는다 — 따로 부르지 않는다.
 *
 * 처음 고르는 일정은 [initialEventId]. 없거나 그날 목록에 없으면 그날 대표(가장 작은
 * 여유, 도착 기록이 없으면 가장 이른 것)다. 고른 것은 화면 로컬 상태다.
 */
@Composable
fun ReportDayScreen(
    report: WeeklyReportView?,
    longTerm: CalibrationView?,
    date: LocalDate,
    initialEventId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = report?.days?.firstOrNull { it.date == date }

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
        ScreenHeader(
            title = "${WEEKDAY_NAMES[date.dayOfWeek.value - 1]}요일 상세",
            onBack = onBack,
            trailing = date.format(HEADER_DATE),
        )

        if (day == null || day.outcomes.isEmpty()) {
            Text("이 날의 기록을 찾지 못했음", color = JitColor.TextSecondary, fontSize = 12.sp)
            return@Column
        }

        val initial = day.outcomes.firstOrNull { it.eventId != null && it.eventId == initialEventId }
            ?.eventId ?: day.representative?.eventId
        var selectedId by rememberSaveable(date) { mutableStateOf(initial) }
        val selected = day.outcomes.firstOrNull { it.eventId != null && it.eventId == selectedId }
            ?: day.representative
            ?: day.outcomes.first()

        // id 가 없는 일정은 고를 수 없다(누를 열쇠가 없다). 대표로만 보일 수 있다.
        val choosable = day.outcomes.filter { it.eventId != null }
        if (choosable.size >= 2) {
            OutcomeChips(choosable, selected) { selectedId = it.eventId }
        }

        ResultCard(selected)
        PlanActualCard(selected)
        selected.cause?.let { CauseCard(it) }
        longTerm?.let { CalibrationTable(it, highlightLower = selected.tauBucketLower) }
    }
}

@Composable
private fun OutcomeChips(
    outcomes: List<MorningOutcome>,
    selected: MorningOutcome,
    onSelect: (MorningOutcome) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        outcomes.forEach { outcome ->
            val chosen = outcome.eventId == selected.eventId
            val shape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .testTag("report_chip_${outcome.eventId}")
                    // 칩은 높이가 22dp 남짓이다. 터치 영역만 48dp 로 넓힌다.
                    .minimumInteractiveComponentSize()
                    .clip(shape)
                    .then(
                        if (chosen) Modifier.border(1.dp, JitColor.Accent, shape)
                        else Modifier.background(JitColor.Surface2)
                    )
                    .clickable(role = Role.Tab, onClickLabel = "이 일정 보기") { onSelect(outcome) }
                    .semantics { this.selected = chosen }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = outcome.chipLabel,
                    color = if (chosen) JitColor.TextPrimary else JitColor.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(outcome: MorningOutcome) {
    val late = outcome.result == OutcomeResult.LATE
    JitCard(
        padding = 16.dp,
        gap = 6.dp,
        container = if (late) JitColor.SurfaceDanger else JitColor.Surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (outcome.result) {
                OutcomeResult.LATE -> JitDotLabel(
                    text = outcome.headline,
                    dotColor = JitColor.Red,
                    textColor = JitColor.Red,
                    fontSize = 20,
                    dotSize = 9.dp,
                )

                OutcomeResult.ON_TIME -> JitDotLabel(
                    text = outcome.headline,
                    dotColor = JitColor.Green,
                    textColor = JitColor.Green,
                    fontSize = 20,
                    dotSize = 9.dp,
                )

                OutcomeResult.UNOBSERVED -> JitDotLabel(
                    text = outcome.headline,
                    dotColor = JitColor.TextSecondary,
                    textColor = JitColor.TextSecondary,
                    fontSize = 18,
                    dotSize = 9.dp,
                )
            }
            if (outcome.isOnlyLateOfWeek) {
                JitChip(
                    label = "이번 주 유일한 지각",
                    contentColor = JitColor.Red,
                    container = JitColor.Red.copy(alpha = 0.16f),
                )
            }
        }
        outcome.headlineNote?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        Text(text = outcome.contextLine, color = JitColor.TextPrimary, fontSize = 12.sp)
        outcome.confidenceLine?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlanActualCard(outcome: MorningOutcome) {
    JitCard(padding = 14.dp, gap = 10.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("계획 대비 실제", color = JitColor.TextSecondary, fontSize = 11.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 실제 막대 색은 줄마다 차이 부호를 따른다. 범례가 그 규칙을 그대로 말한다.
                LegendItem(JitColor.Track, "계획")
                LegendItem(JitColor.Red, "늘어남")
                LegendItem(JitColor.Green, "줄어듦")
            }
        }

        outcome.rows.forEach { row ->
            when (row.kind) {
                RowKind.DURATION -> DurationRow(row)
                RowKind.TIME -> TimeRow(row, actualColor = JitColor.TextPrimary, showDelta = true)
            }
        }

        outcome.arrival?.let {
            HorizontalDivider(color = JitColor.Track)
            TimeRow(it, actualColor = toneColor(it.actualTone), showDelta = false)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(3.dp))
        Text(label, color = JitColor.TextSecondary, fontSize = 9.sp)
    }
}

@Composable
private fun RowTitle(row: PlanActualRow, showDelta: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (showDelta) ToneText(row.deltaLabel, row.deltaTone, fontSize = 12.sp)
    }
}

/** 준비·이동. 막대 두 줄 — 계획(Track)과 실제(늘어남 빨강, 그 밖 초록). */
@Composable
private fun DurationRow(row: PlanActualRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(row, showDelta = true)
        BarLine(
            fraction = row.plannedFraction,
            color = JitColor.Track,
            label = row.plannedLabel,
            labelColor = JitColor.TextSecondary,
            bold = false,
        )
        BarLine(
            fraction = row.actualFraction,
            color = toneColor(row.actualTone),
            label = row.actualLabel ?: "실제 측정 안 됨",
            labelColor = if (row.actualLabel == null) JitColor.TextSecondary else JitColor.TextPrimary,
            bold = row.actualLabel != null,
        )
    }
}

@Composable
private fun BarLine(fraction: Float?, color: Color, label: String, labelColor: Color, bold: Boolean) {
    if (fraction == null) {
        // 값이 없으면 막대를 그리지 않는다. 0 길이 막대는 "0분" 으로 읽힌다.
        Text(text = label, color = labelColor, fontSize = 10.sp)
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            JitProgressBar(fraction = fraction, color = color, height = 6.dp, track = Color.Transparent)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(64.dp),
            maxLines = 1,
        )
    }
}

/** 출발·도착. "계획 8:08 → 실제 8:15", 실제가 없으면 "계획 8:08 · 실제 측정 안 됨". */
@Composable
private fun TimeRow(row: PlanActualRow, actualColor: Color, showDelta: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RowTitle(row, showDelta)
        val text = buildAnnotatedString {
            append(row.plannedLabel)
            val actual = row.actualLabel
            if (actual == null) {
                append(" · 실제 측정 안 됨")
            } else {
                append(" → ")
                withStyle(SpanStyle(color = actualColor, fontWeight = FontWeight.Bold)) {
                    append(actual)
                }
            }
        }
        Text(text = text, color = JitColor.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun CauseCard(cause: CauseExplanation) {
    JitCard(padding = 14.dp, gap = 6.dp) {
        JitDotLabel(
            text = "원인 분석",
            dotColor = JitColor.Blue,
            textColor = JitColor.Blue,
            fontSize = 12,
        )
        Text(
            text = cause.title,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = cause.body, color = JitColor.TextSecondary, fontSize = 11.sp)
        cause.action?.let { Text(text = "→ $it", color = JitColor.Accent, fontSize = 10.sp) }
    }
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 1100, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun ReportDayLatePreview() {
    JitTheme {
        ReportDayScreen(
            report = ReportPreviewData.exampleWeek,
            longTerm = ReportPreviewData.longTerm,
            date = LocalDate.of(2026, 10, 6),
            initialEventId = null,
            onBack = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun ReportDayOnTimePreview() {
    JitTheme {
        ReportDayScreen(
            report = ReportPreviewData.exampleWeek,
            longTerm = ReportPreviewData.longTerm,
            date = LocalDate.of(2026, 10, 8),
            initialEventId = null,
            onBack = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun ReportDayUnobservedPreview() {
    JitTheme {
        ReportDayScreen(
            report = ReportPreviewData.exampleWeek,
            longTerm = ReportPreviewData.thinLongTerm,
            date = LocalDate.of(2026, 10, 9),
            initialEventId = null,
            onBack = {},
        )
    }
}
