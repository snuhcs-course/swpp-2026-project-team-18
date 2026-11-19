// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.domain.model.RiskOptionsView
import com.swpp.wakeup.domain.model.UserAlarmRule
import com.swpp.wakeup.ui.alarm.AlarmTimeSheet
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 일정 시작 뒤에 울리는 휠 시각은 [맞추기] 가 꺼지고 이유 한 줄이 보인다(11-18). 세 칸은 그대로 맞춰진다 */
@RunWith(AndroidJUnit4::class)
class AlarmAfterStartSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RiskOptionsView(
        options = listOf(
            RiskOptionView("safe", "안전", "7:27", "AM", 99, null, .97),
            RiskOptionView("normal", "보통", "7:32", "AM", 96, null, .90),
            RiskOptionView("gamble", "도박", "7:41", "AM", 82, null, .65),
        ),
        selectedKey = "normal", recommendedKey = "normal", autoAdjusted = false, autoReason = null, distribution = null,
    )

    private var custom = 0
    private var preset: String? = null

    private fun show(initial: Int) = compose.setContent {
        JitTheme {
            AlarmTimeSheet(
                subtitle = "수업 · 오전 9:00 시작", options = options,
                onConfirm = { preset = it }, onDismiss = {},
                recommendedKey = "normal", goalTau = .9,
                initialMinutesOfDay = initial, startMinutesOfDay = 9 * 60, customSupported = true,
                onConfirmCustom = { custom++ },
            )
        }
    }

    @Test
    fun 시작_뒤_시각은_맞추기가_꺼지고_이유가_보인다() {
        show(9 * 60 + 30)
        compose.onNodeWithTag("alarm_time_after_start").assertTextEquals(UserAlarmRule.AFTER_START_NOTE)
        compose.onNodeWithTag("alarm_time_confirm").assertIsNotEnabled().performClick()
        assertEquals(0, custom)
    }

    @Test
    fun 시작_전_시각과_세_칸은_그대로_맞춰진다() {
        show(8 * 60 + 50)
        compose.onNodeWithTag("alarm_time_after_start").assertDoesNotExist()
        compose.onNodeWithTag("alarm_time_confirm").assertIsEnabled().performClick()
        assertEquals(1, custom)
    }

}
