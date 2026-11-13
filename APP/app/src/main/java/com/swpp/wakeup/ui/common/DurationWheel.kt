// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 걸리는 시간 휠(iter4 H2-5 ⑮-a, Figma `547:1718`)의 계산. 시간 · 분 두 열이고 [range] 밖으로는 굴러가지 않는다.
 *
 * 분 열은 고른 시간에 따라 줄어든다: 준비 시간(5분~4시간)이면 0시간 → 5~59분, 1~3시간 → 0~59분, 4시간 → 0분만.
 * 시간을 바꿔 분이 범위를 벗어나면 가장 가까운 끝으로 붙인다(0시간 3분 → 0시간 5분).
 */
internal object DurationWheelMath {

    /** 시간 열의 값. 5..240 → 0..4 */
    fun hours(range: IntRange): IntRange = range.first / 60..range.last / 60

    /** [hour] 시간일 때 분 열의 값 */
    fun minutesFor(hour: Int, range: IntRange): IntRange =
        (range.first - hour * 60).coerceAtLeast(0)..(range.last - hour * 60).coerceAtMost(59)

    /** 시간 · 분 → 합계(분). 분은 그 시간의 분 열 안으로 붙인다 */
    fun combine(hour: Int, minute: Int, range: IntRange): Int {
        val h = hour.coerceIn(hours(range))
        return h * 60 + minute.coerceIn(minutesFor(h, range))
    }

    fun guide(range: IntRange): String =
        "${durationLabel(range.first)}부터 ${durationLabel(range.last)}까지, 1분 단위로 정할 수 있어요"
}

/**
 * 준비 시간 고르기 시트(⑮-a). 고른 값 큰 글자("1시간 5분") · 시간 · 분 휠 · [취소] [확인].
 *
 * - [minutes] 가 있으면 그 값에서 연다. 없으면 [range] 의 처음(0시간 5분)에서 열되, 한 번 굴리기 전엔
 *   [확인] 이 꺼져 있다 — 값을 미리 채우지 않는다는 ⑮ 의 원칙(그대로 확인하면 고르지 않은 값이 들어간다).
 * - 범위 밖으로는 굴러가지 않으니 범위 밖 오류 상태(예전 다이얼의 빨간 안내, Figma `352:65`)는 없다.
 */
@Composable
internal fun JitDurationWheelSheet(
    minutes: Int?,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "준비 시간",
    subtitle: String? = "일어나서 집을 나서기까지",
) {
    var total by rememberSaveable { mutableIntStateOf((minutes ?: range.first).coerceIn(range)) }
    var touched by rememberSaveable { mutableStateOf(minutes != null) }
    val hours = DurationWheelMath.hours(range)
    val hour = total / 60
    val minute = total % 60

    WheelSheetFrame(
        title = title,
        subtitle = subtitle,
        tag = "duration_wheel_sheet",
        guide = DurationWheelMath.guide(range),
        confirmEnabled = touched,
        onConfirm = { onConfirm(total) },
        onDismiss = onDismiss,
        readout = {
            Text(
                text = durationLabel(total),
                color = JitColor.Accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wheel_readout")
                    .semantics { contentDescription = durationLabel(total) },
            )
        },
    ) {
        WheelBox {
            WheelColumn(
                values = hours.map { "${it}시간" },
                selected = hour - hours.first,
                loop = false,
                label = "시간",
                tag = "wheel_prep_hour",
                onSelect = { index ->
                    total = DurationWheelMath.combine(hours.first + index, minute, range)
                    touched = true
                },
            )
            // 시간마다 분 열의 값이 달라지므로 시간이 바뀌면 열을 새로 만든다(굴림 위치도 새 값에서 시작).
            key(hour) {
                val mins = DurationWheelMath.minutesFor(hour, range)
                WheelColumn(
                    values = mins.map { "${it}분" },
                    selected = minute - mins.first,
                    loop = false,
                    label = "분",
                    tag = "wheel_prep_minute",
                    onSelect = { index ->
                        total = hour * 60 + mins.first + index
                        touched = true
                    },
                )
            }
        }
    }
}

// AI-generated (Claude)
/** 분 → 사람 말. 35 → "35분", 120 → "2시간", 65 → "1시간 5분" */
internal fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}분"
        rest == 0 -> "${hours}시간"
        else -> "${hours}시간 ${rest}분"
    }
}
