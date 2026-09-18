package com.swpp.wakeup.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
 * 날짜·시각 입력은 시스템 피커를 띄우지 않고 직접 조작한다. Material3 의
 * `DatePicker` 는 라이트 테마 기본값이 강해서 이 앱의 어두운 팔레트와 충돌하고,
 * 필요한 조작이 "며칠 뒤 / 몇 시" 두 가지뿐이라 버튼이 더 빠르다.
 */
@Composable
fun AddEventScreen(
    state: HomeViewModel.AddState,
    hasHome: Boolean,
    onTitleChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (Int, Int) -> Unit,
    onTagChange: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaceSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
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
        TimeRow(state.hour, state.minute, onTimeChange, enabled = !state.submitting)

        PlacePicker(
            label = "장소 검색",
            query = state.query,
            results = state.results,
            searching = state.searching,
            selected = state.selectedPlace,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onSelect = onPlaceSelect,
            enabled = !state.submitting,
        )

        TagDropdown(state.tagKey, onTagChange, enabled = !state.submitting)

        RouteRow(
            routeLabel = state.routeLabel,
            enabled = state.canPickRoute && hasHome,
            hasPlace = state.selectedPlace != null,
            hasHome = hasHome,
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
        } else if (!hasHome) {
            NoticeCard(
                dot = JitColor.Amber,
                title = "집 위치가 없어 알람이 계산되지 않음",
                body = "출발지가 필요함. 홈 화면의 안내에서 집 위치를 먼저 설정",
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
 * 집 위치 설정.
 *
 * 알람 계산의 출발지다. 설정하면 서버가 기존 일정의 알람을 한꺼번에 다시
 * 계산한다(`PATCH /api/profile` → `recompute_for_user`).
 */
@Composable
fun HomeSetupScreen(
    state: HomeViewModel.HomeSetupState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (com.swpp.wakeup.data.remote.PlaceSearchItem?) -> Unit,
    onPrepChange: (String) -> Unit,
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
        ScreenHeader(title = "집 위치 설정", onBack = onBack, enabled = !state.submitting)

        Text(
            text = "어디서 출발하는가?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "이동 시간을 구하려면 출발지가 필요함",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        PlacePicker(
            label = "집 주변 검색",
            query = state.query,
            results = state.results,
            searching = state.searching,
            selected = state.selected,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onSelect = onSelect,
            enabled = !state.submitting,
        )

        JitTextField(
            label = "준비 시간 (분)",
            value = state.prepMinutes,
            onValueChange = onPrepChange,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            enabled = !state.submitting,
        )

        NoticeCard(
            dot = JitColor.Blue,
            title = "저장하는 정보",
            body = "선택한 장소의 좌표와 이름만 저장함. 실시간 위치는 추적하지 않음",
        )

        NoticeCard(
            dot = JitColor.TextSecondary,
            title = "준비 시간은 시작값임",
            body = "지금은 사용자가 답한 값을 그대로 씀. 관측이 쌓이면 학습값으로 대체됨",
        )

        state.error?.let {
            Text(text = it, color = JitColor.Red, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(4.dp))

        JitPrimaryButton(
            label = "저장하고 알람 다시 계산",
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
        )
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
 * 스테퍼 가운데의 값. 누르면 직접 입력으로 바뀐다.
 *
 * **스테퍼만으로는 부족하다.** 09:00 에서 14:30 으로 가려면 +1시간을 다섯 번,
 * +10분을 세 번 눌러야 한다. 그렇다고 입력 필드만 두면 "10분 뒤" 같은 잔손질이
 * 번거롭다. 둘을 같이 둔다.
 *
 * 편집을 마치면 [parse] 로 검증한다. **형식이 틀리면 이전 값을 유지한다.**
 * 잘못된 입력을 0시로 떨어뜨리면 사용자가 눈치채지 못한 채 알람이 어긋난다.
 *
 * @param display 평소에 보여줄 문자열
 * @param editSeed 편집을 시작할 때 입력칸에 넣을 문자열
 * @param parse 입력 문자열을 해석한다. 실패하면 오류 메시지를 돌려준다
 */
@Composable
private fun TappableValue(
    display: String,
    editSeed: String,
    fontSize: Int,
    enabled: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    parse: (String) -> String?,
) {
    var editing by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    /** 완료를 눌렀을 때. 형식이 틀리면 편집 상태를 유지하고 이유를 보여준다. */
    val commit: () -> Unit = {
        error = parse(buffer.trim())
        if (error == null) editing = false
    }

    /**
     * 포커스를 잃었을 때. 반영을 시도하고 **실패해도 조용히 빠져나온다.**
     *
     * 입력을 그냥 버리면 1430 을 치고 다른 곳을 누른 사용자가 값을 잃는다.
     * 반대로 오류를 띄운 채 편집 상태로 두면 포커스도 없는 칸에 빨간 글씨가
     * 남는다. 둘 다 나쁘므로 반영되면 반영하고 아니면 이전 값을 유지한다.
     */
    val commitQuietly: () -> Unit = {
        parse(buffer.trim())
        error = null
        editing = false
    }

    if (editing) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            BasicTextField(
                value = buffer,
                onValueChange = { buffer = it; error = null },
                singleLine = true,
                textStyle = TextStyle(
                    color = JitColor.TextPrimary,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(JitColor.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focus ->
                        // 처음 requestFocus 가 도착하기 전에도 한 번 불린다.
                        // hasFocus 를 이미 얻은 뒤 잃은 경우만 커밋한다.
                        if (hadFocus && !focus.isFocused) commitQuietly()
                        if (focus.isFocused) hadFocus = true
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(JitColor.Bg)
                    .border(1.dp, JitColor.Accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = JitColor.Red, fontSize = 10.sp)
            }
        }
    } else {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) {
                    buffer = editSeed
                    error = null
                    hadFocus = false
                    editing = true
                }
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
            // 눌러서 편집할 수 있음을 알리는 밑줄.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JitColor.Track)
            )
        }
    }
}

@Composable
private fun DateRow(date: LocalDate, onChange: (LocalDate) -> Unit, enabled: Boolean) {
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
                "$relative · 눌러서 입력",
                color = JitColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−1일", enabled) { onChange(date.minusDays(1)) }
            TappableValue(
                display = label,
                editSeed = date.toString(),
                fontSize = 16,
                enabled = enabled,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                // `parse` 는 오류 메시지를 돌려준다. 성공이면 null 이다.
                // `?.let { ...; null } ?: HINT` 로 쓰면 성공했을 때도 let 이
                // null 을 반환해 엘비스가 발동한다. if 로 갈라 쓴다.
                parse = { raw ->
                    val parsed = parseDate(raw, date)
                    if (parsed == null) DATE_HINT else { onChange(parsed); null }
                },
            )
            StepButton("+1일", enabled) { onChange(date.plusDays(1)) }
        }
    }
}

@Composable
private fun TimeRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, enabled: Boolean) {
    JitCard(gap = 9.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("시작 시각", color = JitColor.TextSecondary, fontSize = 11.sp)
            Text(
                (if (hour < 12) "오전" else "오후") + " · 눌러서 입력",
                color = JitColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−1시간", enabled) { onChange((hour + 23) % 24, minute) }
            TappableValue(
                display = "%02d:%02d".format(hour, minute),
                editSeed = "%02d:%02d".format(hour, minute),
                fontSize = 24,
                enabled = enabled,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                parse = { raw ->
                    val parsed = parseTime(raw)
                    if (parsed == null) TIME_HINT
                    else { onChange(parsed.first, parsed.second); null }
                },
            )
            StepButton("+1시간", enabled) { onChange((hour + 1) % 24, minute) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−10분", enabled) {
                val total = (hour * 60 + minute - 10 + 1440) % 1440
                onChange(total / 60, total % 60)
            }
            StepButton("+10분", enabled) {
                val total = (hour * 60 + minute + 10) % 1440
                onChange(total / 60, total % 60)
            }
        }
    }
}

private const val TIME_HINT = "1430 또는 14:30 으로 입력한다"
private const val DATE_HINT = "0918 또는 2026-09-18 로 입력한다"

/** 시각 구분자. 숫자 키보드에서 치기 쉬운 것부터 넣었다. */
private val TIME_SEPARATORS = charArrayOf(':', '.', ' ', '시')

/** 날짜 구분자. */
private val DATE_SEPARATORS = charArrayOf('-', '.', '/', ' ')

/**
 * 시각 문자열 해석.
 *
 * 구분자를 강제하지 않는다. 숫자 키보드에서 콜론을 넣으려면 자판을 한 번 더
 * 바꿔야 하므로 **`1430` 처럼 붙여 쓴 네 자리를 기본으로 본다.**
 *
 * | 입력 | 결과 | 판단 근거 |
 * | --- | --- | --- |
 * | `1430`, `14:30`, `14.30`, `14 30`, `14시30분` | 14:30 | |
 * | `930` | 9:30 | 세 자리는 앞 한 자리가 시 |
 * | `9`, `09` | 9:00 | 분을 생략한 것으로 본다 |
 * | `9:5` | 9:05 | 구분자가 있으면 자리수로 자르지 않는다 |
 * | `2530`, `1470`, `abc`, `` | null | 범위·형식 위반 |
 *
 * 구분자가 있으면 그 앞뒤를 시·분으로 읽는다. `9:5` 를 자리수로 자르면
 * 95 → 9시 5분이 아니라 이상한 값이 되기 때문이다.
 */
internal fun parseTime(raw: String): Pair<Int, Int>? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val separator = TIME_SEPARATORS.firstOrNull { it in text }

    val (hourPart, minutePart) = if (separator != null) {
        val parts = text.split(separator, limit = 2)
        parts[0].filter(Char::isDigit) to parts[1].filter(Char::isDigit)
    } else {
        val digits = text.filter(Char::isDigit)
        // 숫자만 남긴 뒤 자리수로 자른다. 원문에 숫자가 아닌 글자가 섞여 있으면
        // 형식 위반으로 본다 — "1a4b3c0" 을 14:30 으로 읽어 주면 오타를 덮는다.
        if (digits.length != text.length) return null
        when (digits.length) {
            4 -> digits.take(2) to digits.drop(2)
            3 -> digits.take(1) to digits.drop(1)
            1, 2 -> digits to "0"
            else -> return null
        }
    }

    val hour = hourPart.toIntOrNull() ?: return null
    val minute = if (minutePart.isEmpty()) 0 else (minutePart.toIntOrNull() ?: return null)
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/**
 * 날짜 문자열 해석.
 *
 * | 입력 | 결과 |
 * | --- | --- |
 * | `20260918`, `2026-09-18`, `2026.9.18` | 2026-09-18 |
 * | `0918`, `9-18`, `9/18`, `9.18` | 참조 연도의 9월 18일 |
 *
 * 연도를 생략하면 [reference](지금 고른 날짜)의 연도를 쓴다. 대부분 같은 해다.
 */
internal fun parseDate(raw: String, reference: LocalDate): LocalDate? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val parts = text
        .split(*DATE_SEPARATORS)
        .map { it.filter(Char::isDigit) }
        .filter { it.isNotEmpty() }

    return runCatching {
        when {
            // 연-월-일
            parts.size >= 3 ->
                LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())

            // 월-일 (연도 생략)
            parts.size == 2 ->
                LocalDate.of(reference.year, parts[0].toInt(), parts[1].toInt())

            // 구분자 없이 붙여 쓴 경우
            parts.size == 1 && parts[0].length == 8 -> LocalDate.of(
                parts[0].take(4).toInt(),
                parts[0].substring(4, 6).toInt(),
                parts[0].substring(6, 8).toInt(),
            )

            parts.size == 1 && parts[0].length == 4 -> LocalDate.of(
                reference.year,
                parts[0].take(2).toInt(),
                parts[0].drop(2).toInt(),
            )

            else -> null
        }
    }.getOrNull()
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) JitColor.TextPrimary else JitColor.Track,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

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
    enabled: Boolean,
    hasPlace: Boolean,
    hasHome: Boolean,
    onClick: () -> Unit,
) {
    val hint = when {
        !hasPlace -> "장소를 먼저 고름"
        !hasHome -> "집 위치를 먼저 설정"
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
