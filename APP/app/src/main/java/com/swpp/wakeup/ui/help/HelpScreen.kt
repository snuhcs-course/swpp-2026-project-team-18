// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.help

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.Inquiry
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 도움말(iter4 H2-4, Figma `551:1820`). 들어오는 곳: 내 정보 → 도움말, 홈 · 이동 · 리포트 오른쪽 위 ?, 화면 곳곳의 i.
 *
 * 위에서부터 검색 칸 / "자주 묻는 질문 8개" / 질문 여덟 개 / [처음 안내 다시 보기] · [문의하기].
 * 질문은 아코디언이다 — 하나만 펼치고, 펼친 질문은 주황 테두리 카드 하나, 접힌 질문은 이어지는 것끼리 카드 하나에
 * 구분선으로 묶는다. 처음에는 [expand](i 로 왔을 때) 또는 첫 질문을 펼친다. 문구와 규칙은 [HelpFaq].
 *
 * @param onSendInquiry [문의하기] 시트([ContactSheet])가 글을 서버로 보낼 때 부른다. null 이면 버튼을 그리지 않는다
 * @param contactEmail 문의 시트의 "답 받을 이메일" 에 미리 채울 값(로그인 이메일)
 * @param onInquirySent 문의를 보낸 뒤(스낵바)
 */
@Composable
internal fun HelpScreen(
    expand: HelpTopic?,
    onBack: () -> Unit,
    onReplayIntro: () -> Unit,
    modifier: Modifier = Modifier,
    onSendInquiry: (suspend (message: String, email: String) -> Inquiry.Outcome)? = null,
    contactEmail: String? = null,
    onInquirySent: () -> Unit = {},
) {
    var contactOpen by rememberSaveable { mutableStateOf(false) }
    if (contactOpen && onSendInquiry != null) {
        ContactSheet(
            defaultEmail = contactEmail,
            onSend = onSendInquiry,
            onSent = {
                contactOpen = false
                onInquirySent()
            },
            onDismiss = { contactOpen = false },
        )
    }

    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<HelpTopic?>(HelpFaq.initialExpanded(expand)) }
    val results = remember(query) { HelpFaq.search(query) }
    val focus = LocalFocusManager.current

    // 낱말을 바꿔 펼쳐 둔 질문이 빠지면 첫 결과를 펼친다. 찾은 답이 바로 보이게
    LaunchedEffect(query) {
        if (query.isNotBlank()) expanded = HelpFaq.expandAfterSearch(expanded, results)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("help_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "도움말", onBack = onBack)

        SearchField(value = query, onValueChange = { query = it }, onSearch = { focus.clearFocus() })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("자주 묻는 질문", color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = HelpFaq.countLabel(results.size),
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("help_count"),
            )
        }

        if (results.isEmpty()) {
            Text(
                text = HelpFaq.emptyMessage(query),
                color = JitColor.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip16()
                    .background(JitColor.Surface)
                    .padding(16.dp)
                    .testTag("help_empty"),
            )
        }

        HelpFaq.groups(results, expanded).forEach { group ->
            when (group) {
                is FaqGroup.Expanded -> key(group.item.topic) {
                    ExpandedCard(group.item, onToggle = { expanded = HelpFaq.toggle(expanded, it) })
                }
                is FaqGroup.Collapsed -> CollapsedCard(group.items, onToggle = { expanded = HelpFaq.toggle(expanded, it) })
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallButton(
                icon = LineIcons.sunrise,
                label = "처음 안내 다시 보기",
                tag = "help_replay_intro",
                container = JitColor.Surface2,
                content = JitColor.TextPrimary,
                onClick = onReplayIntro,
            )
            if (onSendInquiry != null) {
                SmallButton(
                    icon = LineIcons.help,
                    label = "문의하기",
                    tag = "help_contact",
                    container = Color.Transparent,
                    content = JitColor.Accent,
                    onClick = { contactOpen = true },
                )
            }
        }
    }
}

/** 검색 칸(Figma `search`, 324×44, 모서리 14) */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface2)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LineIcons.search, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(HelpFaq.SEARCH_HINT, color = JitColor.TextSecondary, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = JitColor.TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(JitColor.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("help_search"),
            )
        }
    }
}

/**
 * 펼친 질문(Figma `faq 알람 시각 (펼침)`). 주황 테두리 카드에 아이콘 18 · 질문 15 굵게 · 위 화살표, 그 아래 답.
 * 첫 질문(알람 시각)은 계산식 칩과 한 줄 덧말을 더한다. 펼칠 때 카드가 화면 안에 오게 스크롤한다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandedCard(item: FaqItem, onToggle: (HelpTopic) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { requester.bringIntoView() }
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Accent, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onToggle(item.topic) }
                .testTag("help_q_${item.topic.name}"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(topicIcon(item.topic), contentDescription = null, tint = JitColor.Accent, modifier = Modifier.size(18.dp))
            Text(
                text = item.question,
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(LineIcons.chevronUp, contentDescription = "접기", tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
        }
        Answer(item)
        if (item.topic == HelpTopic.ALARM_TIME) {
            Formula()
            Text(HelpFaq.FORMULA_NOTE, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 답. 문단 사이 10, 글머리 줄은 점 뒤로 들여 쓴다 */
@Composable
private fun Answer(item: FaqItem) {
    Column(
        modifier = Modifier.testTag("help_a_${item.topic.name}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HelpFaq.paragraphs(item.answer).forEach { lines ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line ->
                    if (line.bullet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(JitColor.TextSecondary),
                            )
                            AnswerText(line.text, Modifier.weight(1f))
                        }
                    } else {
                        AnswerText(line.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = JitColor.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp, modifier = modifier)
}

/**
 * 계산식 칩(Figma `formula`): 오전 9:00 − 여유 10분 − 이동 42분 − 준비 28분 = 오전 7:40. 점 색은 ④ 계산 막대와 같다
 * (여유 회색 · 이동 파랑 · 준비 주황). 결과 칩은 주황 테두리 · 알람 아이콘. 좁으면 다음 줄로 넘어간다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Formula() {
    val chips = HelpFaq.formula
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEachIndexed { index, chip ->
            if (index > 0) {
                Text(
                    text = if (chip.kind == FormulaChip.Kind.RESULT) "=" else "−",
                    color = JitColor.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            FormulaChipView(chip)
        }
    }
}

@Composable
private fun FormulaChipView(chip: FormulaChip) {
    val shape = RoundedCornerShape(8.dp)
    val result = chip.kind == FormulaChip.Kind.RESULT
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(JitColor.Bg)
            .then(if (result) Modifier.border(1.dp, JitColor.Accent, shape) else Modifier)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (chip.kind) {
            FormulaChip.Kind.BUFFER -> Dot(JitColor.Track)
            FormulaChip.Kind.TRAVEL -> Dot(JitColor.Blue)
            FormulaChip.Kind.PREP -> Dot(JitColor.Accent)
            FormulaChip.Kind.RESULT ->
                Icon(LineIcons.alarm, contentDescription = null, tint = JitColor.Accent, modifier = Modifier.size(12.dp))
            FormulaChip.Kind.START -> Unit
        }
        chip.label?.let { Text(it, color = JitColor.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        Text(
            text = chip.value,
            color = if (result) JitColor.Accent else JitColor.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 접힌 질문 묶음(Figma `faq 접힌 항목`). 줄마다 아이콘 16 · 질문 14 · 아래 화살표, 사이에 구분선 */
@Composable
private fun CollapsedCard(items: List<FaqItem>, onToggle: (HelpTopic) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip16()
            .background(JitColor.Surface)
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = JitColor.Track)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onToggle(item.topic) }
                    .padding(vertical = 13.dp)
                    .testTag("help_q_${item.topic.name}"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(topicIcon(item.topic), contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
                Text(
                    text = item.question,
                    color = JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(LineIcons.chevronDown, contentDescription = "펼치기", tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 작은 버튼(Figma `fb/ButtonSm`, 높이 36 · 모서리 12). 보조는 회색 칸, 글자 버튼은 바탕 없이 주황 */
@Composable
private fun SmallButton(
    icon: ImageVector,
    label: String,
    tag: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag(tag)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 화면 곳곳의 i(Figma `fb/IconButton/Kind=Plain`, 36 원 · 바탕 없음). ④ 머리에서는 정시 도착 확률 시트를, 그 밖에서는
 * 도움말을 그 항목을 펼친 채로 연다.
 */
@Composable
internal fun InfoButton(
    contentDescription: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 36,
    iconSize: Int = 20,
) {
    Box(
        modifier = modifier
            .testTag(tag)
            .size(size.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LineIcons.info, contentDescription = contentDescription, tint = JitColor.TextPrimary, modifier = Modifier.size(iconSize.dp))
    }
}

/** 질문마다 아이콘(Figma) */
private fun topicIcon(topic: HelpTopic): ImageVector = when (topic) {
    HelpTopic.ALARM_TIME -> LineIcons.alarm
    HelpTopic.ON_TIME_PROBABILITY -> LineIcons.chart
    HelpTopic.PREP_TIME -> LineIcons.sunrise
    HelpTopic.LEARNING -> LineIcons.book
    HelpTopic.RISK_LEVELS -> LineIcons.sliders
    HelpTopic.CHANGE_ALARM -> LineIcons.pencil
    HelpTopic.CALENDAR -> LineIcons.calendar
    HelpTopic.ALARM_DIDNT_RING -> LineIcons.bell
}

private fun Modifier.clip16(): Modifier = this.then(Modifier.clip(RoundedCornerShape(JitRadius.Card)))

@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun HelpScreenPreview() {
    JitTheme {
        HelpScreen(expand = null, onBack = {}, onReplayIntro = {}, onSendInquiry = { _, _ -> Inquiry.Outcome.DONE })
    }
}

@Preview(widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun HelpScreenRingPreview() {
    JitTheme {
        HelpScreen(expand = HelpTopic.ALARM_DIDNT_RING, onBack = {}, onReplayIntro = {})
    }
}
