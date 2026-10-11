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
import org.junit.Test
import org.junit.runner.RunWith

/** BE-9·10: 재생 경로를 제공하는 로컬 QA 서버에서 실제 경로 카드를 확인한다. */
@RunWith(AndroidJUnit4::class)
class RouteDisplayTest {
    @Test
    fun transitBadgesAndAlternativeBuses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.swpp.wakeup.qa", context.packageName)
        assertEquals("http://10.0.2.2:8765/", BuildConfig.BASE_URL)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ui = DemoUi(device)
        val directory = File(context.getExternalFilesDir(null), "demo-qa").apply { mkdirs() }
        val query = "QA 대중교통 목적지"
        val started = System.currentTimeMillis()
        try {
            context.startActivity(Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            ui.await(By.res("next_alarm"), timeout = 90_000)
            ui.click(By.text("+  일정 추가"), "home_list")
            ui.input("event_title", "경로 표시 검증", "add_event_scroll")
            ui.click(By.res("place_query"), "add_event_scroll")
            ui.input("place_query", query, "add_event_scroll")
            ui.click(By.res("place_search"), "add_event_scroll")
            ui.click(By.res("place_result_$query"), "add_event_scroll")
            ui.click(By.textContains("경로 고르기"), "add_event_scroll")
            ui.click(By.text("대중교통"), "route_scroll")
            ui.click(By.res("route_transit:5511"), "route_scroll")
            for (badge in listOf("최단 시간", "최소 비용", "최소 환승")) {
                ui.await(By.text(badge), "route_scroll")
            }
            for (bus in listOf("5513", "643")) {
                ui.await(By.text(bus), "route_scroll")
            }
            device.takeScreenshot(File(directory, "route-display.png"))
            device.dumpWindowHierarchy(File(directory, "route-display.xml"))
            File(directory, "route-display-result.json").writeText(JSONObject()
                .put("status", "passed").put("badges", "최단 시간 · 최소 비용 · 최소 환승")
                .put("alternative_buses", "5513 · 643")
                .put("duration_ms", System.currentTimeMillis() - started).toString(2))
        } catch (error: Throwable) {
            device.takeScreenshot(File(directory, "route-display-failure.png"))
            device.dumpWindowHierarchy(File(directory, "route-display-failure.xml"))
            throw error
        } finally {
            // 일정은 저장하지 않는다. 원래 E2E의 단일 일정·관측 검증을 이어 쓴다.
            context.startActivity(Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
