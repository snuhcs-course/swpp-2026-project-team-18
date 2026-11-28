package com.swpp.wakeup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.EventUpdateRequest
import com.swpp.wakeup.data.remote.BlockObservationInput
import com.swpp.wakeup.data.remote.ringAt
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real local Django API + actual home/sheet + AlarmManager registration, without mocked choices. */
@RunWith(AndroidJUnit4::class)
class AlarmTimeConsistencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = DemoUi(device)
    private val directory = File(context.getExternalFilesDir(null), "alarm-time-qa")
    private val systemChecks = JSONArray()

    @Test
    fun learnedPreparationKeepsHomeRecommendationAndDeviceConsistent() {
        assertEquals("com.swpp.wakeup.qa", context.packageName)
        assertEquals("http://10.0.2.2:8765/", BuildConfig.BASE_URL)
        val config = JSONObject(File(context.filesDir, "alarm-time-config.json").readText())
        val id = config.getLong("event_id")
        directory.mkdirs()
        try {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            context.startActivity(Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            if (device.wait(Until.hasObject(By.res("login_email")), 3_000)) {
                ui.input("login_email", config.getString("email"), "login_scroll", 90_000)
                ui.input("login_password", config.getString("password"), "login_scroll")
                ui.click(By.res("login_submit"), "login_scroll")
            }
            ui.await(By.res("next_alarm"), timeout = 90_000)
            val event = runBlocking { requireNotNull(ApiClient.events.get(id).body()) }
            val plan = requireNotNull(event.alarmPlan)
            assertEquals(.9, requireNotNull(plan.tauUsed), .000001)
            val current = instant(requireNotNull(plan.ringAt))
            val clock = clock(requireNotNull(plan.ringAt))
            ui.await(By.res("next_alarm").hasDescendant(By.text(clock)))
            waitForSchedule(id, current)
            capture("01-home")

            ui.click(By.res("next_alarm_change_time"), "home_list")
            ui.await(By.res("alarm_time_quick_normal"))
            val choices = runBlocking { requireNotNull(ApiClient.events.riskOptions(id).body()) }
            assertEquals("normal", choices.recommendedKey)
            val normal = choices.options.single { it.key == "normal" }
            val recommended = instant(normal.alarmAt)
            ui.await(By.res("alarm_time_quick_normal").hasDescendant(By.textContains(clock(normal.alarmAt))))
            capture("02-time-sheet")
            File(directory, "comparison.json").writeText(JSONObject()
                .put("stored_alarm_at", plan.ringAt)
                .put("recommended_normal_at", normal.alarmAt)
                .put("device_alarm_at_ms", ScheduledAlarmStore(context).find(id)?.alarmAtMillis)
                .put("difference_minutes", (current - recommended) / 60_000)
                .toString(2))
            assertEquals("Stored normal alarm differs from the normal recommendation", current, recommended)

            ui.click(By.res("alarm_time_quick_normal"))
            ui.click(By.res("alarm_time_confirm").enabled(true))
            ui.await(By.res("home_list"))
            waitForSchedule(id, recommended)
            val saved = runBlocking { requireNotNull(ApiClient.events.get(id).body()) }
            assertEquals(recommended, instant(requireNotNull(saved.alarmPlan?.ringAt)))
            ui.await(By.res("next_alarm").hasDescendant(By.text(clock(normal.alarmAt))))
            capture("03-saved-home")

            // Another device can edit the event while this detail screen still holds its old plan.
            ui.click(By.res("next_alarm"), "home_list")
            ui.await(By.res("plan_scroll"))
            ui.await(By.res("risk_entry"))
            val changed = runBlocking {
                val response = ApiClient.events.update(id, EventUpdateRequest(
                    startAt = OffsetDateTime.parse(saved.startAt).plusHours(1).toString()))
                assertEquals(200, response.code())
                requireNotNull(response.body())
            }
            assertTrue(instant(requireNotNull(changed.alarmPlan?.ringAt)) != recommended)
            // A real offline record can finish uploading just as the time sheet is opened.
            val block = runBlocking { requireNotNull(ApiClient.routines.blocks().body()).single() }
            val recorded = OffsetDateTime.now().minusDays(1)
            val queue = BlockObservationQueue(context)
            queue.enqueue(BlockObservationInput(block = block.id, observedOn = recorded.toLocalDate().toString(),
                durationMinutes = 120.0, clientUuid = UUID.randomUUID().toString(), clientRecordedAt = recorded.toString()))
            ui.click(By.res("risk_entry"))
            ui.await(By.res("alarm_time_confirm").enabled(true))
            assertEquals("Pending preparation must upload before choices become writable", 0, queue.pendingCount)
            val refreshed = runBlocking { requireNotNull(ApiClient.events.get(id).body()) }
            val refreshedAt = requireNotNull(refreshed.alarmPlan?.ringAt)
            ui.await(By.res("alarm_time_confirm").enabled(true).hasDescendant(By.textContains(clock(refreshedAt))))
            waitForSchedule(id, instant(refreshedAt))
            val refreshedNormal = runBlocking { requireNotNull(ApiClient.events.riskOptions(id).body()) }
                .options.single { it.key == "normal" }
            assertEquals(instant(refreshedAt), instant(refreshedNormal.alarmAt))
            capture("04-refreshed-cached-plan")
            File(directory, "cache-refresh.json").writeText(JSONObject()
                .put("previous_alarm_at", saved.alarmPlan?.ringAt)
                .put("refreshed_alarm_at", refreshedAt)
                .put("recommended_normal_at", refreshedNormal.alarmAt)
                .put("device_alarm_at_ms", ScheduledAlarmStore(context).find(id)?.alarmAtMillis)
                .toString(2))
            device.pressBack()
            device.pressBack()
            ui.await(By.res("next_alarm").hasDescendant(By.text(clock(refreshedAt))))
            capture("05-refreshed-home")
        } catch (error: Throwable) {
            capture("failure")
            throw error
        }
    }

    private fun waitForSchedule(id: Long, at: Long) {
        val deadline = System.currentTimeMillis() + 30_000
        val registered = Regex("RTC_WAKEUP.*origWhen\\s+$at\\b.*" + Regex.escape(context.packageName))
        while (System.currentTimeMillis() < deadline) {
            val systemRegistered = device.executeShellCommand("dumpsys alarm").lineSequence().any { registered.containsMatchIn(it) }
            if (ScheduledAlarmStore(context).find(id)?.alarmAtMillis == at && systemRegistered) {
                systemChecks.put(JSONObject().put("alarm_at_ms", at).put("os_registered", true))
                File(directory, "system-alarms.json").writeText(systemChecks.toString(2))
                return
            }
            Thread.sleep(200)
        }
        assertEquals(at, ScheduledAlarmStore(context).find(id)?.alarmAtMillis)
        assertTrue("Android alarm service did not register the expected QA alarm timestamp", false)
    }

    private fun instant(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    private fun clock(value: String) = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm"))

    private fun capture(name: String) {
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
