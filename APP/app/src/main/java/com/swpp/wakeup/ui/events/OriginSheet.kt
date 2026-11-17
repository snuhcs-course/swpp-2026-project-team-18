// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.ui.common.sheetScrollGuard
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.swpp.wakeup.ui.common.JitIconCircleButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.common.PlacePicker
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/** 출발지 변경 시트에서 지금 고른 줄 */
internal enum class OriginPick { HOME, SAVED, OTHER }

/**
 * 지금 출발지가 어느 줄인가. 집(비어 있음) · 저장한 다른 집(같은 좌표) · 그 밖(검색·현재 위치).
 * 저장한 다른 집은 출발지에 이름만 남아 id 가 없으므로 좌표로 맞춘다.
 */
internal fun originPickOf(origin: PlaceSearchItem?, savedHomes: List<SavedHome>): Pair<OriginPick, String?> {
    if (origin == null) return OriginPick.HOME to null
    val saved = savedHomes.firstOrNull { it.lat == origin.lat && it.lng == origin.lng }
    return if (saved != null) OriginPick.SAVED to saved.id else OriginPick.OTHER to null
}

/**
 * 출발지 변경 바텀시트(H2-8 `658:2177`). 알람 추가의 "출발지 … · 변경" 칩이 연다.
 *
 * 줄을 고르면 바로 닫는다. "장소 검색" 은 같은 시트 안에 검색을 펼친다. 이번 일정의 출발지만 바뀌고
 * 저장된 집 주소는 그대로다(아래 두 줄이 그렇게 말한다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OriginSheet(
    state: HomeViewModel.AddState,
    homePlace: PlaceSearchItem?,
    hasHome: Boolean,
    savedHomes: List<SavedHome>,
    enabled: Boolean,
    onHome: () -> Unit,
    onSetHome: () -> Unit,
    onSavedHome: (SavedHome) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (PlaceSearchItem?) -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenPlaceUrl: (String) -> Unit,
    onIncludePrepChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val (pick, savedId) = originPickOf(state.origin, savedHomes)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("origin_sheet")
                .fillMaxWidth()
                .sheetScrollGuard()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetTitle(title = "출발지 변경", subtitle = "어디에서 출발하나요?", closeTag = "origin_sheet_close", onClose = onDismiss)

            if (homePlace != null || hasHome) {
                OriginOption(
                    caption = if (pick == OriginPick.HOME) "집 · 현재 출발지" else "집",
                    title = homePlace?.address?.takeIf { it.isNotBlank() } ?: homePlace?.name ?: "저장된 집",
                    selected = pick == OriginPick.HOME,
                    icon = LineIcons.house,
                    tag = "origin_opt_home",
                    enabled = enabled,
                    onClick = onHome,
                )
            } else {
                OriginOption(
                    caption = "저장된 집이 없어요",
                    title = "집 주소 정하기",
                    selected = false,
                    icon = LineIcons.house,
                    tag = "origin_opt_set_home",
                    enabled = enabled,
                    onClick = onSetHome,
                    accent = true,
                )
            }
            savedHomes.forEach { home ->
                val on = pick == OriginPick.SAVED && savedId == home.id
                OriginOption(
                    caption = if (on) "${home.label.ifBlank { "다른 집" }} · 현재 출발지" else home.label.ifBlank { "다른 집" },
                    title = home.address?.takeIf { it.isNotBlank() } ?: home.name,
                    selected = on,
                    icon = LineIcons.house,
                    tag = "origin_opt_saved",
                    enabled = enabled,
                    onClick = { onSavedHome(home) },
                )
            }
            // 검색·현재 위치로 고른 곳은 따로 한 줄로 보여 준다. 그래야 지금 무엇이 골라져 있는지 안다.
            if (pick == OriginPick.OTHER) {
                OriginOption(
                    caption = "현재 출발지",
                    title = state.origin?.name.orEmpty(),
                    selected = true,
                    icon = LineIcons.mapPin,
                    tag = "origin_opt_picked",
                    enabled = false,
                    onClick = {},
                )
            }
            OriginOption(
                caption = "지금 있는 곳에서 출발",
                title = if (state.originLocating) "현재 위치를 확인하는 중" else "현재 위치",
                selected = false,
                icon = LineIcons.mapPin,
                tag = "origin_opt_current",
                enabled = enabled && !state.originLocating,
                onClick = onUseCurrentLocation,
            )
            OriginOption(
                caption = "주소나 장소 이름으로 찾기",
                title = "장소 검색",
                selected = searching,
                icon = LineIcons.search,
                tag = "origin_opt_search",
                enabled = enabled,
                onClick = { searching = !searching },
            )
            if (searching) {
                PlacePicker(
                    label = "출발지 검색",
                    state = state.originPlace,
                    selected = null,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onSelect = onSelect,
                    enabled = enabled,
                    onLoadMore = onLoadMore,
                    onSortChange = onSortChange,
                    onOpenMap = onOpenMap,
                    onOpenPlaceUrl = onOpenPlaceUrl,
                )
            }

            // 집이 아닌 곳에서 자고 바로 나서는 날. 시트를 닫아도 칩 아래에 같은 스위치가 남는다.
            if (state.origin != null) {
                IncludePrepRow(checked = state.includePrep, enabled = enabled, onChange = onIncludePrepChange)
            }

            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("이번 알람의 출발지만 바뀌어요.", color = JitColor.TextSecondary, fontSize = 11.sp)
                Text("저장된 집 주소는 그대로 유지돼요.", color = JitColor.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OriginOption(
    caption: String,
    title: String,
    selected: Boolean,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface2)
            .border(width = if (selected) 1.5.dp else 0.dp, color = if (selected) JitColor.Accent else JitColor.Surface2, shape = shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(caption, color = JitColor.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = title,
                color = if (accent) JitColor.Accent else JitColor.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (selected) LineIcons.check else icon,
            contentDescription = if (selected) "선택됨" else null,
            tint = if (selected) JitColor.Accent else JitColor.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 바텀시트 손잡이. 앱의 다른 시트와 같은 모양 */
@Composable
internal fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 10.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(JitColor.Track),
    )
}

/** 시트 머리: 굵은 제목 · 회색 부제 · 오른쪽 위 X */
@Composable
internal fun SheetTitle(
    title: String,
    subtitle: String?,
    closeTag: String,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    subtitleTag: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            subtitle?.let {
                // 맨 앞 "*" 는 필수 표시라 빨갛게(Figma `545:279`)
                Text(
                    text = buildAnnotatedString {
                        if (it.startsWith("*")) {
                            withStyle(SpanStyle(color = JitColor.Red)) { append("*") }
                            append(it.drop(1))
                        } else append(it)
                    },
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    modifier = if (subtitleTag != null) Modifier.testTag(subtitleTag) else Modifier,
                )
            }
        }
        // 36dp 원형 X(누르는 영역 48dp)
        JitIconCircleButton(
            icon = LineIcons.close,
            contentDescription = "닫기",
            onClick = onClose,
            enabled = closeEnabled,
            tag = closeTag,
        )
    }
}
