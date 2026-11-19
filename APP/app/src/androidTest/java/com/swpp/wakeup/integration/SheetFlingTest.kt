// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.common.sheetScrollGuard
import com.swpp.wakeup.ui.events.AddEventSheet
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 시트 안 내용을 끝까지 빠르게 내려도 시트가 펼친 자리에 그대로 멈춘다(11-16 실기기: 위아래로 계속 흔들림).
 * 흔들림이 다시 생기면 시트 위 끝이 움직였거나, 애니메이션이 끝나지 않아 `waitForIdle` 이 시간 초과로 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class SheetFlingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun flingToEndLeavesSheetInPlace() {
        compose.setContent {
            JitTheme {
                AddEventSheet(locked = false, onDismiss = {}) { modifier ->
                    Column(modifier.testTag("sheet_body")) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .sheetScrollGuard()
                                .verticalScroll(rememberScrollState())
                                .testTag("sheet_scroll"),
                        ) {
                            repeat(40) { Text("줄 $it", modifier = Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("sheet_body", useUnmergedTree = true).getUnclippedBoundsInRoot().top

        repeat(3) {
            compose.onNodeWithTag("sheet_scroll").performTouchInput { swipeUp(durationMillis = 60) }
        }
        compose.waitForIdle()

        val after = compose.onNodeWithTag("sheet_body", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        assertEquals(before.value, after.value, 1f)
    }
}
