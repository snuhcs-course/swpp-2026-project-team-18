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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitSecondaryButton
import kotlinx.coroutines.launch
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
    onRenameHome: suspend (String, String) -> String? = { _, _ -> "다시 시도해 주세요" },
) {
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) renameId = null },
            containerColor = JitColor.Surface,
            title = { Text("집 이름 바꾸기", color = JitColor.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    JitTextField(label = "새 이름", value = label, onValueChange = { label = it; error = null }, enabled = !busy, inputTag = "home_rename_input")
                    Text(error ?: "1~20자로 적어 주세요", color = if (error == null) JitColor.TextSecondary else JitColor.Red, fontSize = 12.sp)
                }
            },
            confirmButton = {
                JitPrimaryButton("이름 저장", enabled = label.trim().length in 1..20, loading = busy, onClick = {
                    val id = renameId ?: return@JitPrimaryButton
                    busy = true
                    scope.launch {
                        try {
                            error = onRenameHome(id, label)
                            if (error == null) renameId = null
                        } finally { busy = false }
                    }
                })
            },
            dismissButton = { JitSecondaryButton("취소", { renameId = null }, enabled = !busy) },
        )
    }
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
                onRename = { renameId = home.id; label = home.label; error = null },
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
                "다른 집은 이 기기에만 저장돼요(계정에는 기본 집 하나만 있어요). \"기본으로\" 를 누르면 " +
                    "그 집이 기본 집이 되고 예전 집은 목록에 남아요"
            },
            color = JitColor.TextSecondary,
            fontSize = 12.sp,
        )
    }
}
