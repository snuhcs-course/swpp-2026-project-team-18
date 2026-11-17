// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor

/*
 * 알람 추가 시트(Figma `545:279`)의 조각들. 알람 추가 · 일정 수정 · 출발지 변경이 같이 쓴다.
 *
 * 높이는 `heightIn(min = …)` 로 최소값만 둔다 — 글자 크기를 키운 기기에서 글자가 잘리지 않게(11-16 실기기).
 * 아이콘은 모두 [LineIcons] 의 선 아이콘이다.
 */

/** 둥근 알약 칩. 고르면 주황 채움 · 진한 글자 굵게, 아니면 Surface2 · 흰 글자(Figma: 높이 32, 가로 12, 아이콘 14 + 12sp) */
@Composable
fun JitPillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tag: String? = null,
    fontSize: Int = 12,
    maxTextWidth: Dp = 160.dp,
) {
    val content = if (selected) JitColor.Bg else JitColor.TextPrimary
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) JitColor.Accent else JitColor.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            color = content,
            fontSize = fontSize.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxTextWidth),
        )
    }
}

/**
 * 누르는 필드 카드(Figma: 높이 59, 모서리 14, Surface2, 세로 12 · 가로 14). 위 라벨 11sp · 아래 값 15sp 굵게 ·
 * 오른쪽 아이콘 18dp. [valueColor] 로 "아직 안 고름" 을 흐리게 보인다.
 */
@Composable
fun JitFieldCard(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    valueColor: Color = JitColor.TextPrimary,
    borderColor: Color? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 59.dp)
            .clip(shape)
            .background(JitColor.Surface2)
            .then(if (borderColor != null) Modifier.border(1.5.dp, borderColor, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = JitColor.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = value,
                color = if (enabled) valueColor else JitColor.Track,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(icon, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

/** 섹션 제목. [required] 면 빨간 " *" (Figma 13sp 굵게) */
@Composable
fun JitSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    fontSize: Int = 13,
    tag: String? = null,
) {
    Text(
        text = buildAnnotatedString {
            append(text)
            if (required) withStyle(SpanStyle(color = JitColor.Red)) { append(" *") }
        },
        color = JitColor.TextPrimary,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** 왼쪽 라벨(폭 60, 13sp 굵게) + 간격 12 + 내용(제목 · 반복 요일 · 종류 줄) */
@Composable
fun JitLabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 60.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.widthIn(min = labelWidth),
        )
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/**
 * 밑줄 · 큰 여백 없는 둥근 입력칸(Figma: 모서리 12, Surface2, 가로 14). [leadingIcon] 은 18dp.
 * [trailing] 은 오른쪽 끝(검색 중 표시 등).
 */
@Composable
fun JitInlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 40.dp,
    leadingIcon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    inputTag: String? = null,
    borderColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(JitColor.Surface2)
            .then(if (borderColor != null) Modifier.border(1.5.dp, borderColor, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = JitColor.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = JitColor.TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(JitColor.Accent),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
                modifier = Modifier.fillMaxWidth().then(if (inputTag != null) Modifier.testTag(inputTag) else Modifier),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 검색칸 오른쪽 끝: 찾는 중이면 돌림표, 아니면 누르는 돋보기(기기 테스트 태그 `place_search`) */
@Composable
fun JitSearchAction(loading: Boolean, enabled: Boolean, onClick: () -> Unit, tag: String = "place_search") {
    if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = JitColor.Accent, strokeWidth = 2.dp)
    } else {
        Text(
            text = "검색",
            color = if (enabled) JitColor.Accent else JitColor.Track,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 36dp 원형 아이콘 버튼(시트 X 등). 누르는 영역은 48dp */
@Composable
fun JitIconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(JitColor.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (enabled) JitColor.TextPrimary else JitColor.Track, modifier = Modifier.size(18.dp))
        }
    }
}
