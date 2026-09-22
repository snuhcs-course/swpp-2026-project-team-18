package com.swpp.wakeup.data.local

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 캐시 조회가 **반드시** 소유자로 범위를 좁히는지 소스에서 검사한다.
 *
 * ## 왜 소스를 읽는가
 *
 * 캐시에는 집 위치·목적지·일정 제목이 들어 있다. 기기를 공유하거나 계정을
 * 바꿨을 때 앞 사용자의 동선이 보이면 안 된다. 그 보장은 "로그아웃 때 지운다"
 * 가 아니라 **모든 조회가 `ownerEmail` 을 요구한다** 는 성질에서 나온다 —
 * 지우는 호출은 앱이 강제 종료되면 돌지 않는다.
 *
 * 지금 쓴 쿼리 하나를 테스트하는 것으로는 부족하다. 위험한 것은 **나중에
 * 추가되는 쿼리**다. 필터 없는 `SELECT` 를 하나 더 넣으면 아무 테스트도
 * 깨지지 않으면서 격리가 무너진다. 그래서 개별 동작이 아니라 파일 전체를
 * 훑어 규칙을 강제한다. 백엔드의 `test_security_audit.py` 와 같은 방식이다.
 *
 * 계측 테스트(Room 실제 구동)가 아닌 이유는 이 검사가 기기를 필요로 하지
 * 않아야 하기 때문이다. 기기 없이 못 도는 검사는 결국 돌지 않는다.
 */
class CacheScopeAuditTest {

    /**
     * 소유자 필터가 **없어도 되는** 쿼리.
     *
     * 근거를 함께 적는다. 목록에 올리는 것으로 예외를 만들 수 있으므로, 그
     * 판단이 리뷰에 보여야 한다.
     */
    private val allowedWithoutOwner = mapOf(
        "DELETE FROM cached_event" to
            "전체 삭제. 로그아웃·계정 전환에서 모든 계정의 잔재를 지운다",
        "DELETE FROM cached_profile" to
            "전체 삭제. 위와 같다",
        "DELETE FROM cached_block" to
            "전체 삭제. 위와 같다",
    )

    @Test
    fun `모든 조회가 소유자로 범위를 좁힌다`() {
        val source = daoSource()
        val queries = extractQueries(source)

        assertTrue(
            "DAO 에서 @Query 를 하나도 찾지 못했다. 검사가 무력화된 것이므로 실패로 본다.",
            queries.size >= 8,
        )

        val violations = queries.filter { query ->
            val normalized = query.replace(Regex("\\s+"), " ").trim()
            if (allowedWithoutOwner.keys.any { normalized.equals(it, ignoreCase = true) }) {
                return@filter false
            }
            // SELECT 와 대상 한정 DELETE 는 소유자를 요구한다.
            val needsOwner = normalized.startsWith("SELECT", ignoreCase = true) ||
                normalized.startsWith("DELETE", ignoreCase = true)
            needsOwner && !normalized.contains("ownerEmail", ignoreCase = true)
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("소유자 범위가 없는 쿼리 ${violations.size}건:")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("캐시에는 집 위치와 다니는 장소가 들어 있다. 조회가 소유자를")
                    appendLine("요구하지 않으면 계정을 바꿨을 때 앞 사용자의 동선이 보인다.")
                    appendLine("전체 삭제처럼 정말 예외라면 allowedWithoutOwner 에 근거를 적을 것.")
                }
            )
        }
    }

    @Test
    fun `예외 목록에 근거가 비어 있지 않다`() {
        // 근거 없이 예외를 늘리는 것을 막는다. 목록이 조용히 자라면 이 검사가
        // 통과하면서도 아무것도 지키지 않게 된다.
        allowedWithoutOwner.forEach { (query, reason) ->
            assertTrue("예외에 근거가 없다: $query", reason.isNotBlank())
        }
    }

    @Test
    fun `모든 캐시 표에 소유자 칸이 있다`() {
        val source = daoSource()
        // 엔티티도 같은 파일에 있다. `@Entity` 뒤의 data class 마다 ownerEmail
        // 이 있어야 한다 — 칸이 없으면 위의 쿼리 검사를 통과할 수가 없지만,
        // 새 표를 추가할 때 이쪽이 먼저 걸리는 편이 원인을 찾기 쉽다.
        val entities = Regex("@Entity\\b[\\s\\S]*?data class\\s+(\\w+)\\s*\\(([\\s\\S]*?)\\n\\)")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertTrue("엔티티를 찾지 못했다", entities.size >= 3)

        entities.forEach { (name, body) ->
            assertTrue(
                "$name 에 ownerEmail 칸이 없다. 소유자 없는 표는 다음 사용자가 읽을 수 있다.",
                body.contains("ownerEmail"),
            )
        }
    }

    // --- 내부 -------------------------------------------------------------

    private fun daoSource(): String {
        val relative = "src/main/java/com/swpp/wakeup/data/local/JitDatabase.kt"
        // Gradle 은 단위 테스트의 작업 디렉터리를 모듈 폴더로 둔다. 다른
        // 실행기(IDE)는 프로젝트 루트일 수 있어 위로 몇 단계 더 찾아본다.
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
            File("APP/app/$relative"),
        )
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            fail(
                "JitDatabase.kt 를 찾지 못했다. 시도한 경로:\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
        }
        return found!!.readText()
    }

    /** `@Query("...")` 안의 문자열을 뽑는다. 여러 줄 연결(`+`)도 이어 붙인다. */
    private fun extractQueries(source: String): List<String> =
        Regex("@Query\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)")
            .findAll(source)
            .map { match ->
                Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .findAll(match.groupValues[1])
                    .joinToString("") { it.groupValues[1] }
            }
            .toList()
}
