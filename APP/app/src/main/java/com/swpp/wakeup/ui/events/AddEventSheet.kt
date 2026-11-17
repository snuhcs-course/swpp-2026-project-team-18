// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.swpp.wakeup.ui.theme.JitColor

/** 시트 위로 남기는 홈 화면 높이(Figma `545:192` 의 비율) */
private val SHEET_TOP_GAP = 134.dp

/**
 * 알람 추가 바텀시트 틀(H2-8 `545:192`). 내용은 [AddEventScreen] (`inSheet = true`)이 그린다.
 *
 * [locked] 이면(저장 중, 10초 전) 바깥 누르기 · 아래로 밀기 · 뒤로 가기로 닫히지 않는다.
 * 닫히는 길은 모두 [onDismiss] 하나로 모인다 — 뷰모델이 쓰다 만 일정 규칙(A4)을 태운다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventSheet(
    locked: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val isLocked by rememberUpdatedState(locked)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !isLocked },
    )
    val height = (LocalConfiguration.current.screenHeightDp.dp - SHEET_TOP_GAP).coerceAtLeast(320.dp)

    ModalBottomSheet(
        onDismissRequest = { if (!isLocked) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !locked),
        modifier = Modifier.testTag("add_sheet"),
    ) {
        content(
            Modifier
                // 시트는 따로 창에 그려져 Scaffold 의 설정이 닿지 않는다. 기기 테스트가 태그를 찾도록 여기서 켠다.
                .semantics { testTagsAsResourceId = true }
                .fillMaxWidth()
                .height(height),
        )
    }
}
