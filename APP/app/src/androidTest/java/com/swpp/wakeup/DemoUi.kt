package com.swpp.wakeup

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern

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
                    // 홈 목록 위에 떠 있는 [+ 일정 추가](iter4 H2-2)에 가린 노드를 누르면 그 버튼이 눌린다.
                    // 가려졌으면 보이는 자리로 더 스크롤한다.
                    val covered = scroll != null && device.findObject(By.res(FLOATING_BUTTON))
                        ?.visibleBounds?.let { Rect.intersects(it, bounds) } == true
                    if (bounds.height() > 0 && !covered && (viewport == null ||
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

    // Partly AI-generated (Claude)
    /**
     * 시각 휠 시트(iter4 H2-5)에서 [time]("21:02")을 고른다. [확인] 은 부르는 쪽이 누른다.
     * 열마다 지금 값(열 설명 "시 9" 의 끝 낱말)에서 가까운 쪽으로, 흐린 위아래 줄을 눌러 한두 칸씩 옮긴다.
     * 끌어서 굴리면 손 떼는 속도에 따라 몇 칸 더 굴러가므로 누르기로 고른다.
     */
    fun pickTime(time: String, onDialReady: () -> Unit = {}) {
        val (hour, minute) = time.split(":").map(String::toInt)
        await(By.res("wheel_hour"))
        onDialReady()
        wheelTo("wheel_ampm", if (hour >= 12) "오후" else "오전", listOf("오전", "오후"), loop = false)
        wheelTo("wheel_hour", (if (hour % 12 == 0) 12 else hour % 12).toString(), (1..12).map(Int::toString), loop = true)
        wheelTo("wheel_minute", "%02d".format(minute), (0..59).map { "%02d".format(it) }, loop = true)
    }

    private fun wheelTo(column: String, target: String, values: List<String>, loop: Boolean) {
        repeat(40) {
            val current = withNode(By.res(column)) { it.contentDescription.orEmpty().substringAfterLast(' ') }
            if (current == target) return
            val from = values.indexOf(current)
            var delta = values.indexOf(target) - from
            if (loop && delta > values.size / 2) delta -= values.size
            if (loop && delta < -values.size / 2) delta += values.size
            val next = values[(from + delta.coerceIn(-2, 2)).mod(values.size)]
            withNode(By.res(column)) { col -> col.findObject(By.text(next)).click() }
            device.waitForIdle()
            Thread.sleep(250) // 굴러가는 애니메이션이 끝나 설명이 바뀔 때까지
        }
        throw AssertionError("휠 $column 을 $target 로 옮기지 못함")
    }

    fun dragMinute(minute: Int, onDialReady: () -> Unit = {}) {
        // 현재 시각과 같으면 상단 시 표시를 누를 수 있으므로 분 선택을 명시한다.
        click(By.desc(Pattern.compile("Select minutes|분 선택")))
        onDialReady()
        val zero = By.desc(Pattern.compile("0 minutes?|0분"))
        val thirty = By.desc(Pattern.compile("30 minutes?|30분"))
        await(zero)
        await(thirty)
        // 상단 분 표시도 같은 설명을 쓴다. 아래쪽 다이얼의 숫자를 기준으로 잡는다.
        val top = withNode(zero) { device.findObjects(zero).maxBy { it.visibleCenter.y }.visibleCenter }
        val bottom = withNode(thirty) { device.findObjects(thirty).maxBy { it.visibleCenter.y }.visibleCenter }
        val x = (top.x + bottom.x) / 2.0
        val y = (top.y + bottom.y) / 2.0
        val radius = (bottom.y - top.y) / 2.0
        val angle = Math.PI * 2 * minute / 60
        // 탭은 5분 단위로 반올림된다. 드래그는 1분 단위로 선택한다.
        device.swipe(x.toInt(), y.toInt(), (x + radius * kotlin.math.sin(angle)).toInt(),
            (y - radius * kotlin.math.cos(angle)).toInt(), 30)
        device.waitForIdle()
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

/** 목록 위에 떠 있어 아래 노드를 가리는 버튼(홈 [+ 일정 추가]) */
private const val FLOATING_BUTTON = "add_event"
