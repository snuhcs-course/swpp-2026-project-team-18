package com.swpp.wakeup

import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/** 화면 갱신 시 요소 조회부터 읽기·클릭까지 새 노드로 다시 수행한다. */
internal class DemoUi(private val device: UiDevice) {
    var staleRetries = 0
        private set

    fun await(selector: BySelector, scroll: String? = null, timeout: Long = 30_000) =
        withNode(selector, scroll, timeout) { }

    fun click(selector: BySelector, scroll: String? = null, timeout: Long = 30_000) {
        withNode(selector, scroll, timeout) { it.click() }
        device.waitForIdle()
    }

    fun input(tag: String, value: String, scroll: String? = null, timeout: Long = 30_000) {
        withNode(By.res(tag), scroll, timeout) { it.text = value }
        hideKeyboard()
    }

    fun text(selector: BySelector, scroll: String? = null): String? =
        withNode(selector, scroll) { it.text }

    fun <T> withNode(selector: BySelector, scroll: String? = null, timeout: Long = 30_000,
                     action: (UiObject2) -> T): T {
        val deadline = SystemClock.uptimeMillis() + timeout
        var swipes = 0
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                device.findObject(selector)?.let { node ->
                    val bounds = node.visibleBounds
                    val viewport = scroll?.let { device.findObject(By.res(it))?.visibleBounds }
                    if (bounds.height() > 0 && (viewport == null ||
                        (bounds.top >= viewport.top + 20 && bounds.bottom <= viewport.bottom - 20))) {
                        return action(node)
                    }
                }
                if (scroll != null) {
                    hideKeyboard()
                    device.findObject(By.res(scroll))?.let { container ->
                        val bounds = container.visibleBounds
                        val x = bounds.left + 12 // 지도 대신 부모 목록을 스크롤한다.
                        val top = bounds.top + bounds.height() / 4
                        val bottom = bounds.bottom - bounds.height() / 4
                        val up = swipes++ / 5 % 2 == 0
                        device.swipe(x, if (up) bottom else top, x, if (up) top else bottom, 80)
                        device.waitForIdle()
                    }
                }
            } catch (_: StaleObjectException) {
                staleRetries++
                Log.i("DemoFlowQA", "RETRY 화면 갱신: $selector")
            }
            Thread.sleep(300)
        }
        throw AssertionError("화면 요소를 찾지 못함: $selector")
    }

    private fun hideKeyboard() {
        // 실제 IME만 닫는다. Back을 무조건 누르면 화면이 닫힐 수 있다.
        repeat(3) { attempt ->
            try {
                device.findObject(By.res("android:id/input_method_nav_back"))?.click()
                return
            } catch (error: StaleObjectException) {
                if (attempt == 2) throw error
                device.waitForIdle()
            }
        }
    }
}
