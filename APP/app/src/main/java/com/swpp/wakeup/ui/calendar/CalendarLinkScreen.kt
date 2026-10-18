package com.swpp.wakeup.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.calendar.DeviceCalendarSource
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.CalendarOnboardingState
import com.swpp.wakeup.domain.model.CalendarPermission
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

// Partly AI-generated (Claude)
/**
 * 캘린더 연동. Figma ⑱ (node 402:9). 가입 직후 ⑮ 준비 시간 다음에 한 번 뜬다.
 *
 * ## 무엇을 고르는가
 *
 * 앱이 읽어도 되는 **기기 캘린더**다(구글 계정의 캘린더가 기기에 동기화돼 있으면 그것도
 * 여기 나온다). 고른 캘린더의 일정은 "캘린더에서 가져오기" 에 나오고, 그중 사용자가
 * 고른 일정만 서버로 간다. 연동했다고 일정을 자동으로 올리지 않는다.
 *
 * ## 아무것도 미리 고르지 않는다
 *
 * 다 켜 둔 채 시작하면 대부분 그대로 누른다. 그러면 공휴일·생일·남이 공유한 캘린더까지
 * 읽게 된다. 무엇을 읽을지는 사용자가 정한다.
 *
 * ## 다시 묻지 않는다
 *
 * 준비 시간과 달리 없어도 앱을 쓸 수 있다. "나중에" 를 누르면 이 기기·계정에서는
 * 다시 띄우지 않고, ⑯ 설정의 스위치로 켠다.
 */
@Composable
fun CalendarLinkScreen(
    link: CalendarLinkState,
    state: CalendarOnboardingState,
    onToggle: (Long) -> Unit,
    onRequestPermission: () -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        // 뒤로 가기는 "나중에" 와 같다. 두 경로가 다르게 끝나면 한쪽만 상태를 정리한다.
        ScreenHeader(title = "캘린더 연동", onBack = onSkip)

        // 줄바꿈을 직접 넣는다. 한글은 낱말 경계를 자동으로 지켜 주지 않는다.
        Text(
            text = "어느 캘린더를\n연동할까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "고른 캘린더의 일정을 가져올 수 있음",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        // 캘린더가 많은 사용자가 있다. 목록만 스크롤하고 버튼은 화면 아래에 남긴다.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("calendar_link_scroll"),
            verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
        ) {
            if (link.showList) {
                CalendarSourceList(
                    calendars = link.ordered,
                    isOn = { it in state.selected },
                    onToggle = { id, _ -> onToggle(id) },
                    control = CalendarRowControl.CHECK,
                )
                Text(
                    text = "읽기만 함. 캘린더를 고치지 않음.\n" +
                        "켠 캘린더의 일정은 ‘캘린더에서 가져오기’에 나옴.\n" +
                        "고른 일정만 서버로 보냄",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
            } else {
                CalendarLinkStatus(link = link, onRequestPermission = onRequestPermission)
            }
        }

        JitPrimaryButton(
            label = state.submitLabel(link),
            modifier = Modifier.testTag("calendar_link_submit"),
            onClick = onSubmit,
            enabled = state.canSubmit(link),
        )

        // 권한이 없거나 캘린더가 없어도 이 화면이 가입을 막으면 안 된다.
        Text(
            text = "나중에",
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSkip)
                .padding(vertical = 6.dp)
                .testTag("calendar_link_skip"),
        )
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun CalendarLinkPreview() {
    val calendars = listOf(
        DeviceCalendarSource(1, "user@example.com", "user@example.com", 0xFF039BE5.toInt(), true),
        DeviceCalendarSource(2, "수업 시간표", "user@example.com", 0xFF33B679.toInt(), false),
        DeviceCalendarSource(3, "동아리", "user@example.com", 0xFFF6BF26.toInt(), false),
    )
    JitTheme {
        CalendarLinkScreen(
            link = CalendarLinkState(permission = CalendarPermission.GRANTED, calendars = calendars),
            state = CalendarOnboardingState(selected = setOf(1L, 2L)),
            onToggle = {},
            onRequestPermission = {},
            onSubmit = {},
            onSkip = {},
        )
    }
}
