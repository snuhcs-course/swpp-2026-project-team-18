package com.swpp.wakeup.ui.alarm

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
import com.swpp.wakeup.domain.model.RerouteBanner
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.home.HomeViewModel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.PrepBlockLine
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.events.ScreenHeader
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
) {
    Column(
        modifier = modifier
            .testTag("plan_scroll")
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
        ScreenHeader(title = "알람 결정", onBack = onBack)

        if (plan == null) {
            LoadingBlock()
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(plan.whenLabel, color = JitColor.TextSecondary, fontSize = 15.sp)
            Text(plan.dateLabel, color = JitColor.TextSecondary, fontSize = 15.sp)
        }

        // 일정 요약
        JitCard(padding = 14.dp, gap = 9.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "첫 일정" 은 서버가 그날 가장 이른 켜진 알람이라고 판정한 일정에만
                // 붙인다. 판정이 없으면 일반 일정으로 표시한다.
                val first = plan.isFirstAlarm == true
                JitDotLabel(
                    text = if (first) "첫 일정" else "일정",
                    dotColor = if (first) JitColor.Accent else JitColor.TextSecondary,
                    textColor = JitColor.TextSecondary,
                    fontSize = 11,
                    bold = false,
                    dotSize = 6.dp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    plan.sensitivityTag?.let { JitChip(it, JitColor.Accent) }
                    // 판정은 서버가 한다(B-4). 앱에서 τ 기준으로 따로 정하지 않는다.
                    if (plan.latenessSensitive) JitChip("지각 민감도 높음", JitColor.Red)
                }
            }
            Text(
                text = plan.eventTitle,
                color = JitColor.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = plan.eventPlace, color = JitColor.TextSecondary, fontSize = 11.sp)
        }

        if (plan.skipped) SkippedCard(onUnskip = onUnskip, enabled = !skipBusy)

        if (plan.isComputed) {
            // 안 간 일정의 알람은 울리지 않는다. 근거는 남기되 흐리게 둔다(Figma ④ 변형).
            if (plan.skipped) {
                Box(Modifier.alpha(SKIPPED_ALPHA)) { RecommendedAlarmCard(plan) }
            } else {
                RecommendedAlarmCard(plan)
            }
            BreakdownCard(plan)

            // 계산 근거 바로 아래에 "지금 어디쯤" 을 둔다. 같은 여정을 계획과
            // 실제 두 면에서 보여 주는 것이고, 순서가 바뀌면 진행률이 어느
            // 계획에 대한 것인지 연결이 끊긴다. 안 간 일정에는 이동이 없다.
            if (!plan.skipped) {
                TripProgressCard(
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
            NotComputedCard(plan)
        }

        Spacer(Modifier.height(4.dp))

        // 준비 시간을 바꾸는 입구. 확률이 없는 이유가 대개 "블록에 범위가 없다"
        // 라서, 그 안내 바로 아래에 행동할 자리를 둔다.
        BlocksEntryCard(plan, onEditBlocks)

        if (plan.isComputed) {
            // 알람은 이미 자동으로 등록돼 있고 켬/끔은 홈의 스위치가 맡는다.
            // 이 버튼은 근거를 확인하고 닫는 용도라 "확인" 으로 쓴다.
            JitPrimaryButton(label = "확인", onClick = onBack)
            // 안 간 일정은 알람이 울리지 않으므로 알람을 바꾸는 두 입구를 숨긴다.
            if (!plan.skipped) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                // "다시 계산" 은 없앴다. 서버가 블록 정의·밤 학습 뒤 알아서 다시 계산하고,
                // 임박한 일정은 경로 갱신 워커가 15분마다 새로 받는다(todo §2 ③).
                Text(
                    text = "지각 위험 바꾸기",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("risk_entry")
                        .clickable(onClick = onChangeRisk)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            // 도착한 일정은 간 것이다. 표시를 내놓지 않는다. 표시한 일정은 위 카드가 되돌린다.
            if (!plan.skipped && stage != TripStage.ARRIVED) {
                Text(
                    text = "안 감으로 표시",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("event_skip")
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !skipBusy, onClick = onSkip)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // 알람을 정한 뒤에도 제목·시각·장소·출발지·경로·종류를 고친다(Figma ④ `489:9`).
            onEdit?.let { edit ->
                Text(
                    text = "일정 수정",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("event_edit")
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = edit)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "일정 삭제",
                color = JitColor.Red,
                fontSize = 12.sp,
                modifier = Modifier
                    .testTag("event_delete")
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// Partly AI-generated (Claude)
/** "안 감" 으로 표시한 일정 안내와 되돌리기(Figma ④ 변형 `463:17`). */
@Composable
private fun SkippedCard(onUnskip: () -> Unit, enabled: Boolean) {
    JitCard(padding = 14.dp, gap = 6.dp) {
        JitDotLabel(
            text = "안 감으로 표시한 일정",
            dotColor = JitColor.TextSecondary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "알람이 울리지 않고 리포트·학습에서 빠짐",
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

@Composable
private fun RecommendedAlarmCard(plan: AlarmPlanView) {
    JitCard(padding = 18.dp, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "추천" 이 아니라 "적용" 이다. 이 화면에 온 시점에서 이 시각이
            // 이미 이 일정의 알람이고, 추천이라고 적으면 아직 고를 것이 남은
            // 것처럼 읽힌다.
            Text("적용 알람", color = JitColor.TextSecondary, fontSize = 11.sp)
            plan.remaining?.let {
                Text(it, color = JitColor.TextSecondary, fontSize = 11.sp)
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = plan.alarmAt ?: "—",
                color = JitColor.TextPrimary,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = plan.meridiem,
                color = JitColor.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // 이 카드는 알람 시각과 정시 확률만 둔다(todo §2 "적용 알람 정보 줄이기", Figma ④ `1:18`).
        // 경로 기준·계산 시각·경로 대체 경고는 "계산 방법" 으로 옮겼다. 도착 예정은 그 카드의
        // 합계 줄에 이미 있다.
        ProbabilityRow(plan)
    }
}

/**
 * 확률 한 줄.
 *
 * null 이면 왜 없는지까지 적는다. 사용자가 "고장인가?" 하고 의심하지 않게
 * 하려는 것이고, 무엇이 있어야 값이 생기는지 알려 준다. 숫자는 만들지 않는다.
 */
@Composable
private fun ProbabilityRow(plan: AlarmPlanView) {
    val confidence = plan.confidence

    if (confidence.isUnavailable) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            JitDotLabel(
                text = confidence.headline,
                dotColor = JitColor.TextSecondary,
                textColor = JitColor.TextSecondary,
                fontSize = 13,
                dotSize = 8.dp,
            )
            confidence.reason?.let {
                Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
            }
            // 할 수 있는 일이 있으면 강조색으로 적는다. "기다려라" 만 있으면
            // 사용자는 기능이 고장났다고 읽는다.
            confidence.action?.let {
                Text(text = "→ $it", color = JitColor.Accent, fontSize = 10.sp)
            }
        }
        return
    }

    // 목표치(τ)를 넘겼는지로 색을 정한다. 90% 고정으로 비교하면 τ=0.99 를
    // 고른 사용자에게 95% 를 초록으로 보여주게 된다 — 목표 미달인데 안심시킨다.
    val color = if (confidence.meets(plan.tauUsed)) JitColor.Green else JitColor.Amber
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        JitDotLabel(
            text = confidence.headline,
            dotColor = color,
            textColor = color,
            fontSize = 14,
            dotSize = 8.dp,
        )
        if (!confidence.meets(plan.tauUsed)) {
            Text(
                text = "목표치보다 낮음. 지각 위험을 낮추면 알람이 앞당겨짐",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

/** Figma "이렇게 계산했음" — 값마다 출처를 붙인다. */
@Composable
private fun BreakdownCard(plan: AlarmPlanView) {
    JitCard(padding = 14.dp, gap = 9.dp) {
        Text("계산 방법", color = JitColor.TextSecondary, fontSize = 11.sp)
        // 어느 경로를 기준으로 계산했는지. 알람 시각만 보면 "왜 이 시각인가" 의
        // 절반(이동 시간)이 어디서 왔는지 알 수 없다. 문장은 앱이 경로 key 로 만든다([RouteBasis]).
        plan.routeBasis?.let {
            Text(
                text = it,
                color = JitColor.TextPrimary,
                fontSize = 11.sp,
                modifier = Modifier.testTag("plan_route_basis"),
            )
        }
        // 고른 경로가 사라져 다른 경로로 계산했다. **조용히 넘기면 안 된다.** 사용자는 자기가
        // 고른 노선대로 계산된 알람이라고 믿고 그 노선을 타러 간다(`route_choice_honored=false`).
        if (plan.routeFellBack) {
            JitDotLabel(
                text = "고른 경로가 없어져 다른 경로로 계산함. 경로를 다시 고르는 것이 정확함",
                dotColor = JitColor.Amber,
                textColor = JitColor.Amber,
                fontSize = 10,
                bold = false,
                dotSize = 6.dp,
            )
        }

        // 항목별로 막대를 따로 그리면 길이가 **각자의 최대값 기준**이라 셋을
        // 서로 비교할 수 없다. 하나의 바를 비율대로 쪼개면 "이동이 준비의 1.5배"
        // 가 눈으로 읽히고, 바 전체가 합계라는 것도 같은 그림에서 드러난다.
        BreakdownBar(plan.breakdown)

        plan.breakdown.forEach { row ->
            BreakdownRow(row)
            // 블록 내역을 준비 시간 **바로 아래**에 들여 쓴다. 별도 카드로
            // 빼면 어느 줄을 쪼갠 것인지 연결이 끊긴다.
            if (row.kind == PlanRow.Kind.PREP && plan.prepBlocks.isNotEmpty()) {
                PrepBlockList(plan.prepBlocks)
            }
        }

        HorizontalDivider(color = JitColor.Track)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = plan.totalMinutes?.let { "합계 ${it}분" } ?: "합계 —",
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            plan.arrivalLine?.let {
                Text(it, color = JitColor.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        plan.tauUsed?.let {
            // 출처는 서버 effective_tau 순서(직접 고른 값 → 일정 종류 → 기본값)를 따른다.
            // 종류가 없는 일정에 "일정 종류에서 결정됨" 이라고 쓰면 틀린다.
            Text(
                text = listOfNotNull("적용 τ ${"%.2f".format(it)}", plan.tauSourceLabel)
                    .joinToString(" · "),
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
        // 이 시각을 **언제** 계산했는지. 백그라운드가 임박한 일정의 경로를
        // 15분마다 다시 조회하므로 알람 시각과 이동 시간이 조용히 바뀐다.
        // 표시하지 않으면 사용자는 화면의 숫자가 방금 받은 것인지 어제 계산한
        // 것인지 모르고, 배차가 바뀌었는데도 낡은 값을 믿고 움직인다.
        computedAgoLabel(plan.computedAtMillis)?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
    }
}

/**
 * 블록 편집 입구.
 *
 * 블록이 없을 때와 있을 때의 문구가 다르다. 없으면 **준비 시간이 왜 그 값인지
 * 설명할 수 없다는 것**을 말해야 하고, 있으면 "이 아침만 조정" 이라는
 * 것을 밝혀야 한다 — 기본 설정을 바꾸는 것으로 오해하면 다른 날짜까지 바뀐
 * 줄 알게 된다.
 */
@Composable
private fun BlocksEntryCard(plan: AlarmPlanView, onClick: () -> Unit) {
    val empty = plan.prepBlocks.isEmpty()

    JitCard(
        modifier = Modifier.clickable(onClick = onClick),
        padding = 14.dp,
        gap = 6.dp,
        accented = empty && plan.confidence.isUnavailable,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = if (empty) "아침 루틴을 등록하면 근거가 생김" else "이 아침 할 일 고르기",
                dotColor = if (empty) JitColor.Amber else JitColor.Accent,
                fontSize = 13,
                dotSize = 7.dp,
            )
            Text("›", color = JitColor.TextSecondary, fontSize = 18.sp)
        }
        Text(
            text = if (empty) {
                // 준비 범위만으로는 확률이 나오지 않는다(이동 변동 폭도 필요). 약속하지 않는다.
                "준비 시간이 한 덩어리라 왜 그 값인지 설명할 수 없음. 항목으로 쪼개고 " +
                    "최소~최대 범위를 주면 준비 시간 변동이 계산에 반영됨"
            } else {
                "체크를 바꾸면 이 일정의 알람만 다시 계산됨. 기본값은 그대로 남음"
            },
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

/**
 * 준비 블록 내역.
 *
 * 왼쪽 세로선으로 "위 줄을 쪼갠 것" 임을 표시한다. 각 줄에 신고 범위와 관측
 * 수를 적어 학습이 일어나고 있는지 사용자가 직접 확인할 수 있게 한다.
 *
 * 합계를 여기서 다시 더하지 않는다. 병렬 블록은 합이 아니라 max 로 들어가므로
 * 화면이 더하면 위 줄의 값과 어긋난다.
 */
@Composable
private fun PrepBlockList(blocks: List<PrepBlockLine>) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .height((blocks.size * 34).dp)
                .clip(RoundedCornerShape(1.dp))
                .background(JitColor.Track)
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            blocks.forEach { block ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (block.learned) JitColor.Green
                                        else JitColor.TextSecondary
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = block.name,
                                color = JitColor.TextPrimary,
                                fontSize = 11.sp,
                            )
                            if (block.parallelizable) {
                                Spacer(Modifier.width(5.dp))
                                JitChip("병렬", JitColor.Blue, fontSize = 8)
                            }
                        }
                        Text(
                            text = block.detail,
                            color = JitColor.TextSecondary,
                            fontSize = 9.sp,
                        )
                    }
                    Text(
                        text = block.minutesLabel,
                        color = if (block.learned) JitColor.Green else JitColor.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 계산 항목의 색. 바 조각과 범례 점이 **같은 색**이어야 서로 이어진다. */
private fun planRowColor(kind: PlanRow.Kind) = when (kind) {
    PlanRow.Kind.PREP -> JitColor.Accent
    PlanRow.Kind.TRAVEL -> JitColor.Blue
    PlanRow.Kind.BUFFER -> JitColor.Track
}

/**
 * 합계를 항목 비율대로 쪼갠 가로 바 하나.
 *
 * 분 수에 비례해 `weight` 를 준다. 0분 항목은 넣지 않는다 — 폭 0 인 조각은
 * 보이지 않으면서 모서리 자르기만 어긋나게 만든다.
 */
@Composable
private fun BreakdownBar(rows: List<PlanRow>) {
    val shown = rows.filter { it.minutes > 0 }
    if (shown.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        shown.forEach { row ->
            Box(
                Modifier
                    .weight(row.minutes.toFloat())
                    .fillMaxHeight()
                    .background(planRowColor(row.kind))
            )
        }
    }
}

/**
 * 항목 한 줄. 색 점으로 위 바의 어느 조각인지 잇는다.
 *
 * 예전에는 줄마다 막대를 따로 그렸다. 길이가 **그 화면의 최대값 기준**이라
 * 항목 간 비교가 되지 않았고, 합계가 어디에도 그림으로 나타나지 않았다.
 */
@Composable
private fun BreakdownRow(row: PlanRow) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(planRowColor(row.kind))
                )
                Spacer(Modifier.width(9.dp))
                Text(row.label, color = JitColor.TextPrimary, fontSize = 12.sp)
            }
            Text(
                text = "${row.minutes}분",
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        row.note?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
    }
}

/** 알람을 계산할 수 없는 경우. 이유를 그대로 보여준다. */
@Composable
private fun NotComputedCard(plan: AlarmPlanView) {
    val (dot, body) = when (plan.status) {
        "no_home" -> JitColor.Amber to
            "이동 시간을 구하려면 출발지가 필요함. 홈 화면 안내에서 집 위치를 설정하면 바로 계산됨"

        "no_place" -> JitColor.Amber to
            "일정에 장소가 없어서 이동 시간을 구할 수 없음. 장소를 넣으면 계산됨"

        "route_failed" -> JitColor.Red to
            "경로 조회가 실패했음. 출발지와 목적지 주변에 정류장이 없거나 일시적 오류일 수 있음"

        else -> JitColor.TextSecondary to "알람이 아직 계산되지 않았음"
    }

    JitCard(padding = 14.dp, gap = 8.dp) {
        JitDotLabel(
            text = plan.statusLabel ?: "알람 미계산",
            dotColor = dot,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
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
                    headline = "정시 도착 확률 · 아직 계산할 수 없음",
                    reason = "이동 시간은 경로 조회값 하나뿐임",
                    action = "이동 기록이 쌓이면 변동성이 생김",
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
