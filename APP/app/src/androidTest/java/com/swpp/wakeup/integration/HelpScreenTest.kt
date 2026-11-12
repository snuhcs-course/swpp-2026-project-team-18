// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.help.HelpScreen
import com.swpp.wakeup.ui.help.HelpTopic
import com.swpp.wakeup.ui.help.OnTimeProbabilitySheet
import com.swpp.wakeup.ui.help.OnTimeSheetSpec
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 도움말(Figma `551:1820`)과 "정시 도착 확률이 뭐예요?" 시트(`548:1798`) — iter4 H2-4 */
@RunWith(AndroidJUnit4::class)
class HelpScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 첫_질문이_펼쳐져_있고_다른_질문을_누르면_그것만_펼친다() {
        compose.setContent { JitTheme { HelpScreen(expand = null, onBack = {}, onReplayIntro = {}) } }

        compose.onNodeWithTag("help_a_ALARM_TIME").assertIsDisplayed()
        compose.onNodeWithTag("help_count").assertTextEquals("8개")

        compose.onNodeWithTag("help_q_PREP_TIME").performScrollTo().performClick()
        compose.onNodeWithTag("help_a_PREP_TIME").assertExists()
        compose.onNodeWithTag("help_a_ALARM_TIME").assertDoesNotExist()
    }

    @Test
    fun i_로_오면_그_질문을_펼친다() {
        compose.setContent {
            JitTheme { HelpScreen(expand = HelpTopic.ALARM_DIDNT_RING, onBack = {}, onReplayIntro = {}) }
        }
        compose.onNodeWithTag("help_a_ALARM_DIDNT_RING").assertExists()
        compose.onNodeWithTag("help_a_ALARM_TIME").assertDoesNotExist()
    }

    @Test
    fun 검색하면_맞는_질문만_남고_펼친다() {
        compose.setContent { JitTheme { HelpScreen(expand = null, onBack = {}, onReplayIntro = {}) } }

        compose.onNodeWithTag("help_search").performTextInput("강제 중지")
        compose.waitForIdle()

        compose.onNodeWithTag("help_count").assertTextEquals("1개")
        compose.onNodeWithTag("help_a_ALARM_DIDNT_RING").assertExists()
        compose.onNodeWithTag("help_q_ALARM_TIME").assertDoesNotExist()
    }

    @Test
    fun 처음_안내_다시_보기() {
        var replayed = 0
        compose.setContent { JitTheme { HelpScreen(expand = null, onBack = {}, onReplayIntro = { replayed++ }) } }
        compose.onNodeWithTag("help_replay_intro").performScrollTo().performClick()
        assertEquals(1, replayed)
    }

    @Test
    fun 시트는_10번_중_9번과_알람_시각_바꾸기를_보여준다() {
        var changed = 0
        compose.setContent {
            JitTheme {
                OnTimeProbabilitySheet(
                    spec = OnTimeSheetSpec(percent = 97, alarmLabel = "오전 7:40", startLabel = "오전 9:00"),
                    tone = JitColor.Green,
                    onChangeTime = { changed++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("오전 7:40에 일어나면").assertExists()
        compose.onNodeWithTag("on_time_percent").assertTextEquals("정시 도착 확률 97%")
        compose.onNodeWithTag("on_time_sentence")
            .assertTextEquals("같은 아침이 10번 온다면\n9번은 오전 9:00 전에 도착한다는 뜻이에요.")
        compose.onNodeWithTag("on_time_change_time").performClick()
        assertEquals(1, changed)
    }
}
