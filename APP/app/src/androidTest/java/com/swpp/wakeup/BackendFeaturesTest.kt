package com.swpp.wakeup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ProfileUpdateRequest
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable replay-server inputs, real API decisions and real emulator UI. */
@RunWith(AndroidJUnit4::class)
class BackendFeaturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ui = DemoUi(device)
    private val directory = File(context.getExternalFilesDir(null), "demo-qa")
    private val config = JSONObject(File(context.filesDir, "demo-qa-config.json").readText())

    @Test
    fun homeDestinationAndRiskChoices() {
        assertEquals("com.swpp.wakeup.qa", context.packageName)
        assertEquals("http://10.0.2.2:8765/", BuildConfig.BASE_URL)
        val started = System.currentTimeMillis()
        try {
            checkHomeDestination()
            checkRiskChoices()
            File(directory, "backend-features-result.json").writeText(JSONObject()
                .put("status", "passed").put("home", "UI create → current-home update; no Place row")
                .put("risk", "empty → modeled choices → explicit save → device alarm sync")
                .put("duration_ms", System.currentTimeMillis() - started).toString(2))
        } catch (error: Throwable) {
            capture("backend-features-failure")
            throw error
        }
    }

    private fun checkHomeDestination() {
        val profile = runBlocking { requireNotNull(ApiClient.profile.get().body()) }
        var addedId: Long? = null
        try {
            launch()
            ui.click(By.text("+  일정 추가"), "home_list")
            ui.input("event_title", "QA 집 도착")
            ui.click(By.res("event_date"))
            val date = LocalDate.parse(config.getString("event_date"))
            if (YearMonth.from(date) != YearMonth.now())
                ui.click(By.desc(Pattern.compile("Change to next month|다음 달.*")))
            ui.click(By.textContains(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(Locale.getDefault()))))
            ui.click(By.text("확인"))
            ui.click(By.res("event_time"))
            ui.pickTime(config.getString("event_time"))
            ui.click(By.text("확인"))
            // Close the default destination search before opening the origin search.
            ui.click(By.res("destination_home"), "add_event_scroll")
            ui.click(By.res("origin_search"), "add_event_scroll")
            val query = config.getString("destination_query")
            ui.input("place_query", query, "add_event_scroll")
            ui.click(By.res("place_search"), "add_event_scroll")
            ui.click(By.res("place_result_$query"), "add_event_scroll")
            capture("feature-home-selected")
            ui.click(By.textContains("경로 고르기"), "add_event_scroll")
            ui.click(By.text("도보"), "route_scroll")
            ui.click(By.res("route_walk"), "route_scroll")
            ui.await(By.res("route_guide"))
            ui.click(By.res("route_preview_confirm"))
            ui.click(By.res("event_submit"), "add_event_scroll")
            ui.await(By.res("home_list"))
            val added = runBlocking { requireNotNull(ApiClient.events.list().body()).results
                .single { it.title == "QA 집 도착" } }
            addedId = added.id
            assertTrue(added.destinationIsHome)
            assertNull(requireNotNull(added.place).id)
            assertEquals(query, added.originLabel)

            val lat = requireNotNull(profile.homeLat) - .002
            val lng = requireNotNull(profile.homeLng) + .001
            assertEquals(200, runBlocking { ApiClient.profile.update(
                ProfileUpdateRequest(homeLat = lat, homeLng = lng, homeLabel = "QA 바뀐 집")).code() })
            val updated = runBlocking { requireNotNull(ApiClient.events.get(added.id).body()) }
            assertEquals(lat, requireNotNull(updated.place).lat, .000001)
            assertEquals(lng, requireNotNull(updated.place).lng, .000001)
            assertNull(requireNotNull(updated.place).id)
            assertEquals("ok", requireNotNull(updated.alarmPlan).status)
            launch()
            ui.click(By.text("QA 집 도착"), "home_list")
            ui.await(By.textContains("QA 바뀐 집"), "plan_scroll")
            capture("feature-home-updated")
        } finally {
            assertEquals(200, runBlocking { ApiClient.profile.update(ProfileUpdateRequest(
                homeLat = profile.homeLat, homeLng = profile.homeLng, homeLabel = profile.homeLabel)).code() })
            addedId?.let { assertEquals(204, runBlocking { ApiClient.events.delete(it).code() }) }
            launch()
        }
    }

    private fun checkRiskChoices() {
        val id = config.getLong("event_id")
        demoControl(config, "/risk_empty")
        openRisk()
        ui.await(By.res("risk_unavailable"), "risk_scroll")
        capture("feature-risk-empty")
        demoControl(config, "/risk_ready")
        openRisk()
        for (key in listOf("safe", "normal", "gamble"))
            ui.await(By.res("risk_option_$key"), "risk_scroll")
        assertFalse("custom τ must require an explicit choice",
            ui.withNode(By.res("risk_continue"), "risk_scroll") { it.isEnabled })
        ui.await(By.text("예상 총 소요시간 분포 · 모형 계산"), "risk_scroll")
        capture("feature-risk-distribution")
        ui.click(By.res("risk_option_normal"), "risk_scroll")
        capture("feature-risk-options")
        ui.click(By.res("risk_continue"), "risk_scroll")
        ui.await(By.res("plan_scroll"))
        val saved = runBlocking { requireNotNull(ApiClient.events.get(id).body()) }
        assertEquals(.9, requireNotNull(saved.tauOverride), .000001)
        val plan = requireNotNull(saved.alarmPlan)
        assertEquals(.9, requireNotNull(plan.tauUsed), .000001)
        val alarmAt = OffsetDateTime.parse(plan.alarmAt).toInstant().toEpochMilli()
        val store = ScheduledAlarmStore(context)
        val deadline = System.currentTimeMillis() + 30_000
        while (store.find(id)?.alarmAtMillis != alarmAt && System.currentTimeMillis() < deadline)
            Thread.sleep(300)
        assertEquals(alarmAt, store.find(id)?.alarmAtMillis)
        capture("feature-risk-saved")
        demoControl(config, "/risk_restore")
        launch()
    }

    private fun openRisk() {
        launch()
        ui.click(By.res("next_alarm"), "home_list")
        ui.click(By.res("risk_entry"), "plan_scroll")
    }

    private fun launch() {
        context.startActivity(Intent(context, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.await(By.res("home_list"), timeout = 90_000)
    }

    private fun capture(name: String) {
        directory.mkdirs()
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
