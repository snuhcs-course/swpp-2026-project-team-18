package com.swpp.wakeup.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.calendar.DeviceCalendarSource
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.CalendarPermission
import com.swpp.wakeup.domain.model.CalendarSources
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

// Partly AI-generated (Claude)
/** 줄 오른쪽(또는 왼쪽)에 붙는 조작. 화면마다 뜻이 다르다. */
enum class CalendarRowControl {
    /** ⑱ 가입 직후: 고르고 버튼으로 끝낸다(저장은 버튼이 한다) */
    CHECK,

    /** ⑯ 설정·가져오기: 누르는 즉시 저장된다 */
    SWITCH,
}

// Partly AI-generated (Claude)
/**
 * 기기 캘린더 목록 카드(Figma ⑱ `card-캘린더`, ⑯ 캘린더 연동).
 *
 * 줄 전체가 눌린다. 작은 체크·스위치만 눌리게 하면 손가락으로 맞히기 어렵다. 색 점은
 * 캘린더 앱과 같은 색이라 "어느 캘린더인지" 를 이름보다 먼저 알아보게 한다.
 */
@Composable
fun CalendarSourceList(
    calendars: List<DeviceCalendarSource>,
    isOn: (Long) -> Boolean,
    onToggle: (id: Long, on: Boolean) -> Unit,
    control: CalendarRowControl,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(horizontal = JitSpace.CardPadding, vertical = 4.dp),
    ) {
        calendars.forEachIndexed { index, source ->
            if (index > 0) HorizontalDivider(color = JitColor.Track, thickness = 1.dp)
            CalendarRow(
                source = source,
                on = isOn(source.id),
                control = control,
                enabled = enabled,
                onToggle = { onToggle(source.id, it) },
            )
        }
    }
}

@Composable
private fun CalendarRow(
    source: DeviceCalendarSource,
    on: Boolean,
    control: CalendarRowControl,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("calendar_row_${source.id}")
            .toggleable(
                value = on,
                enabled = enabled,
                role = if (control == CalendarRowControl.CHECK) Role.Checkbox else Role.Switch,
                onValueChange = onToggle,
            )
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (control == CalendarRowControl.CHECK) {
            CheckMark(on)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                // 제공자가 투명도 0 으로 주는 경우가 있다. 점이 사라지지 않게 불투명으로 그린다.
                .background(source.color?.let { Color(it or OPAQUE) } ?: JitColor.TextSecondary)
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = source.name,
                color = if (on) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CalendarSources.subtitle(source)?.let {
                Text(
                    text = it,
                    color = JitColor.TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (control == CalendarRowControl.SWITCH) {
            Spacer(Modifier.width(10.dp))
            // 줄이 toggleable 이라 스위치는 표시만 한다. 둘 다 받으면 한 번 누름이 두 번 바뀐다.
            Switch(
                checked = on,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JitColor.Bg,
                    checkedTrackColor = JitColor.Accent,
                    uncheckedThumbColor = JitColor.TextSecondary,
                    uncheckedTrackColor = JitColor.Surface2,
                    uncheckedBorderColor = JitColor.Track,
                ),
            )
        }
    }
}

/** 체크 표시. 캘린더 가져오기 화면의 일정 줄과 같은 모양이다 */
@Composable
private fun CheckMark(on: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) JitColor.Accent else JitColor.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Text("✓", color = JitColor.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// Partly AI-generated (Claude)
/**
 * 목록 대신 보일 것: 권한 안내, 불러오는 중, 캘린더 없음. [CalendarLinkState.showList]
 * 가 true 면 호출하지 않는다 — 그때는 호출하는 화면이 [CalendarSourceList] 를 그린다.
 *
 * **무엇에 쓰는지 적는다.** 시스템 대화상자는 "캘린더 접근" 만 말하고 이유를 말하지
 * 않는다. 거절한 뒤에는 시스템이 대화상자를 다시 띄우지 않을 수 있어서, 어디서 켜는지도
 * 함께 적는다.
 */
@Composable
fun CalendarLinkStatus(
    link: CalendarLinkState,
    onRequestPermission: () -> Unit,
) {
    when {
        link.permission == CalendarPermission.MISSING ||
            link.permission == CalendarPermission.DENIED -> {
            JitCard(padding = 16.dp, gap = 9.dp) {
                JitDotLabel(
                    text = "캘린더 읽기 권한이 필요함",
                    dotColor = JitColor.Amber,
                    fontSize = 13,
                    dotSize = 8.dp,
                )
                Text(
                    text = "기기 캘린더 목록과 일정을 읽기만 함. 캘린더를 고치지 않음",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
                Text(
                    text = "권한 없이도 일정을 직접 입력해 쓸 수 있음",
                    color = JitColor.TextSecondary,
                    fontSize = 10.sp,
                )
                if (link.permission == CalendarPermission.DENIED) {
                    Text(
                        text = "권한이 거절됨 · 기기 설정의 앱 권한에서 켤 수 있음",
                        color = JitColor.Amber,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.height(2.dp))
                JitPrimaryButton(
                    label = "권한 허용하기",
                    onClick = onRequestPermission,
                    modifier = Modifier.testTag("calendar_permission"),
                )
            }
        }

        link.noCalendars -> {
            JitCard(padding = 16.dp, gap = 7.dp) {
                JitDotLabel(
                    text = "기기에 캘린더가 없음",
                    dotColor = JitColor.TextSecondary,
                    fontSize = 13,
                    dotSize = 8.dp,
                )
                Text(
                    text = "기기 설정에서 구글 등 계정을 추가하면 그 캘린더가 여기 나옴",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        // 권한을 아직 확인하지 않았거나 목록을 처음 읽는 중이다.
        else -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = JitColor.Accent,
                strokeWidth = 2.dp,
            )
        }
    }
}

/** 캘린더 제공자가 주는 색에 알파가 빠져 있을 때 불투명으로 만든다. 0xFF000000 이다 */
private const val OPAQUE: Int = -0x1000000
