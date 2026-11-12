package com.swpp.wakeup.ui.report

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.LateRow
import com.swpp.wakeup.domain.model.PrepCompare
import com.swpp.wakeup.domain.model.ProbabilityCompare
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import java.time.LocalDate

// Partly AI-generated (Claude)
/**
 * 리포트 탭 첫 화면(iter4 H2-2 · H2-1, Figma `546:1943`). 예전 ⑧ 주간 리포트를 하단 탭으로 옮기고
 * 숫자 · 용어보다 결론을 먼저 문장으로 말한다.
 *
 * 위에서부터
 * 1. 머리 "리포트" + ?(도움말, H2-4. [onHelpClick] 이 null 이면 숨김)
 * 2. 주 선택 ‹ "9월 14일 ~ 20일" ›. 한 주는 월~일이고 이번 주에서는 › 가 꺼진다(서버 `has_next_week`).
 * 3. 첫 카드(주황 테두리): "이번 주 5번 중 4번 제시간에 도착했어요" + 요일 점 + 늦은 일정 줄(→ ⑨)
 * 4. 정시 도착 확률: 예상 · 실제 막대 + 판정 한 줄
 * 5. 준비 시간: 예상 · 실제 막대 + "다음 알람부터 내 기록으로 맞춰요"
 *
 * Figma 화면에 있는 이 다섯 가지만 그린다. 예전 ⑧ 의 요약 문단 · 타일 2개 · 요일 막대(분) · 예측 검증(90일),
 * 그리고 아래에 이어 두었던 출발 · 이동 시간 · 기상 기록 · 여유 시간을 바꾼 이유 · 한 주 요약 카드는 뺐다
 * (사용자 확인 11-11: Figma 와 다른 예전 화면이 남아 보임). 날마다의 자세한 기록은 ⑨ 에서 본다.
 * 값은 모두 서버 주간 리포트에서 오고, 문구는 [ReportTabLogic] 이 만든다.
 *
 * [scrollState] 는 호출부(`MainHost`)가 들고 있다. ⑨ 로 갔다 돌아와도 보던 위치가 남게 하려는 것이다.
 */
@Composable
fun WeeklyReportScreen(
    report: WeeklyReportView?,
    loading: Boolean,
    error: String?,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onRetry: () -> Unit,
    onOpenDay: (LocalDate, Long?) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    today: LocalDate = LocalDate.now(),
    onHelpClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(scrollState)
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ReportHeader(onHelpClick)

        if (loading && report == null) {
            LoadingBlock()
            return@Column
        }

        error?.let { message ->
            ErrorCard(message, onRetry)
            if (report == null) return@Column
        }

        if (report == null) return@Column

        WeekSelector(
            range = ReportTabLogic.range(report),
            previousEnabled = !loading,
            nextEnabled = !loading && report.canGoNext,
            onPrevious = onPreviousWeek,
            onNext = onNextWeek,
        )

        // 로딩 중에는 누르지 못하게 한다. 새 주가 도착한 뒤 ⑨ 가 "찾지 못했음" 이 된다.
        HeadlineCard(report, today, enabled = !loading, onOpenDay = onOpenDay)
        report.probability?.let { ProbabilityCard(it, report.bufferChangeMinutes, report.inProgress) }
        report.prep?.let { PrepCard(it) }
    }
}

@Composable
private fun ReportHeader(onHelpClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "리포트", color = JitColor.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        onHelpClick?.let { help -> CircleButton(LineIcons.help, "도움말", "report_help", true, help) }
    }
}

@Composable
private fun WeekSelector(
    range: String,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(LineIcons.chevronLeft, "지난 주 보기", "report_previous_week", previousEnabled, onPrevious)
        Text(
            text = range,
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("report_range"),
        )
        CircleButton(LineIcons.chevronRight, "다음 주 보기", "report_next_week", nextEnabled, onNext)
    }
}

/** 36dp 둥근 버튼(Figma `btn 지난 주` · `btn 다음 주 (꺼짐)`). 꺼지면 35% 로 흐리게 */
@Composable
private fun CircleButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------
// 첫 카드
// ---------------------------------------------------------------------------

/**
 * "이번 주 5번 중 4번 제시간에 도착했어요" + 요일 점 + 늦은 일정 줄. 늦은 줄을 누르면 ⑨ 상세로 간다.
 * 일정이 있는 날의 점도 누를 수 있다(예전 ⑧ 의 요일 칸과 같다).
 */
@Composable
private fun HeadlineCard(
    report: WeeklyReportView,
    today: LocalDate,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    val headline = ReportTabLogic.headline(report, today)
    Column(
        modifier = Modifier
            .testTag("report_week_card")
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                append(headline.lead)
                headline.highlight?.let { withStyle(SpanStyle(color = JitColor.Green)) { append(it) } }
                append(headline.tail)
            },
            color = JitColor.TextPrimary,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("report_headline"),
        )

        val dots = ReportTabLogic.dots(report, today)
        if (dots.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                dots.forEachIndexed { index, dot ->
                    DayDot(dot, index, enabled, onOpenDay, Modifier.weight(1f))
                }
            }
        }

        report.lateRows.forEach { row -> LateRowItem(row, enabled, onOpenDay) }

        ReportTabLogic.captions(report).forEach {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun DayDot(
    dot: ReportTabLogic.Dot,
    index: Int,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tap = if (dot.clickable) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "그날 상세 보기") {
                onOpenDay(dot.date, dot.eventId)
            }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .testTag("report_day_$index")
            .then(tap)
            .semantics(mergeDescendants = true) { contentDescription = dot.description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 낭독은 칸 전체의 설명이 맡는다.
        Text(
            text = dot.label,
            color = JitColor.TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (dot.isToday) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        val circle = Modifier.size(16.dp).clip(CircleShape)
        when (dot.kind) {
            ReportTabLogic.DotKind.ON_TIME -> Box(circle.background(JitColor.Green))
            ReportTabLogic.DotKind.LATE -> Box(circle.background(JitColor.Red))
            ReportTabLogic.DotKind.UNOBSERVED -> Box(circle.background(JitColor.TextSecondary.copy(alpha = 0.45f)))
            ReportTabLogic.DotKind.TODAY -> Box(circle.border(2.dp, JitColor.TextSecondary, CircleShape))
            ReportTabLogic.DotKind.EMPTY -> Box(circle.border(2.dp, JitColor.Track, CircleShape))
        }
    }
}

/** 늦은 일정 한 줄(Figma `row 늦은 날 (버튼)`). 빨간 점 · "화요일 6분 늦었어요" / "9:00 자료구조 및 알고리즘" · › */
@Composable
private fun LateRowItem(row: LateRow, enabled: Boolean, onOpenDay: (LocalDate, Long?) -> Unit) {
    val title = ReportTabLogic.lateTitle(row)
    val sub = ReportTabLogic.lateSub(row)
    Row(
        modifier = Modifier
            .testTag("report_late_${row.eventId ?: row.date}")
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "그날 상세 보기") {
                onOpenDay(row.date, row.eventId)
            }
            .semantics(mergeDescendants = true) { contentDescription = "$title, $sub" }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(JitColor.Red))
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = sub,
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(LineIcons.chevronRight, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------------------
// 정시 도착 확률 · 준비 시간
// ---------------------------------------------------------------------------

/**
 * "앱이 말한 확률, 맞았을까요?"(Figma `card 앱이 말한 확률`). 예상 · 실제 막대 두 개와 판정 한 줄.
 * 서버가 그 주 판정을 보류했으면(표본 부족) 막대 없이 "기록이 아직 적어요 · N번 더 다니면…" 만 쓴다.
 */
@Composable
private fun ProbabilityCard(compare: ProbabilityCompare, bufferChangeMinutes: Int, inProgress: Boolean) {
    JitCard(padding = 16.dp, gap = 12.dp, modifier = Modifier.testTag("report_probability")) {
        Text("정시 도착 확률", color = JitColor.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val promised = compare.promised
        if (ReportTabLogic.showBars(compare) && promised != null) {
            val actualColor = if (ReportTabLogic.probabilityWorse(compare)) JitColor.Amber else JitColor.Green
            BarRow(
                label = "예상",
                value = "$promised%",
                fraction = ReportTabLogic.percentFraction(promised),
                fill = JitColor.TextSecondary,
                valueColor = JitColor.TextPrimary,
                tag = "report_promised",
            )
            BarRow(
                label = "실제",
                value = "${compare.actual}%",
                fraction = ReportTabLogic.percentFraction(compare.actual),
                fill = actualColor,
                valueColor = actualColor,
                tag = "report_actual",
            )
        }
        val title = ReportTabLogic.verdictTitle(compare)
        val body = ReportTabLogic.verdictBody(compare, bufferChangeMinutes, inProgress)
        if (title != null || body.isNotEmpty()) {
            Note(icon = LineIcons.alarm, bold = title, body = body, tag = "report_verdict")
        }
    }
}

/** "준비 시간"(Figma `card 준비 시간`). 계획한 준비와 실제 준비(블록 기록) 평균 */
@Composable
private fun PrepCard(prep: PrepCompare) {
    JitCard(padding = 16.dp, gap = 12.dp, modifier = Modifier.testTag("report_prep")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("준비 시간", color = JitColor.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "아침 기록 ${prep.count}번",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        val actualColor = if (ReportTabLogic.prepLonger(prep.plannedMinutes, prep.actualMinutes)) {
            JitColor.Amber
        } else {
            JitColor.Green
        }
        BarRow(
            label = "예상",
            value = "${prep.plannedMinutes}분",
            fraction = ReportTabLogic.minutesFraction(prep.plannedMinutes, prep.actualMinutes),
            fill = JitColor.TextSecondary,
            valueColor = JitColor.TextPrimary,
            tag = "report_prep_planned",
        )
        BarRow(
            label = "실제",
            value = "${prep.actualMinutes}분",
            fraction = ReportTabLogic.minutesFraction(prep.actualMinutes, prep.plannedMinutes),
            fill = actualColor,
            valueColor = actualColor,
            tag = "report_prep_actual",
        )
        Note(icon = LineIcons.check, bold = null, body = listOf(ReportTabLogic.PREP_NOTE), tag = "report_prep_note")
    }
}

/** 라벨 · 막대 · 값 한 줄. 막대는 높이 10dp, 바탕 Track */
@Composable
private fun BarRow(label: String, value: String, fraction: Float, fill: Color, valueColor: Color, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(36.dp).clearAndSetSemantics {},
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(JitColor.Track),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(5.dp))
                    .background(fill),
            )
        }
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp).clearAndSetSemantics {},
        )
    }
}

/** 아이콘 + 굵은 첫 줄 + 보통 줄(Figma `note`) */
@Composable
private fun Note(icon: ImageVector, bold: String?, body: List<String>, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = JitColor.Accent,
            modifier = Modifier.padding(top = 3.dp).size(14.dp),
        )
        Text(
            text = buildAnnotatedString {
                bold?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) } }
                body.forEachIndexed { index, line ->
                    if (bold != null || index > 0) append("\n")
                    append(line)
                }
            },
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 880, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun WeeklyReportPreview() {
    JitTheme {
        WeeklyReportScreen(
            report = ReportPreviewData.exampleWeek,
            loading = false,
            error = null,
            onPreviousWeek = {},
            onNextWeek = {},
            onRetry = {},
            onOpenDay = { _, _ -> },
            today = LocalDate.of(2026, 10, 8),
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun WeeklyReportNoRecordsPreview() {
    JitTheme {
        WeeklyReportScreen(
            report = ReportPreviewData.unrecordedWeek,
            loading = false,
            error = null,
            onPreviousWeek = {},
            onNextWeek = {},
            onRetry = {},
            onOpenDay = { _, _ -> },
        )
    }
}
