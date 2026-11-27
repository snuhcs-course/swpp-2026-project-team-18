package com.swpp.wakeup.ui.rooms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.ClockText
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitFieldCard
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitRetryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.common.JitTimeWheelSheet
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Figma 711:2503 / 2560 / 2632 / 2689. Internal pages keep one host navigation entry. */
@Composable
fun RoomsScreen(
    viewModel: RoomsViewModel,
    onExit: () -> Unit,
    onMembershipChanged: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.enter() }
    LaunchedEffect(state.membershipChange) {
        if (state.membershipChange > 0) onMembershipChanged()
    }
    val back = { if (!viewModel.back()) onExit() }
    BackHandler(onBack = back)
    when (state.page) {
        RoomsViewModel.Page.LIST -> RoomsListScreen(state, viewModel::showCreate, viewModel::showJoin,
            viewModel::openRoom, viewModel::refreshList, back)
        RoomsViewModel.Page.CREATE -> CreateRoomScreen(state, viewModel, back)
        RoomsViewModel.Page.JOIN -> JoinRoomScreen(state, viewModel::onCodeChange, viewModel::joinRoom, back)
        RoomsViewModel.Page.DETAIL -> RoomDetailScreen(state, viewModel::refreshRoom, back)
    }
}

@Composable
fun RoomsListScreen(
    state: RoomsViewModel.UiState,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onRoom: (Long) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    RoomPage("약속방", onBack, tag = "rooms_list") {
        RoomSecondary("함께 약속을 정하고, 알람은 각자 맞춰요")
        JitPrimaryButton("방 만들기", onCreate, Modifier.testTag("rooms_create"), enabled = !state.unsupported)
        RoomSecondaryButton("초대 코드로 참가", onJoin, "rooms_join", enabled = !state.unsupported)
        RoomLoadState(state, onRefresh)
        if (!state.unsupported) {
            state.rooms.forEach { room ->
                JitCard(Modifier.testTag("room_${room.id}"), gap = 12.dp) {
                    Text(room.title, color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${room.appointmentLabel()}\n${room.destinationName} · ${room.memberCount}명",
                        color = JitColor.TextPrimary, fontSize = 14.sp)
                    RoomSecondaryButton("방 보기", { onRoom(room.id) }, "room_open_${room.id}")
                }
            }
            if (state.loaded && !state.loading && state.rooms.isEmpty() && state.error == null) {
                RoomInfo("아직 방이 없어요", "방을 만들거나 초대 코드를 입력해 주세요", "rooms_empty")
            }
        }
        if (!state.loading && !state.unsupported && state.error == null) {
            RoomSecondaryButton("새로고침", onRefresh, "rooms_refresh")
        }
    }
}

@Composable
private fun CreateRoomScreen(state: RoomsViewModel.UiState, vm: RoomsViewModel, onBack: () -> Unit) {
    val draft = state.create
    val enabled = !state.submitting
    val focus = LocalFocusManager.current
    val searchPlaces = { focus.clearFocus(); vm.searchPlaces() }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    RoomPage("방 만들기", onBack, enabled, "room_create_form") {
        JitTextField("방 이름", draft.title, vm::onTitleChange, enabled = enabled,
            inputTag = "room_title", isError = "title" in draft.fields)
        RoomFieldError(draft.fields["title"], "room_title_error")
        JitTextField("약속 장소", draft.placeQuery, vm::onPlaceQueryChange, enabled = enabled,
            inputTag = "room_place_query", imeAction = ImeAction.Search, onImeAction = searchPlaces,
            isError = "destination" in draft.fields)
        RoomSecondaryButton(if (draft.searching) "장소를 찾고 있어요" else "장소 이름으로 검색",
            searchPlaces, "room_place_search", enabled = enabled && !draft.searching)
        if (draft.destination != null) {
            RoomInfo(draft.destination.name, draft.destination.address ?: "고른 장소로 약속을 만들어요", "room_selected_place")
        }
        RoomFieldError(draft.fields["destination"], "room_place_error")
        RoomFieldError(draft.searchError, "room_search_error")
        draft.places.forEachIndexed { index, place ->
            RoomPlaceRow(place, { vm.selectPlace(place) }, enabled, "room_place_$index")
        }
        if (draft.searched && draft.places.isEmpty()) {
            RoomSecondary("찾은 장소가 없어요. 다른 이름이나 주소로 검색해 주세요")
        }
        JitFieldCard("약속 날짜", "${draft.startAt.monthValue}월 ${draft.startAt.dayOfMonth}일",
            LineIcons.calendar, { pickingDate = true }, enabled = enabled, tag = "room_date")
        JitFieldCard("약속 시각", ClockText.label(draft.startAt.toLocalTime()), LineIcons.clock,
            { pickingTime = true }, enabled = enabled, tag = "room_time")
        RoomFieldError(draft.fields["start_at"], "room_time_error")
        JitTextField("모두 제때 도착할 목표 (%)", draft.targetPercent, vm::onTargetChange,
            enabled = enabled, inputTag = "room_target", keyboardType = KeyboardType.Number,
            isError = "group_target_probability" in draft.fields)
        RoomFieldError(draft.fields["group_target_probability"], "room_target_error")
        RoomInfo("각자의 알람이 생겨요", "내 집과 준비 시간, 경로로 알람을 정해요. 친구에게는 닉네임과 기상 시각만 보여요.", "room_privacy")
        RoomMutationError(state)
        JitPrimaryButton("방 만들기", { focus.clearFocus(); vm.createRoom() }, Modifier.testTag("room_create_submit"),
            loading = state.submitting, loadingLabel = "방을 만들고 있어요")
        RoomSecondaryButton("취소", onBack, "room_create_cancel", enabled)
    }
    if (pickingDate) {
        RoomDatePicker(draft.startAt.toLocalDate(),
            onPick = { vm.onDateChange(it); pickingDate = false }, onDismiss = { pickingDate = false })
    }
    if (pickingTime) {
        JitTimeWheelSheet("약속 시각", draft.startAt.hour, draft.startAt.minute,
            onConfirm = { h, m -> vm.onTimeChange(LocalTime.of(h, m)); pickingTime = false },
            onDismiss = { pickingTime = false })
    }
}

@Composable
private fun JoinRoomScreen(
    state: RoomsViewModel.UiState,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val join = { focus.clearFocus(); onJoin() }
    RoomPage("초대 코드로 참가", onBack, !state.submitting, "room_join_form") {
        JitTextField("초대 코드", state.joinCode, onCodeChange, enabled = !state.submitting,
            inputTag = "room_code", imeAction = ImeAction.Go, onImeAction = join, isError = state.codeError != null)
        RoomFieldError(state.codeError, "room_code_error")
        RoomInfo("함께 갈 준비를 해 주세요", "내 집과 준비 시간을 먼저 정해 주세요. 참가하면 내 출발지에 맞는 개인 알람을 만들어요.", "room_join_guide")
        RoomMutationError(state)
        JitPrimaryButton("참가하기", join, Modifier.testTag("room_join_submit"), loading = state.submitting,
            loadingLabel = "참가하고 있어요")
        RoomSecondaryButton("취소", onBack, "room_join_cancel", !state.submitting)
    }
}

@Composable
fun RoomDetailScreen(state: RoomsViewModel.UiState, onRefresh: () -> Unit, onBack: () -> Unit) {
    val detail = state.detail
    val context = LocalContext.current
    var copied by remember(detail?.inviteCode) { mutableStateOf(false) }
    RoomPage(detail?.room?.title ?: "약속방", onBack, tag = "room_detail") {
        RoomLoadState(state, onRefresh)
        if (detail != null && !state.unsupported) {
            Text("${detail.room.appointmentLabel()}\n${detail.room.destinationName} · 목표 ${detail.room.targetLabel}",
                color = JitColor.TextPrimary, fontSize = 14.sp)
            RoomSecondaryButton(if (copied) "초대 코드를 복사했어요" else "초대 코드 ${detail.inviteCode} 복사", {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("약속방 초대 코드", detail.inviteCode))
                    copied = true
                }
            }, "room_copy_code")
            JitCard(Modifier.testTag("room_members"), gap = 16.dp) {
                detail.members.forEachIndexed { index, member ->
                    Column(Modifier.testTag("room_member_$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(member.nickname + if (member.isMe) " · 나" else "", color = JitColor.TextPrimary,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(member.alarmLabel(), color = JitColor.Accent,
                            fontSize = if (member.alarmAt == null) 14.sp else 22.sp, fontWeight = FontWeight.Bold)
                        RoomSecondary(member.adjustmentLabel)
                    }
                }
            }
            val suggestion = detail.wakeSuggestion
            val explanation = when {
                suggestion != null -> suggestion.label
                detail.members.any { it.earlierMinutes == null } -> "알람 계산 결과를 기다리고 있어요. 잠시 뒤 새로고침해 주세요"
                detail.members.any { (it.earlierMinutes ?: 0) > 0 } -> "멤버별 기상 시각 아래의 안내를 확인해 주세요"
                else -> "모두 현재 알람을 유지해요"
            }
            RoomInfo("누가 더 일찍 일어나야 하나요?", explanation, "room_wake_suggestion")
            RoomSecondaryButton(if (state.loading) "새로고침하고 있어요" else "새로고침", onRefresh,
                "room_detail_refresh", enabled = !state.loading)
            RoomSecondary("집 주소와 이동 경로는 서로 공유하지 않아요")
        }
    }
}

@Composable
private fun RoomPage(
    title: String,
    onBack: () -> Unit,
    enabled: Boolean = true,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(JitColor.Bg).verticalScroll(rememberScrollState()).testTag(tag)
            .padding(start = JitSpace.ScreenHorizontal, end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop, bottom = JitSpace.ScreenBottom),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onBack, enabled = enabled, modifier = Modifier.size(48.dp).testTag("rooms_back")) {
                Icon(LineIcons.chevronLeft, contentDescription = "이전 화면으로", tint = JitColor.TextPrimary)
            }
            Text(title, Modifier.weight(1f).semantics { heading() }, color = JitColor.TextPrimary,
                fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun RoomLoadState(state: RoomsViewModel.UiState, onRetry: () -> Unit) {
    when {
        state.loading -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = JitColor.Accent, strokeWidth = 2.dp)
            RoomSecondary("약속방을 불러오고 있어요")
        }
        state.unsupported -> {
            RoomInfo("약속방을 아직 쓸 수 없어요", "준비 중인 기능이에요. 잠시 뒤 다시 시도해 주세요", "rooms_unsupported")
            RoomSecondaryButton("다시 시도", onRetry, "rooms_retry")
        }
        state.error != null -> {
            RoomInfo("약속방을 불러오지 못했어요", state.error, "rooms_error")
            RoomSecondaryButton("다시 시도", onRetry, "rooms_retry")
            if (state.detail != null || state.rooms.isNotEmpty()) RoomSecondary("이전에 받아 둔 정보를 보여드려요")
        }
    }
}

@Composable
private fun RoomMutationError(state: RoomsViewModel.UiState) {
    if (state.unsupported) RoomInfo("약속방을 아직 쓸 수 없어요",
        "준비 중인 기능이에요. 입력한 내용은 그대로 있어요. 잠시 뒤 다시 시도해 주세요", "rooms_unsupported")
    else if (state.error != null) RoomInfo(
        if (state.page == RoomsViewModel.Page.JOIN) "참가하지 못했어요" else "방을 만들지 못했어요",
        state.error, "rooms_error")
}

@Composable
private fun RoomInfo(title: String, text: String, tag: String) {
    JitCard(Modifier.testTag(tag), container = JitColor.Surface2) {
        Text(title, color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        RoomSecondary(text)
    }
}

@Composable
private fun RoomSecondary(text: String) {
    Text(text, color = JitColor.TextSecondary, fontSize = 12.sp)
}

@Composable
private fun RoomFieldError(text: String?, tag: String) {
    if (text != null) Text(text, Modifier.testTag(tag), color = JitColor.Red, fontSize = 12.sp)
}

@Composable
private fun RoomSecondaryButton(label: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) {
    com.swpp.wakeup.ui.common.JitSecondaryButton(label, onClick, Modifier.fillMaxWidth().testTag(tag), enabled)
}

@Composable
private fun RoomPlaceRow(place: PlaceSearchItem, onClick: () -> Unit, enabled: Boolean, tag: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(JitRadius.Hint))
        .background(JitColor.Surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(place.name, color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        place.address?.takeIf(String::isNotBlank)?.let { RoomSecondary(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDatePicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate().isBefore(today)
            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )
    val colors = DatePickerDefaults.colors(containerColor = JitColor.Surface, dayContentColor = JitColor.TextPrimary,
        headlineContentColor = JitColor.TextPrimary, titleContentColor = JitColor.TextSecondary,
        weekdayContentColor = JitColor.TextSecondary, subheadContentColor = JitColor.TextSecondary,
        navigationContentColor = JitColor.TextPrimary, yearContentColor = JitColor.TextPrimary,
        currentYearContentColor = JitColor.Accent, selectedYearContainerColor = JitColor.Accent,
        selectedYearContentColor = JitColor.Bg, selectedDayContainerColor = JitColor.Accent,
        selectedDayContentColor = JitColor.Bg, todayContentColor = JitColor.Accent,
        todayDateBorderColor = JitColor.Accent)
    DatePickerDialog(onDismissRequest = onDismiss, colors = colors,
        confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
        }) { Text("확인", color = JitColor.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = JitColor.TextSecondary) } },
    ) {
        DatePicker(state, colors = colors, title = null, showModeToggle = false)
    }
}
