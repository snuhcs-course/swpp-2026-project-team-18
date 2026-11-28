package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.ui.alarm.AlarmDecisionScreen
import com.swpp.wakeup.ui.home.HomeScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.settings.HomesScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** QA 패키지에서만 실행하는, 네트워크와 계정이 필요 없는 변경 화면 검증. */
class Iter5FrontendSmokeTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "iter5-frontend").apply { mkdirs() }
        val output = File(dir, "$name.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Test fun largeTextKeepsAllSavedHomeActionsVisibleAndTouchable() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f),
            ) { JitTheme {
                HomesScreen(
                    state = HomeViewModel.UiState(nickname = "진호", loading = false, hasHome = true, homeLabel = "봉천동 집",
                        savedHomes = listOf(SavedHome("dorm", "관악 기숙사", "생활관", "서울 관악구 관악로 1", 37.46, 126.95))),
                    onChangeHome = {}, onAddExtraHome = {}, onMakeHomeDefault = {}, onRemoveHome = {}, onBack = {},
                )
            } }
        }
        listOf("이름 바꾸기", "기본으로", "삭제").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        capture("homes-large-font")
    }

    @Test fun tripIndexOpensTheAlarmDetailWhereProgressAndArrivalLive() {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now()
        val a = com.swpp.wakeup.sensing.GeoPoint(37.48, 126.95)
        val b = com.swpp.wakeup.sensing.GeoPoint(37.46, 126.95)
        val event = com.swpp.wakeup.domain.model.UpcomingEvent(
            id = 1, startTime = "09:00", dayLabel = "금", title = "자료구조", placeAndRoute = "서울대 · 도보",
            alarmAt = "7:40", onTimeProbability = null, tag = null, planStatus = "ok", planStatusLabel = null,
            startAtEpochSecond = now / 1000 + 3600, startDate = today, alarmOn = true,
        )
        val dto = Gson().fromJson("""{"id":1,"title":"자료구조","start_at":"2030-12-04T09:00:00+09:00",
            "alarm_plan":{"status":"ok","alarm_at":"2030-12-04T07:40:00+09:00","prep_minutes":28,"travel_minutes":40,"buffer_minutes":10}}""", EventDto::class.java)
        val plan = EventRepository(FakeEventsApi(), FakeProfileApi()).planFrom(dto)!!.copy(
            routePath = listOf(a, b), originPoint = a, destinationPoint = b, startAtMillis = now + 3_600_000,
            alarmOn = true, placeName = "서울대", travelMode = "도보",
        )
        val live = com.swpp.wakeup.sensing.TripLiveState.Snapshot(
            eventId = 1, point = com.swpp.wakeup.sensing.GeoPoint(37.47, 126.95), accuracyM = 10f, atMillis = now,
            phase = com.swpp.wakeup.sensing.TripGeofence.Phase.IN_TRANSIT, movingSinceMillis = now - 600_000,
        )
        var arrived = false
        val opened = androidx.compose.runtime.mutableStateOf(false)
        var segmentsRequested = 0
        compose.setContent { JitTheme {
            // P18 (Schedule row124) supersedes the earlier duplicated trip-tab
            // progress UI: the tab indexes the journey, detail owns its actions.
            if (opened.value) {
                AlarmDecisionScreen(
                    plan = plan, onBack = { opened.value = false }, onChangeRisk = {}, onEditBlocks = {}, onDelete = {},
                    stage = com.swpp.wakeup.domain.model.TripStage.IN_TRANSIT,
                    progress = com.swpp.wakeup.domain.model.RouteProgress.ofJourney(plan.routePath, a, b, live.point),
                    nickname = "검토 사용자", initials = "검토", onArrive = { arrived = true },
                )
            } else {
            com.swpp.wakeup.ui.trip.TripTabScreen(
                event = event, hasEvents = true, today = today, trip = HomeViewModel.TripTabState(eventId = 1, plan = plan),
                segments = null, inTransit = true, onOpenEvent = { id -> assertEquals(1L, id); opened.value = true }, onChangeRoute = {}, onAddEvent = {},
                onNeedSegments = { _, _ -> segmentsRequested++ }, onRetry = {}, live = live, homePoint = a, nickname = "검토 사용자", initials = "검토",
                onArrive = { arrived = true },
            )
            }
        } }
        compose.onNodeWithTag("trip_status").assertDoesNotExist()
        compose.onNodeWithTag("trip_arrive").assertDoesNotExist()
        compose.onNodeWithTag("trip_open_details").performScrollTo().assertIsDisplayed()
        assertEquals(0, segmentsRequested)
        capture("trip-index")
        compose.onNodeWithTag("trip_open_details").performClick()
        compose.onNodeWithTag("trip_status").assertExists()
        compose.onNodeWithTag("trip_arrive").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("trip-progress")
        compose.onNodeWithTag("trip_arrive").performClick()
        assertTrue(arrived)
        assertTrue(opened.value)
    }

    @Test fun notificationBannerHasActionAndMinimumTouchArea() {
        var opened = false
        compose.setContent { JitTheme {
            HomeScreen(
                state = HomeViewModel.UiState(nickname = "진호", loading = false, hasHome = true, totalCount = 0),
                onEventClick = {}, onAlarmToggle = { _, _ -> }, onAddEventClick = {}, onSetHomeClick = {}, onMorningClick = {}, onRetry = {},
                notificationsEnabled = false, onEnableNotifications = { opened = true },
            )
        } }
        compose.onNodeWithTag("home_enable_notifications").assertHeightIsAtLeast(48.dp)
        capture("home-notifications")
        compose.onNodeWithTag("home_enable_notifications").performClick()
        assertTrue(opened)
    }

    @Test fun alarmShowsUnknownProbabilityAndConfirmAction() {
        val event = Gson().fromJson("""{"id":1,"title":"자료구조","start_at":"2030-12-04T09:00:00+09:00","alarm_on":true,
            "place":{"name":"서울대","lat":37.46,"lng":126.95},"alarm_plan":{"status":"ok","alarm_at":"2030-12-04T07:40:00+09:00",
            "prep_minutes":28,"travel_minutes":42,"buffer_minutes":10,"alarm_confirmed":false}}""", EventDto::class.java)
        val plan = EventRepository(FakeEventsApi(), FakeProfileApi()).planFrom(event)!!
        var confirmed = false
        compose.setContent { JitTheme {
            AlarmDecisionScreen(plan = plan, onBack = {}, onChangeRisk = {}, onEditBlocks = {}, onDelete = {}, serverSupportsConfirm = true,
                nowMillis = java.time.OffsetDateTime.parse("2030-12-03T23:00:00+09:00").toInstant().toEpochMilli(),
                onConfirmAlarm = { confirmed = true })
        } }
        compose.onNodeWithTag("plan_probability_reason").performScrollTo().assertExists()
        compose.onNodeWithTag("plan_probability").assertDoesNotExist()
        compose.onNodeWithTag("plan_confirm_alarm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("alarm-confirm")
        compose.onNodeWithTag("plan_confirm_alarm").performClick()
        assertTrue(confirmed)
    }

    @Test fun savedHomeRenameKeepsInputAndSavesLabel() {
        var saved: Pair<String, String>? = null
        compose.setContent { JitTheme {
            HomesScreen(
                state = HomeViewModel.UiState(nickname = "진호", loading = false, hasHome = true, homeLabel = "봉천동 집", savedHomesOnServer = true, savedHomes = listOf(
                    SavedHome("dorm", "관악 기숙사", "학생생활관", "서울 관악구 관악로 1", 37.46, 126.95),
                )),
                onChangeHome = {}, onAddExtraHome = {}, onMakeHomeDefault = {}, onRemoveHome = {}, onBack = {},
                onRenameHome = { id, label -> saved = id to label; null },
            )
        } }
        compose.onNodeWithText("이름 바꾸기").performScrollTo().performClick()
        compose.onNodeWithTag("home_rename_input").performTextReplacement("기숙사")
        // AlertDialog is a separate root. Capture the underlying page; input/action is asserted separately.
        compose.onNodeWithText("이름 저장").performClick()
        compose.waitUntil { saved != null }
        assertEquals("dorm" to "기숙사", saved)
        compose.waitForIdle()
        capture("homes")
    }
}
