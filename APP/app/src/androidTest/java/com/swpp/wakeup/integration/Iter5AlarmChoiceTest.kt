package com.swpp.wakeup.integration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.domain.model.RiskOptionsView
import com.swpp.wakeup.ui.alarm.AlarmTimeSheet
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Card identity and direct-clock intent must survive equal server clock values. */
class Iter5AlarmChoiceTest {
    @get:Rule val compose = createComposeRule()
    private var savedKey: String? = null
    private var savedClock: Int? = null

    @Test fun freshReadAndRetryKeepWritesDisabledUntilBothReadsAreReady() {
        val loading = mutableStateOf(true)
        val ready = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var retries = 0
        compose.setContent { JitTheme {
            AlarmTimeSheet(
                subtitle = null, options = sameClock, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = 430, startMinutesOfDay = 540, customSupported = true,
                userAlarm = true, loading = loading.value, canWrite = ready.value, error = error.value,
                onRetry = { retries++ },
            )
        } }
        compose.onNodeWithTag("alarm_time_loading").assertExists()
        compose.onNodeWithTag("alarm_time_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("alarm_time_revert").assertIsNotEnabled()
        captureReadState("loading")
        compose.runOnIdle {
            loading.value = false
            error.value = "알람 시각을 새로 받지 못했어요. 다시 시도해 주세요."
        }
        compose.onNodeWithTag("alarm_time_quick_normal").assertExists()
        compose.onNodeWithTag("alarm_time_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("alarm_time_revert").assertIsNotEnabled()
        compose.onNodeWithTag("alarm_time_retry").performScrollTo()
        captureReadState("error")
        compose.onNodeWithTag("alarm_time_retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { error.value = null; ready.value = true }
        compose.onNodeWithTag("alarm_time_confirm").assertIsEnabled()
        compose.onNodeWithTag("alarm_time_revert").assertIsEnabled()
    }

    private fun captureReadState(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "alarm-time-ui").apply { mkdirs() }
        UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png"))
    }
    private val sameClock = RiskOptionsView(
        options = listOf(
            RiskOptionView("safe", "안전", "7:08", "AM", 97, null, .97),
            RiskOptionView("normal", "보통", "7:10", "AM", 90, null, .90),
            RiskOptionView("gamble", "도박", "7:10", "AM", 65, null, .65),
        ),
        selectedKey = "gamble", recommendedKey = "normal", autoAdjusted = false,
        autoReason = null, distribution = null,
    )

    private fun show(userAlarm: Boolean = false) = compose.setContent {
        JitTheme {
            AlarmTimeSheet(
                subtitle = "수업", options = sameClock, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = 430, startMinutesOfDay = 540, customSupported = true,
                userAlarm = userAlarm, onConfirmCustom = { savedClock = it },
            )
        }
    }

    @Test fun confirmingUntouchedCollapsedChoicePreservesActualSavedRisk() {
        show()
        compose.onNodeWithTag("alarm_time_quick_safe").assertDoesNotExist()
        compose.onNodeWithTag("alarm_time_quick_gamble").assertDoesNotExist()
        compose.onNodeWithTag("alarm_time_collapsed").assertExists()
        compose.onNodeWithTag("alarm_time_confirm").assertIsDisplayed().performClick()
        assertEquals("gamble", savedKey)
        assertNull(savedClock)
    }

    @Test fun explicitVisibleCardTapUsesThatExactCardIdentity() {
        show()
        compose.onNodeWithTag("alarm_time_quick_normal").performClick()
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals("normal", savedKey)
        assertNull(savedClock)
    }

    @Test fun unchangedDirectTimeStaysDirectEvenAtAnIdenticalPresetClock() {
        show(userAlarm = true)
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals(430, savedClock)
        assertNull(savedKey)
    }

    @Test fun choosingACardFromDirectTimeExplicitlyChangesToThePreset() {
        show(userAlarm = true)
        compose.onNodeWithTag("alarm_time_quick_normal").performClick()
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals("normal", savedKey)
        assertNull(savedClock)
    }

    @Test fun asynchronouslyLoadedOptionsPreserveTheOriginalSelection() {
        val loaded = mutableStateOf<RiskOptionsView?>(null)
        compose.setContent { JitTheme {
            AlarmTimeSheet(
                subtitle = null, options = loaded.value, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = 430, startMinutesOfDay = 540, customSupported = true,
                onConfirmCustom = { savedClock = it },
            )
        } }
        compose.runOnIdle { loaded.value = sameClock }
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals("gamble", savedKey)
        assertNull(savedClock)
    }
    @Test fun lateInitialPlanDoesNotUndoAnExplicitCardChoice() {
        val clock = mutableStateOf<Int?>(null)
        compose.setContent { JitTheme {
            AlarmTimeSheet(
                subtitle = null, options = sameClock, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = clock.value, startMinutesOfDay = 540, customSupported = true,
                onConfirmCustom = { savedClock = it }, selectionKey = 1,
            )
        } }
        compose.onNodeWithTag("alarm_time_quick_normal").performClick()
        compose.runOnIdle { clock.value = 480 }
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals("normal", savedKey)
        assertNull(savedClock)
    }

    @Test fun lateInitialPlanStillInitializesAnUntouchedWheel() {
        val clock = mutableStateOf<Int?>(null)
        compose.setContent { JitTheme {
            AlarmTimeSheet(
                subtitle = null, options = sameClock, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = clock.value, startMinutesOfDay = 540, customSupported = true,
                onConfirmCustom = { savedClock = it }, selectionKey = 1,
            )
        } }
        compose.runOnIdle { clock.value = 480 }
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals(480, savedClock)
        assertNull(savedKey)
    }

    @Test fun anotherEventDoesNotInheritThePreviousEventsChoice() {
        val event = mutableStateOf(1L)
        compose.setContent { JitTheme {
            AlarmTimeSheet(
                subtitle = null, options = sameClock, onConfirm = { savedKey = it }, onDismiss = {},
                initialMinutesOfDay = 430, startMinutesOfDay = 540, customSupported = true,
                onConfirmCustom = { savedClock = it }, selectionKey = event.value,
            )
        } }
        compose.onNodeWithTag("alarm_time_quick_normal").performClick()
        compose.runOnIdle { event.value = 2L }
        compose.onNodeWithTag("alarm_time_confirm").performClick()
        assertEquals("gamble", savedKey)
        assertNull(savedClock)
    }

}
