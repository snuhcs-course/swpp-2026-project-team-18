package com.swpp.wakeup.ui.home

import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.TripResume
import com.swpp.wakeup.domain.model.ProbabilityRule
import com.swpp.wakeup.ui.common.JitRetryButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swpp.wakeup.ui.common.rememberTextWidth
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.help.OnTimeProbabilitySheet
import com.swpp.wakeup.ui.help.OnTimeSheetSpec
import com.swpp.wakeup.ui.help.OnTimeSheetText
import com.swpp.wakeup.ui.onboarding.CoachTarget
import com.swpp.wakeup.ui.onboarding.coachmarkTarget
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Partly AI-generated (Claude)
/**
 * 홈 화면. Figma "HE 2-2 · ③ 홈 다시 그림 · 하단 탭" (node `540:123`, iter4 H2-2).
 *
 * 정보를 세 단계로 나눈다. 다음 알람(시각 · 어디로 · 몇 시까지)을 가장 크게, 다가오는 일정은
 * "시작 시각 · 무엇 · 어디 · 알람" 한 줄로, 근거와 설정은 카드나 줄을 눌러 ④ 에서 본다.
 * 예전 홈 아래에 쌓여 있던 입구(주간 리포트 · 캘린더 가져오기 · 아침 루틴 설정)는 리포트 · 내 정보
 * 탭으로 옮겼다. 아바타도 없다(설정은 내 정보 탭).
 *
 * **캘린더 격자가 아니다.** 일정을 위에서 아래로 흐르는 목록으로 보여준다.
 * 격자 한 칸에는 이 앱의 핵심 정보인 "알람 몇 시" 가 안 들어간다.
 *
 * **모든 데이터가 서버에서 온다.** 표본 데이터가 없다. 새 계정은 빈 목록이고
 * 사용자가 추가한 만큼만 보인다.
 */
@Composable
fun HomeScreen(
    state: HomeViewModel.UiState,
    onEventClick: (UpcomingEvent) -> Unit,
    onAlarmToggle: (Long, Boolean) -> Unit,
    onAddEventClick: () -> Unit,
    onSetHomeClick: () -> Unit,
    onMorningClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 오른쪽 위 ?(도움말, H2-4). null 이면 버튼을 그리지 않는다 */
    onHelpClick: (() -> Unit)? = null,
    /** 카드의 [시각 바꾸기]. null 이면 버튼을 그리지 않는다 */
    onChangeAlarmTime: ((UpcomingEvent) -> Unit)? = null,
    onMissedTrackClick: () -> Unit = {},
    onMissedDismiss: () -> Unit = {},
    /** 알람 전 "이 경로로 바꾸기"(다음 알람 카드) */
    onSwitchRoute: (UpcomingEvent) -> Unit = {},
    /** 지금 추적 중인 일정 id(`TripLiveState.trackingEventId`). 진행 중 카드의 단계를 정한다 */
    trackingEventId: Long? = null,
    /** 추적 중인 일정이 집을 나섰는가 */
    trackingInTransit: Boolean = false,
    /** 진행 중 카드의 [기록 다시 시작] */
    onRestartTracking: () -> Unit = {},
    /** 다음 알람의 "바꿀까요" 제안에 답하기(BE-H3d). true = 받아들이기 */
    onAnswerProposal: (UpcomingEvent, Boolean) -> Unit = { _, _ -> },
    proposalBusyId: Long? = null,
    notificationsEnabled: Boolean = true,
    onEnableNotifications: () -> Unit = {},
    onRoutineClick: (() -> Unit)? = null,
    onConfirmNextAlarm: ((UpcomingEvent) -> Unit)? = null,
    confirmBusyId: Long? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    // Partly AI-generated (Claude) — 확률 칩을 누르면 "정시 도착 확률이 뭐예요?"(iter4 H2-4, Figma `548:1798`)
    var onTimeFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val onTimeEvent = state.nextAlarm?.takeIf { it.id == onTimeFor }
    val onTimeSpec = onTimeEvent?.let(::onTimeSpecOf)
    if (onTimeEvent != null && onTimeSpec != null) {
        OnTimeProbabilitySheet(
            spec = onTimeSpec,
            tone = riskColor(onTimeEvent.risk),
            onChangeTime = onChangeAlarmTime?.let { change ->
                {
                    onTimeFor = null
                    change(onTimeEvent)
                }
            },
            onDismiss = { onTimeFor = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg),
    ) {
        LazyColumn(
            modifier = Modifier
                .testTag("home_list")
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
        ) {
            item(key = "header") {
                HomeHeader(today = state.today, onHelpClick = onHelpClick)
            }

            if (!notificationsEnabled) {
                item(key = "notifications-disabled") {
                    com.swpp.wakeup.ui.common.JitCard {
                        Text("알림이 꺼져 있어요", color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("알람을 놓치지 않도록 알림을 켜 주세요", color = JitColor.TextSecondary, fontSize = 12.sp)
                        com.swpp.wakeup.ui.common.JitSecondaryButton("알림 켜기", onEnableNotifications, Modifier.testTag("home_enable_notifications"))
                    }
                }
            }

            // 울리지 않은 알람. 출발 전에만 뜨고, 지금 추적을 시작할 수 있는 유일한
            // 입구라서 다른 무엇보다 위에 둔다.
            state.missedAlarm?.let { missed ->
                item(key = "missed-alarm") {
                    MissedAlarmCard(missed, onTrack = onMissedTrackClick, onDismiss = onMissedDismiss)
                }
            }

            // 캐시로 그린 화면이면 가장 먼저 밝힌다. 알람 시각은 교통 상황에 따라
            // 바뀌는 값이라, 오래된 값을 지금 값으로 믿으면 지각으로 이어진다.
            if (state.offline) {
                item(key = "offline") { OfflineNotice(state.offlineAgeLabel, onRetry) }
            }

            // 진행 중인 아침 기록. 사용자는 씻으러 갔다가 돌아온다 — 재진입 경로가
            // 없으면 그 아침의 기록이 반쯤 남은 채 버려지고, 준비 시간 학습에
            // 들어갈 재료가 사라진다.
            state.morningBlocksLeft?.let { left ->
                item(key = "morning") { MorningShortcut(left, onMorningClick) }
            }

            // 집 위치가 없으면 어떤 일정도 알람을 계산할 수 없다. 가장 먼저 알린다.
            if (!state.hasHome) {
                item(key = "no-home") { NoHomeNotice(onSetHomeClick) }
            }

            state.error?.let { message ->
                item(key = "error") { ErrorNotice(message, onRetry) }
            }

            if (state.loading) {
                item(key = "loading") { LoadingRow() }
            }

            if (state.isEmpty) {
                item(key = "empty") { EmptyState(onAdd = onAddEventClick) }
            }

            // 울렸고 아직 끝나지 않은 아침이 맨 위 큰 카드다(11-17 사용자). 다음 알람은 그 아래 작게.
            state.ongoing?.let { ongoing ->
                item(key = "ongoing") {
                    OngoingTripCard(
                        event = ongoing,
                        schedule = state.ongoingSchedule,
                        stage = TripResume.stage(ongoing.id, trackingEventId, trackingInTransit),
                        onClick = { onEventClick(ongoing) },
                        onRestartTracking = onRestartTracking,
                    )
                }
            }

            state.nextAlarm?.takeIf { state.ongoing != null }?.let { next ->
                item(key = "next-alarm-compact") {
                    NextAlarmRow(event = next, today = state.today, onClick = { onEventClick(next) })
                }
            }

            state.nextAlarm?.takeIf { state.ongoing == null }?.let { next ->
                item(key = "next-alarm") {
                    NextAlarmCard(
                        event = next,
                        today = state.today,
                        onClick = { onEventClick(next) },
                        onChangeTime = onChangeAlarmTime?.let { change -> { change(next) } },
                        onProbabilityClick = { onTimeFor = next.id },
                        switchBusy = state.switchRouteBusyId == next.id,
                        onSwitchRoute = { onSwitchRoute(next) },
                    )
                }
            }

            // 다음 알람의 "바꿀까요" 제안(BE-H3d). 진행 중 아침이 있어도 다음 알람에 걸린 제안이면 보인다.
            state.nextAlarm?.let { next ->
                next.proposal?.let { proposal ->
                    item(key = "next-alarm-proposal-${next.id}") {
                        com.swpp.wakeup.ui.alarm.ProposalCard(
                            proposal = proposal,
                            busy = proposalBusyId == next.id,
                            onAccept = { onAnswerProposal(next, true) },
                            onKeep = { onAnswerProposal(next, false) },
                            tag = "home_proposal",
                        )
                    }
                }
            }

            // 날짜마다 머리 한 줄 + 둥근 패널 하나. 내일 이후도 모두 펼친다(Figma `540:123`).
            val actionsEnabled = !state.loading && !state.offline &&
                state.alarmUpdatingId == null && state.skipUpdatingId == null
            state.sections.forEach { section ->
                item(key = "section-head-${section.label}") { SectionHeader(section) }
                item(key = "section-${section.label}") {
                    EventGroupPanel(
                        events = section.events,
                        onEventClick = onEventClick,
                        onAlarmToggle = onAlarmToggle,
                        alarmsEnabled = actionsEnabled,
                    )
                }
            }
        }

        // P10/P13: the list scrolls independently; these actions remain visible.
        Column(
            Modifier.fillMaxWidth().background(JitColor.Bg)
                .padding(horizontal = JitSpace.ScreenHorizontal, vertical = 12.dp)
                .testTag("home_actions"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.nextAlarm?.takeIf {
                it.alarmConfirmed == false && it.alarmOn && !it.skipped && it.hasAlarm &&
                    (it.alarmAtMillis ?: Long.MIN_VALUE) > nowMillis && !state.offline && !state.loading
            }?.let { next ->
                onConfirmNextAlarm?.let { confirm ->
                    com.swpp.wakeup.ui.common.JitPrimaryButton(
                        "이 알람 확인", { confirm(next) },
                        Modifier.fillMaxWidth().testTag("home_confirm_alarm"),
                        loading = confirmBusyId == next.id,
                    )
                }
            }
            onRoutineClick?.let {
                com.swpp.wakeup.ui.common.JitSecondaryButton(
                    "아침 루틴", it, Modifier.fillMaxWidth().testTag("home_routine"),
                )
            }
            com.swpp.wakeup.ui.common.JitPrimaryButton(
                "+ 일정 추가", onAddEventClick,
                Modifier.fillMaxWidth().testTag("home_add_event")
                    .coachmarkTarget(CoachTarget.ADD_EVENT),
            )
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 울렸고 아직 끝나지 않은 아침(11-17 사용자). 다음 알람 카드와 같은 틀(Surface · 주황 테두리 · 모서리 16 · 여백 16)에
 * 지금 단계(준비 중 · 이동 중)를 크게, 다음 할 일을 한 줄로. 추적이 멈춰 있으면 그렇다고 말하고 [기록 다시 시작] 을 준다.
 * 누르면 ④ 알람 상세(진행 · 지도)로 간다.
 */
@Composable
private fun OngoingTripCard(
    event: UpcomingEvent,
    schedule: com.swpp.wakeup.domain.model.AlarmSchedule?,
    stage: TripResume.Stage,
    onClick: () -> Unit,
    onRestartTracking: () -> Unit,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    val zone = ZoneId.systemDefault()
    fun clockOf(millis: Long?): String? = millis?.let {
        val t = Instant.ofEpochMilli(it).atZone(zone)
        AlarmTimeChoice.clockLabel(t.hour * 60 + t.minute)
    }
    val startClock = clockOf(event.startAtMillis) ?: event.startClock
    val stopped = stage == TripResume.Stage.NOT_TRACKING
    Column(
        modifier = Modifier
            .testTag("home_ongoing")
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            JitDotLabel(text = "진행 중 · 알람이 울렸어요", dotColor = JitColor.Accent, modifier = Modifier.weight(1f))
            JitChip(
                label = if (stopped) "기록 멈춤" else stage.label,
                contentColor = if (stopped) JitColor.Amber else JitColor.Green,
                container = (if (stopped) JitColor.Amber else JitColor.Green).copy(alpha = 0.15f),
                modifier = Modifier.testTag("home_ongoing_stage"),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$startClock ${event.title}",
                color = JitColor.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            event.placeName?.takeIf { it.isNotBlank() }?.let { place ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(LineIcons.mapPin, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(14.dp))
                    Text(place, color = JitColor.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = if (stopped) stage.label else stage.label.let { "지금은 $it" },
            color = if (stopped) JitColor.Amber else JitColor.Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = TripResume.nextLine(stage, clockOf(schedule?.departByMillis), clockOf(schedule?.arriveAtMillis), startClock),
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.testTag("home_ongoing_next"),
        )
        if (stopped) {
            Row(
                modifier = Modifier
                    .testTag("home_ongoing_restart")
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(JitColor.Surface2)
                    .clickable(role = Role.Button, onClick = onRestartTracking),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LineIcons.rotateCcw, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("기록 다시 시작", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Partly AI-generated (Claude)
/** 진행 중 카드 아래의 작은 "다음 알람" 한 줄. 누르면 그 일정의 ④ */
@Composable
private fun NextAlarmRow(event: UpcomingEvent, today: LocalDate, onClick: () -> Unit) {
    val alarmDate = event.alarmAtMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        ?: event.startDate
    val clock = listOfNotNull(HomeLabels.koreanMeridiem(event.alarmMeridiem), event.alarmClock ?: event.alarmAt)
        .joinToString(" ")
    Row(
        modifier = Modifier
            .testTag("next_alarm_compact")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LineIcons.alarm, contentDescription = null, tint = JitColor.Accent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "다음 알람 · ${HomeLabels.alarmDayWord(alarmDate, today)} $clock",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${event.startClock} ${event.title}",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(LineIcons.chevronRight, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** 떠 있는 [+ 일정 추가] 의 높이와 아래 여백(Figma: 탭 바 위 16) */
private val FAB_HEIGHT = 48.dp
private val FAB_BOTTOM = 16.dp

// Partly AI-generated (Claude)
/** 머리: 오늘 날짜 + 오른쪽 위 ?(도움말). */
@Composable
private fun HomeHeader(today: LocalDate, onHelpClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = HomeLabels.headerDate(today),
            modifier = Modifier.testTag("home_date"),
            color = JitColor.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        onHelpClick?.let { help ->
            Box(
                modifier = Modifier
                    .testTag("open_help")
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(JitColor.Surface2)
                    .clickable(role = Role.Button, onClick = help),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LineIcons.help,
                    contentDescription = "도움말",
                    tint = JitColor.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 집 위치 미설정 안내.
 *
 * 알람 계산의 출발지가 집이다. 이게 없으면 서버가 모든 계획을 `no_home` 으로
 * 돌려주므로 일정을 아무리 넣어도 알람이 안 나온다. 원인을 숨기지 않는다.
 */
@Composable
private fun NoHomeNotice(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = "집 위치를 정해야 알람을 계산할 수 있어요",
            dotColor = JitColor.Amber,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "이동 시간을 구하려면 출발지가 필요해요. 눌러서 정해 주세요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * 저장된 정보로 그렸다는 안내.
 *
 * **나이를 함께 적는 것이 핵심이다.** "오프라인" 만 띄우면 사용자는 얼마나 오래된
 * 값인지 모른다. 알람 시각은 교통 상황에 따라 달라지므로 세 시간 전 값을 지금
 * 값으로 믿으면 지각한다.
 *
 * 기기에 걸린 알람은 그대로 유효하다는 것도 밝힌다 — 오프라인이라고 알람이
 * 울리지 않을까 걱정할 필요가 없다.
 */
@Composable
private fun OfflineNotice(ageLabel: String?, onRetry: () -> Unit) {
    // 띠 전체가 눌리지 않는다. [다시 시도] 버튼만 눌린다(HE 2-6).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .testTag("home_offline")
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = ageLabel?.let { "오프라인 · $it" } ?: "오프라인 · 저장된 정보",
            dotColor = JitColor.Amber,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "인터넷에 연결되지 않아 마지막으로 받은 정보를 보여 드려요. 이미 맞춰 둔 알람은 그대로 울려요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        JitRetryButton(onClick = onRetry, modifier = Modifier.padding(top = 3.dp).testTag("home_offline_retry"))
    }
}

/**
 * 진행 중인 아침 기록으로 돌아가는 입구.
 *
 * 알람을 해제하면 기록 화면이 뜨지만 사용자는 곧 씻으러 간다. 돌아왔을 때
 * 재진입 경로가 없으면 그 아침의 기록이 반쯤 남은 채 버려지고, 준비 시간
 * 학습에 들어갈 재료가 사라진다.
 */
@Composable
private fun MorningShortcut(blocksLeft: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = if (blocksLeft > 0) "아침 기록이 ${blocksLeft}개 남았어요" else "아침 기록을 정리해 주세요",
            dotColor = JitColor.Accent,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "항목을 마칠 때마다 누르면 실제로 걸린 시간이 기록돼요. 이 기록이 준비 시간을 " +
                "맞추는 유일한 재료예요",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        Text(
            text = "눌러서 이어가기",
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// Partly AI-generated (Claude)
/**
 * 울리지 않은 알람 안내.
 *
 * **알람이 왜 안 울렸는지 단정하지 않는다.** 앱은 강제 중지가 있었는지 알 수 없고,
 * 아는 것은 "등록했는데 울렸다는 기록이 없다" 뿐이다.
 *
 * 추적을 시작할 수 없으면(기준점·정밀 위치 권한 없음) 버튼 대신 이유를 적는다.
 * 누를 수 없는 버튼을 보여 주면 눌러도 아무 일이 없는 것처럼 보인다.
 */
@Composable
private fun MissedAlarmCard(
    notice: HomeViewModel.MissedAlarmNotice,
    onTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .testTag("missed_alarm")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = "${notice.alarmLine} 알람이 울리지 않았어요",
            dotColor = JitColor.Red,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = notice.schedule.eventLine,
            color = JitColor.TextPrimary,
            fontSize = 12.sp,
        )
        Text(
            text = when {
                notice.canTrack -> (notice.departLabel?.let { "$it 출발 예정이라 " } ?: "") +
                    "지금 이동 기록을 시작할 수 있어요"
                !notice.schedule.canTrack -> "출발지나 도착지 위치를 몰라 이동 기록은 시작할 수 없어요"
                else -> "정밀 위치 권한이 없어 이동 기록은 시작할 수 없어요"
            },
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (notice.canTrack) {
                Text(
                    text = "이동 기록 시작",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("missed_alarm_track")
                        .clickable(onClick = onTrack)
                        .padding(vertical = 4.dp),
                )
            }
            Text(
                text = "닫기",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag("missed_alarm_dismiss")
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = "불러오지 못했어요",
            dotColor = JitColor.Red,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(text = message, color = JitColor.TextSecondary, fontSize = 12.sp)
        JitRetryButton(onClick = onRetry, modifier = Modifier.padding(top = 3.dp).testTag("home_error_retry"))
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = JitColor.Accent,
            strokeWidth = 2.dp,
        )
    }
}


/** 일정이 하나도 없을 때. 새 계정이 처음 보는 화면이다. 가운데 + 를 누르면 알람 추가 시트가 열린다(11-17 사용자). */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        // 다음 알람이 없는 날 홈 코치마크(H2-4)가 이 자리를 밝힌다. 위아래 여백은 빼고 글 묶음만 알린다.
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp).coachmarkTarget(CoachTarget.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(JitColor.Surface)
                .clickable(role = Role.Button, onClickLabel = "알람 추가", onClick = onAdd)
                .testTag("home_empty_add"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LineIcons.plus, contentDescription = "알람 추가", tint = JitColor.Accent, modifier = Modifier.size(26.dp))
        }
        Text(
            text = "아직 일정이 없어요",
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// Partly AI-generated (Claude)
/**
 * 가장 임박한 알람(Figma `hero 다음 알람`). 카드 전체를 누르면 ④ 알람 결정으로 간다.
 *
 * 위에서부터: "다음 알람 · 오늘" + 정시 도착 확률 칩 / 알람 시각 크게 + [시각 바꾸기] /
 * "1시간 28분 뒤 · 9:00 일정" / 핀 + "장소 · 9:00 제목".
 *
 * 확률은 준비·이동 둘 다 변동 폭이 있어야 서버가 준다. 없으면(null) 칩을 숨긴다. 임의의 90% 를
 * 보여주면 화면은 그럴싸해지지만 거짓이다. 이유는 근거 화면(④)이 설명한다.
 */
@Composable
private fun NextAlarmCard(
    event: UpcomingEvent,
    today: LocalDate,
    onClick: () -> Unit,
    onChangeTime: (() -> Unit)?,
    onProbabilityClick: (() -> Unit)? = null,
    switchBusy: Boolean = false,
    onSwitchRoute: () -> Unit = {},
) {
    val now by rememberMinuteClock()
    val shape = RoundedCornerShape(JitRadius.Card)
    val alarmMillis = event.alarmAtMillis
    val alarmDate = alarmMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        ?: event.startDate
    val start = HomeLabels.heroStartClock(event.startClock, event.alarmMeridiem)

    Column(
        modifier = Modifier
            .testTag("next_alarm")
            // 홈 코치마크(H2-4)가 이 카드의 실제 자리를 밝힌다
            .coachmarkTarget(CoachTarget.NEXT_ALARM)
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = "다음 알람 · ${HomeLabels.alarmDayWord(alarmDate, today)}",
                dotColor = JitColor.Accent,
                modifier = Modifier.weight(1f),
            )
            event.onTimeProbability?.let { probability ->
                val tone = riskColor(event.risk)
                JitChip(
                    label = "정시 도착 확률 ${ProbabilityRule.displayLabel(probability)}",
                    contentColor = tone,
                    container = tone.copy(alpha = 0.15f),
                    modifier = Modifier
                        .testTag("next_alarm_probability")
                        .then(
                            if (onProbabilityClick != null) {
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable(role = Role.Button, onClickLabel = "정시 도착 확률이 뭐예요?", onClick = onProbabilityClick)
                            } else Modifier
                        ),
                )
            }
            // 확률을 아직 못 내면 칩 자리를 비운다(11-17 사용자: 설명을 빼 달라고 함)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                HomeLabels.koreanMeridiem(event.alarmMeridiem)?.let { word ->
                    Text(
                        text = word,
                        modifier = Modifier.alignByBaseline(),
                        color = JitColor.Accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = event.alarmClock ?: event.alarmAt ?: "—",
                    modifier = Modifier.alignByBaseline(),
                    color = JitColor.Accent,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            onChangeTime?.let { ChangeTimeButton(it) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = alarmMillis
                    ?.let { HomeLabels.heroWhenLine(HomeLabels.untilLabel(it, now), start) }
                    ?: "$start 일정",
                color = JitColor.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 장소가 없는 일정에 핀을 그리면 장소가 빈 것처럼 보인다.
                if (event.placeName != null) {
                    Icon(
                        imageVector = LineIcons.mapPin,
                        contentDescription = null,
                        tint = JitColor.TextSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = HomeLabels.heroPlaceTitleLine(event.placeName, event.title),
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 더 빠른 경로 바꾸기는 홈에 두지 않는다(11-17 사용자). ④ 알람 상세 · 이동 탭 · 알람 확인 시트에서 바꾼다.
    }
}

/** 카드의 [시각 바꾸기](보조 버튼, 연필). */
@Composable
private fun ChangeTimeButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag("next_alarm_change_time")
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LineIcons.pencil,
            contentDescription = null,
            tint = JitColor.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(text = "시각 바꾸기", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 분이 바뀔 때마다 새 값을 내는 지금 시각(epoch ms). "N시간 M분 뒤" 를 화면이 떠 있는 동안 맞춘다. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        val now = System.currentTimeMillis()
        delay(60_000L - now % 60_000L + 50L)
        value = System.currentTimeMillis()
    }
}

// Partly AI-generated (Claude)
/** 묶음 머리. 왼쪽 "오늘", 오른쪽 "일정 2개" / "9월 18일 금 · 일정 2개". */
@Composable
private fun SectionHeader(section: EventSection) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.label,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = HomeLabels.sectionSummary(section),
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// Partly AI-generated (Claude)
/** 하루치 일정을 담는 둥근 패널 하나. 줄 사이는 얇은 구분선으로 나눈다. */
@Composable
private fun EventGroupPanel(
    events: List<UpcomingEvent>,
    alarmsEnabled: Boolean,
    onEventClick: (UpcomingEvent) -> Unit,
    onAlarmToggle: (Long, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        events.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider(color = JitColor.Surface2)
            key(event.id) {
                EventRow(
                    event = event,
                    onClick = { onEventClick(event) },
                    alarmEnabled = alarmsEnabled,
                    onAlarmToggle = { onAlarmToggle(event.id, it) },
                )
            }
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 일정 한 줄 = 시작 시각 · 무엇 · 어디(핀) · 알람(시각 + 스위치). 줄을 누르면 ④ 로 간다.
 *
 * 예전 줄에 있던 확률 · 종류 칩 · "안 감으로 표시" 는 뺐다. 확률은 맨 위 카드와 ④ 에, "안 감으로 표시" 는
 * ④ 에 있다(Figma 캡션 `540:304`). 안 간 일정은 흐리게 그리고 알람 칸에 "안 감" 이라고 쓴다.
 */
@Composable
private fun EventRow(
    event: UpcomingEvent,
    onClick: () -> Unit,
    alarmEnabled: Boolean,
    onAlarmToggle: (Boolean) -> Unit,
) {
    val ringing = event.alarmOn && !event.skipped && event.hasAlarm && event.planStatus == PLAN_STATUS_OK
    val alarmColor = if (ringing) JitColor.Accent else JitColor.TextSecondary

    Row(
        modifier = Modifier
            .testTag("event_row_${event.id}")
            .fillMaxWidth()
            .then(if (event.skipped) Modifier.alpha(SKIPPED_ALPHA) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = HomeLabels.rowStartClock(event.startClock),
            // 가장 넓은 시각("오후 12:00")을 지금 글자 크기로 잰 폭. 고정 68dp 는 "오전 11:00" 의 끝을 잘랐다(11-19).
            // 그보다 넓은 값이 와도 칸이 늘어나고, 줄어드는 쪽은 제목(weight 1, 말줄임)이다.
            modifier = Modifier
                .testTag("event_row_clock_${event.id}")
                .widthIn(min = maxOf(68.dp, rememberTextWidth("오후 12:00", 15.sp, FontWeight.Bold))),
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 테스트는 이 제목을 누른다.
            Text(
                text = event.title,
                modifier = Modifier.testTag("event_title_${event.id}"),
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.placeName?.let { place ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = LineIcons.mapPin,
                        contentDescription = null,
                        tint = JitColor.TextSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = place,
                        color = JitColor.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = LineIcons.alarm,
                    contentDescription = null,
                    tint = alarmColor,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = HomeLabels.rowAlarmLabel(event),
                    color = alarmColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            // 안 간 일정은 서버가 알람을 껐다. 켜도 울리지 않으므로 스위치를 막는다.
            AlarmSwitch(event, alarmEnabled && !event.skipped, onAlarmToggle)
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 알람 스위치(Figma `fb/Switch`, 44x24). 켜면 주황 바탕에 어두운 손잡이, 끄면 회색.
 *
 * 서버의 판정 결과를 그리고, 저장 요청 동안은 누르지 못하게 한다([enabled]). 알람을 계산하지 못했거나
 * 안 간 일정이라 켤 수 없는 스위치만 흐리게 그린다 — 저장 중에 모든 스위치가 깜빡이지 않게 한다.
 */
@Composable
private fun AlarmSwitch(event: UpcomingEvent, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val usable = event.hasAlarm && event.planStatus == PLAN_STATUS_OK && !event.skipped
    val on = event.alarmOn
    val knob by animateDpAsState(targetValue = if (on) 22.dp else 2.dp, label = "alarm_switch_knob")
    Box(
        modifier = Modifier
            .testTag("event_alarm_${event.id}")
            .size(width = 44.dp, height = 24.dp)
            .alpha(if (usable) 1f else 0.4f)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) JitColor.Accent else JitColor.Track)
            .toggleable(
                value = on,
                enabled = enabled && usable,
                role = Role.Switch,
                onValueChange = onToggle,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) JitColor.Bg else JitColor.TextSecondary)
        )
    }
}

// Partly AI-generated (Claude)
/** [UpcomingEvent.planStatus] 의 "알람 계산 완료" 값 */
private const val PLAN_STATUS_OK = "ok"

/** "안 감" 으로 표시한 줄의 흐리기(Figma ③ 변형 `455:9`) */
private const val SKIPPED_ALPHA = 0.45f

// Partly AI-generated (Claude)
/** 목록 위에 떠 있는 [+ 일정 추가](Figma `fab 일정 추가`, 주황 알약). */
@Composable
private fun AddEventFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .testTag("add_event")
            .coachmarkTarget(CoachTarget.ADD_EVENT)
            .height(FAB_HEIGHT)
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(JitColor.Accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LineIcons.plus,
            contentDescription = null,
            tint = JitColor.Bg,
            modifier = Modifier.size(20.dp),
        )
        Text(text = "일정 추가", color = JitColor.Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// Partly AI-generated (Claude)
/** 확률 칩 → i 시트에 넣을 값. 확률이나 알람 시각이 없으면 null */
private fun onTimeSpecOf(event: UpcomingEvent): OnTimeSheetSpec? {
    val percent = event.onTimeProbability ?: return null
    val alarm = event.alarmAtMillis ?: return null
    return OnTimeSheetSpec(
        percent = percent,
        alarmLabel = OnTimeSheetText.clock(alarm),
        startLabel = OnTimeSheetText.clock(event.startAtMillis),
    )
}

/** 위험 등급 → 색. 경계값은 [UpcomingEvent.risk] 가 소유한다. */
internal fun riskColor(risk: UpcomingEvent.Risk): Color = when (risk) {
    UpcomingEvent.Risk.SAFE -> JitColor.Green
    UpcomingEvent.Risk.WARN -> JitColor.Amber
    UpcomingEvent.Risk.DANGER -> JitColor.Red
    UpcomingEvent.Risk.UNKNOWN -> JitColor.TextSecondary
}

@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun HomeEmptyPreview() {
    JitTheme {
        HomeScreen(
            state = HomeViewModel.UiState(
                nickname = "진호",
                loading = false,
                hasHome = false,
                totalCount = 0,
            ),
            onEventClick = {},
            onAlarmToggle = { _, _ -> },
            onAddEventClick = {},
            onSetHomeClick = {},
            onMorningClick = {},
            onRetry = {},
        )
    }
}

// Partly AI-generated (Claude)
@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun HomeFilledPreview() {
    val today = LocalDate.now()
    val event = UpcomingEvent(
        id = 1,
        startTime = "09:00",
        dayLabel = "목",
        title = "자료구조 및 알고리즘",
        placeAndRoute = "302동 105호 · 버스 20분 · 4.6km",
        alarmAt = "7:40",
        onTimeProbability = 90,
        tag = "수업",
        planStatus = "ok",
        planStatusLabel = "계산 완료",
        startAtEpochSecond = 0,
        startDate = today,
        alarmClock = "7:40",
        alarmMeridiem = "AM",
        placeName = "302동 105호",
        startClock = "9:00AM",
        alarmOn = true,
    )
    val off = event.copy(id = 2, title = "소프트웨어 개발의 원리", placeName = "301동 203호", startClock = "3:30PM", alarmOn = false)
    JitTheme {
        HomeScreen(
            state = HomeViewModel.UiState(
                nickname = "진호",
                loading = false,
                sections = listOf(
                    EventSection("오늘", "9월 17일 목", listOf(event, off)),
                    EventSection("내일", "9월 18일 금", listOf(event.copy(id = 3, title = "전공 발표", startClock = "10:00AM"))),
                ),
                nextAlarm = event,
                totalCount = 3,
                today = today,
            ),
            onEventClick = {},
            onAlarmToggle = { _, _ -> },
            onAddEventClick = {},
            onSetHomeClick = {},
            onMorningClick = {},
            onRetry = {},
            onHelpClick = {},
            onChangeAlarmTime = {},
        )
    }
}
