package com.swpp.wakeup

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.swpp.wakeup.ui.auth.LoginActivity
import com.swpp.wakeup.alarm.ScheduledAlarmStore
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 로컬·공용 서버의 실제 가입·설정·알람 스위치 화면을 검사한다. */
@RunWith(AndroidJUnit4::class)
class PublicSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
                assertFalse("약관 미동의인데 가입 버튼이 켜짐", find(By.res("signup_submit"), "signup_scroll").isEnabled)
                click(By.res("signup_terms"), "signup_scroll")
                until("서버 비밀번호 검사", 90_000) { device.findObject(By.res("signup_submit"))?.isEnabled == true }
                click(By.res("signup_submit"), "signup_scroll")
                find(By.res("home_setup_scroll"), timeout = 150_000)
            }
            step("02_home_setup") {
                searchHomePlace(config.getString("home_query"))
                capture("02_home_address")
                click(By.res("home_setup_submit"), "home_setup_scroll")
                find(By.res("prep_minutes"), timeout = 90_000)
            }
            step("03_prep_setup") {
                input("prep_minutes", config.getInt("prep_minutes").toString())
                click(By.res("prep_submit"))
                find(By.res("home_list"), timeout = 90_000)
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
                click(By.res("event_time"))
                pickTime(config.getString("event_time"))
                click(By.text("확인"))
                click(By.res("place_query"), "add_event_scroll")
                input("place_query", config.getString("destination_query"))
                click(By.res("place_search"), "add_event_scroll")
                val destination = config.getString("destination_query")
                click(By.res("place_result_$destination"), "add_event_scroll", 90_000)
                find(By.text(destination).clazz("android.widget.TextView"), "add_event_scroll")
                click(By.textContains("경로 고르기"), "add_event_scroll")
                click(By.text("도보"), "route_scroll", 90_000)
                click(By.res("route_${config.getString("route_key")}"), "route_scroll", 90_000)
                click(By.res("route_confirm"), "route_scroll")
                click(By.res("event_submit"), "add_event_scroll")
                find(By.res("next_alarm"), timeout = 90_000)
            }
            step("06_plan_and_map") {
                click(By.res("next_alarm"), "home_list")
                find(By.textContains(config.getString("event_title")), "plan_scroll")
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
                assertTrue(find(toggle, "home_list").isChecked)
                click(toggle, "home_list")
                until("OFF 기기 예약 해제") { store.find(eventId) == null }
                launch()
                assertFalse("OFF가 재진입 후 유지되지 않음", find(toggle, "home_list").isChecked)
                click(toggle, "home_list")
                until("ON 기기 예약 복원") { store.find(eventId) != null }
                assertTrue(find(toggle, "home_list").isChecked)
            }
            writeResult("passed")
        } catch (error: Throwable) {
            capture("failure")
            writeResult("failed", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun pickTime(time: String) {
        val (hour, minute) = time.split(":").map(String::toInt)
        click(By.text(Pattern.compile(if (hour >= 12) "PM|오후" else "AM|오전")))
        val clockHour = if (hour % 12 == 0) 12 else hour % 12
        click(By.desc(Pattern.compile("$clockHour (hours?|o'clock)|${clockHour}시")))
        capture("05_time_picker")
        val zero = By.desc(Pattern.compile("0 minutes?|0분"))
        val thirty = By.desc(Pattern.compile("30 minutes?|30분"))
        find(zero)
        find(thirty)
        // 상단 분 표시도 같은 설명을 쓴다. 아래쪽 다이얼의 숫자를 기준으로 잡는다.
        val top = device.findObjects(zero).maxBy { it.visibleCenter.y }.visibleCenter
        val bottom = device.findObjects(thirty).maxBy { it.visibleCenter.y }.visibleCenter
        val x = (top.x + bottom.x) / 2.0
        val y = (top.y + bottom.y) / 2.0
        val radius = (bottom.y - top.y) / 2.0
        val angle = Math.PI * 2 * minute / 60
        device.click((x + radius * kotlin.math.sin(angle)).toInt(),
            (y - radius * kotlin.math.cos(angle)).toInt())
    }

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
        freshNode { find(By.res(tag), scroll, timeout).text = value }
        hideKeyboard()
    }

    private fun click(selector: BySelector, scroll: String? = null, timeout: Long = 30_000) {
        freshNode {
            device.waitForIdle()
            find(selector, scroll, timeout)
            device.waitForIdle()
            find(selector, scroll, timeout).click()
        }
    }

    private fun freshNode(action: () -> Unit) {
        repeat(3) { attempt ->
            try {
                action()
                return
            } catch (error: StaleObjectException) {
                if (attempt == 2) throw error
                device.waitForIdle()
            }
        }
    }

    private fun searchHomePlace(query: String) {
        val scroll = "home_setup_scroll"
        input("place_query", query, scroll)
        click(By.res("place_search"), scroll)
        val row = By.res("place_result_$query")
        repeat(3) {
            click(row, scroll, 90_000)
            device.waitForIdle()
            if (device.findObject(row)?.findObject(By.text("✓")) != null) return
        }
        throw AssertionError("장소 선택 표시가 없음: $query")
    }

    private fun find(selector: BySelector, scroll: String? = null, timeout: Long = 30_000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeout
        var swipes = 0
        while (System.currentTimeMillis() < deadline) {
            device.findObject(selector)?.let {
                val bounds = it.visibleBounds
                val viewport = scroll?.let { tag -> device.findObject(By.res(tag))?.visibleBounds }
                if (bounds.height() > 0 && (viewport == null ||
                    (bounds.top >= viewport.top + 20 && bounds.bottom <= viewport.bottom - 20))) return it
            }
            if (scroll != null) {
                hideKeyboard()
                try {
                    val container = device.findObject(By.res(scroll))
                    if (container != null) {
                        // 가운데 지도 대신 화면 가장자리에서 부모 목록을 스크롤한다.
                        val bounds = container.visibleBounds
                        val x = bounds.left + 12
                        val top = bounds.top + bounds.height() / 4
                        val bottom = bounds.bottom - bounds.height() / 4
                        val up = swipes++ / 5 % 2 == 0
                        device.swipe(x, if (up) bottom else top, x, if (up) top else bottom, 80)
                        device.waitForIdle()
                    }
                } catch (_: StaleObjectException) {
                    // 서버 응답으로 목록이 바뀌면 다음 반복에서 새 컨테이너를 찾는다.
                }
            }
            Thread.sleep(300)
        }
        throw AssertionError("화면 요소를 찾지 못함: $selector")
    }

    private fun hideKeyboard() {
        // Back을 무조건 누르면 화면 자체가 닫힐 수 있다. 실제 IME가 있을 때만 닫는다.
        freshNode { device.findObject(By.res("android:id/input_method_nav_back"))?.click() }
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
            .put("error", error).toString(2))
    }
}
