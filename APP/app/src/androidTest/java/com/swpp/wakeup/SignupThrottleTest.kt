package com.swpp.wakeup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real signup UI after exhausting only the disposable server's normal IP quota. */
@RunWith(AndroidJUnit4::class)
class SignupThrottleTest {
    @Test
    fun limitedSignupShowsServerMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.swpp.wakeup.qa", context.packageName)
        assertEquals("http://10.0.2.2:8765/", BuildConfig.BASE_URL)
        val config = JSONObject(File(context.filesDir, "demo-qa-config.json").readText())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ui = DemoUi(device)
        val directory = File(context.getExternalFilesDir(null), "demo-qa").apply { mkdirs() }
        try {
            context.startActivity(Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            ui.click(By.res("open_settings"), "home_list")
            ui.click(By.text("로그아웃"), "settings_scroll")
            ui.click(By.text("회원가입"), "login_scroll")
            ui.input("signup_nickname", "QA 가입 제한", "signup_scroll")
            ui.input("signup_email", config.getString("throttle_email"), "signup_scroll")
            ui.input("signup_password", config.getString("password"), "signup_scroll")
            ui.input("signup_password_confirm", config.getString("password"), "signup_scroll")
            ui.click(By.res("signup_terms"), "signup_scroll")
            ui.await(By.res("signup_submit").enabled(true), "signup_scroll", 90_000)
            val expected = demoControl(config, "/register_limit").getJSONObject("error")
                .getString("message")
            ui.click(By.res("signup_submit"), "signup_scroll")
            // DRF's wait seconds can tick between the preparation request and UI submission.
            val message = expected.substringBefore(".")
            ui.await(By.textContains(message), "signup_scroll", 90_000)
            assertFalse(device.hasObject(By.res("home_setup_scroll")))
            device.takeScreenshot(File(directory, "feature-signup-throttled.png"))
            device.dumpWindowHierarchy(File(directory, "feature-signup-throttled.xml"))
            File(directory, "signup-throttle-result.json").writeText(JSONObject()
                .put("status", "passed").put("message", ui.text(By.textContains(message)))
                .toString(2))
        } catch (error: Throwable) {
            device.takeScreenshot(File(directory, "signup-throttle-failure.png"))
            device.dumpWindowHierarchy(File(directory, "signup-throttle-failure.xml"))
            throw error
        }
    }
}
