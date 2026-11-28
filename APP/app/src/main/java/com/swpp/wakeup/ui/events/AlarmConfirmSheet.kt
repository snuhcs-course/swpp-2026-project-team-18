// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.ui.common.sheetScrollGuard
import com.swpp.wakeup.domain.model.ProbabilityRule
import com.swpp.wakeup.ui.common.PlacedLabels
import com.swpp.wakeup.ui.common.LabelLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.common.probabilityColor
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/** ④ 알람 결정의 막대와 같은 색(준비 주황 · 이동 파랑 · 여유 회색) */
private fun segmentColor(kind: AlarmConfirmView.Kind): Color = when (kind) {
    AlarmConfirmView.Kind.PREP -> JitColor.Accent
    AlarmConfirmView.Kind.TRAVEL -> JitColor.Blue
    AlarmConfirmView.Kind.BUFFER -> JitColor.Track
}

/**
 * 알람 확인 시트(H2-8 `547:2095`, 캡션 `547:2823`). 새 일정을 만들어 알람이 정해지면 한 번 띄운다.
 *
 * 몇 시에 깨우는지를 문장으로 먼저 말하고, 기상 · 출발 · 도착 · 시작을 한 줄 타임라인으로 보여 준다.
 * 확인 없이 닫아도(아래로 밀기 · 뒤로 · 바깥 누르기) 알람은 이미 그 시각으로 맞춰져 있다.
 *
 * [onConfirm]은 호스트가 서버에 확인 상태를 저장하는 동작으로 연결한다(BE-H3a).
 * 확인 없이 닫으면 "확인 전" 상태를 유지하고 등록된 알람은 그대로 울린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmConfirmSheet(
    view: AlarmConfirmView,
    onChangeTime: () -> Unit,
    onDetails: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** 더 빠른 경로 카드의 [이 경로로 바꾸기]. null 이면 카드를 두지 않는다 */
    onSwitchRoute: ((com.swpp.wakeup.domain.model.FasterRouteOffer) -> Unit)? = null,
) {
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
    ) {
        AlarmConfirmContent(
            view = view, onChangeTime = onChangeTime, onDetails = onDetails,
            onConfirm = onConfirm, onSwitchRoute = onSwitchRoute,
            modifier = Modifier.heightIn(max = maximumHeight),
        )
    }
}

/** P10: the evidence scrolls independently from the always-visible confirmation. */
@Composable
internal fun AlarmConfirmContent(
    view: AlarmConfirmView,
    onChangeTime: () -> Unit,
    onDetails: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchRoute: ((com.swpp.wakeup.domain.model.FasterRouteOffer) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("alarm_confirm_sheet")
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .sheetScrollGuard()
                .verticalScroll(rememberScrollState())
                .testTag("alarm_confirm_scroll")
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(JitColor.Green.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LineIcons.check, contentDescription = null, tint = JitColor.Green, modifier = Modifier.size(26.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = buildAnnotatedString {
                        append("${view.dayWord} ")
                        withStyle(SpanStyle(color = JitColor.Accent)) { append(view.alarmClock) }
                        append("에 깨워 드릴게요")
                    },
                    color = JitColor.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("alarm_confirm_headline"),
                )
                if (view.subline.isNotEmpty()) {
                    Text(view.subline, color = JitColor.TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }

            PlanCard(view)
            // 만들기 응답이 더 빠른 경로를 알려 줬으면 바로 고를 수 있게 한다(11-16 사용자).
            val faster = view.faster?.takeIf { onSwitchRoute != null && it.isOpen(System.currentTimeMillis()) }
            if (faster != null && onSwitchRoute != null) {
                com.swpp.wakeup.ui.alarm.FasterRouteCard(
                    offer = faster,
                    busy = false,
                    onSwitch = { onSwitchRoute(faster) },
                    tagPrefix = "alarm_confirm_faster_route",
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .border(1.dp, JitColor.Track, RoundedCornerShape(JitRadius.Hint))
                        .clickable(role = Role.Button, onClick = onChangeTime)
                        .testTag("alarm_confirm_change")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LineIcons.pencil, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("시각 바꾸기", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .clickable(role = Role.Button, onClick = onDetails)
                        .testTag("alarm_confirm_details")
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("자세히 보기 ›", color = JitColor.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().background(JitColor.Surface)
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 22.dp),
        ) {
            JitPrimaryButton(
                label = "이 시각으로 알람 맞추기",
                onClick = onConfirm,
                modifier = Modifier.testTag("alarm_confirm_ok"),
            )
        }
    }
}

@Composable
private fun PlanCard(view: AlarmConfirmView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface2)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(view.cardTitle, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            view.probability?.let { percent ->
                val tone = probabilityColor(percent, view.tau)
                Text(
                    text = "정시 도착 확률 ${ProbabilityRule.displayLabel(percent)}",
                    color = tone,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .background(tone.copy(alpha = 0.15f))
                        .testTag("alarm_confirm_probability")
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Timeline(view)

        Text(
            text = view.footer,
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.testTag("alarm_confirm_footer"),
        )
    }
}

/**
 * 한 줄 타임라인: 구간 이름 줄 · 점과 선 · 시각과 이름 줄. 구간 폭은 분에 비례한다.
 * 점은 구간 경계에 있다(첫 점 = 기상, 끝 점 = 시작). 시각을 모르는 점이 있으면 점만 고르게 놓는다.
 */
@Composable
private fun Timeline(view: AlarmConfirmView) {
    val fractions = AlarmConfirmText.fractions(view.segments)
    val aligned = fractions.isNotEmpty()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val full = maxWidth
        val dot = 10.dp
        val track = full - dot
        // 점의 가로 위치(0..1). 분에 비례하도록 순수 함수가 정했다
        val stops: List<Float> = view.points.map { it.at }
        val dotPx = with(LocalDensity.current) { dot.toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 구간 이름(준비/이동/여유 N분)은 구간 가운데에 놓되, 실제 글자 폭으로 재서 겹치면 밀거나 두 줄로 엇갈린다.
            // 예전에는 구간 폭(weight)에 글자를 넣어 짧은 구간("여유 2분")이 잘렸다.
            if (aligned) {
                PlacedLabels(
                    count = view.segments.size,
                    anchors = { widths, w ->
                        val track = w - dotPx
                        var acc = 0f
                        LabelLayout.centered(
                            fractions.map { f -> (dotPx / 2 + track * (acc + f / 2)).also { acc += f } },
                            widths,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("alarm_confirm_segments"),
                ) { i ->
                    val seg = view.segments[i]
                    Text(
                        text = seg.label,
                        color = if (seg.kind == AlarmConfirmView.Kind.BUFFER) JitColor.TextSecondary else segmentColor(seg.kind),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(dot)) {
                if (aligned) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = dot / 2).align(Alignment.Center)) {
                        view.segments.forEachIndexed { i, seg ->
                            Box(
                                Modifier
                                    .weight(fractions[i].coerceAtLeast(0.001f))
                                    .height(3.dp)
                                    .background(segmentColor(seg.kind)),
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dot / 2)
                            .height(3.dp)
                            .align(Alignment.Center)
                            .background(JitColor.Track),
                    )
                }
                stops.forEachIndexed { i, x ->
                    Box(
                        Modifier
                            .offset(x = track * x)
                            .size(dot)
                            .clip(CircleShape)
                            .background(if (i == 0) JitColor.Accent else JitColor.TextPrimary),
                    )
                }
            }
            // 시각 · 이름 묶음. 높이를 고정하지 않는다(큰 글자에서 이름 줄이 잘렸다, 11-16 실기기).
            PlacedLabels(
                count = view.points.size,
                anchors = { widths, w ->
                    val track = w - dotPx
                    LabelLayout.centered(stops.map { dotPx / 2 + track * it }, widths)
                },
                modifier = Modifier.fillMaxWidth().testTag("alarm_confirm_ticks"),
            ) { i ->
                val p = view.points[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.clock, color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(p.name, color = JitColor.TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
