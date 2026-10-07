package com.swpp.wakeup

import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.sensing.TripGeofence
import com.swpp.wakeup.sensing.TripLiveState
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 알람·UI·GPS 서비스·업로드를 한 흐름으로 검사한다. 실행은 qa/demo/run.py. */
@RunWith(AndroidJUnit4::class)
class DemoFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = DemoUi(device)
    private val directory = File(context.getExternalFilesDir(null), "demo-qa")
    private val steps = JSONArray()
    private lateinit var config: JSONObject
    private var started = 0L
    private val gpsListener = LocationListener { /* 에뮬레이터의 실제 GPS 공급자를 활성 상태로 둔다. */ }

    @Test
    fun alarmToArrival() {
        assertEquals("QA 전용 앱에서만 실행해야 함", "com.swpp.wakeup.qa", context.packageName)
        config = JSONObject(File(context.filesDir, "demo-qa-config.json").readText())
        val publicE2e = config.optBoolean("public_e2e", false)
        assertEquals(if (publicE2e) "https://justintime-api.onrender.com/" else "http://10.0.2.2:8765/",
            BuildConfig.BASE_URL)
        val eventId = config.getLong("event_id")
        started = System.currentTimeMillis()
        val locationManager = context.getSystemService(LocationManager::class.java)
        try {
            step("01_login") {
                launch()
                // 바로 앞 UI 준비 단계에서 로그인한 계정을 이어 쓴다.
                find(By.res("next_alarm"), timeout = 90_000)
                // FusedLocation의 저전력 요청도 에뮬레이터 GPS를 받을 수 있게 한다.
                // fake Location/TripLiveState/관측을 주입하지 않는다.
                instrumentation.runOnMainSync {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L,
                        0f, gpsListener, Looper.getMainLooper())
                }
            }

            step("02_plan_and_map") {
                openPlan()
                find(By.textContains(config.getString("event_title")), "plan_scroll")
                find(By.res("trip_status"), "plan_scroll")
                find(By.desc("경로 지도"), "plan_scroll", timeout = 45_000)
            }

            var alarmAt = 0L
            step("03_arm_and_lock") {
                alarmAt = control("/arm").getLong("alarm_at_ms")
                launch() // 실제 서버 변경을 새 홈 화면에서 받아 AlarmManager에 등록한다.
                find(By.res("next_alarm"))
                until("앱의 실제 알람 등록", 20_000) {
                    ScheduledAlarmStore(context).find(eventId)?.alarmAtMillis == alarmAt
                }
                assertTrue("알람이 잠그기 전에 지나감", alarmAt > System.currentTimeMillis())
                device.pressHome()
                device.sleep()
            }

            step("04_actual_alarm") {
                // AlarmActivity를 직접 실행하지 않는다. 실제 AlarmManager 발화를 기다린다.
                find(By.res("alarm_dismiss"), timeout = 100_000)
                assertTrue("예약 시각보다 일찍 울림", System.currentTimeMillis() >= alarmAt - 1_000)
                assertTrue("알람 발화가 지나치게 늦음", System.currentTimeMillis() - alarmAt < 45_000)
            }

            step("05_dismiss_and_routines") {
                ui.click(By.res("alarm_dismiss"))
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                find(By.text("아침 기록"))
                until("출발지의 실제 GPS 수신", 45_000) {
                    TripLiveState.pointFor(eventId)?.phase == TripGeofence.Phase.BEFORE_DEPARTURE
                }
                val blocks = config.getJSONArray("blocks")
                for (index in 0 until blocks.length()) {
                    val label = "\"${blocks.getString(index)}\" 마침"
                    find(By.text(label), "morning_scroll")
                    Thread.sleep(config.getLong("block_seconds") * 1_000)
                    ui.click(By.res("morning_action"), "morning_scroll")
                }
                ui.click(By.text("기록 끝내기"), "morning_scroll")
                find(By.res("home_list"))
            }

            step("06_depart_and_move") {
                openPlan()
                find(By.res("trip_status"), "plan_scroll")
                assertNull("출발 전에 도착 결과가 생김", TripLiveState.arrivalFor(eventId))
                control("/travel")
                until("실제 GPS 출발 판정", 100_000) {
                    TripLiveState.pointFor(eventId)?.phase == TripGeofence.Phase.IN_TRANSIT
                }
                find(By.res("trip_status").textStartsWith("이동 중"), "plan_scroll", 15_000)
            }

            step("07_destination_dwell") {
                until("목적지 반경 진입", 90_000) {
                    TripLiveState.pointFor(eventId)?.awaitingDwell == true
                }
                assertNull("2분 체류 전에 도착 완료됨", TripLiveState.arrivalFor(eventId))
                assertNotEquals("도착 완료", ui.text(By.res("trip_status"), "plan_scroll"))
            }

            step("08_arrival_after_service_stop") {
                until("실제 2분 체류 도착 판정", 180_000) {
                    TripLiveState.arrivalFor(eventId) != null
                }
                until("추적 서비스 종료", 20_000) {
                    TripLiveState.pointFor(eventId) == null &&
                        !Regex("ServiceRecord\\{[^\\n]*TripTrackingService").containsMatchIn(
                            device.executeShellCommand("dumpsys activity services ${context.packageName}"))
                }
                assertNull(TripLiveState.liveRouteFor(eventId))
                assertArrival(eventId)
                Thread.sleep(5_000)
                assertArrival(eventId) // 서비스 정리 후에도 준비 중으로 돌아가면 실패한다.
            }

            step("09_arrival_after_reentry") {
                val arrivedAt = TripLiveState.arrivalFor(eventId)!!.atMillis
                device.pressBack()
                openPlan()
                find(By.res("trip_status"), "plan_scroll")
                assertArrival(eventId)
                assertEquals(arrivedAt, TripLiveState.arrivalFor(eventId)!!.atMillis)
            }

            step("10_upload_and_weekly_report") {
                assertTrue(control("/verify").getBoolean("verified"))
                device.pressBack()
                ui.click(By.res("open_report"), "home_list")
                ui.click(By.res("report_next_week"))
                find(By.text("1 / 1건"), timeout = 45_000)
                assertTrue(device.hasObject(By.text("정시 도착 100%")))
            }
            writeResult("passed")
        } catch (error: Throwable) {
            capture("failure")
            writeResult("failed", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            instrumentation.runOnMainSync { locationManager.removeUpdates(gpsListener) }
        }
    }

    private fun launch() {
        device.wakeUp()
        context.startActivity(Intent(context, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.waitForIdle()
    }

    private fun openPlan() {
        ui.click(By.res("next_alarm"), "home_list")
        find(By.text("알람 결정"))
    }

    private fun assertArrival(eventId: Long) {
        find(By.res("trip_status").text("도착 완료"), "plan_scroll", 15_000)
        val arrival = TripLiveState.arrivalFor(eventId)!!
        val clock = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(arrival.atMillis))
        assertEquals("실제 도착 시각 UI", clock, ui.text(By.res("trip_arrival_time"), "plan_scroll"))
    }

    private fun find(selector: BySelector, scrollTag: String? = null, timeout: Long = 30_000) =
        ui.await(selector, scrollTag, timeout)

    private fun until(label: String, timeout: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(500)
        }
        throw AssertionError("시간 초과: $label")
    }

    private fun control(path: String): JSONObject {
        val connection = URL(config.getString("control_url") + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 90_000
        connection.setRequestProperty("X-QA-Token", config.getString("control_token"))
        try {
            val code = connection.responseCode
            val body = (if (code == 200) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            assertEquals("QA 제어 API $path: $body", 200, code)
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun step(name: String, action: () -> Unit) {
        Log.i("DemoFlowQA", "START $name")
        val start = System.currentTimeMillis()
        val row = JSONObject().put("step", name).put("started_at_ms", start)
        try {
            action()
            capture(name)
            row.put("status", "passed")
            Log.i("DemoFlowQA", "PASS $name")
        } catch (error: Throwable) {
            row.put("status", "failed").put("error", error.message)
            throw error
        } finally {
            row.put("duration_ms", System.currentTimeMillis() - start)
            steps.put(row)
            writeResult("running")
        }
    }

    private fun capture(name: String) {
        directory.mkdirs()
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }

    private fun writeResult(status: String, error: String? = null) {
        directory.mkdirs()
        File(directory, "steps.json").writeText(JSONObject().put("status", status)
            .put("duration_ms", System.currentTimeMillis() - started).put("steps", steps)
            .put("stale_retries", ui.staleRetries)
            .put("error", error).toString(2))
    }
}
