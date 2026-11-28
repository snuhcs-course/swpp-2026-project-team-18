package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.ui.events.AddEventScreen
import com.swpp.wakeup.ui.events.RouteChoiceScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P10/P12: primary actions stay reachable when the content needs scrolling. */
class Iter5FormActionsTest {
    @get:Rule val compose = createComposeRule()

    private val home = PlaceSearchItem(null, "집", "검토용 집 주소", 37.48, 126.95, null)
    private val destination = PlaceSearchItem("fixture", "도서관", "검토용 목적지 주소", 37.45, 126.95, null)

    private fun ready(editing: Boolean = false) = HomeViewModel.AddState(
        title = "읽기 모임",
        date = LocalDate.now().plusDays(7),
        hour = 10,
        timePicked = true,
        selectedPlace = destination,
        routeKey = "transit:fixture",
        routeLabel = "32분 · 버스와 도보",
        editingEventId = 42L.takeIf { editing },
    )

    @Composable
    private fun CompactViewport(height: Dp = 420.dp, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
            JitTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = height).testTag("compact_viewport")) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun Form(state: HomeViewModel.AddState, inSheet: Boolean = false, onSubmit: () -> Unit = {}) {
        AddEventScreen(
            state = state, hasHome = true, homePlace = home, tags = emptyList(),
            onTitleChange = {}, onDateChange = {}, onTimeChange = { _, _ -> },
            onRepeatDayToggle = {}, onTagChange = {}, onQueryChange = {}, onSearch = {},
            onPlaceSelect = {}, onDestinationHome = {}, onLoadMore = {}, onSortChange = {},
            onOpenMap = {}, onOpenPlaceUrl = {}, onOriginQueryChange = {}, onOriginSearch = {},
            onOriginSelect = {}, onOriginLoadMore = {}, onOriginSortChange = {},
            onOriginOpenMap = {}, onUseCurrentLocation = {}, onSetHome = {}, onPickRoute = {},
            onSubmit = onSubmit, onBack = {}, inSheet = inSheet,
        )
    }

    @Test fun editSaveIsVisibleBeforeScrollingWithLargeText() {
        var saved = 0
        compose.setContent { CompactViewport { Form(ready(editing = true), onSubmit = { saved++ }) } }

        compose.onNodeWithTag("event_submit").assertIsDisplayed().assertIsEnabled().performClick()
        assertActionFullyInsideViewport("event_submit")
        assertEquals(1, saved)
        capture("edit-save-large-text")
        compose.onNodeWithTag("tag_picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("event_submit").assertIsDisplayed()
        assertActionFullyInsideViewport("event_submit")
    }

    @Test fun fullScreenAddActionSurvivesAKeyboardSizedViewport() {
        val height = mutableStateOf(520.dp)
        var saved = 0
        compose.setContent { CompactViewport(height.value) { Form(ready(), onSubmit = { saved++ }) } }

        compose.onNodeWithTag("event_submit").assertIsDisplayed()
        // The host consumes system/IME insets before sizing the screen. Shrink the
        // allocation the same way without depending on a particular keyboard app.
        compose.runOnIdle { height.value = 300.dp }
        compose.onNodeWithTag("event_submit").assertIsDisplayed().assertIsEnabled().performClick()
        assertActionFullyInsideViewport("event_submit")
        assertEquals(1, saved)
        capture("add-short-viewport")
    }

    @Test fun sheetKeepsValidationAndActionVisibleWithoutScrolling() {
        val state = mutableStateOf(HomeViewModel.AddState())
        var saved = 0
        compose.setContent { CompactViewport { Form(state.value, inSheet = true, onSubmit = { saved++ }) } }

        compose.onNodeWithTag("add_required_hint").assertIsDisplayed()
        compose.onNodeWithTag("event_submit").assertIsDisplayed().assertIsNotEnabled()
        assertActionFullyInsideViewport("event_submit")
        compose.runOnIdle { state.value = ready() }
        compose.onNodeWithTag("event_submit").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test fun formControlsKeep48DpTargetsWithLargeText() {
        compose.setContent { CompactViewport { Form(ready()) } }

        (0..6).forEach { index ->
            compose.onNodeWithTag("repeat_day_$index").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("tag_picker").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("event_submit").assertIsDisplayed()
        assertActionFullyInsideViewport("event_submit")
    }

    @Test fun routeConfirmStaysVisibleWhileLongRouteListScrolls() {
        val options = (1..7).map { index ->
            RouteOption(
                key = "transit:fixture-$index", mode = "버스+도보", minutes = 25 + index,
                minutesLabel = "${25 + index}분", detailLine = "버스 이용 · 환승 0회 · 1,550원", badge = null,
            )
        }
        var confirmed = 0
        compose.setContent { CompactViewport {
            RouteChoiceScreen(
                state = HomeViewModel.RouteState(
                    loading = false,
                    choice = RouteChoice("집", "도서관", options, options.first().key),
                ),
                onSelect = {}, onConfirm = { confirmed++ }, onRetry = {}, onBack = {},
            )
        } }

        compose.onNodeWithTag("route_confirm").assertIsDisplayed().assertIsEnabled()
        assertActionFullyInsideViewport("route_confirm")
        compose.onNodeWithTag("route_${options.last().key}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("route_confirm").assertIsDisplayed().performClick()
        assertActionFullyInsideViewport("route_confirm")
        assertEquals(1, confirmed)
        capture("route-confirm-large-text")
    }

    private fun assertActionFullyInsideViewport(tag: String) {
        val action = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("compact_viewport").getUnclippedBoundsInRoot()
        assertTrue("The full action must stay above the viewport bottom", action.bottom <= viewport.bottom)
        assertTrue("The full action must stay below the viewport top", action.top >= viewport.top)
        assertTrue("The action must retain a 48dp touch target", (action.bottom - action.top) >= 48.dp)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
            "iter5-form-actions",
        ).apply { mkdirs() }
        val output = File(dir, "$name.png")
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(output.length() > 0)
    }
}
