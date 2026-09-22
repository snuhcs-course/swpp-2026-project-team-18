package com.swpp.wakeup.ui.report

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
import com.swpp.wakeup.domain.model.CalibrationPoint
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.LateCauseLine
import com.swpp.wakeup.domain.model.WeekdayLoad
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitProgressBar
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 주간 리포트. Figma ⑨.
 *
 * ## 이 화면은 앱을 변호하지 않는다
 *
 * 앱은 "정시 도착 확률 92%" 라고 말한다. 그 말이 맞는지 확인해 주는 곳이 없으면
 * 사용자는 숫자를 믿을 근거가 없다. 캘리브레이션 카드가 **앱이 약속한 확률과
 * 실제 정시율을 나란히** 놓는다. 과신하고 있으면 그렇게 적는다.
 *
 * ## 좋게 보이게 가공하지 않는다
 *
 * - 결과를 모르는 아침 수를 감추지 않는다. 그 수가 크면 정시율 자체가 적은
 *   표본에 기댄 것이다.
 * - 가장 아슬아슬했던 아침을 평균과 함께 보여준다. 정시였어도 여유가 1분이면
 *   운이 좋았던 것이다.
 * - 표본이 부족하면 판정하지 않는다. "잘 맞음" 을 2건으로 말하면 사용자가 그
 *   말을 믿고 여유를 줄인다.
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
            enabled = !loading,
            onPrevious = onPreviousWeek,
            onNext = onNextWeek,
        )

        SummaryCard(report)

        if (report.observationGapMatters) {
            // 관측 공백이 결론을 흔들 만큼 크면 정시율을 앞세우지 않는다.
            NoticeCard(
                dot = JitColor.Amber,
                title = "측정하지 못한 아침 ${report.unobservedCount}건",
                body = "도착을 기록하지 못한 아침은 정시로 세지 않음. 위 비율은 " +
                    "${report.arrivedCount}건만 반영한 값임",
            )
        }

        WeekdayCard(report.weekdays)

        CalibrationCard(
            title = "이 주 캘리브레이션",
            view = report.calibration,
        )

        // 한 주는 표본이 적어 대개 "표본 부족" 이다. 과신 여부는 긴 창으로 봐야
        // 알 수 있어서 전체 기간을 함께 보여준다.
        longTerm?.let {
            CalibrationCard(title = "전체 기간 (최근 90일)", view = it)
        }

        if (report.causes.isNotEmpty()) {
            CausesCard(report.causes)
        }
    }
}

@Composable
private fun WeekSwitcher(
    rangeLabel: String,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowButton("‹", enabled, onPrevious)
        Text(
            text = rangeLabel,
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        ArrowButton("›", enabled, onNext)
    }
}

@Composable
private fun ArrowButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        color = if (enabled) JitColor.TextPrimary else JitColor.TextSecondary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

@Composable
private fun SummaryCard(report: WeeklyReportView) {
    JitCard(padding = 16.dp, gap = 9.dp) {
        if (!report.hasArrivals) {
            JitDotLabel(
                text = "이 주에는 측정된 아침이 없음",
                dotColor = JitColor.TextSecondary,
                fontSize = 13,
                dotSize = 8.dp,
            )
            Text(
                text = if (report.eventCount == 0) {
                    "이 주에 일정이 없었음"
                } else {
                    "일정 ${report.eventCount}건이 있었지만 도착을 기록하지 못했음. " +
                        "알람을 해제한 뒤 이동하면 자동으로 기록됨"
                },
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
            return@JitCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = report.onTimeLabel ?: "정시 도착 —",
                color = JitColor.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${report.onTimeCount} / ${report.arrivedCount}건",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        }

        report.onTimeFraction?.let {
            JitProgressBar(
                fraction = it,
                color = if (it >= 0.9f) JitColor.Green else JitColor.Amber,
            )
        }

        HorizontalDivider(color = JitColor.Track)

        report.medianSlackLabel?.let {
            Text(it, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        // 평균만 보여주면 "한 번은 1분 남았다" 가 묻힌다.
        report.tightestSlackLabel?.let {
            Text(it, color = JitColor.Amber, fontSize = 11.sp)
        }
        if (report.lateCount > 0) {
            Text(
                text = "지각 ${report.lateCount}건",
                color = JitColor.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 요일별 지각.
 *
 * "화요일마다 늦는다" 같은 패턴이 보여야 행동이 바뀐다. 일정이 없는 요일은
 * **0% 로 그리지 않는다** — 완벽했던 것처럼 보인다.
 */
@Composable
private fun WeekdayCard(rows: List<WeekdayLoad>) {
    JitCard(padding = 14.dp, gap = 9.dp) {
        Text("요일별", color = JitColor.TextSecondary, fontSize = 11.sp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            rows.forEach { row -> WeekdayColumn(row) }
        }

        Text(
            text = "막대가 높을수록 그 요일에 지각이 많았음. 일정이 없던 요일은 비어 있음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun WeekdayColumn(row: WeekdayLoad) {
    val fraction = row.lateFraction
    val barHeight = when {
        fraction == null -> 0
        else -> (4 + fraction * 32).toInt()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(36.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (fraction != null) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (row.late == 0) JitColor.Green else JitColor.Red
                        )
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = row.label,
            color = if (fraction == null) JitColor.Track else JitColor.TextSecondary,
            fontSize = 10.sp,
        )
        Text(
            text = if (row.total == 0) "-" else "${row.late}/${row.total}",
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
        )
    }
}

/**
 * 캘리브레이션 카드.
 *
 * 각 줄이 "앱이 약속한 확률" 과 "실제 정시율" 을 나란히 보여준다. 실제가
 * 약속보다 낮으면 빨강으로 적는다 — 그게 과신이고, 알람이 늦게 잡혔다는 뜻이다.
 */
@Composable
private fun CalibrationCard(title: String, view: CalibrationView) {
    JitCard(padding = 14.dp, gap = 9.dp, accented = view.verdict == "overconfident") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = JitColor.TextSecondary, fontSize = 11.sp)
            JitChip(view.verdictLabel, verdictColor(view.verdict))
        }

        Text(
            text = view.verdictNote,
            color = if (view.isTrustworthy) JitColor.TextPrimary else JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        view.action?.let {
            Text(text = "→ $it", color = JitColor.Accent, fontSize = 10.sp)
        }

        if (view.hasData) {
            HorizontalDivider(color = JitColor.Track)
            view.points.forEach { point -> CalibrationRow(point) }
        }

        if (view.unscoredCount > 0) {
            Text(
                text = "채점할 수 없었던 아침 ${view.unscoredCount}건 (도착 기록 없음)",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun CalibrationRow(point: CalibrationPoint) {
    // 표본이 부족한 줄은 흐리게. 한 번 지각한 것과 계통 오차는 다르다.
    val emphasis = if (point.reliable) 1f else 0.55f
    val actualColor = when {
        !point.reliable -> JitColor.TextSecondary
        point.overconfident -> JitColor.Red
        else -> JitColor.Green
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "약속 ${point.rangeLabel}",
                color = JitColor.TextSecondary.copy(alpha = emphasis),
                fontSize = 11.sp,
            )
            Text(
                text = point.actual?.let { "실제 ${(it * 100).toInt()}%" } ?: "실제 —",
                color = actualColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 두 막대를 위아래로 겹쳐 놓는다. 약속이 위, 실제가 아래다. 아래가
        // 짧으면 과신이라는 것이 한눈에 보인다.
        JitProgressBar(
            fraction = point.promised,
            color = JitColor.Blue.copy(alpha = emphasis),
            height = 5.dp,
        )
        JitProgressBar(
            fraction = point.actual ?: 0f,
            color = actualColor.copy(alpha = emphasis),
            height = 5.dp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = point.countLabel,
                color = JitColor.TextSecondary.copy(alpha = emphasis),
                fontSize = 9.sp,
            )
            if (!point.reliable) {
                Text(
                    text = "표본 부족",
                    color = JitColor.TextSecondary,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/**
 * 지각 원인.
 *
 * 측정하지 못한 아침은 **"원인을 측정하지 못했음"** 이라고 적는다. "계획이
 * 짧았다" 로 합치면 추적이 동작하지 않은 것을 앱 계산 탓으로 읽게 된다.
 */
@Composable
private fun CausesCard(causes: List<LateCauseLine>) {
    JitCard(padding = 14.dp, gap = 9.dp) {
        Text("지각한 아침", color = JitColor.TextSecondary, fontSize = 11.sp)

        causes.forEach { cause ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = cause.headline,
                        color = if (cause.unknown) JitColor.TextSecondary
                        else JitColor.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = cause.lateLabel,
                        color = JitColor.Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(text = cause.detail, color = JitColor.TextSecondary, fontSize = 10.sp)
            }
        }

        Text(
            text = "\"측정 안 됨\" 은 그 요인이 괜찮았다는 뜻이 아니라 재료가 없다는 뜻임. " +
                "위치 권한이 있으면 자동으로 쌓임",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

private fun verdictColor(verdict: String): Color = when (verdict) {
    "calibrated" -> JitColor.Green
    "overconfident" -> JitColor.Red
    "conservative" -> JitColor.Amber
    else -> JitColor.TextSecondary
}

@Composable
private fun NoticeCard(dot: Color, title: String, body: String) {
    JitCard(padding = 14.dp, gap = 5.dp) {
        JitDotLabel(text = title, dotColor = dot, fontSize = 12, dotSize = 7.dp)
        Text(body, color = JitColor.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    JitCard(
        modifier = Modifier.clickable(onClick = onRetry),
        padding = 14.dp,
        gap = 6.dp,
    ) {
        JitDotLabel(
            text = "불러오지 못했음",
            dotColor = JitColor.Red,
            fontSize = 12,
            dotSize = 7.dp,
        )
        Text(text = message, color = JitColor.TextSecondary, fontSize = 11.sp)
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
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

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 1300, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun WeeklyReportPreview() {
    JitTheme {
        WeeklyReportScreen(
            report = WeeklyReportView(
                rangeLabel = "9월 8일 ~ 9월 14일",
                eventCount = 9,
                arrivedCount = 7,
                unobservedCount = 2,
                onTimeCount = 5,
                lateCount = 2,
                onTimeLabel = "정시 도착 71%",
                onTimeFraction = 0.71f,
                medianSlackLabel = "중간 여유 9분 전 도착",
                tightestSlackLabel = "가장 아슬아슬했던 아침 1분 전 도착",
                weekdays = listOf(
                    WeekdayLoad("월", 2, 0),
                    WeekdayLoad("화", 2, 2),
                    WeekdayLoad("수", 1, 0),
                    WeekdayLoad("목", 2, 0),
                    WeekdayLoad("금", 0, 0),
                    WeekdayLoad("토", 0, 0),
                    WeekdayLoad("일", 0, 0),
                ),
                causes = listOf(
                    LateCauseLine(
                        eventId = 1,
                        lateLabel = "12분 늦음",
                        headline = "이동이 12분 더 걸렸음",
                        detail = "준비 측정 안 됨 · 출발 +0분 · 이동 +12분",
                        unknown = false,
                    ),
                    LateCauseLine(
                        eventId = 2,
                        lateLabel = "5분 늦음",
                        headline = "원인을 측정하지 못했음",
                        detail = "준비 측정 안 됨 · 출발 측정 안 됨 · 이동 측정 안 됨",
                        unknown = true,
                    ),
                ),
                calibration = CalibrationView(
                    points = listOf(
                        CalibrationPoint("90~95%", 0.925f, 0.5f, 4, true, "4번 중 2번 정시", true),
                        CalibrationPoint("95~100%", 0.975f, 1.0f, 2, false, "2번 중 2번 정시", false),
                    ),
                    scoredCount = 6,
                    unscoredCount = 2,
                    verdictLabel = "과신 중",
                    verdictNote = "앱이 약속한 것보다 실제 정시율이 낮음. 알람이 늦게 잡히고 있음",
                    action = "지각 위험을 낮추면 알람이 앞당겨짐",
                    verdict = "overconfident",
                ),
            ),
            longTerm = CalibrationView(
                points = listOf(
                    CalibrationPoint("90~95%", 0.925f, 0.88f, 24, true, "24번 중 21번 정시", true),
                ),
                scoredCount = 24,
                unscoredCount = 5,
                verdictLabel = "잘 맞음",
                verdictNote = "앱이 말한 확률과 실제 정시율이 거의 같음",
                action = null,
                verdict = "calibrated",
            ),
            loading = false,
            error = null,
            onBack = {},
            onPreviousWeek = {},
            onNextWeek = {},
            onRetry = {},
        )
    }
}
