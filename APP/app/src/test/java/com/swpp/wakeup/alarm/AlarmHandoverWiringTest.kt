// AI-generated (Claude)
package com.swpp.wakeup.alarm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 알람 화면이 다른 알람으로 넘어갈 때 소리와 자동 멈춤 타이머를 새로 거는지 소스에서 고정한다(STAT-7).
 *
 * 넘길지는 [AlarmHandover] 가 값으로 정하고(`AlarmHandoverTest`), 화면이 바뀌는 것은 기기 테스트
 * `AlarmActivityTest` 가 본다. 그런데 자동 멈춤은 5분 상수라 기기 테스트가 기다릴 수 없고, 소리 확인은 소리가 없는
 * CI 에뮬레이터에서 건너뛴다. 그래서 그 두 줄이 있는지와 순서를 여기서 본다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 넘길 때만 소리를 다시 켜고 타이머를 다시 건다. 같은 알람이 다시 들어온 것(알림을 누름 등)에 다시 켜면 이미 멈춘
 *   알람이 또 울린다. 그래서 둘 다 넘길지 판단([AlarmHandover.of])이 돌려보낸 뒤에 있어야 한다.
 * - 앞 알람의 타이머를 지운 **뒤에** 새로 건다. 지우지 않으면 앞 알람이 울린 지 5분에 새 알람의 소리가 끊기고
 *   `silenced` 가 한 번 더 적힌다. 걸지 않으면 새 알람은 끝없이 울린다.
 */
class AlarmHandoverWiringTest {

    private val body by lazy { functionBody(readSource("alarm/AlarmActivity.kt"), "override fun onNewIntent(") }

    @Test
    fun `다른 알람으로 넘길 때만 소리를 다시 켠다`() {
        // Given onNewIntent 의 본문
        val decide = body.indexOf("AlarmHandover.of(")
        val ring = body.indexOf("AlarmRinger.start(this)")

        // Then 넘길지 판단한 뒤에 소리를 켠다
        assertTrue("넘길지 판단(AlarmHandover.of)이 없다", decide >= 0)
        assertTrue("넘긴 알람의 소리를 다시 켜지 않는다", ring >= 0)
        assertTrue("판단보다 먼저 켜면 같은 알람이 다시 들어와도 울린다", decide < ring)
    }

    @Test
    fun `넘길 때 앞 알람의 자동 멈춤을 지우고 새로 건다`() {
        // Given onNewIntent 의 본문
        val decide = body.indexOf("AlarmHandover.of(")
        val clear = body.indexOf("silenceHandler.removeCallbacks(silenceRunnable)")
        val arm = body.indexOf("silenceHandler.postDelayed(silenceRunnable")

        // Then 판단 뒤에, 지운 다음 건다
        assertTrue("앞 알람의 타이머를 지우지 않는다", clear >= 0)
        assertTrue("새 알람의 타이머를 걸지 않는다", arm >= 0)
        assertTrue("판단보다 먼저 지우거나 걸면 같은 알람에도 타이머가 다시 시작된다", decide in 0 until clear)
        assertTrue("건 다음에 지우면 새 타이머까지 지운다", clear < arm)
    }

    /** [signature] 로 시작하는 함수의 `{ … }` 본문. 괄호 짝을 센다 */
    private fun functionBody(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("$signature 를 찾지 못했다", at >= 0)
        val open = source.indexOf('{', at)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("$signature 의 본문이 닫히지 않는다")
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        return candidates.firstOrNull(File::exists)?.readText()?.replace("\r\n", "\n")
            ?: error("$relative 을 찾지 못했다: ${candidates.joinToString { it.absolutePath }}")
    }
}
