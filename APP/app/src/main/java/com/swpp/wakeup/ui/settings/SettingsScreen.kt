package com.swpp.wakeup.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 설정 (Figma ⑯).
 *
 * **가입할 때 한 번 정한 값을 고칠 수 있는 유일한 곳이다.** 예전에는 아바타를
 * 누르면 읽기 전용 다이얼로그가 떴다. 집 주소는 홈 화면의 "집을 설정하세요"
 * 안내에서만 들어갈 수 있었고, 그 안내는 집이 없을 때만 보였다 — 한 번
 * 저장하면 바꿀 길이 사라졌다.
 *
 * 진단 정보(등록된 알람 수, 권한, 올리지 못한 관측)도 여기로 모았다. 그건
 * 설정이 아니지만 "앱이 지금 제대로 돌고 있나" 를 확인할 자리가 따로 없고,
 * 실기기에서 그걸 볼 유일한 창구다.
 */
@Composable
fun SettingsScreen(
    state: HomeViewModel.UiState,
    avatarInitials: String,
    locationGranted: Boolean,
    onChangeHome: () -> Unit,
    onChangePrep: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 개발 빌드에서만 채운다. null 이면 진단 줄을 그리지 않는다 */
    devInfo: String? = null,
    onDevCheck: (() -> Unit)? = null,
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
        ScreenHeader(title = "설정", onBack = onBack)

        Text(
            text = "무엇을 바꿀까?",
            color = JitColor.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "가입할 때 정한 값은 언제든 고칠 수 있음",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )

        AccountCard(nickname = state.nickname, initials = avatarInitials)

        SectionLabel("기준")

        SettingRow(
            label = "집 주소",
            // 집이 없을 때 빈 줄을 두지 않는다. 무엇을 고치는 것인지 모른다.
            value = state.homeLabel ?: "아직 설정하지 않음",
            action = if (state.hasHome) "변경" else "설정",
            onClick = onChangeHome,
        )

        SettingRow(
            label = "평소 준비 시간",
            value = "관측이 쌓이면 학습값으로 대체됨",
            action = "변경",
            onClick = onChangePrep,
        )

        SectionLabel("지금 상태")

        StatusCard(state = state, locationGranted = locationGranted)

        devInfo?.let { info ->
            SectionLabel("개발 정보")
            Text(text = info, color = JitColor.TextSecondary, fontSize = 11.sp)
            onDevCheck?.let { check ->
                Text(
                    text = "서버 확인",
                    color = JitColor.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .clickable(onClick = check)
                        .padding(vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 로그아웃은 이 화면의 목적이 아니다. 강조색을 주지 않는다 — 주면
        // 설정에 들어온 사용자가 가장 먼저 누를 것처럼 보인다.
        Text(
            text = "로그아웃",
            color = JitColor.Red,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(JitRadius.Button))
                .background(JitColor.Surface2)
                .clickable(onClick = onLogout)
                .padding(vertical = 16.dp),
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = JitColor.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AccountCard(nickname: String, initials: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(JitColor.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = nickname,
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 고칠 수 있는 항목 한 줄.
 *
 * 카드 전체가 눌린다. 오른쪽 "변경" 글자만 눌리게 하면 손가락으로 맞히기
 * 어렵고, 그 글자가 버튼이라는 것도 확실하지 않다.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, color = JitColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                color = JitColor.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = action,
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(JitColor.Surface2)
                .padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

/**
 * 앱이 지금 제대로 돌고 있는지.
 *
 * 서버가 알람 시각을 아는 것과 기기가 그 시각에 울리는 것은 다른 문제다.
 * 등록 수를 보여 주지 않으면 "알람이 안 울렸다" 는 증상에서 원인을 좁힐 수 없다.
 */
@Composable
private fun StatusCard(state: HomeViewModel.UiState, locationGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusLine("일정", "${state.totalCount}개")
        StatusLine(
            "등록된 알람",
            buildString {
                append("${state.registeredAlarms}개")
                state.nextRegisteredLabel?.let { append(" · 다음 $it") }
            },
        )
        if (state.unplannedCount > 0) {
            StatusLine("알람 미계산", "${state.unplannedCount}개", warn = true)
        }
        if (!locationGranted) {
            StatusLine("위치 권한", "없음 · 출발·도착이 기록되지 않음", warn = true)
        }
        if (state.pendingObservations > 0) {
            StatusLine("올리지 못한 이동 기록", "${state.pendingObservations}건", warn = true)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, warn: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = JitColor.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = if (warn) JitColor.Amber else JitColor.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
