// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.onboarding.CoachTarget
import com.swpp.wakeup.ui.onboarding.coachmarkTarget
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 하단 탭 바(iter4 H2-2). 홈 · 이동 · 리포트 · 내 정보.
 *
 * 아이콘은 Figma 공통 아이콘(`fb/…`, `536:9`)과 같은 Lucide 선 아이콘이다. 탭을 누르면 [onSelect] 로
 * [AppTab] 을 넘긴다. 같은 탭을 다시 누르면 그 탭의 첫 화면으로 돌아간다([NavState.selectTab]).
 */
@Composable
fun AppTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.testTag("tab_bar"),
        containerColor = JitColor.Surface,
        tonalElevation = 0.dp,
    ) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(imageVector = TabIcons.of(tab), contentDescription = null) },
                label = { Text(text = tab.label, fontSize = 11.sp) },
                modifier = Modifier
                    .testTag("tab_${tab.name.lowercase()}")
                    // 홈 코치마크(H2-4)가 "이동" 탭 자리를 밝힌다
                    .then(if (tab == AppTab.TRIP) Modifier.coachmarkTarget(CoachTarget.TRIP_TAB) else Modifier),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = JitColor.Accent,
                    selectedTextColor = JitColor.Accent,
                    unselectedIconColor = JitColor.TextSecondary,
                    unselectedTextColor = JitColor.TextSecondary,
                    indicatorColor = JitColor.Surface2,
                ),
            )
        }
    }
}

/** 탭 아이콘. Figma `fb/TabBar` 와 같은 Lucide 선 아이콘([LineIcons]) */
private object TabIcons {

    fun of(tab: AppTab): ImageVector = when (tab) {
        AppTab.HOME -> LineIcons.house
        AppTab.TRIP -> LineIcons.route
        AppTab.REPORT -> LineIcons.chart
        AppTab.ME -> LineIcons.user
    }
}
