package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.common.PlacePicker
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 종류 선택지. 서버 `GET /api/events/tags` 응답으로 만든다 — 이름과 τ 를 앱에
 * 복사해 두면 서버 값을 바꿨을 때 화면만 낡은 값을 보여준다.
 *
 * τ 를 함께 보여주는 이유는 종류를 고르는 행위가 곧 안전 여유를 정하는
 * 행위이기 때문이다. "시험을 고르면 더 일찍 깨운다" 는 걸 알 수 있어야 한다.
 *
 * 목록 끝에는 "기타" 대신 "+ 추가" 를 둔다(명세 12-a, [TagDropdown]). 사용자가
 * 만든 종류를 저장하는 일은 서버(B-8)가 하므로 그 전까지 "+ 추가" 는 안내만 띄운다.
 */
internal fun tagOptionsOf(tags: List<com.swpp.wakeup.data.remote.EventTagDto>): List<TagOption> =
    tags.map { tag ->
        TagOption(
            key = tag.key,
            label = tag.label,
            tau = tag.defaultTau?.let { "τ ${"%.2f".format(java.util.Locale.ROOT, it)}" } ?: "",
        )
    }

internal data class TagOption(val key: String?, val label: String, val tau: String)

private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/**
 * 일정 추가. Figma "⑫ 일정 추가".
 *
 * **장소를 넣어야 알람이 계산된다.** 장소가 없으면 서버가 `no_place` 를
 * 돌려주고 알람 시각이 나오지 않는다. 그래서 장소 검색을 폼 가운데 두고
 * 비워두면 어떻게 되는지 화면에서 미리 알린다.
 *
 * 날짜는 달력 팝업, 시각은 시계 다이얼 팝업으로 고른다(Material3 `DatePicker`,
 * `TimePicker`). 두 피커 모두 기본 색이 밝아 어두운 팔레트와 충돌하므로 색을
 * 앱 팔레트로 지정해서 쓴다.
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
    onTagChange: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaceSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
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
) {
    // 검색 칸은 "검색" 을 눌렀을 때만 펼친다. 고르면 접는다.
    // 도착지는 아직 아무것도 없으면 처음부터 펼쳐 둔다 — 무엇을 할지 바로 보이게.
    var originSearching by rememberSaveable { mutableStateOf(false) }
    var destinationSearching by rememberSaveable { mutableStateOf(state.selectedPlace == null) }
    val enabled = !state.submitting
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("add_event_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "일정 추가", onBack = onBack, enabled = !state.submitting)

        Text(
            text = "무슨 일정인가?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "도착 시각에서 거꾸로 계산해 알람을 정함",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        JitTextField(
            label = "일정 제목",
            inputTag = "event_title",
            value = state.title,
            onValueChange = onTitleChange,
            imeAction = ImeAction.Next,
            enabled = !state.submitting,
        )

        DateRow(state.date, onDateChange, enabled = !state.submitting)
        // Figma 순서: 날짜 → 반복 → 시작 시각
        RepeatRow(selected = emptySet(), onToggle = {}, available = REPEAT_AVAILABLE)
        TimeRow(state.hour, state.minute, onTimeChange, enabled = !state.submitting)

        // Figma 13-a 출발·도착 지정: 각 줄 [값] [집] [검색].
        JitCard(gap = 10.dp) {
            // 출발지: null 이면 집. 집이 저장돼 있으면 처음부터 집이 선택된 상태다.
            val originIsHome = state.origin == null && homePlace != null
            EndpointRow(
                label = "출발지",
                value = when {
                    state.originLocating -> "현재 위치를 확인하는 중"
                    state.origin != null -> state.origin.name
                    homePlace != null -> "집 · ${homePlace.name}"
                    else -> "출발지를 정해 주세요"
                },
                hasValue = state.origin != null || homePlace != null,
                isHome = originIsHome,
                homeAvailable = homePlace != null,
                searching = originSearching,
                enabled = enabled && !state.originLocating,
                onHome = {
                    if (homePlace == null) onSetHome()
                    else {
                        onOriginSelect(null)
                        originSearching = false
                    }
                },
                onSearchToggle = { originSearching = !originSearching },
            )
            state.originNotice?.let { NoticeLine(it) }
            if (originSearching) {
                // 현재 위치는 버튼을 늘리지 않고 검색 맨 위에 한 줄로 둔다.
                Text(
                    text = "📍 현재 위치 사용",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .background(JitColor.Surface2)
                        .clickable(enabled = enabled) {
                            onUseCurrentLocation()
                            originSearching = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                PlacePicker(
                    label = "출발지 검색",
                    state = state.originPlace,
                    selected = null,
                    onQueryChange = onOriginQueryChange,
                    onSearch = onOriginSearch,
                    onSelect = { item ->
                        onOriginSelect(item)
                        originSearching = false
                    },
                    enabled = enabled,
                    onLoadMore = onOriginLoadMore,
                    onSortChange = onOriginSortChange,
                    onOpenMap = onOriginOpenMap,
                    onOpenPlaceUrl = onOpenPlaceUrl,
                )
            }

            HorizontalDivider(color = JitColor.Track)

            // 도착지: 집 버튼을 누르면 저장된 집이 들어간다(퇴근·귀가 일정).
            val destination = state.selectedPlace
            EndpointRow(
                label = "도착지",
                value = destination?.name ?: "도착지를 검색해 주세요",
                hasValue = destination != null,
                isHome = destination != null && homePlace != null && destination.isSameSpot(homePlace),
                homeAvailable = homePlace != null,
                searching = destinationSearching,
                enabled = enabled,
                onHome = {
                    if (homePlace == null) onSetHome()
                    else {
                        onPlaceSelect(homePlace)
                        destinationSearching = false
                    }
                },
                onSearchToggle = { destinationSearching = !destinationSearching },
            )
            if (destinationSearching) {
                PlacePicker(
                    label = "도착지 검색",
                    state = state.place,
                    selected = null,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onSelect = { item ->
                        onPlaceSelect(item)
                        if (item != null) destinationSearching = false
                    },
                    enabled = enabled,
                    onLoadMore = onLoadMore,
                    onSortChange = onSortChange,
                    onOpenMap = onOpenMap,
                    onOpenPlaceUrl = onOpenPlaceUrl,
                )
            }
        }

        // Figma 순서: 출발지 → 도착지 → 경로 → 종류 → "일정 추가"
        val canPickRoute = state.canPickRoute(hasHome)
        RouteRow(
            routeLabel = state.routeLabel,
            enabled = canPickRoute,
            ready = state.selectedPlace != null && (state.origin != null || hasHome),
            onClick = onPickRoute,
        )

        TagDropdown(
            options = tagOptionsOf(tags),
            selected = state.tagKey,
            onChange = onTagChange,
            enabled = !state.submitting,
        )

        state.error?.let {
            Text(
                text = it,
                color = JitColor.Red,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = "일정 추가",
            modifier = Modifier.testTag("event_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )
    }
}

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
) {
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
        ScreenHeader(title = "집 주소", onBack = onBack, enabled = !state.submitting)

        Text(
            text = "집이 어디인가요?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "출발지 설정",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

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
            label = "이 주소로 저장",
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

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
        Text(
            text = title,
            color = JitColor.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 날짜·시각 카드 가운데의 값. 누르면 피커 팝업을 띄운다.
 *
 * 밑줄은 "눌러서 바꿀 수 있음" 을 알리는 표시다.
 */
@Composable
private fun PickerValue(
    display: String,
    fontSize: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = display,
            color = JitColor.TextPrimary,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(3.dp))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(JitColor.Track)
        )
    }
}

@Composable
private fun DateRow(date: LocalDate, onChange: (LocalDate) -> Unit, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val label = "${date.format(DateTimeFormatter.ofPattern("M월 d일"))} " +
        DAY_NAMES[date.dayOfWeek.value - 1]
    val relative = when (val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)) {
        0L -> "오늘"
        1L -> "내일"
        in Long.MIN_VALUE..-1L -> "${-days}일 전"
        else -> "${days}일 뒤"
    }

    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("날짜", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                "$relative · 눌러서 선택",
                color = JitColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        PickerValue(display = label, fontSize = 16, enabled = enabled,
            onClick = { picking = true }, modifier = Modifier.testTag("event_date"))
    }

    if (picking) {
        JitDatePickerDialog(
            initial = date,
            onPick = { picked -> onChange(picked); picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun TimeRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }

    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("시작 시각", color = JitColor.TextSecondary, fontSize = 11.sp)
            // 오전/오후는 다이얼을 열지 않고도 바로 바꿀 수 있게 세그먼트로 둔다.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(false, true).forEach { pm ->
                    val on = (hour >= 12) == pm
                    Text(
                        text = if (pm) "오후" else "오전",
                        color = if (on) JitColor.Bg else JitColor.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) JitColor.Accent else JitColor.Surface2)
                            .clickable(enabled = enabled && !on) { onChange(withMeridiem(hour, pm), minute) }
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
        // 화면은 12시간제로 보여준다. 오전/오후는 오른쪽 위에 따로 적는다.
        PickerValue(
            modifier = Modifier.testTag("event_time"),
            display = "%02d:%02d".format(to12Hour(hour), minute),
            fontSize = 24,
            enabled = enabled,
            onClick = { picking = true },
        )
    }

    if (picking) {
        JitTimePickerDialog(
            hour = hour,
            minute = minute,
            onPick = { h, m -> onChange(h, m); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/**
 * 반복 일정을 서버가 받을 수 있는지.
 *
 * 반복 저장은 서버 작업(task.md B-6)이다. 그 전에 칩을 누를 수 있게 두면
 * 사용자는 반복이 저장된 줄 알지만 실제로는 한 번짜리 일정만 생긴다. 그래서
 * B-6 전까지는 칩을 비활성으로 두고 "준비 중" 이라고 적는다.
 *
 * ⏳ B-6 이후: true 로 바꾸고, 고른 요일을 `AddState` 에 담아 생성 요청에 보낸다.
 */
private const val REPEAT_AVAILABLE = false

/**
 * 반복 카드. Figma "⑫ 반복": 오른쪽 위 선택 요약("월 · 수"), 아래 월~일 칩 7개.
 *
 * @param selected 고른 요일. 0 = 월 … 6 = 일 ([DAY_NAMES] 의 인덱스)
 * @param available false 면 칩을 비활성으로 그리고 요약 자리에 "준비 중"
 */
@Composable
private fun RepeatRow(selected: Set<Int>, onToggle: (Int) -> Unit, available: Boolean) {
    JitCard(gap = 10.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("반복", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                text = if (available) repeatSummary(selected) else "준비 중",
                color = if (available) JitColor.Accent else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DAY_NAMES.forEachIndexed { index, name ->
                val on = available && index in selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .background(if (on) JitColor.Accent else JitColor.Surface2)
                        .clickable(enabled = available) { onToggle(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name,
                        color = when {
                            on -> JitColor.Bg
                            available -> JitColor.TextPrimary
                            else -> JitColor.Track
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 고른 요일 요약. 월요일부터 순서대로 "월 · 수", 하나도 없으면 "반복 안 함". */
internal fun repeatSummary(selected: Set<Int>): String =
    if (selected.isEmpty()) "반복 안 함"
    else selected.sorted().filter { it in DAY_NAMES.indices }.joinToString(" · ") { DAY_NAMES[it] }

/** 0~23 시 → 12시간제 시. 0시와 12시는 12 로 보인다. */
internal fun to12Hour(hour: Int): Int = if (hour % 12 == 0) 12 else hour % 12

/** 시(0~23)는 그대로 두고 오전/오후만 바꾼다. 오전 9시 → 오후 9시 = 21시, 오후 12시 → 오전 12시 = 0시. */
internal fun withMeridiem(hour: Int, pm: Boolean): Int = hour % 12 + if (pm) 12 else 0

/** 0~11 시 = 오전, 12~23 시 = 오후. 오전 12시 = 0시, 오후 12시 = 12시. */
internal fun meridiemLabel(hour: Int): String = if (hour < 12) "오전" else "오후"

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
                modifier = Modifier.testTag("event_date_confirm"),
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

/**
 * 시계 다이얼 팝업. 시를 고르면 분 다이얼로 넘어가고 오전/오후 토글이 함께 있다.
 * 내부 값은 0~23 시 그대로다(`TimePickerState.hour`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JitTimePickerDialog(
    hour: Int,
    minute: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "시작 시각",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = JitColor.Surface2,
                    clockDialSelectedContentColor = JitColor.Bg,
                    clockDialUnselectedContentColor = JitColor.TextPrimary,
                    selectorColor = JitColor.Accent,
                    containerColor = JitColor.Surface,
                    periodSelectorBorderColor = JitColor.Track,
                    periodSelectorSelectedContainerColor = JitColor.Accent,
                    periodSelectorUnselectedContainerColor = JitColor.Surface2,
                    periodSelectorSelectedContentColor = JitColor.Bg,
                    periodSelectorUnselectedContentColor = JitColor.TextSecondary,
                    timeSelectorSelectedContainerColor = JitColor.Accent,
                    timeSelectorUnselectedContainerColor = JitColor.Surface2,
                    timeSelectorSelectedContentColor = JitColor.Bg,
                    timeSelectorUnselectedContentColor = JitColor.TextPrimary,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("취소", color = JitColor.TextSecondary) }
                TextButton(modifier = Modifier.testTag("event_time_confirm"),
                    onClick = { onPick(state.hour, state.minute) }) {
                    Text("확인", color = JitColor.Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

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
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    // "+ 추가" 를 눌렀을 때의 안내. B-8 전까지는 저장할 곳이 없다.
    var addNotice by remember { mutableStateOf(false) }
    // 서버 목록을 아직 못 받았으면 고른 key 의 이름을 모른다. 따로 표시한다.
    // 종류를 비우면(null) 서버가 프로필 기본 τ 를 쓴다.
    val current = options.firstOrNull { it.key == selected }
        ?: if (selected == null) TagOption(null, "선택 안 함", "프로필 기본")
        else TagOption(selected, "불러오는 중", "")

    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("종류", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                text = "시험·발표는 τ 를 높게",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
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
                Text(current.tau, color = JitColor.TextSecondary, fontSize = 11.sp)
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
                                    fontSize = 11.sp,
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
                                    fontSize = 10.sp,
                                )
                            }
                        },
                    )
                }
                // 명세 12-a: 맨 끝은 "기타" 가 아니라 "+ 추가".
                // ⏳ B-8 이후: 이름을 입력받아 서버에 저장하고 목록에 붙인다.
                // 앱에만 저장하는 임시 구현은 하지 않는다 — 다른 기기·재설치에서 사라진다.
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        addNotice = true
                    },
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

        if (addNotice) {
            Text(
                text = "종류 직접 추가는 준비 중",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 경로 선택 행.
 *
 * **선택 사항이다.** 고르지 않으면 서버가 도보·대중교통 중 빠른 쪽을 쓴다
 * (`clients.best_route`). 필수로 만들면 일정 하나 넣을 때마다 화면을 한 번 더
 * 지나야 하고, 경로가 뻔한 짧은 거리에서도 그렇다.
 */
@Composable
private fun RouteRow(
    routeLabel: String?,
    enabled: Boolean,
    /** 출발지와 도착지가 둘 다 정해졌는지 */
    ready: Boolean,
    onClick: () -> Unit,
) {
    val hint = when {
        !ready -> "출발지·도착지를 먼저 고름"
        routeLabel != null -> "선택함"
        else -> "선택 안 함 · 최단 경로 사용"
    }

    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("경로", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                text = hint,
                color = if (routeLabel != null) JitColor.Green else JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(JitRadius.Hint))
                .background(JitColor.Surface2)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = routeLabel ?: "경로 고르기 ›",
                color = when {
                    !enabled -> JitColor.Track
                    routeLabel != null -> JitColor.TextPrimary
                    else -> JitColor.Accent
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            if (routeLabel != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "변경 ›",
                    color = if (enabled) JitColor.TextSecondary else JitColor.Track,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * 출발지·도착지 한 줄. Figma 13-a: `[값] [집] [검색]`.
 *
 * 집 버튼은 값이 집이면 주황(활성), 아니면 회색이다. 집이 저장되지 않았으면
 * 흐리게 그리고, 누르면 ⑭ 집 주소로 보낸다([onHome] 이 처리).
 */
@Composable
private fun EndpointRow(
    label: String,
    value: String,
    hasValue: Boolean,
    isHome: Boolean,
    homeAvailable: Boolean,
    searching: Boolean,
    enabled: Boolean,
    onHome: () -> Unit,
    onSearchToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = JitColor.TextSecondary, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = if (hasValue) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (hasValue) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            EndpointButton(
                text = "집",
                active = isHome,
                dimmed = !homeAvailable,
                enabled = enabled,
                onClick = onHome,
            )
            Spacer(Modifier.width(6.dp))
            EndpointButton(
                text = if (searching) "닫기" else "검색",
                active = searching,
                dimmed = false,
                enabled = enabled,
                onClick = onSearchToggle,
            )
        }
    }
}

@Composable
private fun EndpointButton(
    text: String,
    active: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = when {
            active -> JitColor.Bg
            dimmed -> JitColor.Track
            else -> JitColor.TextPrimary
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(if (active) JitColor.Accent else JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

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

/** 같은 지점인지. 검색 결과와 저장된 집은 객체가 달라서 좌표로 비교한다(약 10m). */
internal fun com.swpp.wakeup.data.remote.PlaceSearchItem.isSameSpot(
    other: com.swpp.wakeup.data.remote.PlaceSearchItem,
): Boolean = kotlin.math.abs(lat - other.lat) < 1e-4 && kotlin.math.abs(lng - other.lng) < 1e-4
