// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.common.JitTimeWheelSheet
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 시각 휠 시트(iter4 H2-5, Figma `545:670`). 큰 글자가 고른 값을 따라가고 [확인] 만 값을 넘긴다. */
@RunWith(AndroidJUnit4::class)
class TimeWheelSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmed: Pair<Int, Int>? = null
    private var dismissed = false

    private fun open(hour: Int, minute: Int, step: Int = 1) {
        compose.setContent {
            JitTheme {
                JitTimeWheelSheet(
                    title = "시작 시각",
                    initialHour = hour,
                    initialMinute = minute,
                    minuteStep = step,
                    onConfirm = { h, m -> confirmed = h to m },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitForIdle()
    }

    /** [column] 열에 보이는 흐린 줄 [text] 를 눌러 그 값으로 굴린다 */
    private fun tapCell(column: String, text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(column)))
            .onFirst()
            .performClick()
        compose.waitForIdle()
    }

    private fun readout(): SemanticsNodeInteraction = compose.onNodeWithTag("wheel_readout")

    @Test
    fun 처음_값을_큰_글자로_읽어_준다() {
        open(9, 0)
        readout().assertContentDescriptionEquals("오전 9:00")
        compose.onNodeWithTag("wheel_hour").assertContentDescriptionEquals("시 9")
        compose.onNodeWithTag("wheel_minute").assertContentDescriptionEquals("분 00")
        compose.onNodeWithTag("wheel_ampm").assertContentDescriptionEquals("오전 오후 오전")
    }

    @Test
    fun 시를_굴리면_큰_글자가_따라가고_확인하면_넘긴다() {
        open(9, 0)
        tapCell("wheel_hour", "10")
        readout().assertContentDescriptionEquals("오전 10:00")

        compose.onNodeWithTag("wheel_confirm").performClick()
        compose.waitForIdle()
        assertEquals(10 to 0, confirmed)
    }

    @Test
    fun 오후로_바꾸면_12를_더하고_분은_그대로다() {
        open(9, 30)
        tapCell("wheel_ampm", "오후")
        readout().assertContentDescriptionEquals("오후 9:30")

        compose.onNodeWithTag("wheel_confirm").performClick()
        compose.waitForIdle()
        assertEquals(21 to 30, confirmed)
    }

    @Test
    fun 분을_59에서_00으로_넘겨도_시는_그대로다() {
        open(9, 59)
        tapCell("wheel_minute", "00")
        readout().assertContentDescriptionEquals("오전 9:00")
    }

    @Test
    fun 취소는_값을_넘기지_않는다() {
        open(9, 0)
        tapCell("wheel_hour", "11")
        compose.onNodeWithTag("wheel_cancel").performClick()
        compose.waitForIdle()
        assertTrue(dismissed)
        assertNull(confirmed)
    }

    @Test
    fun 오분_간격이면_맞지_않는_분을_앞_칸으로_내린다() {
        open(7, 43, step = 5)
        readout().assertContentDescriptionEquals("오전 7:40")
    }
}
