package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.events.SegmentPalette
import com.swpp.wakeup.ui.events.TimelineRow
import com.swpp.wakeup.ui.events.TransitLineChips
import com.swpp.wakeup.ui.events.clockLabel
import com.swpp.wakeup.ui.events.timelineRows
import com.swpp.wakeup.ui.theme.JitColor
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Partly AI-generated (Claude)
/** "경로 정류장" 카드 한 행. 화면이 바로 그리는 값만 담는다 */
data class CheckpointRow(
    val name: String,
    /** "출발" / "승차" / "환승" / "하차" / "도착" */
    val sub: String,
    /** 승차·환승 행의 노선 칩 */
    val line: RouteSegment?,
    /** "오전 8:22" / "8:27". 시각을 모르거나 도착 행이면 null */
    val timeLabel: String?,
    val isEnd: Boolean,
)

private val SHORT_CLOCK = DateTimeFormatter.ofPattern("h:mm")

/**
 * 기준 경로의 정류장 행. 시각은 **실제 출발 시각 + 카카오 구간 시간**이다. 출발 시각을 모르면
 * ([departedAtMillis] null) 시각은 전부 비우고 이름만 남긴다 — 지어낸 시각을 보여 주지 않는다.
 * 도착 행에는 시각을 달지 않는다(진행 카드의 도착 예상과 숫자가 둘이 되지 않게).
 * 대중교통 구간이 없으면 빈 목록이다.
 */
fun checkpointRows(
    segments: RouteSegments,
    destination: String,
    departedAtMillis: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CheckpointRow> {
    val depart = departedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
    var previous: LocalTime? = null
    return timelineRows(segments, origin = "출발", destination = destination, depart = depart).map { row ->
        val end = row.kind == TimelineRow.Kind.END
        val time = row.time?.takeIf { !end }
        val label = time?.let {
            val withMeridiem = previous == null || (previous!!.hour < 12) != (it.hour < 12)
            previous = it
            if (withMeridiem) clockLabel(it) else it.format(SHORT_CLOCK)
        }
        CheckpointRow(row.name, row.sub, row.line, label, end)
    }
}

// Partly AI-generated (Claude)
/**
 * 이동 중 "경로 정류장"(Figma ④-c 변형 `510:9`). 출발 → 승차 → 환승 → 하차 → 도착을 시각과 함께 적는다.
 *
 * @param timed 시각을 적었는가. 아니면 시각을 뺀 이유를 밝힌다
 */
@Composable
fun CheckpointCard(rows: List<CheckpointRow>, timed: Boolean, modifier: Modifier = Modifier) {
    JitCard(modifier = modifier.testTag("trip_checkpoints"), padding = 14.dp, gap = 9.dp) {
        Text("경로 정류장", color = JitColor.TextSecondary, fontSize = 11.sp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { CheckpointRowView(it) }
        }
        Text(
            text = if (timed) {
                "시각은 출발 시각에 카카오 구간 시간을 더한 예상"
            } else {
                "출발 시각을 몰라 시각은 생략함"
            },
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.testTag("trip_checkpoints_note"),
        )
    }
}

@Composable
private fun CheckpointRowView(row: CheckpointRow) {
    val dot = row.line?.let { Color(SegmentPalette.fill(it)) } ?: JitColor.Track
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                row.line?.let { TransitLineChips(it) }
            }
            Text(text = row.sub, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
        row.timeLabel?.let {
            Spacer(Modifier.width(8.dp))
            Text(text = it, color = JitColor.TextPrimary, fontSize = 12.sp)
        }
    }
}
