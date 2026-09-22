package com.swpp.wakeup.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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

        state.sections.forEach { section ->
            item(key = "section-${section.label}") {
                SectionHeader(section.label, section.dateLabel)
            }
            items(section.events, key = { "event-${it.id}" }) { event ->
                EventPanel(event = event, onClick = { onEventClick(event) })
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
    JitCard(modifier = Modifier.clickable(onClick = onClick), accented = true, gap = 8.dp) {
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
 * Figma panel-* — 목록의 기본 단위.
 *
 * 한 패널에 시각·제목·장소·이동수단·알람·확률을 모두 담는다. 이 정보 밀도가
 * 캘린더 격자를 쓰지 않는 이유다.
 */
@Composable
private fun EventPanel(event: UpcomingEvent, onClick: () -> Unit) {
    val accent = riskColor(event.risk)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(44.dp)) {
            Text(
                text = event.startTime,
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = event.dayLabel, color = JitColor.TextSecondary, fontSize = 10.sp)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = event.title,
                    color = JitColor.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.placeAndRoute,
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.width(12.dp))

        // 태그는 제목 옆이 아니라 이 열의 맨 위에 둔다. 제목 옆에 두면 긴 제목이
        // 폭을 먹어 칩이 눌리고 글자가 세로로 접혔다. 여기서는 제목 길이와 무관하다.
        Column(
            modifier = Modifier.widthIn(min = 64.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (event.tag != null) {
                JitChip(event.tag, JitColor.Accent)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = event.alarmAt?.let { "알람 $it" } ?: "알람 —",
                color = if (event.hasAlarm) JitColor.Accent else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = event.onTimeProbability?.let { "$it%" } ?: "학습 중",
                color = if (event.onTimeProbability == null) JitColor.TextSecondary else accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
        }
    }
}

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
