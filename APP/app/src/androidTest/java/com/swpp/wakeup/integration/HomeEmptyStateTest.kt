// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.home.HomeScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 일정이 없을 때 가운데 + 가 알람 추가를 연다(11-17 사용자). 설명 문구는 두지 않는다 */
@RunWith(AndroidJUnit4::class)
class HomeEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyPlusOpensAddEvent() {
        var opened = 0
        compose.setContent {
            JitTheme {
                HomeScreen(
                    state = HomeViewModel.UiState(nickname = "진호", loading = false, hasHome = true, totalCount = 0),
                    onEventClick = {},
                    onAlarmToggle = { _, _ -> },
                    onAddEventClick = { opened++ },
                    onSetHomeClick = {},
                    onMorningClick = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("아직 일정이 없어요").assertExists()
        compose.onNodeWithText("거꾸로 계산", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("home_empty_add").performClick()
        assertEquals(1, opened)
    }
}
