package com.swpp.wakeup.ui.events

import com.swpp.wakeup.ui.common.JitInlineInput
import com.swpp.wakeup.ui.common.JitLabeledRow
import com.swpp.wakeup.ui.common.JitSectionTitle
import com.swpp.wakeup.ui.common.JitFieldCard
import com.swpp.wakeup.ui.common.JitPillChip
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.RecentDestinations
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import com.swpp.wakeup.ui.common.LineIcons
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.foundation.verticalScroll
import com.swpp.wakeup.ui.common.sheetScrollGuard
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPickerValue
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.common.JitTimeWheelSheet
import com.swpp.wakeup.ui.common.PlacePicker
import com.swpp.wakeup.ui.home.AddField
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// AI-generated (Claude)
/**
 * 종류 선택지. 서버 `GET /api/events/tags` 응답으로 만든다 — 이름과 τ 를 앱에
 * 복사해 두면 서버 값을 바꿨을 때 화면만 낡은 값을 보여준다.
 *
 * τ 를 함께 보여주는 이유는 종류를 고르는 행위가 곧 안전 여유를 정하는
 * 행위이기 때문이다. "시험을 고르면 더 일찍 깨운다" 는 걸 알 수 있어야 한다.
 *
 * 목록 끝에는 "기타" 대신 "+ 추가" 를 둔다(명세 12-a, [TagDropdown]). 사용자가 만든 종류는
 * 서버가 저장하고(B1), τ 를 정하지 않은 종류(`default_tau` null)는 프로필 기본을 쓴다.
 */
internal fun tagOptionsOf(tags: List<com.swpp.wakeup.data.remote.EventTagDto>): List<TagOption> =
    tags.map { tag ->
        TagOption(
            key = tag.key,
            label = tag.label,
            tau = tag.defaultTau?.let { "목표 ${com.swpp.wakeup.domain.model.ProbabilityRule.displayLabel((it * 100).toInt())}" } ?: "프로필 기본",
            custom = tag.custom == true,
        )
    }

// Partly AI-generated (Claude)
/**
 * 방금 만든 종류를 목록에 붙인다. 같은 `key` 가 이미 있으면(서버가 같은 이름을 200 으로 돌려준
 * 경우, 목록을 다시 받은 경우) 두 번 붙이지 않는다.
 */
internal fun tagsWith(
    tags: List<com.swpp.wakeup.data.remote.EventTagDto>,
    created: com.swpp.wakeup.data.remote.EventTagDto,
): List<com.swpp.wakeup.data.remote.EventTagDto> =
    if (tags.any { it.key == created.key }) tags else tags + created

// Partly AI-generated (Claude)
/** 이름을 바꾼 종류를 같은 자리에 끼운다. 목록에 없으면(다른 화면에서 다시 받은 뒤) 그대로 둔다. */
internal fun tagsRenamed(
    tags: List<com.swpp.wakeup.data.remote.EventTagDto>,
    renamed: com.swpp.wakeup.data.remote.EventTagDto,
): List<com.swpp.wakeup.data.remote.EventTagDto> =
    tags.map { if (it.key == renamed.key) renamed else it }

// Partly AI-generated (Claude)
/** 지운 종류를 목록에서 뺀다. */
internal fun tagsWithout(
    tags: List<com.swpp.wakeup.data.remote.EventTagDto>,
    key: String,
): List<com.swpp.wakeup.data.remote.EventTagDto> =
    tags.filterNot { it.key == key }

/** @property custom 내가 만든 종류. 드롭다운에서 [편집] 으로 이름 바꾸기·지우기를 열 수 있다 */
internal data class TagOption(
    val key: String?,
    val label: String,
    val tau: String,
    val custom: Boolean = false,
)

private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

// Partly AI-generated (Claude)
/**
 * 일정 추가. Figma "⑫ 일정 추가".
 *
 * **장소를 넣어야 알람이 계산된다.** 장소가 없으면 서버가 `no_place` 를
 * 돌려주고 알람 시각이 나오지 않는다. 그래서 장소 검색을 폼 가운데 두고
 * 비워두면 어떻게 되는지 화면에서 미리 알린다.
 *
 * 날짜는 달력 팝업(Material3 `DatePicker`, 기본 색이 밝아 앱 팔레트로 칠함), 시각은
 * 휠 바텀시트([JitTimeWheelSheet], iter4 H2-5 · 아이폰처럼 오전/오후 · 시 · 분 열을 굴림)로 고른다.
 */
@Composable
fun AddEventScreen(
    state: HomeViewModel.AddState,
    hasHome: Boolean,
    /**
     * 저장된 집. 장소 검색 옆의 "집" 버튼에 쓴다. null 이면 버튼이 뜨지 않는다.
     *
     * 기본값을 두지 않는다. 두면 호출부가 빠뜨려도 컴파일되고, 집 버튼이
     * 조용히 사라진 것을 아무도 모른다.
     */
    homePlace: com.swpp.wakeup.data.remote.PlaceSearchItem?,
    /** 서버가 준 일정 종류 목록(`GET /api/events/tags`). 비어 있으면 "기타" 만 보인다 */
    tags: List<com.swpp.wakeup.data.remote.EventTagDto>,
    onTitleChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (Int, Int) -> Unit,
    onRepeatDayToggle: (Int) -> Unit,
    onTagChange: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaceSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
    onDestinationHome: () -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenPlaceUrl: (String) -> Unit,
    onOriginQueryChange: (String) -> Unit,
    onOriginSearch: () -> Unit,
    /** 출발지 선택. null 이면 집(서버가 프로필 집을 쓴다) */
    onOriginSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
    onOriginLoadMore: () -> Unit,
    onOriginSortChange: (String) -> Unit,
    onOriginOpenMap: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    /** 집이 없을 때 집 버튼을 누르면 ⑭ 집 주소로 보낸다 */
    onSetHome: () -> Unit,
    onPickRoute: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** "여기서 자고 출발해요 (준비 시간 포함)". 출발지가 집이 아닐 때만 보인다 */
    onIncludePrepChange: (Boolean) -> Unit = {},
    /** 기기에만 둔 다른 집. 출발지 검색을 열면 맨 위에 보인다(Figma ⑯ 변형 `498:9` ②) */
    savedHomes: List<SavedHome> = emptyList(),
    onOriginSavedHome: (SavedHome) -> Unit = {},
    /** 종류 "+ 추가" 대화상자(B1). `open` 이 아니면 그리지 않는다 */
    tagAdd: HomeViewModel.TagAddState = HomeViewModel.TagAddState(),
    onAddTag: () -> Unit = {},
    onTagAddLabelChange: (String) -> Unit = {},
    onTagAddSubmit: () -> Unit = {},
    onTagAddDismiss: () -> Unit = {},
    /** 내 종류 이름 바꾸기·지우기 대화상자. `open` 이 아니면 그리지 않는다 */
    tagEdit: HomeViewModel.TagEditState = HomeViewModel.TagEditState(),
    /** 드롭다운의 내 종류 줄 [편집]. 인자는 종류 key */
    onEditTag: (String) -> Unit = {},
    onTagEditLabelChange: (String) -> Unit = {},
    onTagRename: () -> Unit = {},
    /** true 면 "지울까요?" 를 묻고, false 면 이름 입력으로 돌아간다 */
    onTagAskDelete: (Boolean) -> Unit = {},
    onTagDelete: () -> Unit = {},
    onTagEditDismiss: () -> Unit = {},
    /** 반복 회차 저장 시트(iter4 A2)에서 고른 범위. 시트는 [HomeViewModel.AddState.scopePrompt] 일 때만 뜬다 */
    onSaveScope: (com.swpp.wakeup.data.repository.EventScope) -> Unit = {},
    onSaveScopeDismiss: () -> Unit = {},
    /**
     * 홈 위 바텀시트 안에 그린다(새 일정, H2-8 `545:192`). 머리말에 뒤로 화살표 대신 오른쪽 위 X 를 두고,
     * 내용만 스크롤하며 [알람 맞추기] 는 시트 아래에 고정한다. 높이는 바깥([modifier])이 정한다.
     */
    inSheet: Boolean = false,
    /** 시트의 X. 기본은 [onBack] */
    onClose: () -> Unit = onBack,
    /** 도착지 칩에 넣을 최근 도착지(최신순 2곳, 집 제외). 새 일정에서만 쓴다 */
    recentDestinations: List<com.swpp.wakeup.data.remote.PlaceSearchItem> = emptyList(),
    /** 도착지를 집으로 바꾼 직후의 [되돌리기](H2-9) */
    onUndoDestination: () -> Unit = {},
) {
    if (state.scopePrompt) {
        RecurrenceScopeSheet(
            action = ScopeAction.SAVE,
            choices = state.saveScopeChoices,
            weekdaysChanged = state.weekdaysChanged,
            onChoose = onSaveScope,
            onDismiss = onSaveScopeDismiss,
        )
    }
    if (tagAdd.open) {
        TagAddDialog(
            state = tagAdd,
            onLabelChange = onTagAddLabelChange,
            onSubmit = onTagAddSubmit,
            onDismiss = onTagAddDismiss,
        )
    }
    if (tagEdit.open) {
        TagEditDialog(
            state = tagEdit,
            onLabelChange = onTagEditLabelChange,
            onRename = onTagRename,
            onAskDelete = onTagAskDelete,
            onDelete = onTagDelete,
            onDismiss = onTagEditDismiss,
        )
    }
    // 일정 수정이면 같은 화면을 수정 모드로 쓴다(Figma ⑫ 변형 `488:9`).
    // 새 일정은 알람 추가(H2-8 `545:192`): 필수(어디로 · 몇 시까지 · 어떻게) → 선택(제목 · 반복 · 종류).
    val editing = state.isEditing
    // 검색 칸은 "검색" 을 눌렀을 때만 펼친다. 고르면 접는다.
    // 도착지는 아직 아무것도 없으면 처음부터 펼쳐 둔다 — 무엇을 할지 바로 보이게.
    // 새 일정의 출발지 변경 시트(H2-8 `658:2177`). "출발지 … · 변경" 칩이 연다.
    var originSheet by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.submitting
    // 저장을 기다리는 동안은 닫지 않는다. 10초가 넘으면 닫아도 된다(H2-8 `546:766`, 요청은 뷰모델에서 이어진다).
    val closeLocked = state.submitting && !state.submitSlow
    // 출발지와 도착지가 같은가(H2-9). 도착지 줄을 노랗게 두르고 저장을 끈다 — 서버의 50m 오류보다 먼저 막는다.
    val sameEndpoints = SameEndpoints.same(state, homePlace)
    // 고른 날짜·시각이 이미 지났는가. 화면을 그릴 때의 지금으로 본다(저장할 때 뷰모델이 다시 본다).
    val pastTime = PastTimeRule.isPast(state)

    if (originSheet) {
        OriginSheet(
            state = state,
            homePlace = homePlace,
            hasHome = hasHome,
            savedHomes = savedHomes,
            enabled = enabled,
            onHome = {
                onOriginSelect(null)
                originSheet = false
            },
            onSetHome = {
                originSheet = false
                onSetHome()
            },
            onSavedHome = {
                onOriginSavedHome(it)
                originSheet = false
            },
            onUseCurrentLocation = {
                onUseCurrentLocation()
                originSheet = false
            },
            onQueryChange = onOriginQueryChange,
            onSearch = onOriginSearch,
            onSelect = { item ->
                onOriginSelect(item)
                if (item != null) originSheet = false
            },
            onLoadMore = onOriginLoadMore,
            onSortChange = onOriginSortChange,
            onOpenMap = onOriginOpenMap,
            onOpenPlaceUrl = onOpenPlaceUrl,
            onIncludePrepChange = onIncludePrepChange,
            onDismiss = { originSheet = false },
        )
    }

    // P12: 입력 내용만 스크롤한다. 추가/수정 모두 저장 동작은 아래에 남긴다.
    val bottomBlock: @Composable () -> Unit = {
        state.error?.let {
            Text(
                text = it,
                color = JitColor.Red,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().testTag("add_error"),
            )
        }

        state.destinationUndo?.let { undo ->
            DestinationUndoBar(message = undo.message, enabled = enabled, onUndo = onUndoDestination)
        }

        val canSubmit = if (editing) state.canSubmitEdit(homePlace) else state.canSubmitAdd(hasHome, homePlace)
        if (!editing && !canSubmit && !state.submitting) {
            // 왜 못 누르는지 버튼 바로 위에 한 줄로 말해 준다(H2-8).
            AddRequiredRule.hint(AddRequiredRule.missing(state, hasHome), sameEndpoints, pastTime)?.let {
                Text(
                    text = it,
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("add_required_hint"),
                )
            }
        }
        if (!editing && state.submitting) {
            // 알람 정하는 중(H2-8 `546:766`). 내부 말 대신 지금 하는 일과 기다리는 이유를 말한다.
            Text(
                text = addPendingNote(state.submitSlow),
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("add_pending_note"),
            )
        }

        JitPrimaryButton(
            label = if (editing) "수정 저장" else "알람 맞추기",
            modifier = Modifier.testTag("event_submit"),
            onClick = onSubmit,
            enabled = canSubmit,
            loading = state.submitting,
            loadingLabel = if (editing) null else ADD_PENDING_BUTTON,
        )
    }

    Column(
        modifier = if (inSheet) modifier.fillMaxWidth().imePadding()
        else modifier.fillMaxSize().background(JitColor.Bg),
    ) {
    if (inSheet) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)) {
            SheetTitle(
                title = "알람 추가",
                subtitle = "* 필수 3가지만 정하면 바로 추가할 수 있어요",
                closeTag = "add_sheet_close",
                onClose = onClose,
                closeEnabled = !closeLocked,
                subtitleTag = "add_required_note",
            )
        }
    } else {
        Column(
            Modifier.padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = 8.dp,
            ),
        ) {
            ScreenHeader(title = if (editing) "일정 수정" else "알람 추가", onBack = onBack, enabled = !state.submitting)
        }
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(if (inSheet) Modifier.sheetScrollGuard() else Modifier)
            .verticalScroll(rememberScrollState())
            .testTag("add_event_scroll")
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = 4.dp,
                bottom = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 알람 추가(Figma `545:279`)와 일정 수정이 같은 구성 · 같은 순서다(사용자 피드백 11-16):
        // 어디로 가요 → 몇 시까지 도착해요 → 어떻게 가요 → 제목 → 반복 요일 → 종류.
        if (editing) {
            // 반복 회차는 저장할 때 범위를 고른다(iter4 A2). 범위 수정을 모르는 서버면 그 회차만 바뀌고 요일은 못 고친다.
            editNoteOf(state)?.let { note ->
                Text(
                    text = note,
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("edit_note"),
                )
            }
        } else if (!inSheet) {
            Text(
                text = "* 필수 3가지만 정하면 바로 추가할 수 있어요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.testTag("add_required_note"),
            )
        }

        // ① 어디로 가요? + 출발지 알약(누르면 출발지 변경 시트)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            JitSectionTitle("어디로 가요?", required = !editing, tag = "label_destination", modifier = Modifier.weight(1f))
            OriginPill(
                text = when {
                    state.originLocating -> "현재 위치 확인 중"
                    state.origin != null -> "출발지 ${state.origin.name} · 변경"
                    hasHome -> "출발지 집 · 변경"
                    else -> "출발지 정하기"
                },
                accent = state.origin == null && !hasHome,
                enabled = enabled && !state.originLocating,
                onClick = { originSheet = true },
            )
        }
        // 출발지 오류 · 안내 · "여기서 자고 출발해요" 는 시트를 닫아도 알약 아래에 남는다.
        state.fieldErrors[AddField.ORIGIN]?.let { EndpointError(it, tag = "origin_error") }
        state.originNotice?.let { NoticeLine(it) }
        if (state.origin != null) {
            IncludePrepRow(checked = state.includePrep, enabled = enabled, onChange = onIncludePrepChange)
        }
        DestinationSection(
            state = state,
            recent = recentDestinations,
            homeAvailable = homePlace != null,
            sameEndpoints = sameEndpoints,
            enabled = enabled,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onPlaceSelect = onPlaceSelect,
            onHome = { if (homePlace == null) onSetHome() else onDestinationHome() },
            onLoadMore = onLoadMore,
            onSortChange = onSortChange,
            onOpenMap = onOpenMap,
            onOpenPlaceUrl = onOpenPlaceUrl,
        )

        // ② 몇 시까지 도착해요?
        Spacer(Modifier.height(4.dp))
        JitSectionTitle("몇 시까지 도착해요?", required = !editing, tag = "label_arrival")
        ArrivalDateChips(state.date, onDateChange, enabled)
        ArrivalTimeField(
            hour = state.hour,
            minute = state.minute,
            picked = editing || state.timePicked,
            enabled = enabled,
            onChange = onTimeChange,
        )
        state.fieldErrors[AddField.TIME]?.let { EndpointError(it, tag = "time_error") }
        // 이미 지난 시각(11-16). 서버는 한 번짜리 일정을 거절하지 않아 저장 전에 막는다.
        if (pastTime) {
            EndpointError(if (editing) PastTimeRule.EDIT_MESSAGE else PastTimeRule.ADD_MESSAGE, tag = "time_past_error")
        }

        // ③ 어떻게 가요?
        Spacer(Modifier.height(4.dp))
        JitSectionTitle("어떻게 가요?", required = !editing, fontSize = 12, tag = "label_route")
        val routeReady = state.selectedPlace != null && (state.origin != null || hasHome)
        JitFieldCard(
            label = state.origin?.name?.let { "${it}에서 출발" } ?: "집에서 출발",
            value = state.routeLabel
                ?: "도착지가 바뀌어 경로를 다시 골라야 해요".takeIf { state.destinationUndo?.routeCleared == true }
                ?: if (routeReady) "이동 경로를 선택해 주세요" else "도착지를 먼저 골라 주세요",
            valueColor = if (state.routeLabel != null) JitColor.TextPrimary else JitColor.TextSecondary,
            icon = LineIcons.chevronDown,
            enabled = state.canPickRoute(hasHome, homePlace),
            onClick = onPickRoute,
            tag = "route_field",
        )
        // 앱이 자동으로 채운 경로임을 숨기지 않는다(C8). 경로 선택 화면에서 고르면 사라진다.
        state.routeAutoCaption?.takeIf { state.routeLabel != null }?.let {
            Text(
                text = it,
                color = JitColor.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("route_auto"),
            )
        }
        state.fieldErrors[AddField.ROUTE]?.let { EndpointError(it, tag = "route_error") }

        // 선택 칸: 제목 → 반복 요일 → 종류
        Spacer(Modifier.height(4.dp))
        JitLabeledRow("제목") {
            JitInlineInput(
                value = state.title,
                onValueChange = onTitleChange,
                placeholder = "예: 전공 발표",
                enabled = enabled,
                inputTag = "event_title",
                modifier = Modifier.weight(1f),
            )
        }
        state.fieldErrors[AddField.TITLE]?.let { EndpointError(it, tag = "title_error") }
        if (!editing) {
            Text(
                text = "비우면 장소 이름을 제목으로 써요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 72.dp).testTag("title_hint"),
            )
        }
        // 수정에서는 반복 회차이고 서버가 "이후 일정 모두" 를 받을 때만 요일을 고칠 수 있다. 못 고치면 꺼진 채로 보인다.
        RepeatDaysRow(
            selected = state.repeatWeekdays,
            onToggle = onRepeatDayToggle,
            enabled = enabled && (!editing || state.canEditWeekdays),
        )
        JitLabeledRow("종류") {
            TagDropdown(
                options = tagOptionsOf(tags),
                selected = state.tagKey,
                onChange = onTagChange,
                onAddTag = onAddTag,
                onEditTag = onEditTag,
                enabled = !state.submitting,
                compact = true,
            )
        }
        state.fieldErrors[AddField.TAG]?.let { EndpointError(it, tag = "tag_error") }

    }
    // The fullscreen host already consumes system/IME space. The separate
    // bottom-sheet window keeps its existing root imePadding above.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (inSheet) JitColor.Surface else JitColor.Bg)
            .testTag("event_action_bar")
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = if (inSheet) 22.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { bottomBlock() }
    }
}

/** 알람 정하는 중 버튼 글자(H2-8 `546:766`) */
internal const val ADD_PENDING_BUTTON = "알람 시각을 정하고 있어요…"

/** 알람 정하는 중 버튼 위 한 줄. 10초가 넘으면 닫아도 된다고 말한다(H2-8 `546:1942`) */
internal fun addPendingNote(slow: Boolean): String =
    if (slow) "조금 더 걸리고 있어요. 닫아도 알람은 정해지는 대로 맞춰 둘게요"
    else "잠시만 기다려 주세요. 길과 준비 시간을 확인하고 있어요"

// Partly AI-generated (Claude)
/** 도착 날짜 칩 3개(H2-8): 오늘 · 내일 · 다른 날. "다른 날" 은 달력 팝업을 연다. */
@Composable
private fun ArrivalDateChips(date: LocalDate, onChange: (LocalDate) -> Unit, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        JitPillChip("오늘", selected = date == today, onClick = { onChange(today) }, enabled = enabled, tag = "arrival_date_today")
        JitPillChip("내일", selected = date == tomorrow, onClick = { onChange(tomorrow) }, enabled = enabled, tag = "arrival_date_tomorrow")
        JitPillChip(
            text = otherDateLabel(date, today),
            selected = date != today && date != tomorrow,
            onClick = { picking = true },
            icon = LineIcons.calendar,
            enabled = enabled,
            tag = "arrival_date_other",
        )
    }
    if (picking) {
        JitDatePickerDialog(
            initial = date,
            onPick = { picked -> onChange(picked); picking = false },
            onDismiss = { picking = false },
        )
    }
}

// Partly AI-generated (Claude)
/** "다른 날" 칩의 글자. 오늘·내일이면 "다른 날", 아니면 "9월 20일 일" 처럼 고른 날짜 */
internal fun otherDateLabel(date: LocalDate, today: LocalDate): String =
    if (date == today || date == today.plusDays(1)) "다른 날"
    else "${date.format(DateTimeFormatter.ofPattern("M월 d일"))} ${DAY_NAMES[date.dayOfWeek.value - 1]}"

// Partly AI-generated (Claude)
/** "여기서 자고 출발해요 (준비 시간 포함)" 스위치 한 줄(Figma ⑫ 변형 `488:50`). */
@Composable
internal fun IncludePrepRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag("include_prep")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "여기서 자고 출발해요 (준비 시간 포함)",
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (checked) "준비 시간을 넣어 알람을 앞당겨요" else "끄면 이동 시간만 계산해요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 줄 전체가 스위치다. 안쪽 Switch 는 그리기만 한다(이중 입력을 막는다).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

// Partly AI-generated (Claude)
/**
 * 집 주소 설정 (Figma ⑭).
 *
 * 알람 계산의 출발지다. 저장하면 서버가 기존 일정의 알람을 한꺼번에 다시
 * 계산한다(`PATCH /api/profile` → `recompute_for_user`).
 *
 * **준비 시간을 여기서 받지 않는다.** 예전에는 같은 화면에서 둘을 함께
 * 받았는데, 그러면 가입 직후 이 화면이 `onboarding_prep_min` 을 채워 버려서
 * 준비 시간을 묻는 화면(Figma ⑮)이 뜨지 않았다. 지금은 집만 받고 준비 시간은
 * 다음 화면이 묻는다.
 */
@Composable
fun HomeSetupScreen(
    state: HomeViewModel.HomeSetupState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenPlaceUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 다른 집의 이름 칸(Figma ⑯ 변형 `498:9` ③). 기본 집에서는 그리지 않는다 */
    onLabelChange: (String) -> Unit = {},
    /** 다른 집을 계정(서버)에 저장하는 중이면 true. 안내 문구만 바뀐다(B2) */
    savedOnServer: Boolean = false,
) {
    val extra = state.target == HomeViewModel.HomeTarget.EXTRA
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("home_setup_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = if (extra) "다른 집" else "집 주소", onBack = onBack, enabled = !state.submitting)

        Text(
            text = if (extra) "다른 집은 어디인가요?" else "집이 어디인가요?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (extra) {
                if (savedOnServer) "계정에 저장해요 · 일정 출발지로 고를 수 있어요" else "이 기기에만 저장해요 · 일정 출발지로 고를 수 있어요"
            } else {
                "출발지 설정"
            },
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        if (extra) {
            // Partly AI-generated (Claude): same_as_default 는 Figma F-50 처럼 이름 칸 아래에 빨간 글씨로 보인다
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JitTextField(
                    label = "이름 (예: 본가·기숙사)",
                    inputTag = "home_setup_label",
                    value = state.label,
                    onValueChange = onLabelChange,
                    enabled = !state.submitting,
                )
                state.labelError?.let { EndpointError(it, tag = "home_setup_label_error") }
            }
        }

        PlacePicker(
            label = "주소 검색",
            state = state.place,
            selected = state.selected,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onSelect = onSelect,
            enabled = !state.submitting,
            onLoadMore = onLoadMore,
            onSortChange = onSortChange,
            onOpenMap = onOpenMap,
            onOpenPlaceUrl = onOpenPlaceUrl,
        )

        state.error?.let {
            Text(text = it, color = JitColor.Red, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = if (extra) "이 집 저장" else "이 주소로 저장",
            modifier = Modifier.testTag("home_setup_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )

        // 온보딩에서만 건너뛸 수 있다. 네트워크가 죽었을 때 이 화면이 앱의
        // 입구를 막으면 안 된다. 설정에서 들어온 경우에는 이미 집이 있으므로
        // 건너뛸 것이 없고, 헤더의 뒤로가기가 그 역할을 한다.
        if (state.onboarding) {
            Text(
                text = "나중에 입력",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JitRadius.Hint))
                    .clickable(enabled = !state.submitting, onClick = onSkip)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------

// Partly AI-generated (Claude)
/**
 * 하위 화면 머리. [trailing] 이 있으면 오른쪽 끝에 붙인다(상세 리포트의 "10월 6일").
 * [action] 은 오른쪽 끝의 버튼이다(④ 의 i, iter4 H2-4).
 */
@Composable
internal fun ScreenHeader(
    title: String,
    /** null 이면 "‹" 를 그리지 않는다. 하단 탭의 첫 화면(리포트·내 정보)이다 */
    onBack: (() -> Unit)?,
    enabled: Boolean = true,
    trailing: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = if (trailing != null || action != null) Modifier.fillMaxWidth() else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                text = "‹",
                color = JitColor.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            // 화살표 자리 대신 제목을 화면 여백에 맞춘다.
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            color = JitColor.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(text = trailing, color = JitColor.TextSecondary, fontSize = 13.sp)
        }
        if (action != null) {
            if (trailing == null) Spacer(Modifier.weight(1f))
            action()
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 수정 화면 맨 위 안내. 반복 회차는 저장할 때 범위를 고를 수 있는지에 따라 다르다(iter4 A2).
 * 범위를 고를 수 있으면 안내를 띄우지 않는다(null) — 저장할 때 범위 시트가 묻는다(사용자 요청 11-22).
 */
internal fun editNoteOf(state: HomeViewModel.AddState): String? = when {
    !state.recurring -> "바꾼 항목만 저장해요. 장소나 출발지를 바꾸면 경로를 다시 골라요"
    state.scopeSupported -> null
    else -> "반복 일정의 이 회차만 바뀌어요. 요일은 고칠 수 없어요"
}

/** 고른 요일 요약. 월요일부터 순서대로 "월 · 수", 하나도 없으면 "반복 안 함". */
internal fun repeatSummary(selected: Set<Int>): String =
    if (selected.isEmpty()) "반복 안 함"
    else selected.sorted().filter { it in DAY_NAMES.indices }.joinToString(" · ") { DAY_NAMES[it] }

// AI-generated (Claude)
/** 0~23 시 → 12시간제 시. 0시와 12시는 12 로 보인다. */
internal fun to12Hour(hour: Int): Int = if (hour % 12 == 0) 12 else hour % 12

// AI-generated (Claude)
/** 시(0~23)는 그대로 두고 오전/오후만 바꾼다. 오전 9시 → 오후 9시 = 21시, 오후 12시 → 오전 12시 = 0시. */
internal fun withMeridiem(hour: Int, pm: Boolean): Int = hour % 12 + if (pm) 12 else 0

// AI-generated (Claude)
/** 0~11 시 = 오전, 12~23 시 = 오후. 오전 12시 = 0시, 오후 12시 = 12시. */
internal fun meridiemLabel(hour: Int): String = if (hour < 12) "오전" else "오후"

// AI-generated (Claude)
/**
 * 달력 팝업. 오늘 이전 날짜는 고를 수 없다.
 *
 * Material3 `DatePicker` 는 기본 색이 밝아 어두운 팔레트와 충돌하므로 색을 전부
 * 앱 팔레트로 지정한다. 날짜는 UTC 자정 밀리초로 주고받는다(`DatePickerState` 규약).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JitDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !utcTimeMillis.toUtcLocalDate().isBefore(today)

            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = JitColor.Surface,
        titleContentColor = JitColor.TextSecondary,
        headlineContentColor = JitColor.TextPrimary,
        weekdayContentColor = JitColor.TextSecondary,
        subheadContentColor = JitColor.TextSecondary,
        navigationContentColor = JitColor.TextPrimary,
        yearContentColor = JitColor.TextPrimary,
        disabledYearContentColor = JitColor.Track,
        currentYearContentColor = JitColor.Accent,
        selectedYearContentColor = JitColor.Bg,
        selectedYearContainerColor = JitColor.Accent,
        dayContentColor = JitColor.TextPrimary,
        disabledDayContentColor = JitColor.Track,
        selectedDayContentColor = JitColor.Bg,
        selectedDayContainerColor = JitColor.Accent,
        todayContentColor = JitColor.Accent,
        todayDateBorderColor = JitColor.Accent,
        dividerColor = JitColor.Track,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) onPick(picked.toUtcLocalDate()) else onDismiss()
                },
            ) { Text("확인", color = JitColor.Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", color = JitColor.TextSecondary) }
        },
        colors = colors,
    ) {
        DatePicker(
            state = state,
            colors = colors,
            // 기본 제목과 직접 입력 전환 버튼은 영어 문구라 뺀다.
            title = null,
            showModeToggle = false,
        )
    }
}

// AI-generated (Claude)
private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

// Partly AI-generated (Claude)
private fun Long.toUtcLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

// Partly AI-generated (Claude)
/**
 * 종류 드롭다운. Figma "⑫-a 종류 드롭다운".
 *
 * 칩 6개를 두 줄로 늘어놓던 것을 한 줄로 바꿨다. 항목이 고정된 선택지라면
 * 드롭다운이 자리를 덜 쓰고, "기타" 처럼 목록을 늘리기도 쉽다.
 *
 * [DropdownMenu] 를 쓰되 색만 지정한다. Material3 기본 배경이 밝아서
 * 어두운 팔레트에서 눈에 튀기 때문이다.
 */
@Composable
private fun TagDropdown(
    options: List<TagOption>,
    selected: String?,
    onChange: (String?) -> Unit,
    /** 맨 끝 "+ 추가" */
    onAddTag: () -> Unit,
    /** 내가 만든 종류 줄의 [편집]. 인자는 종류 key */
    onEditTag: (String) -> Unit,
    enabled: Boolean,
    /** 시트의 "종류" 줄에 들어가는 작은 버튼 모양(제목 · 안내 줄 없이) */
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    // 서버 목록을 아직 못 받았으면 고른 key 의 이름을 모른다. 따로 표시한다.
    // 종류를 비우면(null) 서버가 프로필 기본 τ 를 쓴다.
    val current = options.firstOrNull { it.key == selected }
        ?: if (selected == null) TagOption(null, "선택 안 함", "프로필 기본")
        else TagOption(selected, "불러오는 중", "")

    val body: @Composable () -> Unit = {
        Box(modifier = if (compact) Modifier else Modifier.fillMaxWidth()) {
            // 알람 추가 · 수정 시트(Figma `545:279`): 높이 36 · 모서리 12 · 아래 꺾쇠 16 + 13sp 굵게
            if (compact) Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                    .testTag("tag_picker"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JitColor.Surface2)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(LineIcons.chevronDown, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
                    Text(current.label, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            } else Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JitRadius.Hint))
                    .background(JitColor.Surface2)
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current.label,
                    color = JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(current.tau, color = JitColor.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = if (expanded) JitColor.Accent else JitColor.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = JitColor.Surface2,
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                options.forEach { option ->
                    val on = option.key == current.key
                    DropdownMenuItem(
                        onClick = {
                            onChange(option.key)
                            expanded = false
                        },
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (on) "✓" else " ",
                                    color = JitColor.Accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = option.label,
                                    color = if (on) JitColor.Accent else JitColor.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = option.tau,
                                    color = JitColor.TextSecondary,
                                    fontSize = 12.sp,
                                )
                                // 내가 만든 종류만 이름 바꾸기·지우기가 된다(전역 종류는 서버가 404).
                                // 줄을 누르면 고르고, 이 버튼을 누르면 고르지 않고 대화상자만 연다.
                                val editKey = option.key
                                if (option.custom && editKey != null) {
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(
                                        onClick = {
                                            expanded = false
                                            onEditTag(editKey)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.testTag("tag_edit_$editKey"),
                                    ) {
                                        Text("편집", color = JitColor.Accent, fontSize = 12.sp)
                                    }
                                }
                            }
                        },
                    )
                }
                // 명세 12-a: 맨 끝은 "기타" 가 아니라 "+ 추가". 이름을 받아 서버에 저장하고(B1)
                // 목록에 붙여 고른다. 앱에만 저장하면 다른 기기·재설치에서 사라지므로 하지 않는다.
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onAddTag()
                    },
                    modifier = Modifier.testTag("tag_add_item"),
                    text = {
                        Text(
                            text = "+ 추가",
                            color = JitColor.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                )
            }
        }
    }
    if (compact) body() else JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("종류", color = JitColor.TextSecondary, fontSize = 12.sp)
            Text(
                text = "시험·발표처럼 늦으면 안 되는 일정은 알람을 여유 있게 잡아요",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        }
        body()
    }
}

/**
 * 각 줄(제목·시각·출발지·도착지·경로·종류) 바로 아래의 서버 오류. 예: "출발지와 도착지는 50m 이상 떨어져
 * 있어야 한다." 규칙은 서버가 소유하므로 문구를 그대로 쓴다.
 */
@Composable
private fun EndpointError(text: String, tag: String) {
    Text(
        text = text,
        color = JitColor.Red,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

// AI-generated (Claude)
/** 행 아래 짧은 안내. 예: 현재 위치를 못 구했을 때 */
@Composable
private fun NoticeLine(text: String) {
    JitDotLabel(
        text = text,
        dotColor = JitColor.Amber,
        textColor = JitColor.TextSecondary,
        fontSize = 11,
        bold = false,
        dotSize = 6.dp,
    )
}

// Partly AI-generated (Claude)
/**
 * 도착지를 집으로 바꾼 직후 6초 동안 버튼 위에 뜨는 줄(H2-9). 스낵바 모양이지만 시트 안에 그린다 —
 * 바텀시트는 따로 창이라 화면의 스낵바가 시트 뒤에 가려진다.
 */
@Composable
private fun DestinationUndoBar(message: String, enabled: Boolean, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface2)
            .testTag("destination_undo")
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = JitColor.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(JitRadius.Hint))
                .clickable(enabled = enabled, role = Role.Button, onClick = onUndo)
                .testTag("destination_undo_action")
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("되돌리기", color = JitColor.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Partly AI-generated (Claude)
/** 출발지 알약(Figma: 높이 24 · Surface2 · 집 아이콘 14 + 12sp). 누르는 영역은 48dp */
@Composable
private fun OriginPill(text: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("origin_chip"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(JitColor.Surface2)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val color = if (accent) JitColor.Accent else JitColor.TextPrimary
            Icon(LineIcons.house, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 도착지: 검색칸(Figma: 높이 44 · 돋보기) + 아래 칩 줄(최근 도착지 · 고른 곳 · 집). 고른 도착지는 칩이 주황으로 채워진다.
 * 출발지와 같은 곳이면 검색칸에 노란 테두리와 이유 한 줄(H2-9).
 */
@Composable
private fun DestinationSection(
    state: HomeViewModel.AddState,
    recent: List<com.swpp.wakeup.data.remote.PlaceSearchItem>,
    homeAvailable: Boolean,
    sameEndpoints: Boolean,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaceSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
    onHome: () -> Unit,
    onLoadMore: () -> Unit,
    onSortChange: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenPlaceUrl: (String) -> Unit,
) {
    PlacePicker(
        label = "도착할 장소 이름이나 주소",
        state = state.place,
        selected = state.selectedPlace,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        onSelect = onPlaceSelect,
        enabled = enabled,
        onLoadMore = onLoadMore,
        onSortChange = onSortChange,
        onOpenMap = onOpenMap,
        onOpenPlaceUrl = onOpenPlaceUrl,
        compact = true,
        borderColor = if (sameEndpoints) JitColor.Amber else null,
        modifier = if (sameEndpoints) Modifier.testTag("destination_same_border") else Modifier,
    )
    val selected = state.selectedPlace
    // 검색으로 고른 곳이 최근 도착지에 없으면 맨 앞 칩으로 보인다 — 무엇이 골라졌는지 칩 줄에서 보이게.
    val extra = selected?.takeIf { !state.destinationIsHome && recent.none { r -> RecentDestinations.samePlace(r, it) } }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("destination_chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        extra?.let {
            JitPillChip(it.name, selected = true, onClick = {}, icon = LineIcons.mapPin, enabled = enabled, tag = "destination_chip_selected")
        }
        recent.forEachIndexed { index, place ->
            val on = !state.destinationIsHome && selected != null && RecentDestinations.samePlace(selected, place)
            JitPillChip(
                text = place.name,
                selected = on,
                onClick = { onPlaceSelect(place) },
                icon = LineIcons.mapPin,
                enabled = enabled,
                tag = "destination_chip_$index",
            )
        }
        JitPillChip(
            text = "집",
            selected = state.destinationIsHome,
            onClick = onHome,
            icon = LineIcons.house,
            enabled = enabled,
            tag = "destination_chip_home",
        )
    }
    if (!homeAvailable && recent.isEmpty() && selected == null) {
        Text("장소를 검색해 골라 주세요", color = JitColor.TextSecondary, fontSize = 12.sp)
    }
    if (sameEndpoints) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("destination_same_warning"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LineIcons.alert, contentDescription = null, tint = JitColor.Amber, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(SameEndpoints.WARNING, color = JitColor.Amber, fontSize = 12.sp)
        }
    }
    state.fieldErrors[AddField.DESTINATION]?.let { EndpointError(it, tag = "destination_error") }
}

// Partly AI-generated (Claude)
/** "도착할 시각" 필드 카드 + 휠 시트(H2-5). 고르기 전에는 "시각을 골라 주세요" */
@Composable
private fun ArrivalTimeField(hour: Int, minute: Int, picked: Boolean, enabled: Boolean, onChange: (Int, Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    JitFieldCard(
        label = "도착할 시각",
        value = if (picked) AlarmTimeChoice.clockLabel(hour * 60 + minute) else "시각을 골라 주세요",
        valueColor = if (picked) JitColor.TextPrimary else JitColor.TextSecondary,
        icon = LineIcons.clock,
        enabled = enabled,
        onClick = { picking = true },
        tag = "event_time",
    )
    if (picking) {
        JitTimeWheelSheet(
            title = "도착할 시각",
            initialHour = hour,
            initialMinute = minute,
            minuteStep = 1,
            onConfirm = { h, m -> onChange(h, m); picking = false },
            onDismiss = { picking = false },
        )
    }
}

// Partly AI-generated (Claude)
/** 작은 요일 알약은 유지하고 48dp 터치 영역을 확보한다. 좁으면 다음 줄로 감싼다. */
@Composable
private fun RepeatDaysRow(selected: Set<Int>, onToggle: (Int) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        JitLabeledRow("반복 요일") {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DAY_NAMES.forEachIndexed { index, name ->
                    val on = index in selected
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .testTag("repeat_day_$index")
                            .toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(index) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(if (on) JitColor.Accent else JitColor.Surface2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = name,
                                color = when {
                                    on -> JitColor.Bg
                                    enabled -> JitColor.TextPrimary
                                    else -> JitColor.Track
                                },
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            Text(
                text = repeatSummary(selected),
                color = JitColor.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 72.dp).testTag("repeat_summary"),
            )
        }
    }
}
