package com.swpp.wakeup.ui.report

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.DayStatus
import com.swpp.wakeup.domain.model.LateDayLine
import com.swpp.wakeup.domain.model.ReportDay
import com.swpp.wakeup.domain.model.ReportSummary
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

// Partly AI-generated (Claude)
/**
 * 주간 리포트. Figma ⑧(`1:137`).
 *
 * 맨 위에 서버가 쓴 3~5문장 **주간 요약**을 두고, 그 아래에 근거 수치(정시율·평소
 * 도착·요일별 도착·지각한 날·계획 대비 평균·예측 검증)를 둔다. 요일 칸이나 지각한
 * 날을 누르면 ⑨ 상세 리포트로 간다([onOpenDay]).
 *
 * ## 이 화면은 앱을 변호하지 않는다
 *
 * - 결과를 모르는 아침 수를 감추지 않는다. 그 수가 크면 정시율 자체가 적은 표본에
 *   기댄 것이다.
 * - 가장 아슬아슬했던 날을 중간값과 함께 보여준다. 정시였어도 여유가 1분이면 운이
 *   좋았던 것이다.
 * - 측정하지 못한 값은 "측정 안 됨" 이다. 0 으로 그리지 않는다.
 *
 * [scrollState] 는 호출부(`MainHost`)가 들고 있다. ⑨ 로 갔다 돌아와도 보던 위치가
 * 남게 하려는 것이다 — 화면 전환이 이 컴포저블을 컴포지션에서 뺀다.
 *
 * 요일 칸은 360dp 화면에서 폭이 약 42dp 라 48dp 터치 기준보다 좁다. 7칸을 한 줄에
 * 두기 위한 한계이고, 같은 일정은 "지각한 날" 줄(높이 48dp 이상)로도 열 수 있다.
 */
@Composable
fun WeeklyReportScreen(
    report: WeeklyReportView?,
    longTerm: CalibrationView?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onRetry: () -> Unit,
    onOpenDay: (LocalDate, Long?) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
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
        ScreenHeader(title = "주간 리포트", onBack = onBack, enabled = !loading)

        if (loading && report == null) {
            LoadingBlock()
            return@Column
        }

        error?.let { message ->
            ErrorCard(message, onRetry)
            if (report == null) return@Column
        }

        if (report == null) return@Column

        WeekSwitcher(
            rangeLabel = report.rangeLabel,
            previousEnabled = !loading,
            nextEnabled = !loading && report.canGoNext,
            onPrevious = onPreviousWeek,
            onNext = onNextWeek,
        )

        report.summary?.let { SummaryCard(it, inProgress = report.inProgress) }

        if (report.showTiles) Tiles(report)

        report.gapNotice?.let {
            // 관측 공백이 결론을 흔들 만큼 크면 정시율을 앞세우지 않는다.
            NoticeCard(dot = JitColor.Amber, title = it.title, body = it.body, caption = it.caption)
        }

        // 로딩 중에는 누르지 못하게 한다. 새 주가 도착한 뒤 ⑨ 가 "찾지 못했음" 이 된다.
        if (report.showDays) DaysCard(report, enabled = !loading, onOpenDay = onOpenDay)

        if (report.showLateDays) LateDaysCard(report, enabled = !loading, onOpenDay = onOpenDay)

        if (report.showDeviations) DeviationCard(report)

        longTerm?.let { VerdictCard(it) }
    }
}

@Composable
private fun WeekSwitcher(
    rangeLabel: String,
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
        ArrowButton("‹", "지난 주 보기", "report_previous_week", previousEnabled, onPrevious)
        Text(
            text = rangeLabel,
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        ArrowButton("›", "다음 주 보기", "report_next_week", nextEnabled, onNext)
    }
}

@Composable
private fun ArrowButton(
    glyph: String,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = glyph,
        // 꺼진 화살표는 Track. TextSecondary 는 이 화면에서 읽히는 글자에 쓴다.
        color = if (enabled) JitColor.TextPrimary else JitColor.Track,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .testTag(tag)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/**
 * 주간 요약. 서버가 쓴 문장을 그대로 보여준다.
 *
 * LLM 이 쓴 것이면 "AI 요약 · 집계 수치만 사용함" 을 단다 — 서버는 좌표·제목 없이
 * 집계값만 보내고, 숫자는 집계값에 있는 것만 통과시킨다.
 */
@Composable
private fun SummaryCard(summary: ReportSummary, inProgress: Boolean) {
    JitCard(modifier = Modifier.testTag("report_summary"), padding = 16.dp, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(text = "주간 요약", dotColor = JitColor.Accent, fontSize = 12)
            when {
                inProgress -> JitChip("진행 중", JitColor.Amber)
                summary.isAi -> Text(
                    text = "AI 요약 · 집계 수치만 사용함",
                    color = JitColor.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            text = summary.text,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun Tiles(report: WeeklyReportView) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tile(
            value = report.onTimeCountLabel,
            valueColor = toneColor(report.onTimeTone),
            label = report.onTimeLabel,
            sub = report.onTimeSubLabel,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("report_on_time_count"),
        )
        Tile(
            value = report.slackValueLabel,
            valueColor = toneColor(report.slackTone),
            label = report.slackLabel,
            sub = report.slackSubLabel,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun Tile(
    value: String,
    valueColor: Color,
    label: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    JitCard(modifier = modifier, padding = 14.dp, gap = 3.dp) {
        Text(text = value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = JitColor.TextSecondary, fontSize = 11.sp)
        sub?.let { Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp) }
    }
}

/**
 * 요일별 도착. 기준선 위는 일찍, 아래는 늦게 도착한 분이다.
 *
 * 대표값은 그날 가장 작은 여유다. 일정만 있고 도착 기록이 없는 날은 회색 점, 일정이
 * 없는 날은 비운다 — **0 으로 그리지 않는다**(완벽했던 것처럼 보인다).
 */
@Composable
private fun DaysCard(
    report: WeeklyReportView,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
) {
    JitCard(padding = 14.dp, gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("요일별 도착", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(report.daysCoverageLabel, color = JitColor.TextSecondary, fontSize = 10.sp)
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            report.days.forEachIndexed { index, day ->
                DayCell(
                    day = day,
                    index = index,
                    enabled = enabled,
                    onOpenDay = onOpenDay,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text = "위는 일찍, 아래는 늦게 도착한 분. 요일을 누르면 그날 상세를 봄",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

private val BAR_AREA = 64.dp
private val BAR_HALF = 32.dp

@Composable
private fun DayCell(
    day: ReportDay,
    index: Int,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when (day.status) {
        DayStatus.ON_TIME -> JitColor.Green
        DayStatus.LATE -> JitColor.Red
        else -> JitColor.TextSecondary
    }
    val tap = if (day.clickable) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "그날 상세 보기") {
                onOpenDay(day.date, day.representative?.eventId)
            }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .testTag("report_day_$index")
            .then(tap)
            .semantics(mergeDescendants = true) { contentDescription = day.description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(BAR_AREA)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JitColor.Track)
            )
            val minutes = day.slackMinutes
            when {
                minutes != null -> {
                    val height = (3 + 29 * min(abs(minutes), 30) / 30f).dp
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            // 정시는 기준선에 바닥을, 지각은 기준선에 머리를 댄다.
                            .offset(y = if (minutes >= 0) BAR_HALF - height else BAR_HALF)
                            .width(14.dp)
                            .height(height)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                    )
                }

                day.status == DayStatus.UNOBSERVED -> Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = BAR_HALF - 8.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(JitColor.TextSecondary)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        // 낭독은 칸 전체의 설명이 맡는다. 글자 하나씩 따로 읽지 않게 숨긴다.
        Text(
            text = day.label,
            color = if (day.status == DayStatus.NONE) JitColor.Track else JitColor.TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = day.valueLabel,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** 지각한 날. 줄을 누르면 그 일정의 ⑨ 로 간다. */
@Composable
private fun LateDaysCard(
    report: WeeklyReportView,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
) {
    JitCard(padding = 14.dp, gap = 4.dp) {
        Text(
            text = "지각한 날 · ${report.lateCount}건",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        report.lateDays.forEach { line -> LateDayRow(line, enabled, onOpenDay) }
        if (report.hasUnknownCause) {
            Text(
                text = "\"원인 측정 못 함\"은 그 요인이 괜찮았다는 뜻이 아니라 기록이 없다는 뜻임",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun LateDayRow(
    line: LateDayLine,
    enabled: Boolean,
    onOpenDay: (LocalDate, Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("report_late_${line.eventId}")
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "그날 상세 보기") {
                onOpenDay(line.date, line.eventId)
            }
            .semantics(mergeDescendants = true) { contentDescription = line.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = line.dateLabel,
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = line.causeTitle, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        Text(
            text = line.lateLabel,
            color = JitColor.Red,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(6.dp))
        // 장식이다. 줄 전체가 버튼이라 따로 읽지 않는다.
        Text(
            text = "›",
            color = JitColor.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun DeviationCard(report: WeeklyReportView) {
    JitCard(padding = 14.dp, gap = 8.dp) {
        Text("계획 대비 실제 · 이번 주 평균", color = JitColor.TextSecondary, fontSize = 11.sp)
        report.deviations.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line.label, color = JitColor.TextPrimary, fontSize = 12.sp)
                ToneText(line.valueLabel, line.tone, fontSize = 12.sp)
            }
        }
        report.learningLabel?.let {
            HorizontalDivider(color = JitColor.Track)
            JitDotLabel(text = "기록 반영", dotColor = JitColor.Blue, fontSize = 11)
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
    }
}

/**
 * 예측 검증 · 최근 90일. 판정만 보여준다 — 구간별 표는 ⑨ 에 있다.
 *
 * 한 주는 표본이 적어 거의 늘 "표본 부족" 이라, 이 주 캘리브레이션 대신 90일을 쓴다.
 */
@Composable
private fun VerdictCard(view: CalibrationView) {
    JitCard(
        padding = 14.dp,
        gap = 6.dp,
        accented = view.verdict == CalibrationView.WIRE_OVERCONFIDENT,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("예측 검증 · 최근 90일", color = JitColor.TextSecondary, fontSize = 11.sp)
            JitChip(view.verdictLabel, verdictColor(view.verdict))
        }
        Text(
            text = view.verdictNote,
            color = if (view.isTrustworthy) JitColor.TextPrimary else JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        view.action?.let { Text(text = "→ $it", color = JitColor.Accent, fontSize = 10.sp) }
        Text(text = view.countsLabel, color = JitColor.TextSecondary, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 1500, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun WeeklyReportPreview() {
    JitTheme {
        WeeklyReportScreen(
            report = ReportPreviewData.exampleWeek,
            longTerm = ReportPreviewData.longTerm,
            loading = false,
            error = null,
            onBack = {},
            onPreviousWeek = {},
            onNextWeek = {},
            onRetry = {},
            onOpenDay = { _, _ -> },
        )
    }
}

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun WeeklyReportNoRecordsPreview() {
    JitTheme {
        WeeklyReportScreen(
            report = ReportPreviewData.unrecordedWeek,
            longTerm = ReportPreviewData.thinLongTerm,
            loading = false,
            error = null,
            onBack = {},
            onPreviousWeek = {},
            onNextWeek = {},
            onRetry = {},
            onOpenDay = { _, _ -> },
        )
    }
}
