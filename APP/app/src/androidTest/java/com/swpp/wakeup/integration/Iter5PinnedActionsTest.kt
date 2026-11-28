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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.MorningBlock
import com.swpp.wakeup.domain.model.MorningSession
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.ui.alarm.AlarmDecisionScreen
import com.swpp.wakeup.ui.events.AlarmConfirmContent
import com.swpp.wakeup.ui.events.AlarmConfirmView
import com.swpp.wakeup.ui.home.HomeScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.morning.MorningProgressScreen
import com.swpp.wakeup.ui.theme.JitTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P10/P12/P19/P21: primary actions and genuine server evidence survive long content. */
class Iter5PinnedActionsTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val start = ZonedDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)

    private fun event() = UpcomingEvent(
        id = 1, startTime = "09:00", dayLabel = "월", title = "읽기 모임", placeAndRoute = "도서관 · 버스",
        alarmAt = "7:40", onTimeProbability = null, tag = null, planStatus = "ok", planStatusLabel = null,
        startAtEpochSecond = now / 1000 + 10_800,
        startDate = Instant.ofEpochMilli(now + 10_800_000).atZone(ZoneId.systemDefault()).toLocalDate(),
        alarmOn = true, alarmAtEpochSecond = now / 1000 + 3_600, alarmConfirmed = false,
    )

    private fun homeState(next: UpcomingEvent = event()) = HomeViewModel.UiState(
        nickname = "검토 사용자", loading = false, hasHome = true, totalCount = 12, nextAlarm = next,
        sections = (1..6).map { index ->
            EventSection("검토 일정 $index", "12월 $index", listOf(next.copy(id = 100L + index, title = "일정 $index")))
        },
    )

    private fun plan(prepSource: String = "observed"): AlarmPlanView {
        val dto = Gson().fromJson("""{
            "id":1,"title":"읽기 모임","start_at":"${start.toOffsetDateTime()}","alarm_on":true,
            "place":{"name":"도서관","lat":37.45,"lng":126.95},
            "alarm_plan":{"status":"ok","alarm_at":"${start.minusHours(2).toOffsetDateTime()}",
                "prep_minutes":30,"travel_minutes":40,"buffer_minutes":10,"total_minutes":80,
                "prep_source":"$prepSource","travel_time_source":"kakao_transit+observed_route",
                "travel_sample_count":12,"buffer_source":"fixed","travel_mode":"버스",
                "alarm_confirmed":false,"on_time_probability":null}}
        """, EventDto::class.java)
        return EventRepository(FakeEventsApi(), FakeProfileApi()).planFrom(dto)!!
    }

    @Composable
    private fun CompactViewport(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
            JitTheme {
                Box(Modifier.requiredSize(360.dp, 500.dp).testTag("pinned_viewport")) { content() }
            }
        }
    }

    @Composable
    private fun Home(
        state: HomeViewModel.UiState,
        busy: Long? = null,
        onConfirm: (UpcomingEvent) -> Unit = {},
        onRoutine: () -> Unit = {},
        onAdd: () -> Unit = {},
    ) {
        HomeScreen(
            state = state, onEventClick = {}, onAlarmToggle = { _, _ -> }, onAddEventClick = onAdd,
            onSetHomeClick = {}, onMorningClick = {}, onRetry = {},
            onRoutineClick = onRoutine, onConfirmNextAlarm = onConfirm, confirmBusyId = busy, nowMillis = now,
        )
    }

    @Composable
    private fun Alarm(
        value: AlarmPlanView?, supported: Boolean = true, editBusy: Boolean = false, confirmBusy: Boolean = false,
        onConfirm: () -> Unit = {}, onChange: () -> Unit = {}, onEdit: () -> Unit = {},
    ) {
        AlarmDecisionScreen(
            plan = value, onBack = {}, onChangeRisk = onChange, onEditBlocks = {}, onDelete = {},
            onEdit = onEdit, editBusy = editBusy, confirmBusy = confirmBusy,
            serverSupportsConfirm = supported, onConfirmAlarm = onConfirm, nowMillis = now,
        )
    }

    @Test fun homeConfirmRoutineAndAddStayVisibleBeforeAndAfterListScroll() {
        val calls = mutableListOf<String>()
        compose.setContent { CompactViewport {
            Home(homeState(), onConfirm = { calls += "confirm:${it.id}" },
                onRoutine = { calls += "routine" }, onAdd = { calls += "add" })
        } }

        listOf("home_confirm_alarm", "home_routine", "home_add_event").forEach {
            assertPinned(it)
            compose.onNodeWithTag(it).assertIsEnabled().performClick()
        }
        assertEquals(listOf("confirm:1", "routine", "add"), calls)
        compose.onNodeWithTag("home_list").performScrollToIndex(8)
        listOf("home_confirm_alarm", "home_routine", "home_add_event").forEach(::assertPinned)
        capture("home-fixed-actions")
    }

    @Test fun homeDoesNotOfferConfirmationForUnknownOffPastOrUnavailableAlarms() {
        val original = homeState()
        val state = mutableStateOf(original)
        compose.setContent { CompactViewport { Home(state.value) } }
        assertPinned("home_confirm_alarm")

        val next = event()
        val ineligible = listOf(
            original.copy(nextAlarm = next.copy(alarmConfirmed = null)),
            original.copy(nextAlarm = next.copy(alarmConfirmed = true)),
            original.copy(nextAlarm = next.copy(alarmOn = false)),
            original.copy(nextAlarm = next.copy(skipped = true)),
            original.copy(nextAlarm = next.copy(alarmAt = null)),
            original.copy(nextAlarm = next.copy(alarmAtEpochSecond = null)),
            original.copy(nextAlarm = next.copy(alarmAtEpochSecond = now / 1000 - 1)),
            original.copy(offline = true),
            original.copy(loading = true),
            original.copy(nextAlarm = null),
        )
        ineligible.forEach { value ->
            compose.runOnIdle { state.value = value }
            compose.onNodeWithTag("home_confirm_alarm").assertDoesNotExist()
            assertPinned("home_routine")
            assertPinned("home_add_event")
        }
    }

    @Test fun homeConfirmationIsDisabledWhileItsRequestIsPending() {
        compose.setContent { CompactViewport { Home(homeState(), busy = 1) } }
        assertPinned("home_confirm_alarm")
        compose.onNodeWithTag("home_confirm_alarm").assertIsNotEnabled()
        compose.onNodeWithTag("home_routine").assertIsEnabled()
    }

    @Test fun alarmConfirmChangeAndEditArePinnedAndCallDistinctActions() {
        val calls = mutableListOf<String>()
        val busy = mutableStateOf(false)
        val value = plan()
        compose.setContent { CompactViewport {
            Alarm(value, editBusy = busy.value, onConfirm = { calls += "confirm" },
                onChange = { calls += "change" }, onEdit = { calls += "edit" })
        } }

        listOf("plan_confirm_alarm", "risk_entry", "event_edit").forEach {
            assertPinned(it)
            compose.onNodeWithTag(it).assertIsEnabled().performClick()
        }
        assertEquals(listOf("confirm", "change", "edit"), calls)
        compose.onNodeWithTag("event_delete").performScrollTo()
        listOf("plan_confirm_alarm", "risk_entry", "event_edit").forEach(::assertPinned)
        capture("alarm-fixed-actions")
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("event_edit").assertIsNotEnabled()
    }

    @Test fun alarmConfirmationNeedsAComputedFutureEnabledUnconfirmedPlan() {
        val original = plan()
        val value = mutableStateOf<AlarmPlanView?>(original)
        val supported = mutableStateOf(true)
        compose.setContent { CompactViewport { Alarm(value.value, supported = supported.value) } }
        assertPinned("plan_confirm_alarm")

        listOf(
            original.copy(alarmConfirmed = null), original.copy(alarmConfirmed = true),
            original.copy(alarmOn = false), original.copy(skipped = true),
            original.copy(alarmPassed = true), original.copy(alarmAtMillis = null),
            original.copy(alarmAtMillis = now - 1, alarmPassed = false),
            original.copy(status = "route_failed", alarmAt = null), null,
        ).forEach { next ->
            compose.runOnIdle { value.value = next }
            compose.onNodeWithTag("plan_confirm_alarm").assertDoesNotExist()
        }
        compose.runOnIdle { value.value = original; supported.value = false }
        compose.onNodeWithTag("plan_confirm_alarm").assertDoesNotExist()
    }

    @Test fun metricBoxesKeepActualAmountsAndMeasuredOrFixedSources() {
        val value = mutableStateOf(plan())
        compose.setContent { CompactViewport { Alarm(value.value) } }

        compose.onNodeWithTag("plan_metric_PREP").performScrollTo().assertIsDisplayed()
            .assertTextContains("30분").assertTextContains("측정 · 준비 기록")
        compose.onNodeWithTag("plan_metric_TRAVEL").assertTextContains("40분")
            .assertTextContains("고정 예상 · 기록 12회로 보정")
        compose.onNodeWithTag("plan_metric_BUFFER").assertTextContains("10분")
            .assertTextContains("고정 · 기본 여유")
        compose.onNodeWithTag("plan_probability").assertDoesNotExist()
        compose.onNodeWithTag("plan_probability_reason").assertExists()
        capture("alarm-source-boxes")

        compose.runOnIdle { value.value = plan(prepSource = "not_from_home") }
        compose.onNodeWithTag("plan_metric_PREP").assertDoesNotExist()
        compose.onNodeWithTag("plan_metric_TRAVEL").assertExists()
    }

    @Test fun confirmationSheetKeepsItsActionOutsideLongScrollableEvidence() {
        var confirmed = 0
        val view = AlarmConfirmView(
            eventId = 1, dayWord = "내일", alarmClock = "오전 7:43",
            subline = "읽기 모임 · 검토용 도서관 · 긴 일정 이름과 장소도 내용 영역에서 확인해요",
            cardTitle = "내일 아침", probability = null, tau = .9,
            segments = listOf(
                AlarmConfirmView.Segment(AlarmConfirmView.Kind.PREP, 30),
                AlarmConfirmView.Segment(AlarmConfirmView.Kind.TRAVEL, 40),
                AlarmConfirmView.Segment(AlarmConfirmView.Kind.BUFFER, 10),
            ),
            points = listOf(
                AlarmConfirmView.Point("7:43", "기상", 0f), AlarmConfirmView.Point("8:13", "출발", .375f),
                AlarmConfirmView.Point("8:53", "도착", .875f), AlarmConfirmView.Point("9:03", "시작", 1f),
            ),
            footer = List(6) { "선택한 출발지와 이동 경로를 기준으로 준비·이동·여유 시간을 확인해요" }.joinToString("\n"),
        )
        compose.setContent { CompactViewport {
            AlarmConfirmContent(view, onChangeTime = {}, onDetails = {}, onConfirm = { confirmed++ })
        } }

        assertPinned("alarm_confirm_ok")
        compose.onNodeWithTag("alarm_confirm_ok").performClick()
        assertEquals(1, confirmed)
        compose.onNodeWithTag("alarm_confirm_details").performScrollTo().assertIsDisplayed()
        assertPinned("alarm_confirm_ok")
        capture("confirmation-fixed-action")
    }

    @Test fun morningUndoFinishSkipAndMarkKeep48DpActionsWithLargeText() {
        val calls = mutableListOf<String>()
        val session = MorningSession(
            eventId = 1, startedAtMillis = now - 1_200_000, departByMillis = now + 900_000,
            plannedPrepMinutes = 20, blocks = listOf(
                MorningBlock(1, "세수", 5.0, doneAtMillis = now - 900_000),
                MorningBlock(2, "아침 식사", 10.0), MorningBlock(3, "외출 준비", 5.0),
            ),
        )
        compose.setContent { CompactViewport {
            MorningProgressScreen(
                session = session, onBack = {}, onMarkDone = { calls += "done:$it" },
                onUndo = { calls += "undo" }, onFinish = { calls += "finish" },
                onSkipToday = { calls += "skip" },
            )
        } }

        listOf("morning_undo", "morning_finish", "morning_skip", "morning_action").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag needs a 48dp touch height", (bounds.bottom - bounds.top) >= 48.dp)
            compose.onNodeWithTag(tag).performClick()
        }
        assertEquals(listOf("undo", "finish", "skip", "done:2"), calls)
        capture("morning-large-text-actions")
    }

    @Test fun predepartureItineraryLivesInsideAlarmDetail() {
        val requests = mutableListOf<Long>()
        val data = plan()
        compose.setContent { CompactViewport {
            AlarmDecisionScreen(
                plan = data, nowMillis = now, onBack = {}, onChangeRisk = {}, onEditBlocks = {},
                onDelete = {}, onEdit = {}, onNeedSegments = { id, _ -> requests += id },
            )
        } }
        compose.onNodeWithTag("trip_route").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("가는 길").assertExists()
        assertEquals(listOf(data.eventId), requests)
        assertPinned("event_edit")
    }

    private fun assertPinned(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val action = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("pinned_viewport").getUnclippedBoundsInRoot()
        assertTrue("$tag must be wholly inside the viewport", action.top >= viewport.top && action.bottom <= viewport.bottom)
        assertTrue("$tag must retain its touch height", (action.bottom - action.top) >= 48.dp)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "iter5-pinned-actions").apply { mkdirs() }
        val output = File(dir, "$name.png")
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(output.length() > 0)
    }
}
