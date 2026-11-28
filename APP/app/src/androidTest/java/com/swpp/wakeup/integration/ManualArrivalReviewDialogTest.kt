package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.ui.alarm.ManualArrivalReview
import com.swpp.wakeup.ui.alarm.ManualArrivalReviewDialog
import com.swpp.wakeup.ui.theme.JitTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualArrivalReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "iter5-schedule/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("manual_arrival_review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun farArrivalShowsWarningAndCancelDoesNotRecord() {
        var recorded = 0
        val visible = mutableStateOf(true)
        compose.setContent {
            JitTheme {
                if (visible.value) ManualArrivalReviewDialog(
                    ManualArrivalReview.Position.FAR,
                    onConfirm = { recorded++; visible.value = false },
                    onDismiss = { visible.value = false },
                )
            }
        }
        compose.onNodeWithText("목적지와 떨어져 있어요").assertIsDisplayed()
        compose.onNodeWithTag("manual_arrival_cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("manual_arrival_confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("manual-arrival-far")
        compose.onNodeWithTag("manual_arrival_cancel").performClick()
        compose.runOnIdle { assertEquals(0, recorded) }
    }

    @Test fun unknownPositionAsksExplicitlyBeforeRecording() {
        var recorded = 0
        val visible = mutableStateOf(true)
        compose.setContent {
            JitTheme {
                if (visible.value) ManualArrivalReviewDialog(
                    ManualArrivalReview.Position.UNKNOWN,
                    onConfirm = { recorded++; visible.value = false },
                    onDismiss = { visible.value = false },
                )
            }
        }
        compose.onNodeWithText("도착을 직접 기록할까요?").assertIsDisplayed()
        compose.onNodeWithText("현재 위치를 확인하지 못했어요. 실제로 도착한 경우에만 기록해 주세요.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, recorded) }
        capture("manual-arrival-unknown")
        compose.onNodeWithTag("manual_arrival_confirm").performClick()
        compose.runOnIdle { assertEquals(1, recorded) }
    }
}
