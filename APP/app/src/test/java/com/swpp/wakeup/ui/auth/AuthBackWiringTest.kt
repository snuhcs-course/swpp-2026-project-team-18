// AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 인증 화면의 시스템 뒤로 연결을 소스에서 고정한다(HE 2-7, K1·K2).
 *
 * `AuthViewModel` 은 Application 이 필요해 JVM 에서 만들 수 없고, 서버를 기다리는 화면은 기기에서도 서버를 불러야
 * 열린다. 그래서 무엇을 켜고 어디로 가는지는 [AuthSystemBackTest] 가 값으로 보고, 그 값이 화면에 이어졌는지는 여기서
 * 본다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - `AuthHost` 의 BackHandler 는 기다리는 중에도 켜져 있는 값(`handlesSystemBack`)으로 켠다. 움직일 수 있을 때만
 *   켜는 값(`canGoBack`)으로 바꾸면 기다리는 중의 뒤로가 시스템으로 빠져 앱이 닫히고 보낸 코드를 잃던 K2 가
 *   돌아온다. 그 화면은 기기 테스트로 열 수 없다.
 * - 시스템 뒤로는 화면마다 그 화면의 ‹ 와 **같은 함수**를 부른다. 같은 곳으로 가는 다른 함수를 부르면 ‹ 쪽만
 *   고쳤을 때 둘이 어긋난다.
 */
class AuthBackWiringTest {

    private val host by lazy { readSource("ui/auth/LoginActivity.kt") }
    private val viewModel by lazy { readSource("ui/auth/AuthViewModel.kt") }

    @Test
    fun `BackHandler 는 기다리는 중에도 켜져 있는 값으로 켠다`() {
        // Given AuthHost 의 BackHandler 호출들(import 줄 제외)
        val calls = Regex("""BackHandler\(([^)]*)\)""").findAll(host)
            .map { it.groupValues[1].trim() }
            .toList()

        // Then 하나뿐이고, handlesSystemBack 으로 켠다
        assertEquals("AuthHost 의 BackHandler 는 하나여야 한다: $calls", 1, calls.size)
        assertEquals("enabled = state.handlesSystemBack", calls.single())
        assertFalse("canGoBack 으로 켜면 기다리는 중의 뒤로가 앱을 닫는다", calls.single().contains("canGoBack"))
    }

    @Test
    fun `시스템 뒤로는 움직일 수 있을 때만 화면의 함수를 부른다`() {
        // Given onSystemBack 의 본문
        val body = functionBody(viewModel, "fun onSystemBack()")

        // Then 첫 줄에서 기다리는 중(STAY)과 로그인(SYSTEM)을 거른다
        val gate = body.indexOf("canGoBack")
        val firstCall = Regex("""->\s*\w+\(\)""").find(body)?.range?.first ?: -1
        assertTrue("canGoBack 으로 거르지 않는다", gate >= 0)
        assertTrue("화면을 옮긴 뒤에 거르면 늦다", firstCall < 0 || gate < firstCall)
    }

    @Test
    fun `시스템 뒤로는 화면마다 그 화면의 ‹ 와 같은 함수를 부른다`() {
        // Given 화면마다 ‹ 에 이어진 함수(AuthHost)와 시스템 뒤로가 부르는 함수(onSystemBack)
        val screens = listOf("SIGNUP", "SOCIAL_TERMS", "RESET_REQUEST", "RESET_CONFIRM")
        val onScreen = screens.associateWith { backHandlerOf(it) }
        val onSystem = systemBackArms()

        // Then 둘이 같다
        screens.forEach { screen ->
            assertEquals("$screen: ‹ 와 시스템 뒤로가 다른 함수를 부른다", onScreen[screen], onSystem[screen])
        }
    }

    /** `AuthHost` 의 `when (state.screen)` 에서 [screen] 갈래가 ‹ 에 넘기는 `viewModel::함수` 이름 */
    private fun backHandlerOf(screen: String): String {
        val header = Regex("""\n\s*AuthViewModel\.Screen\.$screen\s*->""").find(host)
            ?: error("AuthHost 에 $screen 갈래가 없다")
        val start = header.range.last
        val next = Regex("""\n\s*AuthViewModel\.Screen\.[A-Z_]+\s*->""").find(host, start)?.range?.first ?: host.length
        val branch = host.substring(start, next)
        return Regex("""on(?:Back|BackToLogin)Click\s*=\s*viewModel::(\w+)""").find(branch)?.groupValues?.get(1)
            ?: error("$screen 갈래에서 ‹ 함수를 찾지 못했다")
    }

    /** onSystemBack 의 `Screen.A, Screen.B -> 함수()` 갈래를 화면 → 함수 이름으로 */
    private fun systemBackArms(): Map<String, String> {
        val body = functionBody(viewModel, "fun onSystemBack()")
        val arms = mutableMapOf<String, String>()
        Regex("""((?:Screen\.[A-Z_]+\s*,\s*)*Screen\.[A-Z_]+)\s*->\s*(\w+)\(\)""").findAll(body).forEach { m ->
            Regex("""Screen\.([A-Z_]+)""").findAll(m.groupValues[1]).forEach { arms[it.groupValues[1]] = m.groupValues[2] }
        }
        return arms
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
