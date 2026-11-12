// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.swpp.wakeup.sensing.LocationPermissions
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.me.MeTabLogic
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 알림·권한(예전 ⑯ 의 "지금 상태"). 내 정보 탭(Figma `547:1244`) › "알림·권한" 줄에서 연다.
 *
 * 위는 알람이 제대로 울리고 기록되는 데 필요한 기기 권한 세 가지, 가운데는 일요일 주간 리포트 알림 스위치,
 * 아래는 앱이 지금 제대로 돌고 있는지(등록된 알람 수 등)다. 꺼진 권한은 [켜기] 로 그 권한의 기기 설정 화면을 연다 — 한 번 거절한 권한은
 * 시스템이 대화상자를 다시 띄우지 않을 수 있다. 돌아오면 [rememberDevicePermissions] 가 다시 읽는다.
 */
@Composable
internal fun PermissionsScreen(
    state: HomeViewModel.UiState,
    permissions: MeTabLogic.Permissions,
    onOpenSettings: (MeTabLogic.PermissionKind) -> Unit,
    /** 일요일 낮 12시 주간 리포트 알림(기기에 저장, 기본 켬) */
    weeklyReminder: Boolean,
    onWeeklyReminderChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("permissions_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "알림·권한", onBack = onBack)

        MeTabLogic.permissionRows(permissions).forEach { row ->
            Box(Modifier.testTag("permission_${row.kind.name.lowercase()}")) {
                SettingRow(
                    label = row.label,
                    value = row.value,
                    valueColor = if (row.on) JitColor.TextPrimary else JitColor.Amber,
                    action = if (row.on) null else "켜기",
                    onClick = { onOpenSettings(row.kind) }.takeUnless { row.on },
                )
            }
        }
        Text(
            text = "기기 설정에서 바꾸고 돌아오면 바로 반영돼요",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )

        SectionLabel("알림")
        ReminderRow(
            value = MeTabLogic.reminder(weeklyReminder, permissions.notifications),
            on = weeklyReminder,
            onChange = onWeeklyReminderChange,
        )

        SectionLabel("지금 상태")
        StatusCard(state = state)
    }
}

/** "주간 리포트 알림" 스위치 줄. 줄 전체가 스위치다(스위치만 누르게 하면 맞히기 어렵다) */
@Composable
private fun ReminderRow(value: MeTabLogic.Value, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weekly_report_reminder")
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "주간 리포트 알림", color = JitColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = value.text,
                color = if (value.warn) JitColor.Amber else JitColor.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        // 줄이 toggleable 이라 스위치는 표시만 한다. 둘 다 받으면 한 번 누름이 두 번 바뀐다.
        Switch(
            checked = on,
            onCheckedChange = null,
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

/** 기기 권한을 읽는다. 화면이 다시 보일 때마다(기기 설정에서 돌아올 때) 새로 읽는다 */
@Composable
internal fun rememberDevicePermissions(): MeTabLogic.Permissions {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(readDevicePermissions(context)) }
    LifecycleResumeEffect(Unit) {
        permissions = readDevicePermissions(context)
        onPauseOrDispose { }
    }
    return permissions
}

internal fun readDevicePermissions(context: Context): MeTabLogic.Permissions = MeTabLogic.Permissions(
    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    // Android 14 부터 알람 · 전화 앱이 아니면 사용자가 꺼 둘 수 있다. 읽지 못하면 켜진 것으로 둔다.
    fullScreen = context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true,
    preciseLocation = LocationPermissions.granted(context),
    coarseOnly = LocationPermissions.coarseOnly(context),
)

/** [kind] 를 켜는 기기 설정 화면을 연다. 그 화면이 없는 기기면 앱 정보 화면을 연다 */
internal fun openPermissionSettings(context: Context, kind: MeTabLogic.PermissionKind) {
    val packageUri = "package:${context.packageName}".toUri()
    val intent = when (kind) {
        MeTabLogic.PermissionKind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        MeTabLogic.PermissionKind.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
        MeTabLogic.PermissionKind.LOCATION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
    }
}
