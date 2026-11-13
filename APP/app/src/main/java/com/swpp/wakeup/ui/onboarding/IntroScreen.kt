// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.launch

/**
 * 첫 실행 안내 세 장(iter4 H2-4, Figma `545:1305` · `546:2214` · `547:2009`).
 *
 * 1장 거꾸로 더한 알람 · 2장 알람 시각 직접 바꾸기 · 3장 쓸수록 맞춰짐. 옆으로 밀거나 [다음] 으로 넘기고,
 * [건너뛰기] 는 어느 장에서든 [onFinish] 다(홈으로 간다). 세 장은 건너뛰기 · 그림(324×340) · 제목 · 본문 ·
 * 쪽 점 · 버튼 자리가 모두 같다 — 건너뛰기와 쪽 점 · 버튼은 넘기는 판 밖에 두어 넘길 때 흔들리지 않는다.
 *
 * 그림의 숫자(7:40 · 28분 · 90% …)는 Figma 공통 시나리오 그대로인 예시다. 사용자의 값이 아니다.
 *
 * 뒤로 가기는 앞 장으로, 1장에서는 [건너뛰기] 와 같다.
 */
@Composable
internal fun IntroScreen(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { IntroPages.COUNT }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == IntroPages.COUNT - 1

    BackHandler {
        if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else onFinish()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .padding(top = JitSpace.ScreenTop, bottom = JitSpace.ScreenBottom),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = JitSpace.ScreenHorizontal),
            horizontalArrangement = Arrangement.End,
        ) {
            SkipButton(onClick = onFinish)
        }
        Spacer(Modifier.height(12.dp))

        // 판 사이를 화면 여백만큼 띄워, 넘기는 동안 옆 장이 여백에 비치지 않게 한다.
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("intro_pager"),
            contentPadding = PaddingValues(horizontal = JitSpace.ScreenHorizontal),
            pageSpacing = JitSpace.ScreenHorizontal,
            verticalAlignment = Alignment.Top,
        ) { index ->
            IntroPage(index)
        }

        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = JitSpace.ScreenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            PageDots(count = IntroPages.COUNT, current = page)
            JitPrimaryButton(
                label = IntroPages.cta(page),
                onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(page + 1) } },
                modifier = Modifier.testTag("intro_next"),
            )
        }
    }
}

/** 오른쪽 위 [건너뛰기](Figma `fb/ButtonSm/Kind=Ghost`, 56×36) */
@Composable
private fun SkipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .testTag("intro_skip")
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "건너뛰기", color = JitColor.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 그림 · 제목 · 본문. 화면이 작으면 이 판만 세로로 밀린다(버튼은 그대로 아래에 있다) */
@Composable
private fun IntroPage(index: Int) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("intro_page_$index"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(JitColor.Surface),
            contentAlignment = Alignment.Center,
        ) {
            // 그림은 Figma 의 324×340 판을 그대로 옮긴 것이라 글자 크기 설정을 따라 커지면 판 밖으로 잘린다.
            // 그래서 그림 안에서는 글자 배율을 1 로 고정하고, Material 기본 줄 높이(24sp)가 작은 글자에 붙어
            // 줄마다 높아지는 것을 막는다(줄 높이는 글자마다 Figma 값으로 적는다).
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1f),
                LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            ) {
                when (index) {
                    0 -> BackwardTimeline()
                    1 -> ChangeTimeIllustration()
                    else -> LearningIllustration()
                }
            }
        }
        Spacer(Modifier.height(12.dp + 20.dp))
        Text(
            text = IntroPages.title(index),
            modifier = Modifier.fillMaxWidth(),
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = introBody(index),
            modifier = Modifier.fillMaxWidth(),
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 2장 본문의 "시각 바꾸기" 는 버튼 이름과 같은 글자를 굵게 써서 화면에서 찾게 한다(Figma 캡션) */
private fun introBody(index: Int) = buildAnnotatedString {
    val body = IntroPages.body(index)
    val bold = IntroPages.BODY_EMPHASIS.takeIf { index == 1 }
    val at = bold?.let { body.indexOf(it) } ?: -1
    if (bold == null || at < 0) {
        append(body)
    } else {
        append(body.substring(0, at))
        withStyle(SpanStyle(color = JitColor.TextPrimary, fontWeight = FontWeight.Bold)) { append(bold) }
        append(body.substring(at + bold.length))
    }
}

/** 쪽 점. 지금 장은 20×6 주황 막대, 나머지는 6×6 회색 점 */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.testTag("intro_dots"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 20.dp else 6.dp, label = "intro-dot")
            val color by animateColorAsState(if (i == current) JitColor.Accent else JitColor.Track, label = "intro-dot-color")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

// --- 1장 · 거꾸로 더한 알람 ---------------------------------------------------

/**
 * 알람(주황 원) → 준비 · 이동 · 여유 막대 → 내가 알려 준 시각(깃발). 막대 색은 ④ 알람 결정의 계산 막대와 같다
 * (준비 주황 · 이동 파랑 · 여유 회색). 끝 시각은 '9:00까지' 다 — 9:00 은 일정 시작이고 여유 10분을 남겨 8:50 에
 * 도착하므로 '9:00 도착' 이라고 쓰지 않는다.
 */
@Composable
private fun BackwardTimeline() {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        TimelineEnd(
            badge = JitColor.Accent,
            icon = LineIcons.alarm,
            iconTint = JitColor.Bg,
            caption = "앱이 맞춘 알람",
        ) {
            Text("오전", Modifier.alignByBaseline(), color = JitColor.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Text("7:40", Modifier.alignByBaseline(), color = JitColor.Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        TimelineSegment(56.dp, JitColor.Accent, LineIcons.sunrise, JitColor.Accent, "준비 28분", "일어나서 집을 나서기까지")
        TimelineSegment(82.dp, JitColor.Blue, LineIcons.train, JitColor.Blue, "이동 42분", "지하철·버스로 302동까지")
        TimelineSegment(38.dp, JitColor.Track, LineIcons.hourglass, JitColor.TextSecondary, "여유 10분", "늦지 않게 남겨 두는 시간")
        TimelineEnd(
            badge = JitColor.Surface2,
            icon = LineIcons.flag,
            iconTint = JitColor.TextPrimary,
            caption = "내가 알려 준 시각",
        ) {
            Text("오전", Modifier.alignByBaseline(), color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = JitColor.TextPrimary)) {
                        append("9:00")
                    }
                    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = JitColor.TextSecondary)) {
                        append("까지")
                    }
                },
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** 타임라인 양 끝. 44 원 + 작은 설명 + 큰 시각 */
@Composable
private fun TimelineEnd(
    badge: Color,
    icon: ImageVector,
    iconTint: Color,
    caption: String,
    time: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(caption, color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            Row(content = time)
        }
    }
}

/** 타임라인 한 구간. 44 폭 레일 가운데 6 폭 막대 + 아이콘 · 이름 · 설명 */
@Composable
private fun TimelineSegment(
    bar: Dp,
    barColor: Color,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(44.dp).padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 6.dp, height = bar).clip(RoundedCornerShape(3.dp)).background(barColor))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// --- 2장 · 알람 시각 직접 바꾸기 ---------------------------------------------

/**
 * 위는 새 홈의 다음 알람 카드(Figma `540:123`)를 줄인 것이고 손가락 원이 [시각 바꾸기] 를 누른다. 아래는 그때
 * 열리는 알람 시각 바꾸기 시트를 줄인 것으로, 고르는 동안 정시 도착 확률이 바뀐다(7:40 90% → 7:50 81% →
 * 8:00 65%). 확률 색은 앱 공통 규칙이다(90% 이상 초록 · 70% 이상 주황 · 그 아래 빨강).
 */
@Composable
private fun ChangeTimeIllustration() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniAlarmCard()
        MiniPicker()
    }
}

@Composable
private fun MiniAlarmCard() {
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Bg)
            .border(1.dp, JitColor.Accent, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(JitColor.Accent))
                Text("다음 알람 · 오늘", color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                MiniTag("추천", JitColor.Accent, JitColor.Accent.copy(alpha = 0.18f))
            }
            MiniTag("정시 도착 확률 90%", JitColor.Green, JitColor.Green.copy(alpha = 0.15f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                Text("오전", Modifier.alignByBaseline(), color = JitColor.Accent, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Text("7:40", Modifier.alignByBaseline(), color = JitColor.Accent, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            }
            MiniChangeTimeButton()
        }
        Text("1시간 28분 뒤 · 9:00 일정", color = JitColor.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            "302동 105호 · 9:00 자료구조 및 알고리즘",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 그림 속 [시각 바꾸기]. 누르는 손가락 원(주황 28% + 테두리 원)을 연필 위에 겹친다 */
@Composable
private fun MiniChangeTimeButton() {
    Row(
        modifier = Modifier
            .drawWithContent {
                drawContent()
                val center = Offset((14 + 8).dp.toPx(), size.height / 2)
                drawCircle(JitColor.Accent.copy(alpha = 0.28f), radius = 24.dp.toPx(), center = center)
                drawCircle(JitColor.Accent, radius = 11.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
            }
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = LineIcons.pencil, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
        Text("시각 바꾸기", color = JitColor.TextPrimary, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MiniPicker() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Bg)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(JitColor.Track))
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 2.dp)) {
            Text("알람 시각", Modifier.weight(1f), color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            Text("정시 도착 확률", color = JitColor.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
        }
        PickerRow(time = "오전 7:40", percent = "90%", tone = JitColor.Green, recommended = true)
        PickerRow(time = "오전 7:50", percent = "81%", tone = JitColor.Amber, selected = true)
        PickerRow(time = "오전 8:00", percent = "65%", tone = JitColor.Red)
    }
}

@Composable
private fun PickerRow(time: String, percent: String, tone: Color, recommended: Boolean = false, selected: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) JitColor.Surface2 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = if (selected) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = time,
                color = if (selected) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = if (selected) 16.sp else 14.sp,
                lineHeight = if (selected) 20.sp else 17.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            if (recommended) MiniTag("추천", JitColor.TextSecondary, JitColor.Surface2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected) Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
            Text(
                text = percent,
                color = tone,
                fontSize = if (selected) 15.sp else 13.sp,
                lineHeight = if (selected) 19.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 알약 태그(Figma `fb/Tag`). 10 Bold */
@Composable
private fun MiniTag(label: String, content: Color, container: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = content, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

// --- 3장 · 쓸수록 맞춰짐 ------------------------------------------------------

/**
 * 위 카드는 한 주의 아침(정시 초록 · 늦음 빨강 · 일정 없음 빈 원), 아래 카드는 예상 준비 28분이 실제 기록 31분으로
 * 맞춰지는 것을 보여 준다(Figma 공통 시나리오: 아침 기록 3번, 평균 31분).
 */
@Composable
private fun LearningIllustration() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IllustrationCard(title = "한 주 기록", meta = "정시 4번 · 늦음 1번") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                WEEK.forEach { (day, result) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(day, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        DayDot(result, 20.dp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(DayResult.ON_TIME, "정시")
                Legend(DayResult.LATE, "늦음")
                Legend(DayResult.NONE, "일정 없음")
            }
        }
        IllustrationCard(title = "준비 시간", meta = "아침 기록 3번") {
            PrepBar(label = "예상", minutes = 28, color = JitColor.TextSecondary)
            PrepBar(label = "실제", minutes = 31, color = JitColor.Accent)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(imageVector = LineIcons.check, contentDescription = null, tint = JitColor.Accent, modifier = Modifier.size(14.dp))
                Text("다음 알람부터 내 기록으로 맞춰요", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun IllustrationCard(title: String, meta: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(meta, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

private enum class DayResult { ON_TIME, LATE, NONE }

private val WEEK = listOf(
    "월" to DayResult.ON_TIME, "화" to DayResult.LATE, "수" to DayResult.ON_TIME, "목" to DayResult.ON_TIME,
    "금" to DayResult.ON_TIME, "토" to DayResult.NONE, "일" to DayResult.NONE,
)

@Composable
private fun DayDot(result: DayResult, size: Dp) {
    val base = Modifier.size(size).clip(CircleShape)
    when (result) {
        DayResult.ON_TIME -> Box(base.background(JitColor.Green))
        DayResult.LATE -> Box(base.background(JitColor.Red))
        DayResult.NONE -> Box(base.border(if (size > 10.dp) 2.dp else 1.5.dp, JitColor.Track, CircleShape))
    }
}

@Composable
private fun Legend(result: DayResult, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        DayDot(result, 7.dp)
        Text(label, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 준비 시간 막대. 길이는 36분을 끝으로 잡았다(Figma 178 폭에 28분 138 · 31분 153) */
@Composable
private fun PrepBar(label: String, minutes: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(26.dp), color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(JitColor.Surface2)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(minutes / PREP_BAR_FULL_MINUTES)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
            )
        }
        Text(
            "${minutes}분",
            Modifier.width(32.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
        )
    }
}

private const val PREP_BAR_FULL_MINUTES = 36f

@Preview(widthDp = 360, heightDp = 800, backgroundColor = 0xFF0E1320, showBackground = true)
@Composable
private fun IntroPreview() {
    JitTheme { IntroScreen(onFinish = {}) }
}
