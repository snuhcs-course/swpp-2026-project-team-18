package com.swpp.wakeup.ui.home

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `init` 블록이 자기보다 아래에 선언된 프로퍼티를 건드리지 않는지 소스에서 검사한다.
 *
 * ## 왜 필요한가
 *
 * Kotlin 은 프로퍼티 초기화와 `init` 블록을 **선언 순서대로** 실행한다. 그래서
 * `init` 이 자기보다 아래에 선언된 프로퍼티를 읽으면 그 값은 아직 null 이다.
 * 타입이 `String` 이든 무엇이든 상관없다 — 컴파일러가 통과시키고, 실행 시점에
 * `NullPointerException` 이 난다.
 *
 * 실제로 그렇게 깨졌다. 아침 기록 기능을 넣을 때 `init` 에 `reloadMorning()` 을
 * 추가했는데, 그 함수가 쓰는 `morningStore` 선언은 파일 중간의 "아침 기록"
 * 섹션에 있었다 — `init` 보다 180줄 아래였다. 결과는 이랬다.
 *
 * ```
 * java.lang.RuntimeException: Cannot create an instance of class HomeViewModel
 * Caused by: java.lang.NullPointerException:
 *   Attempt to invoke virtual method '...MorningSessionStore.current()'
 *   on a null object reference
 *     at HomeViewModel.reloadMorning(HomeViewModel.kt:330)
 *     at HomeViewModel.<init>(HomeViewModel.kt:161)
 * ```
 *
 * 홈 화면이 곧 첫 화면이므로 **앱을 켤 때마다 100% 죽었다.** 그런데 그 상태로
 * 여러 커밋이 올라갔다. 단위 테스트는 `Context` 가 필요해서 이 클래스를 만들지
 * 않고, lint 은 이 순서를 보지 않으며, `assembleDebug` 는 당연히 통과한다.
 * 계약 검사는 서버만 본다. **아무도 앱을 실행하지 않았다는 것이 진짜 원인이다.**
 *
 * 그래서 사람의 주의력이 아니라 검사로 막는다. 파일을 정리하다가 선언을 아래로
 * 옮기는 것은 자연스러운 행동이고, 그 순간 앱이 죽는다는 것을 알려 주는 것이
 * 없으면 반드시 다시 일어난다.
 *
 * ## 무엇을 검사하지 않는가
 *
 * 실제로 생성해서 확인하는 것이 가장 확실하지만 `AndroidViewModel` 은
 * `Application` 이 필요해 계측 테스트여야 한다. 기기 없이 못 도는 검사는 결국
 * 돌지 않으므로, 여기서는 소스의 **선언 순서**만 본다. 실행 확인은 기기에서
 * 앱을 띄우는 것으로 대신한다.
 */
class HomeViewModelInitOrderTest {

    /**
     * 검사할 파일.
     *
     * `init` 블록을 가진 `ViewModel` 을 추가하면 여기에 넣어야 한다. 목록을
     * 사람이 관리하는 것이 약점이지만, 소스 전체를 파싱하는 것보다 오해가 적다.
     */
    private val sources = listOf(
        "ui/home/HomeViewModel.kt",
        "ui/auth/AuthViewModel.kt",
    )

    @Test
    fun `init 이 쓰는 프로퍼티는 모두 init 보다 위에 선언돼 있다`() {
        val violations = mutableListOf<String>()

        sources.forEach { relative ->
            val text = readSource(relative)
            val initAt = findInitBlock(text) ?: return@forEach

            // init 아래에서 선언되는 프로퍼티 이름을 모은다.
            val declaredBelow = propertyNames(text.substring(initAt.last))

            // init 블록 본문이 참조하는 식별자.
            val used = identifiers(text.substring(initAt.first, initAt.last))

            (used intersect declaredBelow).sorted().forEach { name ->
                violations += "$relative — init 이 '$name' 을 쓰지만 선언이 init 아래에 있다"
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("init 보다 아래에 선언된 프로퍼티를 init 이 쓴다 (${violations.size}건):")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Kotlin 은 선언 순서대로 초기화하므로 그 값은 실행 시점에 null 이다.")
                    appendLine("컴파일은 통과하고 앱을 켤 때 NullPointerException 으로 죽는다.")
                    appendLine("해당 선언을 init 블록보다 위로 옮길 것.")
                }
            )
        }
    }

    @Test
    fun `init 이 부르는 함수가 쓰는 프로퍼티도 init 보다 위에 있다`() {
        // 위 검사는 init 본문에 이름이 직접 보이는 것만 잡는다. 실제 사고는
        // init 이 reloadMorning() 을 부르고 **그 함수가** morningStore 를 쓰는
        // 모양이었다. 한 단계 더 따라가야 그 형태가 걸린다.
        val violations = mutableListOf<String>()

        sources.forEach { relative ->
            val text = readSource(relative)
            val initAt = findInitBlock(text) ?: return@forEach

            val declaredBelow = propertyNames(text.substring(initAt.last))
            val initBody = text.substring(initAt.first, initAt.last)

            // init 이 부르는 이 클래스의 함수를 찾아 그 본문까지 본다.
            methodNames(text).filter { it in identifiers(initBody) }.forEach { fn ->
                val body = methodBody(text, fn) ?: return@forEach
                (identifiers(body) intersect declaredBelow).sorted().forEach { name ->
                    violations +=
                        "$relative — init 이 $fn() 을 부르고 그 안에서 '$name' 을 쓰지만 " +
                        "선언이 init 아래에 있다"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("init 이 부르는 함수가 아직 초기화 안 된 프로퍼티를 쓴다 (${violations.size}건):")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("init 에서 함수를 부르면 그 함수가 쓰는 것까지 init 위에 있어야 한다.")
                    appendLine("이 모양이 실제로 앱 시작 크래시를 만들었다.")
                }
            )
        }
    }

    @Test
    fun `검사 대상 파일이 실제로 존재한다`() {
        // 파일을 옮기면 위 검사들이 조용히 아무것도 안 하게 된다. 그쪽이 더
        // 위험하므로 존재 자체를 따로 확인한다.
        sources.forEach { relative ->
            assertTrue("$relative 이 비어 있다", readSource(relative).isNotBlank())
        }
    }

    @Test
    fun `HomeViewModel 의 아침 기록 선언이 init 보다 위에 있다`() {
        // 위 검사들은 일반 규칙이다. 실제로 깨졌던 지점은 회귀로 따로 못 박는다.
        val text = readSource("ui/home/HomeViewModel.kt")
        val initAt = findInitBlock(text)!!
        val storeAt = text.indexOf("private val morningStore")
        val flowAt = text.indexOf("private val _morning")

        assertTrue("morningStore 선언을 찾지 못했다", storeAt >= 0)
        assertTrue("_morning 선언을 찾지 못했다", flowAt >= 0)
        assertTrue(
            "morningStore 선언이 init 아래에 있다. init 의 reloadMorning() 이 " +
                "이것을 읽으므로 앱을 켤 때 죽는다.",
            storeAt < initAt.first,
        )
        assertTrue(
            "_morning 선언이 init 아래에 있다. init 의 reloadMorning() 이 " +
                "여기에 쓰므로 앱을 켤 때 죽는다.",
            flowAt < initAt.first,
        )
    }

    // --- 내부 -------------------------------------------------------------

    /** `init {` 본문의 범위. 없으면 null. */
    private fun findInitBlock(text: String): IntRange? {
        val marker = Regex("""(?m)^\s{4}init\s*\{""").find(text) ?: return null
        val open = text.indexOf('{', marker.range.first)
        val close = matchingBrace(text, open) ?: return null
        return open..close
    }

    /** [open] 위치의 `{` 에 대응하는 `}` 위치. */
    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 프로퍼티 선언 이름. `private val x = ...` 의 `x`. */
    private fun propertyNames(text: String): Set<String> =
        Regex("""(?m)^\s{4}(?:private\s+|internal\s+)?(?:val|var)\s+(\w+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

    /** 이 클래스가 가진 함수 이름. */
    private fun methodNames(text: String): Set<String> =
        Regex("""(?m)^\s{4}(?:private\s+|internal\s+)?fun\s+(\w+)\s*\(""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

    /** [name] 함수의 본문. 없으면 null. */
    private fun methodBody(text: String, name: String): String? {
        val head = Regex("""(?m)^\s{4}(?:private\s+|internal\s+)?fun\s+$name\s*\(""")
            .find(text) ?: return null
        val open = text.indexOf('{', head.range.last)
        if (open < 0) return null
        val close = matchingBrace(text, open) ?: return null
        return text.substring(open, close)
    }

    /**
     * 코드에 등장하는 식별자.
     *
     * 주석과 문자열을 먼저 지운다. 주석에 이름이 적혀 있는 것만으로 실패하면
     * 검사를 믿을 수 없게 된다 — 실제로 이 파일들의 주석에는 프로퍼티 이름이
     * 자주 나온다.
     */
    private fun identifiers(code: String): Set<String> {
        val stripped = code
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex(""""(?:[^"\\]|\\.)*""""), " ")
        return Regex("""\b(\w+)\b""").findAll(stripped).map { it.groupValues[1] }.toSet()
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(
            File(path),
            File("app/$path"),
            File("../app/$path"),
            File("APP/app/$path"),
        )
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            fail(
                "$relative 을 찾지 못했다. 파일을 옮겼다면 이 검사의 목록도 고쳐야 한다.\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
        }
        return found!!.readText()
    }
}
