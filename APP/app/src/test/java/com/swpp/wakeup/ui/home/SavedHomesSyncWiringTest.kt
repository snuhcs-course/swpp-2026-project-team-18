// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 저장한 집 목록 다시 받기의 판(`savedHomesVersion`)이 `HomeViewModel` 에 바르게 이어져 있는지 소스에서 검사한다
 * (SAVED-4).
 *
 * ## 왜 소스를 보는가
 *
 * 목록을 받는 동안 이 기기가 집을 바꾸면(기본으로 · 삭제 · 추가) 바꾸기 전에 서버가 만든 목록이 뒤에 와서 바꾼 결과를
 * 덮었다 — 지운 집이 되살아나고 방금 더한 집이 사라졌다. 판을 비교하는 규칙
 * ([com.swpp.wakeup.domain.model.savedHomesSyncApplies])은 `SavedHomeSyncTest` 가 보지만, 실제로 막는 것은
 * `HomeViewModel` 의 배선 세 가지다.
 *
 * 1. 다시 받기는 첫 서버 호출 **전에** 시작 판을 읽는다. 호출(일시 중단) 뒤에 읽으면 받는 동안 올린 판을 시작 판으로
 *    삼아 늦게 온 목록을 그대로 쓴다.
 * 2. 받은 목록은 판이 그대로일 때만 기기 목록에 쓴다.
 * 3. 서버의 저장한 집을 바꾸는 호출은 모두 성공하면 판을 올린다.
 *
 * 어느 하나를 지우거나 옮겨도 컴파일되고 다른 테스트도 모두 통과한다. `HomeViewModel` 은 AndroidViewModel 이라 JVM 에서
 * 만들 수 없어서(`HomeViewModelInitOrderTest` 와 같은 사정) 소스의 순서와 자리를 본다. 주석과 문자열 안은 보지 않는다.
 */
class SavedHomesSyncWiringTest {

    /** 주석과 문자열 안을 공백으로 지운 `HomeViewModel.kt` */
    private val code: String by lazy { codeOnly(readSource("ui/home/HomeViewModel.kt")) }

    @Test
    fun `목록 다시 받기는 첫 서버 호출 전에 시작 판을 읽는다`() {
        // Given 저장한 집 목록을 받는 함수
        val body = functionBody("syncSavedHomes")

        // When 시작 판을 읽는 자리와 첫 서버 호출(일시 중단)의 자리
        val read = START_VERSION.find(body)
            ?: failWith("syncSavedHomes 에서 `val startVersion = savedHomesVersion` 을 찾지 못했다")
        val firstCall = SAVED_PLACES_CALL.find(body)
            ?: failWith("syncSavedHomes 에서 savedPlaces 호출을 찾지 못했다")

        // Then 읽기가 먼저다
        assertTrue(
            "syncSavedHomes 가 savedPlaces.${firstCall.groupValues[1]}() 뒤에 시작 판을 읽는다. 받는 동안 올린 판을 " +
                "시작 판으로 삼아, 바꾸기 전의 목록이 바꾼 결과를 덮는다(SAVED-4)",
            read.range.first < firstCall.range.first,
        )
    }

    @Test
    fun `받은 목록은 받는 동안 판이 그대로일 때만 기기 목록에 쓴다`() {
        // Given 목록을 받은 뒤의 성공 갈래
        val ok = okBranch("syncSavedHomes", functionBody("syncSavedHomes"))

        // When 판을 비교하는 자리와 기기 목록에 쓰는 자리
        val check = SYNC_APPLIES.find(ok)
            ?: failWith("syncSavedHomes 의 Ok 갈래가 savedHomesSyncApplies(startVersion, savedHomesVersion) 로 판을 보지 않는다")
        val write = REPLACE_SYNCED.find(ok)
            ?: failWith("syncSavedHomes 의 Ok 갈래에서 savedHomesStore.replaceSynced 를 찾지 못했다")

        // Then 비교가 먼저다
        assertTrue(
            "syncSavedHomes 가 판을 보기 전에 받은 목록을 기기 목록에 쓴다(SAVED-4)",
            check.range.first < write.range.first,
        )
    }

    @Test
    fun `서버의 저장한 집을 바꾸는 호출은 성공하면 모두 판을 올린다`() {
        // Given savedPlaces 의 쓰기(목록 받기 · 처음 올리기 말고)를 부르는 함수들
        val writers = classFunctions().mapNotNull { (name, text) ->
            val writes = SAVED_PLACES_CALL.findAll(text).map { it.groupValues[1] }.filterNot { it in SYNC_CALLS }.toSet()
            if (writes.isEmpty()) null else Triple(name, text, writes)
        }
        // 검사가 아무것도 찾지 못한 채 통과하지 않게, 지금 아는 쓰기가 모두 잡혔는지 먼저 본다.
        val found = writers.flatMap { it.third }.toSet()
        assertTrue("저장한 집 쓰기 호출 $KNOWN_WRITES 를 다 찾지 못했다. 찾은 것: $found", found.containsAll(KNOWN_WRITES))

        // When 각 함수의 성공 갈래
        val missing = writers.filterNot { (name, text, _) -> bumpsBeforeReturn(okBranch(name, text)) }.map { it.first }

        // Then 모두 다른 갈래로 빠지기(return) 전에 판을 올린다
        assertTrue(
            "성공 갈래에서 savedHomesVersion 을 올리지 않는 쓰기가 있다: $missing. 그 쓰기가 끝나기 전에 시작한 목록 " +
                "다시 받기가 바꾼 결과를 덮는다(SAVED-4)",
            missing.isEmpty(),
        )
    }

    // --- 내부 -------------------------------------------------------------

    /** 성공 갈래 [ok] 가 `return` 보다 먼저 판을 올리는가 */
    private fun bumpsBeforeReturn(ok: String): Boolean {
        val bump = BUMP.find(ok) ?: return false
        val firstReturn = RETURN.find(ok) ?: return true
        return bump.range.first < firstReturn.range.first
    }

    /** [name] 함수의 본문(`{` 부터 짝이 맞는 `}` 까지). 블록 본문 함수만 찾는다 */
    private fun functionBody(name: String): String {
        val head = Regex("""(?m)^ {4}${MODIFIERS}fun\s+$name\s*\(""").find(code)
            ?: failWith("HomeViewModel 에서 $name 을 찾지 못했다. 이름을 바꿨다면 이 검사도 고쳐야 한다")
        val open = code.indexOf('{', head.range.last)
        val close = matchingBrace(code, open) ?: failWith("$name 의 본문 끝을 찾지 못했다")
        return code.substring(open, close + 1)
    }

    /** 클래스의 함수마다 (이름, 선언부터 다음 함수 선언 앞까지). 식 본문 함수가 있어도 다음 함수 본문을 빌리지 않는다 */
    private fun classFunctions(): List<Pair<String, String>> {
        val heads = Regex("""(?m)^ {4}${MODIFIERS}fun\s+(\w+)\s*\(""").findAll(code).toList()
        return heads.mapIndexed { i, head ->
            val end = heads.getOrNull(i + 1)?.range?.first ?: code.length
            head.groupValues[1] to code.substring(head.range.first, end)
        }
    }

    /** [text] 의 `is SavedPlacesResult.Ok -> { ... }` 갈래 안 */
    private fun okBranch(name: String, text: String): String {
        val arrow = OK_ARROW.find(text) ?: failWith("$name 에서 `SavedPlacesResult.Ok ->` 갈래를 찾지 못했다")
        val open = text.indexOf('{', arrow.range.last)
        val between = if (open < 0) "" else text.substring(arrow.range.last + 1, open)
        if (open < 0 || between.isNotBlank()) failWith("$name 의 `SavedPlacesResult.Ok ->` 갈래가 { } 블록이 아니다")
        val close = matchingBrace(text, open) ?: failWith("$name 의 Ok 갈래 끝을 찾지 못했다")
        return text.substring(open, close + 1)
    }

    /** [open] 위치의 `{` 에 짝이 맞는 `}` 위치 */
    private fun matchingBrace(text: String, open: Int): Int? {
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }

    /**
     * 주석과 문자열 안을 공백으로 바꾼 코드. 길이와 줄바꿈은 그대로라 자리가 원문과 같다. 주석이나 문자열에 이름이나
     * 괄호가 있어도 검사가 흔들리지 않는다.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        fun blank(until: Int) {
            while (i < until) {
                out.append(if (text[i] == '\n') '\n' else ' ')
                i++
            }
        }
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> blank(text.indexOf('\n', i).let { if (it < 0) text.length else it })
                c == '/' && next == '*' -> blank(text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 })
                c == '"' -> {
                    out.append('"')
                    i++
                    var end = i
                    while (end < text.length && text[end] != '"') end += if (text[end] == '\\') 2 else 1
                    blank(minOf(end, text.length))
                    if (i < text.length) {
                        out.append('"')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
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
            ?: failWith(
                "$relative 을 찾지 못했다. 파일을 옮겼다면 이 검사도 고쳐야 한다.\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
        return found.readText()
    }

    private fun failWith(message: String): Nothing = throw AssertionError(message)

    private companion object {
        /** 함수 앞에 올 수 있는 수식어 */
        const val MODIFIERS = """(?:(?:private|internal|public|protected|override|suspend|inline)\s+)*"""

        val START_VERSION = Regex("""\bval\s+startVersion\s*=\s*savedHomesVersion\b""")
        val SAVED_PLACES_CALL = Regex("""\bsavedPlaces\s*\.\s*(\w+)\s*\(""")
        val SYNC_APPLIES = Regex("""\bsavedHomesSyncApplies\s*\(\s*startVersion\s*,\s*savedHomesVersion\s*\)""")
        val REPLACE_SYNCED = Regex("""\bsavedHomesStore\s*\.\s*replaceSynced\s*\(""")
        val BUMP = Regex("""\bsavedHomesVersion\s*\+=\s*1L?\b|\+\+\s*savedHomesVersion\b|\bsavedHomesVersion\s*\+\+""")
        val RETURN = Regex("""\breturn\b""")
        val OK_ARROW = Regex("""\bSavedPlacesResult\s*\.\s*Ok\s*->""")

        /** 목록을 받는 호출. 다시 받기(syncSavedHomes) 자신이 부른다 */
        val SYNC_CALLS = setOf("list", "bulk")

        /** 지금 있는 서버 쓰기(`SavedPlacesRepository`). 하나라도 못 찾으면 검사가 엉뚱한 곳을 보고 있다 */
        val KNOWN_WRITES = setOf("create", "delete", "makeDefault")
    }
}
