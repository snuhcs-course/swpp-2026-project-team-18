package com.swpp.wakeup.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 홈 화면. Figma "3. 홈 · 일정 목록" (node 65:2).
 *
 * **캘린더 격자가 아니다.** 일정을 위에서 아래로 흐르는 목록으로 보여준다.
 * 격자 한 칸에는 이 앱의 핵심 정보인 "알람 몇 시, 도착 확률 몇 %" 가 안 들어간다.
 * 목록이면 한 줄에 시각·제목·장소·이동수단·알람·확률을 모두 담을 수 있다.
 *
 * **모든 데이터가 서버에서 온다.** 표본 데이터가 없다. 새 계정은 빈 목록이고
 * 사용자가 추가한 만큼만 보인다.
 */
@Composable
fun HomeScreen(
    state: HomeViewModel.UiState,
    avatarInitials: String,
    onAvatarClick: () -> Unit,
    onEventClick: (UpcomingEvent) -> Unit,
    onAddEventClick: () -> Unit,
    onSetHomeClick: () -> Unit,
    onRoutineClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onReportClick: () -> Unit,
    onMorningClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .testTag("home_list")
            .fillMaxSize()
            .background(JitColor.Bg),
        contentPadding = PaddingValues(
            start = JitSpace.ScreenHorizontal,
            end = JitSpace.ScreenHorizontal,
            top = JitSpace.ScreenTop,
            bottom = JitSpace.ScreenBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        item(key = "header") {
            HomeHeader(
                todayLine = state.todayLine,
                avatarInitials = avatarInitials,
                onAvatarClick = onAvatarClick,
            )
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
            item(key = "empty") { EmptyState(onAddEventClick) }
        }

        state.nextAlarm?.let { next ->
            item(key = "next-alarm") { NextAlarmCard(next, onClick = { onEventClick(next) }) }
        }

        // 날짜마다 헤더 아래 둥근 패널 하나에 행들을 담는다(Figma).
        state.sections.forEach { section ->
            item(key = "section-${section.label}") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(section.label, section.dateLabel)
                    EventGroupPanel(
                        events = section.events,
                        highlightedId = state.nextAlarm?.id,
                        onEventClick = onEventClick,
                    )
                }
            }
        }

        if (!state.isEmpty) {
            item(key = "add") {
                Spacer(Modifier.height(4.dp))
                AddEventButton(onAddEventClick)
            }
        }

        // 항상 보여준다. 일정이 없는 새 계정이 먼저 할 일이 루틴 등록이다 —
        // 블록이 없으면 준비 시간이 한 덩어리라 확률 계산이 시작되지 않는다.
        item(key = "routine") { RoutineShortcut(onRoutineClick) }

        item(key = "calendar") { CalendarShortcut(onCalendarClick) }

        item(key = "report") { ReportShortcut(onReportClick) }
    }
}

/**
 * 주간 리포트 입구.
 *
 * 설명에 "얼마나 맞았는지" 를 쓴다. 리포트를 "내 기록" 으로만 소개하면 사용자는
 * 굳이 열지 않는다. 이 화면의 값어치는 **앱이 과신하는지 확인하는 것**이다.
 */
@Composable
private fun ReportShortcut(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag("open_report")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "주간 리포트",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "앱이 말한 확률이 실제로 맞았는지 확인함",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
        Text(text = "›", color = JitColor.TextSecondary, fontSize = 18.sp)
    }
}

/**
 * 캘린더 가져오기 입구.
 *
 * "가져오기" 라고 쓰고 "동기화" 라고 쓰지 않는다. 동기화는 계속 자동으로
 * 맞춰진다는 뜻인데, 이 기능은 사용자가 고른 것만 한 번 올린다. 말과 동작이
 * 다르면 사용자가 캘린더를 지웠을 때 앱에서도 사라질 것이라고 잘못 기대한다.
 */
@Composable
private fun CalendarShortcut(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "캘린더에서 가져오기",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "기기 캘린더의 앞으로 2주 일정을 보고 고른 것만 가져옴",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
        Text(text = "›", color = JitColor.TextSecondary, fontSize = 18.sp)
    }
}

/**
 * 아침 루틴 편집 입구.
 *
 * 계정 메뉴에 숨기지 않고 목록에 둔다. 루틴 블록이 없으면 확률이 만들어지지
 * 않으므로 이 앱을 쓰는 데 필요한 설정인데, 다이얼로그 안에 있으면 아무도
 * 찾지 못한다.
 */
@Composable
private fun RoutineShortcut(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "아침 루틴 설정",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "준비 시간을 항목으로 쪼개면 근거가 생기고 확률 계산이 시작됨",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
        Text(text = "›", color = JitColor.TextSecondary, fontSize = 18.sp)
    }
}

@Composable
private fun HomeHeader(
    todayLine: String,
    avatarInitials: String,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "다가오는 일정",
                color = JitColor.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(text = todayLine, color = JitColor.TextSecondary, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .testTag("open_settings")
                .size(30.dp)
                .clip(CircleShape)
                .background(JitColor.Surface2)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarInitials,
                color = JitColor.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
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
            text = "집 위치를 설정해야 알람이 계산됨",
            dotColor = JitColor.Amber,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "이동 시간을 구하려면 출발지가 필요함. 눌러서 설정",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(onClick = onRetry)
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
            text = "서버에 닿지 못해 마지막으로 받은 값을 보여줌. 이미 걸린 알람은 그대로 울림",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
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
            text = if (blocksLeft > 0) "아침 기록 ${blocksLeft}개 남음" else "아침 기록 정리 필요",
            dotColor = JitColor.Accent,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(
            text = "항목을 마칠 때마다 탭하면 실제 소요가 기록됨. 이 기록이 준비 시간 " +
                "학습의 유일한 재료임",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "눌러서 이어가기",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(onClick = onRetry)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(
            text = "불러오지 못했음",
            dotColor = JitColor.Red,
            textColor = JitColor.TextPrimary,
            fontSize = 12,
            dotSize = 6.dp,
        )
        Text(text = message, color = JitColor.TextSecondary, fontSize = 11.sp)
        Text(text = "눌러서 다시 시도", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

/** 일정이 하나도 없을 때. 새 계정이 처음 보는 화면이다. */
@Composable
private fun EmptyState(onAddEventClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(JitColor.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = JitColor.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "아직 일정이 없음",
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "일정을 추가하면 도착 시각을 거꾸로 계산해\n알람을 정해 줌",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        AddEventButton(onAddEventClick)
    }
}

/**
 * 가장 임박한 알람.
 *
 * 확률은 관측이 쌓이기 전까지 서버가 null 로 준다. 그때는 "학습 중" 으로
 * 표시한다. 임의의 90% 를 보여주면 화면은 그럴싸해지지만 거짓이다.
 */
@Composable
private fun NextAlarmCard(event: UpcomingEvent, onClick: () -> Unit) {
    JitCard(modifier = Modifier.testTag("next_alarm").clickable(onClick = onClick), accented = true, gap = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(text = "다음 알람", dotColor = JitColor.Accent)
            Text(
                text = "${event.startTime} ${event.dayLabel}",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = event.alarmAt ?: "—",
                color = JitColor.Accent,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(text = event.title, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(text = event.placeAndRoute, color = JitColor.TextSecondary, fontSize = 11.sp)

        HorizontalDivider(color = JitColor.Track)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProbabilityLabel(event)
            Text(text = "눌러서 근거 보기", color = JitColor.TextSecondary, fontSize = 11.sp)
        }
    }
}

/**
 * 확률 표시.
 *
 * null 이면 "학습 중" 이다. 서버가 확률을 만들 재료(관측 분포)를 아직 갖고
 * 있지 않다는 뜻이고, 카카오 경로 응답에도 변동성 정보가 없다.
 */
@Composable
private fun ProbabilityLabel(event: UpcomingEvent) {
    val probability = event.onTimeProbability
    if (probability == null) {
        JitDotLabel(
            text = "정시 도착 확률 학습 중",
            dotColor = JitColor.TextSecondary,
            textColor = JitColor.TextSecondary,
            fontSize = 11,
            bold = false,
            dotSize = 6.dp,
        )
    } else {
        JitDotLabel(
            text = "정시 도착 확률 $probability%",
            dotColor = riskColor(event.risk),
            fontSize = 11,
            dotSize = 6.dp,
        )
    }
}

@Composable
private fun SectionHeader(label: String, dateLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = JitColor.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = dateLabel, color = JitColor.TextSecondary, fontSize = 11.sp)
    }
}

/**
 * 하루치 일정을 담는 둥근 패널 하나. 행 사이는 얇은 구분선으로 나눈다.
 *
 * 주황 테두리는 맨 위 카드와 같은 일정 행([highlightedId])에만 준다(Figma).
 */
@Composable
private fun EventGroupPanel(
    events: List<UpcomingEvent>,
    highlightedId: Long?,
    onEventClick: (UpcomingEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface),
    ) {
        events.forEachIndexed { index, event ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = JitColor.Track,
                )
            }
            key(event.id) {
                EventPanel(
                    event = event,
                    highlighted = event.id == highlightedId,
                    onClick = { onEventClick(event) },
                )
            }
        }
    }
}

/**
 * Figma panel-* — 목록의 기본 단위.
 *
 * 한 패널에 시각·제목·장소·이동수단·알람·확률을 모두 담는다. 이 정보 밀도가
 * 캘린더 격자를 쓰지 않는 이유다.
 */
@Composable
private fun EventPanel(event: UpcomingEvent, highlighted: Boolean, onClick: () -> Unit) {
    val accent = riskColor(event.risk)
    val shape = RoundedCornerShape(JitRadius.Card)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (highlighted) Modifier.border(1.dp, JitColor.Accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽: 알람 시각 + AM/PM. 알람을 계산하지 못했으면 "—".
        Column(modifier = Modifier.width(52.dp)) {
            Text(
                text = event.alarmClock ?: "—",
                color = if (event.hasAlarm) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            if (event.alarmMeridiem != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = event.alarmMeridiem, color = JitColor.TextSecondary, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        // 가운데: 색 점 + 제목 (+ 태그 칩) / "장소 · 9:00AM 시작"
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(7.dp))
                // 긴 제목이 칩을 밀어내지 않도록 제목만 줄여 말줄임한다.
                Text(
                    text = event.title,
                    modifier = Modifier.weight(1f, fill = false),
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.tag != null) {
                    Spacer(Modifier.width(6.dp))
                    JitChip(event.tag, JitColor.Accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(event.placeName, "${event.startClock} 시작").joinToString(" · "),
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        // 오른쪽: 스위치 / 확률. 알람을 계산하지 못한 일정은 확률 자리에 사유를 둔다.
        Column(horizontalAlignment = Alignment.End) {
            AlarmSwitch(event)
            Text(
                text = when {
                    event.planStatus != PLAN_STATUS_OK -> event.planStatusLabel ?: "알람 계산 불가"
                    else -> event.onTimeProbability?.let { "$it%" } ?: "학습 중"
                },
                color = if (event.onTimeProbability == null) JitColor.TextSecondary else accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * 일정 행의 알람 켬/끔 스위치(Figma: 행 오른쪽 위).
 *
 * 지금은 **보기 전용**이다. 켬/끔을 저장하고 "하루 첫 알람" 을 판정하는 일은
 * 서버(task.md B-3)가 하므로, 그 API 가 생기기 전에는 앱이 상태를 바꾸지 않는다.
 * 켜짐 = 이 일정에 알람 시각이 있어 실제로 등록된다([UpcomingEvent.hasAlarm]).
 * 알람을 계산하지 못한 일정(`planStatus != ok`)은 비활성으로 보인다.
 *
 * ⏳ B-3 이후: 서버의 `alarm_on` 으로 칠하고, onCheckedChange 로 저장 API 를 부른다.
 */
@Composable
private fun AlarmSwitch(event: UpcomingEvent) {
    // 기본 Switch(52x32dp)는 행에 비해 크다. scale 은 그리는 크기만 줄이고 차지하는
    // 자리는 그대로라, 바깥 Box 로 자리를 34x20dp 로 고정하고 그 안에서 줄여 그린다.
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 20.dp)
            // 스위치 터치가 행 클릭(알람 결정 화면 이동)으로 새지 않게 여기서 먹는다.
            // B-3 전에는 아무 동작도 하지 않는다.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Switch(
                checked = event.hasAlarm,
                onCheckedChange = null,
                enabled = event.planStatus == PLAN_STATUS_OK,
                modifier = Modifier.scale(0.62f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JitColor.TextPrimary,
                    checkedTrackColor = JitColor.Accent,
                    checkedBorderColor = JitColor.Accent,
                    uncheckedThumbColor = JitColor.TextSecondary,
                    uncheckedTrackColor = JitColor.Surface2,
                    uncheckedBorderColor = JitColor.TextSecondary,
                    disabledUncheckedThumbColor = JitColor.TextSecondary.copy(alpha = 0.4f),
                    disabledUncheckedTrackColor = JitColor.Surface2,
                    disabledUncheckedBorderColor = JitColor.TextSecondary.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

/** [UpcomingEvent.planStatus] 의 "알람 계산 완료" 값 */
private const val PLAN_STATUS_OK = "ok"

@Composable
private fun AddEventButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface2)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+  일정 추가",
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
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
            avatarInitials = "진호",
            onAvatarClick = {},
            onEventClick = {},
            onAddEventClick = {},
            onSetHomeClick = {},
            onRoutineClick = {},
            onCalendarClick = {},
            onReportClick = {},
            onMorningClick = {},
            onRetry = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun HomeFilledPreview() {
    val event = UpcomingEvent(
        id = 1,
        startTime = "09:00",
        dayLabel = "목",
        title = "자료구조 및 알고리즘",
        placeAndRoute = "서울대 302동 · 버스 20분 · 4.6km",
        alarmAt = "7:32",
        onTimeProbability = null,
        tag = "수업",
        planStatus = "ok",
        planStatusLabel = "계산 완료",
        startAtEpochSecond = 0,
        startDate = java.time.LocalDate.now(),
        alarmClock = "7:32",
        alarmMeridiem = "AM",
        placeName = "서울대 302동",
        startClock = "9:00AM",
    )
    JitTheme {
        HomeScreen(
            state = HomeViewModel.UiState(
                nickname = "진호",
                loading = false,
                sections = listOf(EventSection("오늘", "9월 17일 목", listOf(event))),
                nextAlarm = event,
                totalCount = 1,
            ),
            avatarInitials = "진호",
            onAvatarClick = {},
            onEventClick = {},
            onAddEventClick = {},
            onSetHomeClick = {},
            onRoutineClick = {},
            onCalendarClick = {},
            onReportClick = {},
            onMorningClick = {},
            onRetry = {},
        )
    }
}
