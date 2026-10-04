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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
 * 종류 선택지. 서버 `EventTag.key` 와 값이 맞아야 한다.
 *
 * τ 는 마이그레이션 `0002_seed_event_tags` 의 값이다. 화면에 함께 보여주는
 * 이유는 종류를 고르는 행위가 곧 안전 여유를 정하는 행위이기 때문이다.
 * "시험을 고르면 더 일찍 깨운다" 는 걸 알 수 있어야 한다.
 *
 * **마지막 "기타" 는 `key = null` 이다.** 태그를 비우면 서버가
 * `profile.default_tau` 로 내려간다(`Event.effective_tau`). τ 숫자를 여기
 * 복사해 두면 프로필 기본값을 바꿀 때 낡은 값이 남으므로 복사하지 않는다.
 */
private val TAG_OPTIONS: List<TagOption> = listOf(
    TagOption("class", "수업", "τ 0.90"),
    TagOption("exam", "시험", "τ 0.99"),
    TagOption("presentation", "발표", "τ 0.98"),
    TagOption("train", "기차·비행", "τ 0.99"),
    TagOption("parttime", "알바", "τ 0.95"),
    TagOption("meetup", "약속", "τ 0.85"),
    TagOption(null, "기타", "프로필 기본"),
)

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
    onPickRoute: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
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
            value = state.title,
            onValueChange = onTitleChange,
            imeAction = ImeAction.Next,
            enabled = !state.submitting,
        )

        DateRow(state.date, onDateChange, enabled = !state.submitting)
        // Figma 순서: 날짜 → 반복 → 시작 시각
        RepeatRow(selected = emptySet(), onToggle = {}, available = REPEAT_AVAILABLE)
        TimeRow(state.hour, state.minute, onTimeChange, enabled = !state.submitting)

        PlacePicker(
            label = "장소 검색",
            state = state.place,
            selected = state.selectedPlace,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onSelect = onPlaceSelect,
            enabled = !state.submitting,
            onLoadMore = onLoadMore,
            onSortChange = onSortChange,
            onOpenMap = onOpenMap,
            onOpenPlaceUrl = onOpenPlaceUrl,
            // 목적지가 집인 경우도 있다 — 퇴근·귀가 일정이 그렇다.
            homePlace = homePlace,
        )

        TagDropdown(state.tagKey, onTagChange, enabled = !state.submitting)

        // 집이 없어도 경로를 고를 수 있다. 경로 선택 화면이 현재 위치를 출발지로
        // 잡으므로 집이 없다고 막으면 쓸 수 있는 길을 닫는다.
        RouteRow(
            routeLabel = state.routeLabel,
            originLabel = state.originLabel,
            enabled = state.canPickRoute,
            hasPlace = state.selectedPlace != null,
            onClick = onPickRoute,
        )

        // 알람이 계산되지 않을 조건을 미리 알린다. 추가한 뒤에 "왜 알람이
        // 없지" 하고 헤매지 않게 하려는 것이다.
        if (state.selectedPlace == null) {
            NoticeCard(
                dot = JitColor.Amber,
                title = "장소를 넣지 않으면 알람이 계산되지 않음",
                body = "이동 시간을 구할 수 없어서다. 일정은 저장되고 나중에 장소를 넣으면 계산됨",
            )
        } else if (!hasHome && state.origin == null) {
            // 집도 없고 출발지도 고르지 않은 상태에서만 경고한다. 경로 선택에서
            // 출발지를 잡았으면 집이 없어도 계산되므로 경고가 거짓이 된다.
            NoticeCard(
                dot = JitColor.Amber,
                title = "출발지가 없어 알람이 계산되지 않음",
                body = "경로 고르기에서 출발지를 정하거나, 홈 화면 안내에서 집 위치를 설정",
            )
        }

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
            text = "집에서 나서는 시각을 이 위치로 계산함",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        PlacePicker(
            label = "집 주변 검색",
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

        NoticeCard(
            dot = JitColor.Blue,
            title = "저장하는 정보",
            body = "고른 지점의 좌표와 이름만 저장함. 이동 중 실시간 위치는 서버에 보내지 않음",
        )

        state.error?.let {
            Text(text = it, color = JitColor.Red, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = "이 주소로 저장",
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
        PickerValue(display = label, fontSize = 16, enabled = enabled, onClick = { picking = true })
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
                TextButton(onClick = { onPick(state.hour, state.minute) }) {
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
private fun TagDropdown(selected: String?, onChange: (String?) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val current = TAG_OPTIONS.firstOrNull { it.key == selected } ?: TAG_OPTIONS.last()

    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("종류", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                text = "고르면 안전 여유(τ)가 정해짐",
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
                TAG_OPTIONS.forEach { option ->
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
            }
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
    originLabel: String?,
    enabled: Boolean,
    hasPlace: Boolean,
    onClick: () -> Unit,
) {
    val hint = when {
        !hasPlace -> "장소를 먼저 고름"
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
                text = routeLabel ?: "경로 고르기",
                color = when {
                    !enabled -> JitColor.Track
                    routeLabel != null -> JitColor.TextPrimary
                    else -> JitColor.Accent
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (routeLabel != null) "변경 ›" else "›",
                color = if (enabled) JitColor.TextSecondary else JitColor.Track,
                fontSize = if (routeLabel != null) 12.sp else 16.sp,
            )
        }

        // 어디서 출발하는 기준인지 적는다. 출발지가 집이 아닐 수 있으므로
        // 숨기면 알람 시각을 설명할 수 없다.
        originLabel?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "$it 에서 출발",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun NoticeCard(dot: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        JitDotLabel(text = title, dotColor = dot, fontSize = 12, dotSize = 6.dp)
        Text(text = body, color = JitColor.TextSecondary, fontSize = 11.sp)
    }
}
