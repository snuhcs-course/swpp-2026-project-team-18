package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.report.ReportPreviewData
import com.swpp.wakeup.ui.report.WeeklyReportScreen
import com.swpp.wakeup.ui.settings.SettingsScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** P23/P24: real semantics and hit bounds under a narrow viewport and large font. No API calls. */
class Iter5SecondaryAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Compact(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
            JitTheme { Box(Modifier.requiredSize(320.dp, 560.dp)) { content() } }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(750)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "iter5-a11y").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test fun accountActionsStayTouchableAndKeepTheirDistinctCallbacksAtLargeFont() {
        val linked = mutableListOf<SocialProvider>()
        val unlinked = mutableListOf<SocialProvider>()
        var logoutCalls = 0
        compose.setContent { Compact {
            SettingsScreen(
                state = HomeViewModel.UiState(nickname = "검토 사용자", loginMethods = listOf("password", "kakao")),
                onLogout = {}, onBack = {}, onSocialLink = { linked += it },
                onSocialUnlink = { unlinked += it }, onLogoutAll = { logoutCalls++ },
            )
        } }
        listOf("social_kakao_action", "social_google_action", "logout_all").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(SocialProvider.KAKAO), unlinked)
            assertEquals(listOf(SocialProvider.GOOGLE), linked)
            assertEquals(1, logoutCalls)
        }
        capture("settings-large-font-actions")
    }

    @Test fun busyLogoutRetainsItsButtonBoundsButCannotBeSubmittedAgain() {
        var calls = 0
        compose.setContent { Compact {
            SettingsScreen(
                state = HomeViewModel.UiState(nickname = "검토 사용자"), onLogout = {}, onBack = {},
                onLogoutAll = { calls++ }, logoutAllBusy = true,
            )
        } }
        compose.onNodeWithTag("logout_all").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun reportWeekKeepsAllSeven48DpDaysReachableOnSmallScreen() {
        val report = ReportPreviewData.exampleWeek
        val opened = mutableListOf<LocalDate>()
        compose.setContent { Compact {
            WeeklyReportScreen(
                report = report, loading = false, error = null,
                onPreviousWeek = {}, onNextWeek = {}, onRetry = {},
                onOpenDay = { date, _ -> opened += date }, today = LocalDate.of(2030, 1, 1),
            )
        } }
        for (index in 0..6) {
            compose.onNodeWithTag("report_day_$index").performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("report_day_4").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(report.weekStart!!.plusDays(4)), opened) }
        capture("report-large-font-days")
    }
}
