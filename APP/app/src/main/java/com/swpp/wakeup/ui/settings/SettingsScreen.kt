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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

// Partly AI-generated (Claude)
/**
 * 설정(예전 Figma ⑯ 에서 계정 쪽만 남김). 내 정보 탭(iter4 H2-2, Figma `547:1244`) › "설정" 줄에서 연다.
 *
 * 로그인 연결 · 비밀번호 · 이메일 인증 · 로그아웃만 둔다. 집 주소 · 준비 시간 · 아침 루틴 · 캘린더는 내 정보
 * 탭 첫 화면의 줄로, 진단 줄(등록된 알람 수 등)은 "알림·권한"([PermissionsScreen])으로 옮겼다.
 */
@Composable
fun SettingsScreen(
    state: HomeViewModel.UiState,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 개발 빌드에서만 채운다. null 이면 진단 줄을 그리지 않는다 */
    devInfo: String? = null,
    onDevCheck: (() -> Unit)? = null,
    /** "모든 기기에서 로그아웃"(token-security-api.md §5). null 이면 그리지 않는다 */
    onLogoutAll: (() -> Unit)? = null,
    /** 모든 기기 로그아웃을 서버에 요청하는 중 */
    logoutAllBusy: Boolean = false,
    /** "로그인 연결"의 [연결](social-login-api.md §3). null 이면 그 칸을 그리지 않는다 */
    onSocialLink: ((SocialProvider) -> Unit)? = null,
    onSocialUnlink: (SocialProvider) -> Unit = {},
    /** 로그인 방법을 받지 못했을 때 다시 받기 */
    onRetryLoginMethods: () -> Unit = {},
    /** "비밀번호" 줄의 [변경]. null 이면 줄을 그리지 않는다. 비밀번호 로그인이 있는 계정에서만 그린다 */
    onChangePassword: (() -> Unit)? = null,
    /** "이메일 인증" 줄의 [인증](R3-10). null 이면 줄을 그리지 않는다 */
    onVerifyEmail: (() -> Unit)? = null,
    /** 인증 코드를 보내는 중 */
    emailVerifySending: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("settings_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "설정", onBack = onBack)

        // Figma F-53c `530:85`. 서버가 `email_verified` 를 주고 메일을 받을 수 있는 주소일 때만 그린다.
        if (onVerifyEmail != null && state.emailVerified != null && state.accountEmail != null) {
            Box(Modifier.testTag("settings_email_verify")) {
                if (state.emailVerified) {
                    SettingRow(label = "이메일 인증", value = "인증됨", valueColor = JitColor.Green)
                } else {
                    SettingRow(
                        label = "이메일 인증",
                        value = "아직 인증하지 않음 · 로그인에는 필요 없음",
                        action = if (emailVerifySending) "보내는 중…" else "인증",
                        onClick = if (emailVerifySending) null else onVerifyEmail,
                    )
                }
            }
        }
        // Figma F-40 `507:57`. 방법을 아직 모르거나(null) 소셜만 있는 계정에는 그리지 않는다.
        if (onChangePassword != null && state.loginMethods?.contains("password") == true) {
            Box(Modifier.testTag("settings_password")) {
                SettingRow(
                    label = "비밀번호",
                    value = "이메일 로그인에 쓰는 비밀번호",
                    action = "변경",
                    onClick = onChangePassword,
                )
            }
        }

        // 카카오·Google 연결·끊기(Figma ⑯ `496:29`).
        onSocialLink?.let { link ->
            SectionLabel("로그인 연결")
            LoginConnections(
                methods = state.loginMethods,
                failed = state.loginMethodsFailed,
                busy = state.socialLinkBusy,
                onLink = link,
                onUnlink = onSocialUnlink,
                onRetry = onRetryLoginMethods,
            )
            Text(
                text = "마지막 로그인 방법은 끊을 수 없음(버튼을 숨김). 소셜로만 가입한 계정은 비밀번호가 없음",
                color = JitColor.TextSecondary,
                fontSize = 9.sp,
            )
        }

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

        // 다른 기기(잃어버린 폰 등)에 남은 로그인까지 끝낸다(Figma ⑯ `474:9`). 서버가 닿아야
        // 하므로 기다리는 동안 다시 누르지 못하게 한다.
        onLogoutAll?.let { logoutAll ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (logoutAllBusy) "로그아웃하는 중…" else "모든 기기에서 로그아웃",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("logout_all")
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .clickable(enabled = !logoutAllBusy, onClick = logoutAll)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = "다른 기기에 남은 로그인도 모두 끝남. 연결돼 있어야 함",
                    color = JitColor.TextSecondary,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        color = JitColor.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

// Partly AI-generated (Claude)
/** 다른 집 한 줄(Figma ⑯ 변형 `498:9`). [기본으로]는 서버의 집을 바꾼다 */
@Composable
internal fun SavedHomeRow(
    home: SavedHome,
    busy: Boolean,
    enabled: Boolean,
    onMakeDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag("saved_home_row")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = home.label, color = JitColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = home.address?.takeIf { it.isNotBlank() } ?: home.name,
                color = JitColor.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallPill(
            label = if (busy) "바꾸는 중…" else "기본으로",
            color = JitColor.Accent,
            enabled = enabled,
            onClick = onMakeDefault,
        )
        Spacer(Modifier.width(6.dp))
        SmallPill(label = "삭제", color = JitColor.TextSecondary, enabled = enabled, onClick = onRemove)
    }
}

@Composable
internal fun SmallPill(label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

// Partly AI-generated (Claude)
/**
 * 로그인 연결(Figma ⑯ `496:29`). 연결된 제공자는 [끊기], 아니면 [연결]. 남은 로그인 방법이 하나뿐이면
 * 끊기를 숨긴다(서버도 400 `social_last_login_method` 로 거절한다).
 *
 * @param methods 서버 `login_methods`. 아직 모르면 null
 */
@Composable
private fun LoginConnections(
    methods: List<String>?,
    failed: Boolean,
    busy: SocialProvider?,
    onLink: (SocialProvider) -> Unit,
    onUnlink: (SocialProvider) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .testTag("login_connections")
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .padding(horizontal = 15.dp, vertical = 4.dp),
    ) {
        SocialProvider.entries.forEachIndexed { index, provider ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(JitColor.Track))
            }
            val linked = methods?.contains(provider.path) == true
            val lastMethod = linked && methods.orEmpty().distinct().size <= 1
            val status = when {
                methods == null && failed -> "연결 상태를 확인하지 못함"
                methods == null -> "확인 중"
                linked -> "연결됨 · 이 계정으로 로그인할 수 있음"
                else -> "연결 안 됨"
            }
            val action: Pair<String, (() -> Unit)?>? = when {
                busy == provider -> (if (linked) "끊는 중…" else "연결 중…") to null
                methods == null && failed -> "다시 시도" to onRetry
                methods == null -> null
                linked && lastMethod -> null
                linked -> "끊기" to { onUnlink(provider) }
                else -> "연결" to { onLink(provider) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (provider == SocialProvider.KAKAO) JitColor.Kakao else JitColor.GoogleSymbol),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(provider.label, color = JitColor.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(status, color = JitColor.TextSecondary, fontSize = 10.sp)
                }
                action?.let { (label, onClick) ->
                    Text(
                        text = label,
                        color = if (label == "연결" || label == "다시 시도") JitColor.Accent else JitColor.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .testTag("social_${provider.path}_action")
                            .clip(RoundedCornerShape(JitRadius.Hint))
                            .clickable(enabled = onClick != null && busy == null) { onClick?.invoke() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 고칠 수 있는 항목 한 줄.
 *
 * 카드 전체가 눌린다. 오른쪽 "변경" 글자만 눌리게 하면 손가락으로 맞히기
 * 어렵고, 그 글자가 버튼이라는 것도 확실하지 않다.
 */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
    valueColor: Color = JitColor.TextPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            // 눌러도 할 일이 없는 줄(인증됨)과 처리 중인 줄은 누를 수 없다.
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, color = JitColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = action,
                color = if (onClick != null) JitColor.Accent else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(JitColor.Surface2)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 앱이 지금 제대로 돌고 있는지.
 *
 * 서버가 알람 시각을 아는 것과 기기가 그 시각에 울리는 것은 다른 문제다.
 * 등록 수를 보여 주지 않으면 "알람이 안 울렸다" 는 증상에서 원인을 좁힐 수 없다.
 * 권한은 위 줄([PermissionsScreen])에서 따로 보인다.
 */
@Composable
internal fun StatusCard(state: HomeViewModel.UiState) {
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
        if (state.pendingObservations > 0) {
            StatusLine("올리지 못한 이동 기록", "${state.pendingObservations}건", warn = true)
        }
    }
}

@Composable
internal fun StatusLine(label: String, value: String, warn: Boolean = false) {
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
