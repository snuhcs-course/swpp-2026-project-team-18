package com.swpp.wakeup

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.swpp.wakeup.ui.auth.LoginActivity
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.PlaceInput
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 로컬·공용 서버의 실제 가입·설정·알람 스위치 화면을 검사한다. */
@RunWith(AndroidJUnit4::class)
class PublicSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ui = DemoUi(device)
    private val directory = File(context.getExternalFilesDir(null), "demo-qa")
    private val steps = JSONArray()
    private lateinit var config: JSONObject
    private var started = 0L

    @Test
    fun setupViaUi() {
        assertEquals("com.swpp.wakeup.qa", context.packageName)
        assertTrue(BuildConfig.BASE_URL in setOf("https://justintime-api.onrender.com/", "http://10.0.2.2:8765/"))
        config = JSONObject(File(context.filesDir, "demo-qa-config.json").readText())
        started = System.currentTimeMillis()
        try {
            step("01_signup") {
                launch()
                click(By.text("회원가입"), "login_scroll")
                input("signup_nickname", config.getString("nickname"), "signup_scroll")
                input("signup_email", config.getString("email"), "signup_scroll")
                input("signup_password", config.getString("password"), "signup_scroll")
                input("signup_password_confirm", config.getString("password"), "signup_scroll")
                assertFalse("약관 미동의인데 가입 버튼이 켜짐", ui.withNode(By.res("signup_submit"), "signup_scroll") { it.isEnabled })
                click(By.res("signup_terms"), "signup_scroll")
                until("서버 비밀번호 검사", 90_000) { ui.withNode(By.res("signup_submit"), "signup_scroll") { it.isEnabled } }
                click(By.res("signup_submit"), "signup_scroll")
                find(By.res("home_setup_scroll"), timeout = 150_000)
            }
            step("02_home_setup") {
                searchHomePlace(config.getString("home_query"))
                capture("02_home_address")
                click(By.res("home_setup_submit"), "home_setup_scroll")
                find(By.res("prep_value"), timeout = 90_000)
            }
            // Partly AI-generated (Claude)
            step("03_prep_setup") {
                // 준비 시간은 시계 다이얼(24시간제, 시:분 = 걸리는 시간)로만 고른다(Phase A, A4).
                val prep = config.getInt("prep_minutes")
                val changedPrep = if (prep == 15) 30 else 15
                assertPrepValue("—") // 값을 미리 채우지 않는다.
                pickDuration(prep)
                click(By.res("prep_submit"))
                find(By.res("home_list"), timeout = 90_000)
                click(By.res("open_settings"), "home_list")
                find(By.text(durationText(prep)), "settings_scroll")
                click(By.text("평소 준비 시간"), "settings_scroll")
                assertPrepValue(durationText(prep))
                pickDuration(changedPrep)
                click(By.res("prep_submit"))
                find(By.res("settings_scroll"), timeout = 90_000)
                find(By.text(durationText(changedPrep)), "settings_scroll", 90_000)
                click(By.text("평소 준비 시간"), "settings_scroll")
                assertPrepValue(durationText(changedPrep))
                // 설정에서 연 화면에는 "나중에 입력" 이 없다. 뒤로 = 저장하지 않고 닫기.
                assertFalse("설정에서 연 화면에 나중에 입력이 있음", device.hasObject(By.text("나중에 입력")))
                pickDuration(prep)
                device.pressBack()
                find(By.res("settings_scroll"))
                find(By.text(durationText(changedPrep)), "settings_scroll")
                click(By.text("평소 준비 시간"), "settings_scroll")
                assertPrepValue(durationText(changedPrep))
                pickDuration(prep)
                click(By.res("prep_submit"))
                find(By.res("settings_scroll"), timeout = 90_000)
                find(By.text(durationText(prep)), "settings_scroll", 90_000)
                capture("03_prep_settings")
                device.pressBack()
            }
            step("04_create_routines") {
                click(By.text("아침 루틴 설정"), "home_list")
                val blocks = config.getJSONArray("blocks")
                for (i in 0 until blocks.length()) {
                    click(By.res("routine_new"), "routine_scroll")
                    input("block_name", blocks.getString(i), "block_draft_scroll")
                    input("block_min", config.getInt("block_min_minutes").toString())
                    input("block_max", config.getInt("block_max_minutes").toString())
                    click(By.res("block_save"), "block_draft_scroll")
                    find(By.text(blocks.getString(i)).clazz("android.widget.TextView"), "routine_scroll", timeout = 90_000)
                }
                device.pressBack()
            }
            step("05_add_event_and_route") {
                click(By.text("+  일정 추가"), "home_list")
                input("event_title", config.getString("event_title"))
                click(By.res("event_date"))
                val date = LocalDate.parse(config.getString("event_date"))
                if (YearMonth.from(date) != YearMonth.now()) click(By.desc(Pattern.compile("Change to next month|다음 달.*")))
                click(By.textContains(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))))
                click(By.text("확인"))
                // 기본 9시와 같은 시각·오전/오후·경계 분도 같은 다이얼로 확인한다.
                for (time in listOf("21:02", "09:00", "12:30", "00:59", config.getString("event_time"))) {
                    click(By.res("event_time"))
                    pickTime(time)
                    click(By.text("확인"))
                    val (hour, minute) = time.split(":").map(String::toInt)
                    val display = "%02d:%02d".format(Locale.ROOT, if (hour % 12 == 0) 12 else hour % 12, minute)
                    assertTrue("시각 선택 실패: $time", ui.withNode(By.res("event_time")) { it.findObject(By.text(display)) != null })
                }
                click(By.res("place_query"), "add_event_scroll")
                input("place_query", config.getString("destination_query"))
                click(By.res("place_search"), "add_event_scroll")
                val destination = config.getString("destination_query")
                click(By.res("place_result_$destination"), "add_event_scroll", 90_000)
                find(By.text(destination).clazz("android.widget.TextView"), "add_event_scroll")
                click(By.textContains("경로 고르기"), "add_event_scroll")
                click(By.text("도보"), "route_scroll", 90_000)
                click(By.res("route_${config.getString("route_key")}"), "route_scroll", 90_000)
                // 좌표가 있는 도보 카드는 누르면 전체 화면 지도 미리보기가 열린다(Phase A, A1).
                // 좌표가 없는 응답이면 목록에서 고르기만 된다.
                val previewed = device.wait(Until.hasObject(By.res("route_preview_confirm")), 15_000) == true
                if (config.optBoolean("replay_routing")) {
                    assertTrue("재생 도보 경로에 지도 미리보기가 열리지 않음", previewed)
                    find(By.res("candidate_route_map"))
                    find(By.desc("경로 지도"), timeout = 90_000)
                    find(By.textContains("걸음"))
                    find(By.textContains("거리 기반 추정"))
                    capture("05_walk_candidate")
                }
                if (previewed) click(By.res("route_preview_confirm")) else click(By.res("route_confirm"), "route_scroll")
                if (config.optBoolean("replay_routing")) {
                    click(By.text("수업"), "add_event_scroll")
                    click(By.text("시험"))
                }
                click(By.res("event_submit"), "add_event_scroll")
                find(By.res("next_alarm"), timeout = 90_000)
            }
            step("06_plan_and_map") {
                click(By.res("next_alarm"), "home_list")
                find(By.res("plan_scroll"), timeout = 90_000)
                find(By.textContains(config.getString("event_title")), timeout = 90_000)
                capture("06_plan_header")
                if (config.optBoolean("replay_routing")) find(By.text("지각 민감도 높음"))
                val blocks = config.getJSONArray("blocks")
                for (i in 0 until blocks.length()) find(By.text(blocks.getString(i)), "plan_scroll", 90_000)
                capture("06_prep_breakdown")
                find(By.res("trip_status"), "plan_scroll")
                find(By.desc("경로 지도"), "plan_scroll", 90_000)
            }
            step("07_relogin_and_persistence") {
                launch()
                find(By.res("next_alarm"), timeout = 90_000) // 토큰으로 자동 로그인.
                click(By.res("open_settings"), "home_list")
                find(By.text(config.getString("home_query")), "settings_scroll")
                find(By.text(durationText(config.getInt("prep_minutes"))), "settings_scroll")
                click(By.text("로그아웃"), "settings_scroll")
                input("login_email", config.getString("email"), timeout = 90_000)
                input("login_password", config.getString("password"))
                click(By.res("login_submit"), "login_scroll")
                find(By.res("next_alarm"), timeout = 90_000)
                click(By.text("아침 루틴 설정"), "home_list")
                val blocks = config.getJSONArray("blocks")
                for (i in 0 until blocks.length())
                    find(By.text(blocks.getString(i)).clazz("android.widget.TextView"), "routine_scroll")
                device.pressBack()
                find(By.res("next_alarm"), "home_list")
            }
            step("08_alarm_switch") {
                val store = ScheduledAlarmStore(context)
                val eventId = store.all().single().eventId
                val toggle = By.res("event_alarm_$eventId")
                assertTrue(ui.withNode(toggle, "home_list") { it.isChecked })
                click(toggle, "home_list")
                until("OFF 기기 예약 해제") { store.find(eventId) == null }
                launch()
                assertFalse("OFF가 재진입 후 유지되지 않음", ui.withNode(toggle, "home_list") { it.isChecked })
                click(toggle, "home_list")
                until("ON 기기 예약 복원") { store.find(eventId) != null }
                assertTrue(ui.withNode(toggle, "home_list") { it.isChecked })
            }
            if (config.optBoolean("replay_routing")) step("09_new_event_resets_alarm_choice") {
                val store = ScheduledAlarmStore(context)
                val eventId = store.all().single().eventId
                val api = ApiClient.events
                val original = runBlocking { requireNotNull(api.get(eventId).body()) }
                val place = requireNotNull(original.place)
                val toggle = By.res("event_alarm_$eventId")
                click(toggle, "home_list")
                until("초기화 검증 전 OFF 예약 해제") { store.find(eventId) == null }

                // UI와 같은 생성 API를 호출한 뒤 실제 화면·기기 예약을 함께 확인한다.
                val response = runBlocking { api.create(EventCreateRequest(
                    title = "QA 초기화 검증", startAt = original.startAt,
                    place = PlaceInput(place.name, place.lat, place.lng, place.address, place.kakaoPlaceId),
                    tagKey = original.tag?.key, routeKey = original.routeKey,
                    originLat = original.originLat, originLng = original.originLng,
                    originLabel = original.originLabel,
                )) }
                assertEquals(201, response.code())
                val added = requireNotNull(response.body())
                try {
                    val reset = runBlocking { requireNotNull(api.get(eventId).body()) }
                    assertNull(reset.alarmEnabled)
                    launch()
                    assertTrue(ui.withNode(toggle, "home_list") { it.isChecked })
                    assertFalse(ui.withNode(By.res("event_alarm_${added.id}"), "home_list") { it.isChecked })
                    until("자동 ON 기기 예약 복원") { store.find(eventId) != null }
                    assertNull(store.find(added.id))
                } finally {
                    assertEquals(204, runBlocking { api.delete(added.id).code() })
                    launch()
                }
            }
            writeResult("passed")
        } catch (error: Throwable) {
            capture("failure")
            writeResult("failed", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun pickTime(time: String) = ui.pickTime(time) { capture("05_time_picker") }

    // AI-generated (Claude)
    /**
     * 준비 시간 다이얼(24시간제, 시:분 = 걸리는 시간)에서 [minutes] 를 고르고 확인한다.
     * 오전/오후 버튼이 없고 시 설명은 0~23 이다("0 hours" / "0시").
     */
    private fun pickDuration(minutes: Int) {
        click(By.res("prep_value"))
        val hour = minutes / 60
        click(By.desc(Pattern.compile("$hour (hours?|o'clock)|${hour}시(간)?")))
        dragMinute(minutes % 60, "03_duration_picker")
        find(By.text("총 ${durationText(minutes)}"))
        click(By.text("확인"))
    }

    // AI-generated (Claude)
    /** 준비 시간 칸의 표시값. 값이 없으면 "—" 다. */
    private fun assertPrepValue(display: String) {
        assertTrue("준비 시간 표시가 $display 가 아님",
            ui.withNode(By.res("prep_value")) { it.findObject(By.text(display)) != null })
    }

    // AI-generated (Claude)
    /** 앱의 준비 시간 표기와 같다. 35 → "35분", 120 → "2시간", 65 → "1시간 5분" */
    private fun durationText(minutes: Int): String = when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    /** 분 다이얼에서 [minute] 를 1분 단위로 고른다. 시는 이미 고른 상태다. */
    private fun dragMinute(minute: Int, shot: String) = ui.dragMinute(minute) { capture(shot) }

    private fun until(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(300)
        }
        throw AssertionError("시간 초과: $label")
    }

    private fun launch() {
        device.wakeUp()
        context.startActivity(Intent(context, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.waitForIdle()
    }

    private fun input(tag: String, value: String, scroll: String? = null, timeout: Long = 30_000) {
        ui.input(tag, value, scroll, timeout)
    }

    private fun click(selector: BySelector, scroll: String? = null, timeout: Long = 30_000) {
        ui.click(selector, scroll, timeout)
    }

    private fun searchHomePlace(query: String) {
        val scroll = "home_setup_scroll"
        input("place_query", query, scroll)
        click(By.res("place_search"), scroll)
        val row = By.res("place_result_$query")
        repeat(3) {
            click(row, scroll, 90_000)
            device.waitForIdle()
            if (ui.withNode(row, scroll) { it.findObject(By.text("✓")) != null }) return
        }
        throw AssertionError("장소 선택 표시가 없음: $query")
    }

    private fun find(selector: BySelector, scroll: String? = null, timeout: Long = 30_000) =
        ui.await(selector, scroll, timeout)

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
