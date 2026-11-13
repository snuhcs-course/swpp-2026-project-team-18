// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.ProbabilityRule
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.domain.model.RiskOptionsView
import com.swpp.wakeup.ui.help.InfoButton
import com.swpp.wakeup.ui.common.JitTimeWheel
import com.swpp.wakeup.ui.common.SheetButton
import com.swpp.wakeup.ui.common.TimeWheelMath
import com.swpp.wakeup.ui.common.probabilityColor
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 알람 시각 바꾸기 바텀시트(iter4 H2-3, Figma `546:1595`).
 *
 * 위는 "빠르게 고르기" 세 칸(서버가 준 risk-options 그대로, 라벨만 "여유 있게·보통·빠듯하게"),
 * 아래는 5분 단위 휠과 결과 한 줄이다. ⑤ 지각 위험 화면을 대신한다.
 *
 * **저장되는 것은 세 칸뿐이다.** 서버에는 아직 임의 시각을 저장할 길이 없어서(백엔드 BE-H3b),
 * 휠은 고른 시각의 확률을 **미리 보는** 자리로 두고, 세 칸 중 하나와 같은 시각일 때만 [확인]
 * 이 켜진다. 세 칸을 누르면 휠도 그 시각으로 움직인다.
 *
 * @param recommendedKey "추천" 을 붙일 칸. 일정 종류 기본값에서 온 τ 다
 * @param goalTau 색을 가르는 목표치. 적용 중인 τ
 * @param initialMinutesOfDay 휠이 처음 가리킬 시각(지금 알람). 없으면 추천 칸
 * @param startMinutesOfDay 일정 시작 시각(분). 휠로 고른 시각의 확률을 낼 때 쓴다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmTimeSheet(
    subtitle: String?,
    options: RiskOptionsView?,
    onConfirm: (key: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    recommendedKey: String? = null,
    goalTau: Double? = null,
    initialMinutesOfDay: Int? = null,
    startMinutesOfDay: Int? = null,
    loading: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    /** 제목 줄 오른쪽 i. 도움말 › "여유 있게·보통·빠듯하게는 뭐가 달라요?" 를 펼쳐 연다(⑤ 에 있던 i) */
    onHelp: (() -> Unit)? = null,
) {
    val cells = options?.options.orEmpty()
    val recommended = recommendedKey?.let { key -> cells.firstOrNull { it.key == key } }
        ?: cells.firstOrNull { it.key == RiskOptionView.KEY_NORMAL }
    val recommendedMinutes = recommended?.let { AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) }
    val start = initialMinutesOfDay ?: recommendedMinutes ?: DEFAULT_WHEEL_MINUTES

    var picked by rememberSaveable(start) {
        mutableIntStateOf(TimeWheelMath.snapMinute(start % 60, AlarmTimeChoice.MINUTE_STEP) + start / 60 * 60)
    }
    val pickedLabel = AlarmTimeChoice.clockLabel(picked)

    // 휠이 가리키는 시각과 같은 칸. 이 칸이 있어야 저장할 수 있다.
    val match = cells.firstOrNull { AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) == picked }
    val percent = match?.probability
        ?: startMinutesOfDay?.let {
            AlarmTimeChoice.estimate(
                options?.distribution,
                AlarmTimeChoice.availableMinutes(picked, it),
            )
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JitColor.Track),
            )
        },
        modifier = modifier.testTag("alarm_time_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("알람 시각 바꾸기", color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    subtitle?.let { Text(it, color = JitColor.TextSecondary, fontSize = 12.sp) }
                }
                // Figma 시트에는 없지만, ⑤ 를 지우면서 "단계는 뭐가 달라요?" 로 가는 길이 사라지지 않게 둔다.
                onHelp?.let {
                    InfoButton(
                        contentDescription = "단계는 뭐가 달라요?",
                        tag = "alarm_time_info",
                        onClick = it,
                        iconSize = 18,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("빠르게 고르기", color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                when {
                    cells.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cells.forEach { option ->
                            QuickPickCell(
                                option = option,
                                recommended = option.key == recommended?.key,
                                selected = option.key == match?.key,
                                goalTau = goalTau,
                                enabled = !saving,
                                onClick = {
                                    AlarmTimeChoice.minutesOfDay(option.alarmAt, option.meridiem)
                                        ?.let { picked = it }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    loading -> Text("고를 시각을 계산하고 있어요", color = JitColor.TextSecondary, fontSize = 12.sp)

                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = options?.unavailableReason
                                ?: "지금은 고를 시각을 계산하지 못했어요",
                            color = JitColor.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("alarm_time_unavailable"),
                        )
                        Text(
                            text = "다시 시도",
                            color = JitColor.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .testTag("alarm_time_retry")
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onRetry)
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("직접 정하기", color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                JitTimeWheel(
                    hour24 = picked / 60,
                    minute = picked % 60,
                    minuteStep = AlarmTimeChoice.MINUTE_STEP,
                    onChange = { hour, minute -> picked = AlarmTimeChoice.minutesOfDay(hour, minute) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ResultLine(pickedLabel, percent, goalTau, recommendedMinutes, picked)
            }

            error?.let { Text(it, color = JitColor.Red, fontSize = 12.sp) }
            // 세 칸 밖의 시각은 아직 서버에 저장할 수 없다. 버튼만 흐려 두면 고장으로 읽혀서 이유를 적는다.
            if (match == null && cells.isNotEmpty()) {
                Text(
                    text = "직접 정한 시각으로 맞추는 기능은 준비 중이에요. 위 세 칸 중 하나를 골라 주세요.",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("alarm_time_pending"),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(
                    label = "취소",
                    container = JitColor.Surface2,
                    content = JitColor.TextPrimary,
                    tag = "alarm_time_cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    label = AlarmTimeChoice.confirmLabel(pickedLabel),
                    container = JitColor.Accent,
                    content = JitColor.Bg,
                    tag = "alarm_time_confirm",
                    onClick = { match?.let { onConfirm(it.key) } },
                    enabled = match != null && !saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 휠이 가리킬 기본 시각(오전 7:00). 지금 알람도 추천도 없을 때만 쓴다 */
private const val DEFAULT_WHEEL_MINUTES = 7 * 60

// Partly AI-generated (Claude)
/** 빠르게 고르기 한 칸: 시각 · 라벨(추천) · 확률(Figma `option 보통 (추천)`) */
@Composable
private fun QuickPickCell(
    option: RiskOptionView,
    recommended: Boolean,
    selected: Boolean,
    goalTau: Double?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag("alarm_time_quick_${option.key}")
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .then(
                if (selected) Modifier.border(1.dp, JitColor.Accent, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (option.meridiem == "PM") "오후" else "오전",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(option.alarmAt, color = JitColor.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = AlarmTimeChoice.quickLabel(option.key, option.label),
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (recommended) {
                Text("· 추천", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        // 확률이 없으면 자리를 비운다. 빈 0% 도 숫자처럼 읽힌다.
        Text(
            text = option.probability?.let { "$it%" } ?: "—",
            color = probabilityColor(option.probability, goalTau),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// Partly AI-generated (Claude)
/** 결과 한 줄: 고른 시각의 확률과 추천과의 차이(Figma `result`) */
@Composable
private fun ResultLine(
    pickedLabel: String,
    percent: Int?,
    goalTau: Double?,
    recommendedMinutes: Int?,
    pickedMinutes: Int,
) {
    val color = probabilityColor(percent, goalTau)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm_time_result")
            .padding(top = 2.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (percent == null) {
                Text(
                    text = AlarmTimeChoice.resultUnknown(pickedLabel),
                    color = JitColor.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Row {
                    Text(
                        text = AlarmTimeChoice.resultPrefix(pickedLabel),
                        color = JitColor.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = "$percent%", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            ProbabilityRule.warning(percent)?.let {
                Text(it, color = JitColor.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            AlarmTimeChoice.gapLine(pickedMinutes, recommendedMinutes)?.let {
                Text(it, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
