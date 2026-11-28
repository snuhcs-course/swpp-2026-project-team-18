// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Spacer
import com.swpp.wakeup.domain.model.UserAlarmRule
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
 * **휠로 고른 시각을 그대로 저장한다**(서버 0.21.0, BE-H3b). 세 칸과 같은 시각이면 예전처럼 그 칸(τ)으로,
 * 아니면 직접 정한 시각(`PUT …/alarm/time`)으로 저장한다 — 길은 [UserAlarmRule.choose] 가 정한다.
 * 서버가 0.21.0 미만이면 세 칸만 저장되고, 휠이 칸 밖이면 버튼을 끄고 이유 한 줄([UserAlarmRule.LOCKED_NOTE])을
 * 보인다. 직접 정한 알람이면 위에 "직접 정한 시각" 과 [추천 시각으로 되돌리기] 가 붙는다.
 * 세 칸을 누르면 휠도 그 시각으로 움직인다.
 *
 * @param recommendedKey "추천" 을 붙일 칸. 일정 종류 기본값에서 온 τ 다
 * @param goalTau 색을 가르는 목표치. 적용 중인 τ
 * @param initialMinutesOfDay 휠이 처음 가리킬 시각(지금 알람). 없으면 추천 칸
 * @param startMinutesOfDay 일정 시작 시각(분). 휠로 고른 시각의 확률을 낼 때 쓴다
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
    /** 지금 알람([initialMinutesOfDay])에 서버가 준 정시 도착 확률. 홈 카드 · 알람 화면과 같은 값이다 */
    currentProbability: Int? = null,
    loading: Boolean = false,
    saving: Boolean = false,
    canWrite: Boolean = true,
    error: String? = null,
    onRetry: () -> Unit = {},
    /** 제목 줄 오른쪽 i. 도움말 › "여유 있게·보통·빠듯하게는 뭐가 달라요?" 를 펼쳐 연다(⑤ 에 있던 i) */
    onHelp: (() -> Unit)? = null,
    /** 서버가 직접 정한 시각을 받는가(0.21.0 이상, [UserAlarmRule.supported]) */
    customSupported: Boolean = false,
    /** 세 칸 밖의 시각(0~1439 분)으로 맞추기 */
    onConfirmCustom: (minutesOfDay: Int) -> Unit = {},
    /** 지금 알람이 직접 정한 시각인가(서버 `alarm_source == "user"`) */
    userAlarm: Boolean = false,
    /** [추천 시각으로 되돌리기]. [userAlarm] 이고 [customSupported] 일 때만 보인다 */
    onRevert: () -> Unit = {},
    /** An explicit risk preset can also be reset to the event's true default. */
    canRestoreDefault: Boolean = false,
    /** Scope edits to an event, independently from an asynchronously arriving plan clock. */
    selectionKey: Long? = null,
) {
    val quickPicks = AlarmTimeChoice.quickPicks(options, recommendedKey ?: options?.recommendedKey)
    val cells = quickPicks.options
    val recommended = quickPicks.recommendedKey?.let { key -> cells.firstOrNull { it.key == key } }
    val recommendedMinutes = recommended?.let { AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) }
    val start = initialMinutesOfDay ?: recommendedMinutes ?: DEFAULT_WHEEL_MINUTES

    var picked by rememberSaveable(selectionKey) { mutableIntStateOf(start) }
    var selectedPresetKey by rememberSaveable(selectionKey) { mutableStateOf<String?>(null) }
    var choiceTouched by rememberSaveable(selectionKey) { mutableStateOf(false) }
    // A late initial plan can initialize an untouched wheel, but must not undo an explicit edit.
    LaunchedEffect(selectionKey, start) { if (!choiceTouched) picked = start }
    val step = AlarmTimeChoice.wheelStep(
        cells.map { AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) } +
            initialMinutesOfDay + picked.takeIf { choiceTouched },
    )
    val original = options?.options.orEmpty()
    val preferredPresetKey = if (choiceTouched) selectedPresetKey else {
        options?.selectedKey?.takeIf { key -> !userAlarm && original.any {
            it.key == key && AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) == picked
        } }
    }
    val manualSelection = if (choiceTouched) selectedPresetKey == null else userAlarm
    val saveCells = (cells + original.filter { it.key == preferredPresetKey }).distinctBy { it.key }
    val pickedLabel = AlarmTimeChoice.clockLabel(picked)

    // 휠이 가리키는 시각과 같은 칸. 있으면 그 칸(τ)으로, 없으면 직접 정한 시각으로 저장한다.
    val match = saveCells.firstOrNull { it.key == preferredPresetKey && AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) == picked }
        ?: cells.firstOrNull { AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) == picked }
    val proposedSave = UserAlarmRule.choose(
        picked = picked,
        presets = saveCells.associate { it.key to AlarmTimeChoice.minutesOfDay(it.alarmAt, it.meridiem) },
        supported = customSupported,
        startMinutes = startMinutesOfDay,
        preferredPresetKey = preferredPresetKey,
    )
    // A custom wheel time remains custom, even if it happens to equal a preset clock.
    val save = if (manualSelection && customSupported && proposedSave is UserAlarmRule.Save.Preset) {
        UserAlarmRule.Save.Custom(picked)
    } else proposedSave
    // 서버가 준 값만 보인다: 지금 알람이면 그 알람의 확률, 세 칸이면 그 칸의 확률, 그 밖이면 비운다(11-19).
    val percent = AlarmTimeChoice.shownProbability(
        picked = picked,
        current = initialMinutesOfDay,
        currentProbability = currentProbability,
        cellProbability = match?.probability,
        startMinutes = startMinutesOfDay,
        table = options?.probabilityByMinutesBefore,
    )

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
        modifier = modifier.testTag("alarm_time_sheet").semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                // (직접 정한 시각 줄은 제목 줄 아래에 따로 그린다)
                onHelp?.let {
                    InfoButton(
                        contentDescription = "단계는 뭐가 달라요?",
                        tag = "alarm_time_info",
                        onClick = it,
                        iconSize = 18,
                    )
                }
            }

            // 직접 정한 알람이면 그렇다고 말하고 되돌리는 길을 둔다(Figma `546:2398`).
            if (userAlarm || canRestoreDefault) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("alarm_time_user"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (userAlarm) UserAlarmRule.USER_LABEL else "직접 고른 단계",
                        color = JitColor.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(JitColor.Surface2)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    if (customSupported) {
                        com.swpp.wakeup.ui.common.JitSecondaryButton(
                            label = "기본 추천으로 되돌리기",
                            onClick = onRevert,
                            enabled = !saving && !loading && canWrite,
                            modifier = Modifier.fillMaxWidth().testTag("alarm_time_revert"),
                        )
                    }
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
                                        ?.let { picked = it; selectedPresetKey = option.key; choiceTouched = true }
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
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onRetry)
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }

            if (quickPicks.collapsed) {
                Text("차이가 작은 시각은 하나로 묶었어요", color = JitColor.TextSecondary,
                    fontSize = 12.sp, modifier = Modifier.testTag("alarm_time_collapsed"))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("직접 정하기", color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                // 칸을 누르면 휠도 그 시각으로 굴러간다(JitTimeWheel 이 바뀐 값을 따라간다). 간격이 바뀌면 열을 새로 만든다.
                key(step) { JitTimeWheel(
                    hour24 = picked / 60,
                    minute = picked % 60,
                    minuteStep = step,
                    onChange = { hour, minute ->
                        val changed = AlarmTimeChoice.minutesOfDay(hour, minute)
                        if (changed != picked) { selectedPresetKey = null; choiceTouched = true }
                        picked = changed
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) }
                ResultLine(pickedLabel, percent, goalTau, recommendedMinutes, picked)
            }

            if (loading && cells.isNotEmpty()) {
                Text("현재 알람 시각을 확인하고 있어요", color = JitColor.TextSecondary,
                    fontSize = 12.sp, modifier = Modifier.testTag("alarm_time_loading"))
            }
            error?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, color = JitColor.Red, fontSize = 12.sp,
                        modifier = Modifier.testTag("alarm_time_error"))
                    if (cells.isNotEmpty()) {
                        com.swpp.wakeup.ui.common.JitSecondaryButton(
                            label = "다시 시도", onClick = onRetry, enabled = !loading && !saving,
                            modifier = Modifier.fillMaxWidth().testTag("alarm_time_retry"),
                        )
                    }
                }
            }
            // 서버가 아직 직접 정한 시각을 받지 않으면(0.21.0 미만) 세 칸 밖은 저장할 수 없다.
            // 버튼만 흐려 두면 고장으로 읽혀서 이유를 적는다.
            if (save is UserAlarmRule.Save.Locked) {
                Text(
                    text = UserAlarmRule.LOCKED_NOTE,
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("alarm_time_pending"),
                )
            }
            // 일정 시작과 같거나 그 뒤에 울리는 시각은 맞추지 않는다(11-18). 위와 같이 버튼을 끄고 이유를 적는다.
            if (save is UserAlarmRule.Save.AfterStart) {
                Text(
                    text = UserAlarmRule.AFTER_START_NOTE,
                    color = JitColor.Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("alarm_time_after_start"),
                )
            }

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
                    onClick = {
                        when (save) {
                            is UserAlarmRule.Save.Preset -> onConfirm(save.key)
                            is UserAlarmRule.Save.Custom -> onConfirmCustom(save.minutesOfDay)
                            UserAlarmRule.Save.Locked, UserAlarmRule.Save.AfterStart -> Unit
                        }
                    },
                    enabled = (save is UserAlarmRule.Save.Preset || save is UserAlarmRule.Save.Custom) &&
                        !saving && !loading && canWrite,
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
                text = com.swpp.wakeup.domain.model.ClockText.meridiemOf(option.meridiem) ?: "",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(option.alarmAt, color = JitColor.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = AlarmTimeChoice.quickLabel(option.key, option.label),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (recommended) {
                Text("· 추천", color = JitColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        // 확률이 없으면 자리를 비운다. 빈 0% 도 숫자처럼 읽힌다.
        Text(
            text = option.probability?.let { ProbabilityRule.displayLabel(it) } ?: "—",
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
    // 확률을 아직 못 내면 이 줄을 두지 않는다(11-17 사용자: 설명 문구를 빼 달라고 함)
    if (percent == null) return
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
            run {
                Row {
                    Text(
                        text = AlarmTimeChoice.resultPrefix(pickedLabel),
                        color = JitColor.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = ProbabilityRule.displayLabel(percent), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            ProbabilityRule.warning(percent)?.let {
                Text(it, color = JitColor.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            AlarmTimeChoice.gapLine(pickedMinutes, recommendedMinutes)?.let {
                Text(it, color = JitColor.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
