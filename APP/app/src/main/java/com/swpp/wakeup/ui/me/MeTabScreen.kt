// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.me

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.LearningReset
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 내 정보 탭 첫 화면(iter4 H2-2, Figma `547:1244`).
 *
 * 맨 위 프로필, 아래로 "내 아침 · 일정 · 도움 · 계정" 네 묶음. 줄마다 아이콘 + 제목 + 지금 값 + › 이고 줄
 * 전체가 버튼이다. 들어가지 않아도 지금 무엇이 설정돼 있는지 보이게 값을 한 줄로 적고, 사용자가 고쳐야
 * 하는 상태(집 없음 · 권한 꺼짐)는 주황으로 칠한다([MeTabLogic]).
 *
 * 예전 ⑯ 설정 한 화면에 있던 것은 갈라졌다: 로그인 연결 · 비밀번호 · 로그아웃은 "설정" 안으로, 진단 줄은
 * "알림·권한" 안으로, 집 목록은 "집 주소" 안으로.
 *
 * @param routineNames 아침 루틴 블록 이름(순서대로). 아직 못 받았으면 null
 * @param onHelp "도움말". 도움말 화면(H2-4)이 생기기 전에는 null 이라 "도움" 묶음을 그리지 않는다
 * @param onReplayIntro "처음 안내 다시 보기". 위와 같다
 * @param onResetLearning "기록 초기화". 확인 창을 띄운다(지우는 것은 창에서 한 번 더 누른 뒤). null 이면 줄이 없다
 */
@Composable
internal fun MeTabScreen(
    state: HomeViewModel.UiState,
    routineNames: List<String>?,
    calendarLink: CalendarLinkState,
    permissions: MeTabLogic.Permissions,
    onHomes: () -> Unit,
    onPrep: () -> Unit,
    onRoutine: () -> Unit,
    onCalendar: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    onHelp: (() -> Unit)? = null,
    onReplayIntro: (() -> Unit)? = null,
    onResetLearning: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(scrollState)
            .testTag("me_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        Profile(nickname = state.nickname, email = state.accountEmail)

        Group("내 아침") {
            MeRow(
                LineIcons.house, "집 주소",
                MeTabLogic.home(state.homeLabel.takeIf { state.hasHome }, state.savedHomes.size),
                "me_home", onHomes,
            )
            RowDivider()
            MeRow(LineIcons.clock, "준비 시간", MeTabLogic.prep(state.onboardingPrepMin), "me_prep", onPrep)
            RowDivider()
            MeRow(LineIcons.sliders, "아침 루틴", MeTabLogic.routine(routineNames), "me_routine", onRoutine)
        }

        Group("일정") {
            MeRow(LineIcons.calendar, "캘린더에서 가져오기", MeTabLogic.calendar(calendarLink), "me_calendar", onCalendar)
            RowDivider()
            MeRow(LineIcons.bell, "알림·권한", MeTabLogic.permissions(permissions), "me_permissions", onPermissions)
        }

        if (onHelp != null || onReplayIntro != null) {
            Group("도움") {
                onHelp?.let {
                    MeRow(
                        LineIcons.book, "도움말",
                        MeTabLogic.Value("알람 시각을 정하는 방법, 화면 보는 법"), "me_help", it,
                    )
                }
                if (onHelp != null && onReplayIntro != null) RowDivider()
                onReplayIntro?.let {
                    MeRow(
                        LineIcons.sunrise, "처음 안내 다시 보기",
                        MeTabLogic.Value("처음 켰을 때 본 안내를 다시 봐요"), "me_intro", it,
                    )
                }
            }
        }

        Group("계정") {
            MeRow(
                LineIcons.settings, "설정",
                MeTabLogic.Value(MeTabLogic.settings(state.loginMethods)), "me_settings", onSettings,
            )
            onResetLearning?.let {
                RowDivider()
                MeRow(
                    LineIcons.rotateCcw, LearningReset.ROW_TITLE,
                    MeTabLogic.Value(LearningReset.ROW_VALUE), "me_reset_learning", it,
                )
            }
        }
    }
}

/** 아바타(사람 아이콘) + 닉네임 + 이메일(Figma `profile`). 이메일을 주지 않은 소셜 계정은 이메일 줄이 없다 */
@Composable
private fun Profile(nickname: String, email: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(JitColor.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LineIcons.user, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = nickname,
                color = JitColor.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            email?.let {
                Text(
                    text = it,
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 묶음 이름(13 Bold) + 줄 카드 */
@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/**
 * 줄 하나(Figma `row … (버튼)`). 줄 전체가 버튼이라 카드 끝까지 눌린다. 값은 한 줄로 자른다 — 루틴 이름이
 * 길어도 줄 높이가 바뀌지 않는다.
 */
@Composable
private fun MeRow(icon: ImageVector, title: String, value: MeTabLogic.Value, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value.text,
                color = if (value.warn) JitColor.Amber else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(LineIcons.chevronRight, contentDescription = null, tint = JitColor.TextSecondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(JitColor.Track),
    )
}
