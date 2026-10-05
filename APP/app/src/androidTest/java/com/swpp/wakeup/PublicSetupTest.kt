package com.swpp.wakeup

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 공용 서버 준비 화면을 검사한다. run.py --public-ui 또는 --public-e2e. */
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
        assertEquals("https://justintime-api.onrender.com/", BuildConfig.BASE_URL)
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
                click(By.res("signup_submit"), "signup_scroll")
                find(By.res("home_setup_scroll"), timeout = 150_000)
            }
            step("02_home_setup") {
                searchPlace(config.getString("home_query"), "home_setup_scroll")
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
                input("event_date_input", config.getString("event_date"))
                click(By.res("event_time")) // 포커스를 옮겨 날짜 입력을 확정한다.
                input("event_time_input", config.getString("event_time"))
                // 장소 입력으로 포커스를 옮기면 시각도 확정된다.
                click(By.res("place_query"), "add_event_scroll")
                input("place_query", config.getString("destination_query"))
                click(By.res("place_search"), "add_event_scroll")
                click(By.text(config.getString("destination_query")).clazz("android.widget.TextView"),
                    "add_event_scroll", 90_000)
                click(By.textContains("경로 고르기"), "add_event_scroll")
                click(By.text("집에서 출발"), "route_scroll", 90_000)
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
            writeResult("passed")
        } catch (error: Throwable) {
            capture("failure")
            writeResult("failed", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
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

    private fun searchPlace(query: String, scroll: String) {
        input("place_query", query, scroll)
        click(By.res("place_search"), scroll)
        click(By.text(query).clazz("android.widget.TextView"), scroll, 90_000)
    }

    private fun find(selector: BySelector, scroll: String? = null, timeout: Long = 30_000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeout
        var direction = Direction.DOWN
        while (System.currentTimeMillis() < deadline) {
            device.findObject(selector)?.let { return it }
            if (scroll != null) {
                hideKeyboard()
                try {
                    val container = device.findObject(By.res(scroll))
                    if (container != null && !container.scroll(direction, .55f)) {
                        direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
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
