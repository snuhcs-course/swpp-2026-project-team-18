// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 집 주소(예전 ⑯ 의 "기준" 칸 · 변형 `498:9`). 내 정보 탭(Figma `547:1244`) › "집 주소" 줄에서 연다.
 *
 * 맨 위는 알람을 계산하는 기본 집, 아래는 다른 집 목록이다. [기본으로] 는 서버의 집을 바꾸고 예전 집은
 * 목록에 남긴다. 고치는 화면(⑭ 집 설정)은 그대로 쓴다.
 */
@Composable
fun HomesScreen(
    state: HomeViewModel.UiState,
    onChangeHome: () -> Unit,
    onAddExtraHome: () -> Unit,
    onMakeHomeDefault: (String) -> Unit,
    onRemoveHome: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .testTag("homes_scroll")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "집 주소", onBack = onBack)

        Box(Modifier.testTag("homes_default")) {
            SettingRow(
                label = "기본 집 · 알람을 계산하는 출발지",
                // 집이 없을 때 빈 줄을 두지 않는다. 무엇을 고치는 것인지 모른다.
                value = state.homeLabel ?: "아직 정하지 않았어요",
                action = if (state.hasHome) "변경" else "설정",
                onClick = onChangeHome,
            )
        }

        SectionLabel("다른 집")
        state.savedHomes.forEach { home ->
            SavedHomeRow(
                home = home,
                busy = state.savedHomeBusyId == home.id,
                enabled = state.savedHomeBusyId == null,
                onMakeDefault = { onMakeHomeDefault(home.id) },
                onRemove = { onRemoveHome(home.id) },
            )
        }
        Text(
            text = "+ 다른 집 추가",
            color = JitColor.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .testTag("saved_home_add")
                .clip(RoundedCornerShape(JitRadius.Hint))
                .clickable(onClick = onAddExtraHome)
                .padding(vertical = 4.dp),
        )
        Text(
            text = if (state.savedHomesOnServer) {
                "다른 집은 계정에 저장돼 다른 기기에서도 보여요. \"기본으로\" 를 누르면 기본 집과 자리를 바꿔요"
            } else {
                "다른 집은 이 기기에만 저장돼요(서버는 집을 하나만 알고 있어요). \"기본으로\" 를 누르면 " +
                    "서버의 집을 바꾸고 예전 집은 목록에 남아요"
            },
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}
