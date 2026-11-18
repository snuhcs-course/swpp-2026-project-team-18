package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.ProbabilityRule
import com.swpp.wakeup.ui.common.PlacedLabels
import com.swpp.wakeup.ui.events.AlarmConfirmView
import com.swpp.wakeup.ui.events.AlarmConfirmText
import com.swpp.wakeup.ui.common.LineIcons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.domain.model.ArrivalOutlook
import com.swpp.wakeup.domain.model.FasterRouteOffer
import com.swpp.wakeup.domain.model.RerouteBanner
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.home.HomeViewModel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.repository.EventScope
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.PrepBlockLine
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.probabilityColor
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.events.RecurrenceScopeSheet
import com.swpp.wakeup.ui.events.ScopeAction
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.help.HelpTopic
import com.swpp.wakeup.ui.help.InfoButton
import com.swpp.wakeup.ui.help.OnTimeProbabilitySheet
import com.swpp.wakeup.ui.help.OnTimeSheetSpec
import com.swpp.wakeup.ui.help.OnTimeSheetText
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 알람 결정. Figma "4. 알람 결정" (node 1:2).
 *
 * **근거를 함께 보여주는 것이 이 화면의 목적이다.** "7:32" 만 주면 사용자가
 * 믿을 이유가 없다. 준비·이동·버퍼를 분해하고 각 값의 출처를 적는다.
 *
 * 확률은 서버가 null 로 줄 수 있다. 준비·이동 둘 다 변동 폭이 있어야 나와서다.
 * 그때는 숫자를 만들지 않고 "아직 계산할 수 없음" 과 그 이유를 밝힌다.
 */
@Composable
fun AlarmDecisionScreen(
    plan: AlarmPlanView?,
    onBack: () -> Unit,
    onChangeRisk: () -> Unit,
    /** 이 아침에 할 블록 고르기. 저장하면 이 일정만 즉시 재계산된다 */
    onEditBlocks: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * "다시 계산"(`POST /api/events/{id}/recompute`). **알람을 계산하지 못한(`route_failed`) 계획에만**
     * 보인다. 계산된 계획에는 없다(서버가 알아서 다시 계산하고 경로 갱신 워커가 새로 받는다). null 이면 그리지 않는다
     */
    onRecompute: (() -> Unit)? = null,
    /** 다시 계산하는 중. 버튼을 막고 문구를 바꾼다 */
    recomputeBusy: Boolean = false,
    /** 진행 바에 쓸 이름과 아바타 두 글자 */
    nickname: String = "나",
    initials: String = "나",
    /** 지금 어느 단계인가. 추적 중이면 판정기 값, 아니면 알람 시각 기준 */
    stage: TripStage = TripStage.BEFORE_ALARM,
    /** 경로 거리 기준 진행률. null 이면 측정하지 않는 상태다 */
    progress: RouteProgress? = null,
    /** 지각 전망. 진행 바 색의 근거다. null 이면 판단할 수 없다 */
    outlook: ArrivalOutlook? = null,
    /** 진행 바 오른쪽에 크게 놓을 시각. [outlook] 이 있으면 예상 도착이다 */
    arrivalClock: String? = null,
    /** "3분 전 갱신" */
    freshness: String? = null,
    /** 경로 지도 상태. null 이면 경로 좌표가 없어 지도를 그릴 수 없다 */
    routeMap: HomeViewModel.RouteMapState? = null,
    onRouteMapViewport: (widthDp: Int, heightDp: Int) -> Unit = { _, _ -> },
    onRouteMapFit: () -> Unit = {},
    onRouteMapGestureEnd: (pan: Offset, zoom: Float, metersPerPixel: Double) -> Unit =
        { _, _, _ -> },
    onRouteMapRecenter: () -> Unit = {},
    /** "안 감으로 표시"(skip-event-api.md §6) */
    onSkip: () -> Unit = {},
    /** "안 감" 표시 되돌리기 */
    onUnskip: () -> Unit = {},
    /** 표시를 저장하는 중. 두 버튼을 막는다 */
    skipBusy: Boolean = false,
    /** "도착했어요"(수동 도착). null 이면 버튼을 그리지 않는다 */
    onArrive: (() -> Unit)? = null,
    /** "일정 수정"(event-edit-api.md). null 이면 그리지 않는다 */
    onEdit: (() -> Unit)? = null,
    /** 이동 중 늦을 때의 경로 바꾸기 카드([com.swpp.wakeup.domain.model.RerouteAlert.banner]). null 이면 없다 */
    rerouteBanner: RerouteBanner? = null,
    /** [이 경로로 바꾸기]. 경로 key 를 받는다 */
    onReroute: (String) -> Unit = {},
    /** 경로를 바꾸는 중 */
    rerouteBusy: Boolean = false,
    /** 이동 중 늦는 이유 한 줄([com.swpp.wakeup.domain.model.LateCause]). null 이면 없다 */
    lateCause: String? = null,
    /** 이동 중 "경로 정류장" 행. null 이거나 비어 있으면 카드를 그리지 않는다 */
    checkpoints: List<CheckpointRow>? = null,
    /** [checkpoints] 에 시각이 있는가. 없으면 카드 아래에 이유를 적는다 */
    checkpointsTimed: Boolean = true,
    /** 알람 전 더 빠른 경로 제안([com.swpp.wakeup.domain.model.FasterRoute.offer]). null 이면 카드가 없다 */
    fasterRoute: FasterRouteOffer? = null,
    /** 알람 전에 일정의 경로를 바꾸는 중 */
    fasterRouteBusy: Boolean = false,
    /** [이 경로로 바꾸기] (알람 전) */
    onSwitchFasterRoute: (FasterRouteOffer) -> Unit = {},
    /** "바꿀까요" 제안에 답하기(BE-H3d). true = 받아들이기 */
    onAnswerProposal: (Boolean) -> Unit = {},
    proposalBusy: Boolean = false,
    /**
     * 반복 일정 삭제 범위(iter4 A2, [com.swpp.wakeup.data.repository.RecurrenceScope.deleteChoices]). 비어 있으면
     * 묻지 않고 [onDelete] 를 부른다(반복이 아니거나 서버가 범위 삭제를 모름).
     */
    deleteScopes: List<EventScope> = emptyList(),
    /** 삭제 시트에서 고른 범위로 지운다 */
    onDeleteScoped: (EventScope) -> Unit = {},
    /**
     * 도움말을 그 항목을 펼친 채로 연다(iter4 H2-4). null 이면 i 를 그리지 않는다. 오른쪽 위 i 와 확률 줄은 확률이
     * 있으면 "정시 도착 확률이 뭐예요?" 시트를, 없으면 도움말의 그 질문을 연다. "계산 방법" 의 i 는 알람 시각 질문이다
     */
    onHelp: ((HelpTopic) -> Unit)? = null,
    /** 서버가 직접 정한 시각을 받는가(0.21.0 이상). 아니면 [추천 시각으로 되돌리기] 를 두지 않는다 */
    serverSupportsConfirm: Boolean = false,
    /** 되돌리기를 보내는 중 */
    confirmBusy: Boolean = false,
    /** 직접 정한 시각일 때 [추천 시각으로 되돌리기](BE-H3b) */
    onRevertAlarm: () -> Unit = {},
    /** 남은 시간을 셀 지금 시각. 테스트가 고정한다 */
    nowMillis: Long = System.currentTimeMillis(),
) {
    var askDeleteScope by rememberSaveable { mutableStateOf(false) }
    // Partly AI-generated (Claude) — i 시트(Figma `548:1798`)
    var showOnTime by rememberSaveable { mutableStateOf(false) }
    val onTimeSpec = plan?.let(::onTimeSpecOf)
    if (showOnTime && plan != null && onTimeSpec != null) {
        OnTimeProbabilitySheet(
            spec = onTimeSpec,
            tone = planProbabilityColor(plan),
            // [알람 시각 바꾸기] 는 H2-3 시트 자리다. 시트가 생기기 전에는 ⑤ 지각 위험 고르기로 간다.
            onChangeTime = if (plan.isComputed && !plan.skipped) {
                {
                    showOnTime = false
                    onChangeRisk()
                }
            } else null,
            onDismiss = { showOnTime = false },
        )
    }
    val openProbability: (() -> Unit)? = onHelp?.let { help ->
        {
            if (onTimeSpec != null) {
                showOnTime = true
            } else {
                help(HelpTopic.ON_TIME_PROBABILITY)
            }
        }
    }
    if (askDeleteScope && deleteScopes.isNotEmpty()) {
        RecurrenceScopeSheet(
            action = ScopeAction.DELETE,
            choices = deleteScopes,
            onChoose = { scope ->
                askDeleteScope = false
                onDeleteScoped(scope)
            },
            onDismiss = { askDeleteScope = false },
        )
    }
    // 위는 스크롤, 아래(타일 넷 · 확인)는 화면 바닥에 붙는다(Figma `545:920`).
    Column(modifier = modifier.fillMaxSize().background(JitColor.Bg)) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag("plan_scroll")
            .verticalScroll(rememberScrollState())
            .padding(
                start = SIDE,
                end = SIDE,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "알람",
            onBack = onBack,
            action = openProbability?.let { open ->
                {
                    InfoButton(
                        contentDescription = "정시 도착 확률이 뭐예요?",
                        tag = "plan_info",
                        onClick = open,
                        size = 36,
                        iconSize = 18,
                    )
                }
            },
        )

        if (plan == null) {
            LoadingBlock()
            return@Column
        }

        EventCard(plan)

        if (plan.skipped) SkippedCard(onUnskip = onUnskip, enabled = !skipBusy)

        if (plan.isComputed) {
            // 안 간 일정의 알람은 울리지 않는다. 근거는 남기되 흐리게 둔다(Figma ④ 변형).
            Box(if (plan.skipped) Modifier.alpha(SKIPPED_ALPHA) else Modifier) {
                AlarmCard(
                    plan = plan,
                    nowMillis = nowMillis,
                    onChangeTime = if (plan.skipped) null else onChangeRisk,
                    onProbabilityClick = openProbability,
                    canRevert = serverSupportsConfirm && !confirmBusy,
                    onRevert = onRevertAlarm,
                )
            }
            // 답을 기다리는 "바꿀까요" 제안(BE-H3d). 알람 카드 바로 아래 — 바뀔 시각과 같이 읽힌다.
            plan.proposal?.takeIf { !plan.skipped }?.let { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = proposalBusy,
                    onAccept = { onAnswerProposal(true) },
                    onKeep = { onAnswerProposal(false) },
                    tag = "plan_proposal",
                )
            }
            // 알람이 울리기 전에만 나온다(Figma ④ 변형 `512:30`). 그 뒤에는 일정에 저장한 경로를 바꾸지 않는다.
            fasterRoute?.takeIf { !plan.skipped && it.isOpen(System.currentTimeMillis()) }?.let {
                FasterRouteCard(offer = it, busy = fasterRouteBusy, onSwitch = { onSwitchFasterRoute(it) })
            }
            WhyCard(plan, onInfo = onHelp?.let { help -> { help(HelpTopic.ALARM_TIME) } })

            // 계산 근거 바로 아래에 "지금 어디쯤" 을 둔다. 같은 여정을 계획과
            // 실제 두 면에서 보여 주는 것이고, 순서가 바뀌면 진행률이 어느
            // 계획에 대한 것인지 연결이 끊긴다. 안 간 일정에는 이동이 없다.
            if (!plan.skipped) {
                // 알람 전에는 진행할 것이 없다 — Figma `545:920` 처럼 근거 카드 아래를 비운다. 알람이 울린 뒤(준비 · 이동 · 도착)에만.
                if (stage != TripStage.BEFORE_ALARM) TripProgressCard(
                    nickname = nickname,
                    initials = initials,
                    stage = stage,
                    progress = progress,
                    outlook = outlook,
                    // 전망이 있으면 예상 도착 시각을, 없으면 계획한 도착 예정을 쓴다.
                    arrivalAt = arrivalClock ?: plan.arrivalAt,
                    freshness = freshness,
                    onArrive = onArrive,
                )
                // 늦는 이유 한 줄(Figma ④-c 변형 `510:25`). 이동 중에만 의미가 있다.
                if (stage == TripStage.IN_TRANSIT) lateCause?.let {
                    Text(
                        text = it,
                        color = JitColor.Amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("trip_late_cause"),
                    )
                }
                // 늦는다는 전망 바로 아래에 바꿀 길을 둔다(Figma ⑦ 변형 `491:9`).
                rerouteBanner?.let { RerouteCard(banner = it, onReroute = onReroute, busy = rerouteBusy) }
                if (stage == TripStage.IN_TRANSIT && !checkpoints.isNullOrEmpty()) {
                    CheckpointCard(rows = checkpoints, timed = checkpointsTimed)
                }
            }

            routeMap?.let { map ->
                RouteMapCard(
                    state = map,
                    progress = progress,
                    moving = stage == TripStage.IN_TRANSIT || map.pathFromCurrent,
                    onViewport = onRouteMapViewport,
                    onFitRoute = onRouteMapFit,
                    onGestureEnd = onRouteMapGestureEnd,
                    onRecenter = onRouteMapRecenter,
                )
            }
        } else {
            NotComputedCard(plan, onRecompute, recomputeBusy)
        }

        // 아이콘 타일 넷은 스크롤 내용의 맨 끝에 둔다 — 끝까지 내렸을 때 보인다(사용자 피드백 11-16,
        // 화면 바닥에 늘 붙어 있으면 너무 크다). 내용이 짧으면 카드 아래에 그대로 이어진다.
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 알람을 정한 뒤에도 제목·시각·장소·출발지·경로·종류를 고친다(Figma ④ `489:9`).
            ActionTile(
                icon = LineIcons.pencil,
                label = "일정 수정",
                tag = "event_edit",
                enabled = onEdit != null,
                onClick = { onEdit?.invoke() },
                modifier = Modifier.weight(1f),
            )
            // 이 아침에 할 준비 항목 고르기(예전 "이 아침 할 일 고르기" 카드)
            ActionTile(
                icon = LineIcons.sliders,
                label = "준비 항목",
                tag = "plan_blocks",
                onClick = onEditBlocks,
                modifier = Modifier.weight(1f),
            )
            // 도착한 일정은 간 것이다. 표시한 일정은 같은 타일이 되돌린다.
            if (plan.skipped) {
                ActionTile(
                    icon = LineIcons.calendarX,
                    label = "다시 가요",
                    tag = "event_unskip_tile",
                    enabled = !skipBusy,
                    onClick = onUnskip,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ActionTile(
                    icon = LineIcons.calendarX,
                    label = "안 가요",
                    tag = "event_skip",
                    enabled = !skipBusy && stage != TripStage.ARRIVED,
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                )
            }
            ActionTile(
                icon = LineIcons.trash,
                label = "일정 삭제",
                tag = "event_delete",
                tint = JitColor.Red,
                // 반복 회차면 "이 일정만 / 이후 일정 모두" 를 먼저 묻는다(iter4 A2).
                onClick = { if (deleteScopes.isNotEmpty()) askDeleteScope = true else onDelete() },
                modifier = Modifier.weight(1f),
            )
        }
    }

    }
}

/** 화면 좌우 여백(Figma `545:920` 18dp) */
private val SIDE = 18.dp

// Partly AI-generated (Claude)
/** "안 감" 으로 표시한 일정 안내와 되돌리기(Figma ④ 변형 `463:17`). */
@Composable
private fun SkippedCard(onUnskip: () -> Unit, enabled: Boolean) {
    JitCard(padding = 14.dp, gap = 6.dp) {
        JitDotLabel(
            text = "안 가요로 표시한 일정",
            dotColor = JitColor.TextSecondary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "알람이 울리지 않고 리포트·학습에서 빠져요",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "되돌리기",
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .testTag("event_unskip")
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onUnskip)
                .padding(vertical = 6.dp),
        )
    }
}

/** 안 간 일정의 "적용 알람" 흐리기 */
private const val SKIPPED_ALPHA = 0.45f

/** 확률 색. 앱 공통 규칙([ProbabilityRule])을 그대로 쓴다. 줄과 i 시트가 같은 색을 쓴다 */
private fun planProbabilityColor(plan: AlarmPlanView): Color =
    probabilityColor(plan.confidence.percent, plan.tauUsed)

// Partly AI-generated (Claude)
/** i 시트에 넣을 값. 확률이 없으면 null(시트 대신 도움말을 연다) */
private fun onTimeSpecOf(plan: AlarmPlanView): OnTimeSheetSpec? {
    val percent = plan.confidence.percent ?: return null
    val alarm = plan.alarmAtMillis?.let { OnTimeSheetText.clock(it) }
        ?: plan.alarmAt?.let { listOfNotNull(koreanMeridiem(plan.meridiem), it).joinToString(" ") }
        ?: return null
    val start = plan.startAtMillis?.let { OnTimeSheetText.clock(it) } ?: "일정 시작"
    return OnTimeSheetSpec(percent = percent, alarmLabel = alarm, startLabel = start)
}

private fun koreanMeridiem(ampm: String): String? = when (ampm) {
    "AM" -> "오전"
    "PM" -> "오후"
    else -> null
}

// Partly AI-generated (Claude)
/** Figma `545:920` 카드 바탕: Surface · 모서리 16 */
private fun Modifier.planCard(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(JitRadius.Card))
    .background(JitColor.Surface)

/** 둥근 알약 하나. [color] 글자 · 같은 색 [alpha] 바탕 */
@Composable
private fun Pill(text: String, color: Color, tag: String, alpha: Float = 0.18f, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = alpha))
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// Partly AI-generated (Claude)
/** 일정 카드: "오늘 오전 9:00" · 종류 태그 / 제목 / 장소 · 수단 · 이동 분 */
@Composable
private fun EventCard(plan: AlarmPlanView) {
    Column(
        modifier = Modifier.planCard().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AlarmDecisionLogic.whenLine(plan.startAtMillis) ?: plan.whenLabel,
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 종류 태그. 서버가 지각 민감도 높음으로 판정한 종류는 빨강, 그 밖은 주황(지금 규칙 그대로)
            val tagText = plan.sensitivityTag ?: if (plan.latenessSensitive) "늦으면 안 되는 일정" else null
            tagText?.let {
                Pill(it, if (plan.latenessSensitive) JitColor.Red else JitColor.Accent, tag = "plan_tag")
            }
        }
        Text(
            text = plan.title.ifBlank { plan.eventTitle },
            color = JitColor.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LineIcons.mapPin, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = AlarmDecisionLogic.placeLine(plan.placeName, plan.travelMode, plan.travelMinutes),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Partly AI-generated (Claude)
/** 알람 카드(주황 1dp 테두리): 라벨 · 알약 · 확률 / 오전 7:40 · [시각 바꾸기] / 남은 시간 */
@Composable
private fun AlarmCard(
    plan: AlarmPlanView,
    nowMillis: Long,
    onChangeTime: (() -> Unit)?,
    onProbabilityClick: (() -> Unit)?,
    canRevert: Boolean,
    onRevert: () -> Unit,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = Modifier
            .planCard()
            .border(1.dp, JitColor.Accent, shape)
            .testTag("plan_alarm_card")
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("알람", color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            val pill = AlarmDecisionLogic.pill(plan)
            Pill(
                text = pill.text,
                color = if (pill.kind == AlarmDecisionLogic.PillKind.USER) JitColor.TextSecondary else JitColor.Accent,
                tag = "plan_pill",
            )
            Spacer(Modifier.weight(1f))
            plan.confidence.percent?.let { percent ->
                val color = planProbabilityColor(plan)
                Pill(
                    text = "정시 도착 확률 ${ProbabilityRule.displayLabel(percent)}",
                    color = color,
                    tag = "plan_probability",
                    alpha = 0.15f,
                    modifier = if (onProbabilityClick != null) {
                        Modifier.clip(RoundedCornerShape(percent = 50)).clickable(onClick = onProbabilityClick)
                    } else Modifier,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                koreanMeridiem(plan.meridiem)?.let {
                    Text(
                        text = it,
                        color = JitColor.Accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = plan.alarmAt ?: "—",
                    color = JitColor.Accent,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline().testTag("plan_alarm_time"),
                )
            }
            onChangeTime?.let { ChangeTimeButton(it) }
        }

        AlarmDecisionLogic.remainingLine(plan.alarmAtMillis, plan.startAtMillis, nowMillis)?.let {
            Text(it, color = JitColor.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        // 작은 상태 한 줄(확인함이면 초록 ✓). 승인 버튼은 이 화면에 두지 않는다(사용자 피드백 11-16).
        AlarmDecisionLogic.alarmStatus(plan)?.let { status ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(if (status.confirmed) "plan_confirmed" else "plan_alarm_status"),
            ) {
                if (status.confirmed) {
                    Icon(LineIcons.check, contentDescription = null, tint = JitColor.Green, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = status.text,
                    color = if (status.confirmed) JitColor.Green else JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (status.confirmed) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // 확률이 없으면 알약 대신 이유 한 줄(숫자를 지어내지 않는다)
        // 확률을 아직 못 낼 때의 이유 · 할 일 문구는 보이지 않는다(11-17 사용자: 빼 달라고 함). 알약도 없다 —
        // 숫자를 지어내지 않는다.

        // 직접 정한 시각: 추천과의 차이 + [추천 시각으로 되돌리기](주황 글자, 꽉 찬 버튼 아님)
        if (plan.userAlarm) {
            AlarmDecisionLogic.userGapLine(plan.alarmAtMillis, plan.recommendedAlarmAtMillis)?.let {
                Text(it, color = JitColor.TextSecondary, fontSize = 12.sp, modifier = Modifier.testTag("plan_user_gap"))
            }
            if (canRevert) {
                Text(
                    text = "추천 시각으로 되돌리기",
                    color = JitColor.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onRevert)
                        .testTag("plan_revert_alarm")
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/** 회색 [시각 바꾸기]: Surface2 · 높이 36 · 연필 16dp. 누르는 영역은 48dp 이상 */
@Composable
private fun ChangeTimeButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "알람 시각 바꾸기", onClick = onClick)
            .testTag("risk_entry"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JitColor.Surface2)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LineIcons.pencil, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("시각 바꾸기", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 막대 · 점 색. ④ 의 예전 막대 · 알람 확인 시트와 같다(준비 주황 · 이동 파랑 · 여유 회색) */
private fun segmentColor(kind: AlarmConfirmView.Kind): Color = when (kind) {
    AlarmConfirmView.Kind.PREP -> JitColor.Accent
    AlarmConfirmView.Kind.TRAVEL -> JitColor.Blue
    AlarmConfirmView.Kind.BUFFER -> JitColor.Track
}

// Partly AI-generated (Claude)
/**
 * "왜 오전 7:40인가요?" — 분 비례 막대 · 기상 · 출발 · 도착 · 시작 눈금 · 설명 세 줄.
 * 예전 "계산 방법" 카드의 τ · 카카오 · "12분 전 계산" 같은 내부 말은 뺐다. 고른 경로가 사라져 다른 경로로
 * 계산했다는 경고처럼 사용자가 알아야 하는 것은 남긴다.
 */
@Composable
private fun WhyCard(plan: AlarmPlanView, onInfo: (() -> Unit)?) {
    val timeline = AlarmDecisionLogic.timeline(plan)
    Column(
        modifier = Modifier.planCard().testTag("plan_why").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AlarmDecisionLogic.whyTitle(plan.alarmAtMillis),
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            onInfo?.let {
                InfoButton(
                    contentDescription = "알람 시각은 어떻게 정해지나요?",
                    tag = "plan_breakdown_info",
                    onClick = it,
                    size = 24,
                    iconSize = 14,
                )
            }
        }

        val fractions = AlarmConfirmText.fractions(timeline.segments)
        if (fractions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .testTag("plan_bar"),
            ) {
                timeline.segments.forEachIndexed { i, seg ->
                    Box(Modifier.weight(fractions[i]).fillMaxHeight().background(segmentColor(seg.kind)))
                }
            }
        }
        TickRow(timeline.points)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            timeline.segments.forEach { seg ->
                val note = when (seg.kind) {
                    AlarmConfirmView.Kind.PREP -> AlarmDecisionLogic.PREP_NOTE
                    AlarmConfirmView.Kind.TRAVEL -> AlarmDecisionLogic.travelNote(plan.travelMode)
                    AlarmConfirmView.Kind.BUFFER -> AlarmDecisionLogic.bufferNote(plan.bufferSource)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(segmentColor(seg.kind)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = seg.label,
                        color = JitColor.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        // 최소 폭만 둔다 — 큰 글자에서 "준비 3 / 분" 처럼 꺾이지 않게(11-16 fontScale 1.5 캡처)
                        modifier = Modifier.widthIn(min = 64.dp).padding(end = 8.dp),
                    )
                    Text(note, color = JitColor.TextSecondary, fontSize = 12.sp)
                }
            }
        }

        // 고른 경로가 사라져 다른 경로로 계산했다. **조용히 넘기면 안 된다.** 사용자는 자기가
        // 고른 노선대로 계산된 알람이라고 믿고 그 노선을 타러 간다(`route_choice_honored=false`).
        if (plan.routeFellBack) {
            JitDotLabel(
                text = "고른 경로가 없어져 다른 경로로 계산했어요. 경로를 다시 고르면 더 정확해요",
                dotColor = JitColor.Amber,
                textColor = JitColor.Amber,
                fontSize = 11,
                bold = false,
                dotSize = 6.dp,
            )
        }
    }
}

/**
 * 눈금 줄: 시각(위) · 이름(아래). 기상은 왼쪽 끝, 시작은 오른쪽 끝, 출발 · 도착은 경계에 오른쪽 정렬.
 * 글자 폭을 실제로 재서 겹치면 밀고, 한 줄에 안 들어가면 두 줄로 엇갈린다([PlacedLabels]). 높이는 고정하지 않는다.
 */
@Composable
private fun TickRow(points: List<AlarmConfirmView.Point>) {
    if (points.isEmpty()) return
    val stops = points.map { it.at }
    PlacedLabels(
        count = points.size,
        anchors = { widths, w -> AlarmDecisionLogic.decisionAnchors(stops, widths, w) },
        modifier = Modifier.fillMaxWidth().testTag("plan_ticks"),
    ) { i ->
        val p = points[i]
        Column(horizontalAlignment = if (i == 0) Alignment.Start else Alignment.End) {
            Text(p.clock, color = JitColor.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(p.name, color = JitColor.TextSecondary, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** 타일 하나: Surface2 · 모서리 14 · 아이콘 18dp + 11sp(누르는 영역 48dp 이상) */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = JitColor.TextPrimary,
) {
    val color = if (enabled) tint else JitColor.Track
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .heightIn(min = 48.dp)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}


/** 알람을 계산할 수 없는 경우. 이유를 그대로 보여준다. */
@Composable
private fun NotComputedCard(plan: AlarmPlanView, onRecompute: (() -> Unit)?, recomputeBusy: Boolean) {
    val (dot, body) = when (plan.status) {
        "no_home" -> JitColor.Amber to
            "이동 시간을 구하려면 출발지가 필요해요. 홈 화면 안내에서 집 위치를 정하면 바로 계산해요"

        "no_place" -> JitColor.Amber to
            "일정에 장소가 없어 이동 시간을 구할 수 없어요. 장소를 넣으면 계산해요"

        "route_failed" -> JitColor.Red to
            "경로를 찾지 못했어요. 출발지나 도착지 근처에 정류장이 없거나 잠깐 생긴 오류일 수 있어요"

        else -> JitColor.TextSecondary to "알람을 아직 계산하지 못했어요"
    }

    JitCard(padding = 14.dp, gap = 8.dp) {
        JitDotLabel(
            text = plan.statusLabel ?: "알람을 아직 계산하지 못했어요",
            dotColor = dot,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
        // 경로 조회 실패에서만 다시 계산할 수 있다(Figma F-48 `526:176`). 카카오를 한 번 더 부르므로 눌렀을 때만.
        if (plan.status == "route_failed" && onRecompute != null) {
            Text(
                text = if (recomputeBusy) "계산하는 중…" else "다시 계산",
                color = if (recomputeBusy) JitColor.TextSecondary else JitColor.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .testTag("plan_recompute")
                    .clickable(enabled = !recomputeBusy, onClick = onRecompute)
                    .padding(vertical = 8.dp),
            )
            Text(text = "경로를 한 번 더 찾아봐요", color = JitColor.TextSecondary, fontSize = 10.sp)
        }
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

@Preview(widthDp = 360, heightDp = 900, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun AlarmDecisionPreview() {
    JitTheme {
        AlarmDecisionScreen(
            plan = AlarmPlanView(
                eventId = 1,
                whenLabel = "내일 아침",
                dateLabel = "9월 18일 금",
                eventTitle = "09:00 자료구조 및 알고리즘",
                eventPlace = "서울대학교 302동 · 서울 관악구 관악로 1",
                sensitivityTag = "수업",
                alarmAt = "7:32",
                meridiem = "AM",
                remaining = "7시간 28분 남음",
                onTimeProbability = null,
                tauUsed = 0.90,
                confidence = ConfidenceView(
                    percent = null,
                    headline = "정시 도착 확률 · 아직 계산할 수 없어요",
                ),
                tauSourceLabel = "수업 일정 기본값",
                breakdown = listOf(
                    PlanRow("준비 시간", 28, "신고 범위 기반 · 블록 3개. 실제 소요가 쌓이면 학습값으로 바뀜", PlanRow.Kind.PREP),
                    PlanRow("버스 이동", 20, "카카오 경로 추정 · 5511 · 4.6km · 환승 1회", PlanRow.Kind.TRAVEL),
                    PlanRow("안전 버퍼", 10, "문 앞에서 실제 출발까지의 여유", PlanRow.Kind.BUFFER),
                ),
                prepBlocks = listOf(
                    PrepBlockLine(1, "샤워", "14분", 14.0, "신고 12~18분 · 관측 7회로 학습됨", true, false),
                    PrepBlockLine(2, "아침 식사", "9분", 9.0, "신고 8~15분 · 관측 없음", false, false),
                    PrepBlockLine(3, "세탁기", "5분", 5.0, "신고 5분 · 관측 없음 · 병렬 진행", false, true),
                ),
                totalMinutes = 58,
                arrivalLine = "8:50 도착 예정",
                status = "ok",
                statusLabel = "계산 완료",
            ),
            onBack = {},
            onChangeRisk = {},
            onEditBlocks = {},
            onDelete = {},
        )
    }
}
