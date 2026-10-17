// AI-generated (Claude)
package com.swpp.wakeup

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 서버 동작과 맞지 않는 문구가 화면에 다시 들어오는 것을 막는다(P5, A3).
 *
 * - "학습 중": 확률이 없을 때 기다리면 생긴다고 약속한다. 실제로는 준비·이동 변동 폭이
 *   둘 다 있어야 나온다.
 * - "같은 경로를 몇 번": 서버 보정은 모든 사용자의 이동 기록으로 만든다.
 * - "0일 / 14일": 서버에 없는 기준으로 고정한 값이다.
 * - "확률 계산이 시작됨"/"확률 계산도 시작됨": 준비 범위만으로는 확률이 나오지 않는다.
 * - "이동 시간만 실측값": 지운 안내 상자.
 *
 * 컴파일러·lint 는 문구를 보지 않는다. 그래서 소스를 읽는다(TripLiveRouteBackgroundContractTest
 * 와 같은 방식). 주석 줄은 빼고 본다 — 왜 지웠는지 설명하는 주석은 남아도 된다.
 */
class UnreliableCopyAuditTest {

    private val banned = listOf(
        "학습 중",
        "같은 경로를 몇 번",
        "0일 / 14일",
        "확률 계산이 시작됨",
        "확률 계산도 시작됨",
        "이동 시간만 실측값",
    )

    @Test
    fun `화면 문자열에 서버와 맞지 않는 문구가 없다`() {
        val root = sourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("소스를 하나도 읽지 못했다: ${root.absolutePath}", files.size > 20)

        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                banned.firstOrNull { !comment && code.contains(it) }
                    ?.let { "${file.relativeTo(root)}:${index + 1} \"$it\"" }
            }
        }
        assertTrue("다시 들어온 문구:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    private fun sourceRoot(): File {
        val path = "src/main/java/com/swpp/wakeup"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        return candidates.firstOrNull { it.isDirectory }
            ?: run { fail("소스 디렉터리를 찾지 못했다"); error("unreachable") }
    }
}
