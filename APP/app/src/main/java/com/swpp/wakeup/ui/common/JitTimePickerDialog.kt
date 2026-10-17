// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * 시계 다이얼 팝업(Material3 `TimePicker`). 시를 고르면 분 다이얼로 넘어간다.
 *
 * 일정 추가의 시작 시각(12시간제)과 준비 시간([JitDurationPickerDialog], 24시간제)이
 * 같이 쓴다. 다이얼을 두 벌 두면 색·버튼이 화면마다 달라진다.
 *
 * 기본 색이 밝아 어두운 팔레트와 충돌하므로 앱 팔레트로 칠한다. 내부 값은 0~23 시
 * 그대로다(`TimePickerState.hour`). 상태가 스냅숏이라 [confirmEnabled]·[supporting] 은
 * 다이얼을 돌리는 즉시 다시 계산된다.
 *
 * @param confirmEnabled 확인 버튼을 켤지. 꺼지면 글자가 흐려진다(Track 색)
 * @param supporting 다이얼과 버튼 사이에 넣을 한 줄
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JitTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    is24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: (hour: Int, minute: Int) -> Boolean = { _, _ -> true },
    supporting: (@Composable (hour: Int, minute: Int) -> Unit)? = null,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
    )
    val canConfirm = confirmEnabled(state.hour, state.minute)

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = JitColor.Surface2,
                    clockDialSelectedContentColor = JitColor.Bg,
                    clockDialUnselectedContentColor = JitColor.TextPrimary,
                    selectorColor = JitColor.Accent,
                    containerColor = JitColor.Surface,
                    periodSelectorBorderColor = JitColor.Track,
                    periodSelectorSelectedContainerColor = JitColor.Accent,
                    periodSelectorUnselectedContainerColor = JitColor.Surface2,
                    periodSelectorSelectedContentColor = JitColor.Bg,
                    periodSelectorUnselectedContentColor = JitColor.TextSecondary,
                    timeSelectorSelectedContainerColor = JitColor.Accent,
                    timeSelectorUnselectedContainerColor = JitColor.Surface2,
                    timeSelectorSelectedContentColor = JitColor.Bg,
                    timeSelectorUnselectedContentColor = JitColor.TextPrimary,
                ),
            )
            supporting?.invoke(state.hour, state.minute)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("취소", color = JitColor.TextSecondary) }
                TextButton(
                    onClick = { onConfirm(state.hour, state.minute) },
                    enabled = canConfirm,
                ) {
                    // 꺼진 확인은 "취소" 와 같은 색이면 구분되지 않는다. Track 으로 흐리게.
                    Text(
                        text = "확인",
                        color = if (canConfirm) JitColor.Accent else JitColor.Track,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 24시간 다이얼이 나타낼 수 있는 최대 분(23:59). */
private const val DIAL_MAX_MINUTES = 23 * 60 + 59

/**
 * 걸리는 시간을 고르는 다이얼. 24시간제 시:분 = 걸리는 시간이다(1:05 = 1시간 5분).
 *
 * - [minutes] 가 있으면 그 값에서, 없으면 0:00 에서 연다. 0:00 은 제안값이 아니다 —
 *   범위 밖이라 그대로 확인할 수 없다(값을 미리 채우지 않는다는 ⑮ 의 원칙).
 * - [range] 밖이면 확인이 꺼지고 범위를 알려 준다. **잘라 넣지 않는다.** 다이얼을
 *   건드리기 전에는 안내를 흐리게, 범위 밖으로 움직였을 때만 빨갛게 쓴다.
 * - 24시간 다이얼은 0~23시를 다 보여 준다. 23:00 처럼 범위를 넘는 값도 고를 수는
 *   있지만 확인되지 않는다.
 */
@Composable
fun JitDurationPickerDialog(
    minutes: Int?,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "준비 시간 · 시간:분",
) {
    val initial = (minutes ?: 0).coerceIn(0, DIAL_MAX_MINUTES)
    JitTimePickerDialog(
        title = title,
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = true,
        onConfirm = { hour, minute -> onConfirm(hour * 60 + minute) },
        onDismiss = onDismiss,
        confirmEnabled = { hour, minute -> hour * 60 + minute in range },
        supporting = { hour, minute ->
            val total = hour * 60 + minute
            val inRange = total in range
            Text(
                text = if (inRange) {
                    "총 ${durationLabel(total)}"
                } else {
                    "${durationLabel(range.first)}~${durationLabel(range.last)} 사이로 고름"
                },
                color = if (!inRange && total != initial) JitColor.Red else JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
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
