// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.common.JitDurationWheelSheet
import com.swpp.wakeup.ui.events.PrepOnboardingScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 준비 시간 화면 다시 그림(Figma ⑮ `546:2098`)과 준비 시간 휠(⑮-a `547:1718`) — iter4 H2-5 */
@RunWith(AndroidJUnit4::class)
class PrepTimeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var split = false

    private fun openScreen(minutes: String = "") {
        compose.setContent {
            var state by remember { mutableStateOf(HomeViewModel.PrepOnboardingState(minutes = minutes)) }
            JitTheme {
                PrepOnboardingScreen(
                    state = state,
                    onMinutesChange = { state = state.copy(minutes = it) },
                    onSubmit = {},
                    onSkip = {},
                    onSplit = { split = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t)

    @Test
    fun 값이_없으면_대시이고_빼기_더하기_저장이_꺼져_있다() {
        openScreen()
        tag("prep_display").assertTextEquals("—")
        tag("prep_minus").assertIsNotEnabled()
        tag("prep_plus").assertIsNotEnabled()
        tag("prep_submit").assertIsNotEnabled()
        tag("prep_chip_30").assertIsNotSelected()
    }

    @Test
    fun 칩을_고르면_값이_생기고_빼기_더하기는_5분씩이다() {
        openScreen()
        tag("prep_chip_30").performClick()
        tag("prep_display").assertTextEquals("30분")
        tag("prep_chip_30").assertIsSelected()
        tag("prep_submit").assertIsEnabled()

        tag("prep_plus").performClick()
        tag("prep_display").assertTextEquals("35분")
        tag("prep_chip_30").assertIsNotSelected()

        tag("prep_chip_60").performClick()
        tag("prep_display").assertTextEquals("1시간")
        tag("prep_minus").performClick()
        tag("prep_display").assertTextEquals("55분")
    }

    @Test
    fun 범위_끝에서는_그쪽_버튼이_꺼진다() {
        openScreen(minutes = "5")
        tag("prep_minus").assertIsNotEnabled()
        tag("prep_plus").assertIsEnabled()
    }

    @Test
    fun 카드를_누르면_휠이_뜨고_확인하면_값이_바뀐다() {
        openScreen(minutes = "35")
        tag("prep_value").performClick()
        compose.waitForIdle()
        tag("wheel_readout").assertContentDescriptionEquals("35분")

        tapCell("wheel_prep_hour", "1시간")
        tag("wheel_readout").assertContentDescriptionEquals("1시간 35분")
        tag("wheel_confirm").performClick()
        compose.waitForIdle()
        tag("prep_display").assertTextEquals("1시간 35분")
    }

    @Test
    fun 항목으로_나눠_적기는_루틴으로_간다() {
        openScreen()
        tag("prep_split").performClick()
        assertTrue(split)
    }

    // --- 휠 시트 하나만 -------------------------------------------------------------------

    private var confirmed: Int? = null

    private fun openWheel(minutes: Int?) {
        compose.setContent {
            JitTheme {
                JitDurationWheelSheet(
                    minutes = minutes,
                    range = 5..240,
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tapCell(column: String, text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(column)))
            .onFirst()
            .performClick()
        compose.waitForIdle()
    }

    @Test
    fun 값이_없으면_0시간_5분에서_열고_굴리기_전엔_확인이_꺼져_있다() {
        openWheel(null)
        tag("wheel_readout").assertContentDescriptionEquals("5분")
        tag("wheel_prep_minute").assertContentDescriptionEquals("분 5분")
        tag("wheel_confirm").assertIsNotEnabled()

        tapCell("wheel_prep_minute", "6분")
        tag("wheel_confirm").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(6, confirmed)
    }

    @Test
    fun 시간을_4로_바꾸면_분은_0으로_붙는다() {
        openWheel(3 * 60 + 35)
        tapCell("wheel_prep_hour", "4시간")
        tag("wheel_readout").assertContentDescriptionEquals("4시간")
        tag("wheel_prep_minute").assertContentDescriptionEquals("분 0분")
    }

    @Test
    fun 값이_있으면_그_값에서_열고_취소하면_넘기지_않는다() {
        openWheel(35)
        tag("wheel_confirm").assertIsEnabled()
        tag("wheel_cancel").performClick()
        assertNull(confirmed)
    }
}
