package com.swpp.wakeup.data.local

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * SharedPreferences 저장소도 소유자로 범위를 좁히는지 소스에서 검사한다.
 *
 * ## 왜 필요한가
 *
 * Room 캐시는 [CacheScopeAuditTest] 가 지킨다. 그런데 뒤에 추가한
 * SharedPreferences 저장소들은 그 규칙 밖에 있었고, 실제로 새고 있었다 — A 가
 * 로그아웃하고 같은 기기에서 B 가 로그인하면 `HomeViewModel.init` 이
 * `reloadMorning()` 을 부르면서 **B 가 A 의 아침 기록을 봤다.** 블록 이름과
 * 진행 상황까지.
 *
 * 그때 놓친 이유가 분명하다. 새 저장소를 만들면서 "지우는 쪽" 목록에 넣는 것을
 * 잊었고, 잊었다는 사실을 알려 주는 것이 없었다. 그래서 개별 동작이 아니라
 * **파일 목록 자체**를 검사한다. 다음 사람이 저장소를 하나 더 추가하고 격리를
 * 빠뜨리면 여기서 걸린다.
 *
 * ## 무엇을 검사하지 않는가
 *
 * 실제 읽기·쓰기 동작은 `Context` 가 필요해서 계측 테스트여야 한다. 기기 없이
 * 못 도는 검사는 결국 돌지 않으므로, 여기서는 **규칙이 코드에 적용됐는지**만
 * 본다. 격리 판정 자체의 논리는 `PrefsOwnerRuleTest` 가 본다.
 */
class PrefsScopeAuditTest {

    /**
     * 소유자 격리가 필요한 저장소.
     *
     * 값은 "왜 필요한가" 다. 근거가 있어야 다음 사람이 목록을 함부로 줄이지
     * 않는다.
     */
    private val mustBeScoped = mapOf(
        "data/local/MorningSessionStore.kt" to
            "블록 이름과 진행 상황이 들어 있다. 계정을 바꾸면 앞 사람의 아침이 보인다",
        "sensing/BlockObservationQueue.kt" to
            "앞 사용자의 준비 기록을 지금 계정의 토큰으로 올리게 된다",
        "sensing/TripObservationQueue.kt" to
            "앞 사용자의 출발·도착 시각을 지금 계정의 토큰으로 올리게 된다",
        "alarm/ScheduledAlarmStore.kt" to
            "잠금화면 위에 앞 사용자의 일정 제목이 뜬다",
    )

    /**
     * 소유자 격리가 **필요 없는** 저장소. 예외에는 근거를 적는다.
     */
    private val allowedUnscoped = mapOf(
        "data/local/TokenStore.kt" to
            "소유자를 정하는 곳 자체다. 여기에 소유자 판정을 넣으면 순환이 된다",
    )

    @Test
    fun `소유자가 필요한 저장소는 전부 판정을 지난다`() {
        val violations = mutableListOf<String>()

        mustBeScoped.forEach { (relative, why) ->
            val source = readSource(relative)
            val reads = source.contains("discardIfForeign") || source.contains("isForeignOwner")
            val writes = source.contains("stampOwner")

            if (!reads) violations += "$relative — 읽을 때 소유자를 확인하지 않는다 ($why)"
            if (!writes) violations += "$relative — 쓸 때 소유자를 남기지 않는다 ($why)"
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("소유자 격리가 빠진 저장소 ${violations.size}건:")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("읽을 때 discardIfForeign(context) 로 남의 것을 걸러내고,")
                    appendLine("쓸 때 stampOwner(context) 로 소유자를 남겨야 한다.")
                    appendLine("근거는 data/local/PrefsOwner.kt 상단에 있다.")
                }
            )
        }
    }

    @Test
    fun `로그아웃이 모든 저장소를 지운다`() {
        val source = readSource("data/local/LocalStores.kt")

        // 파일 이름에서 클래스 이름을 뽑아 LocalStores 가 그것을 부르는지 본다.
        // 지우는 목록을 사람이 관리하면 반드시 하나를 빠뜨린다.
        val missing = mustBeScoped.keys
            .map { it.substringAfterLast('/').removeSuffix(".kt") }
            .filterNot { source.contains(it) }

        assertTrue(
            "LocalStores.wipeAll 이 지우지 않는 저장소가 있다: $missing\n" +
                "조회의 소유자 격리는 노출을 막지만 기기에 남는 것은 그대로다. 둘 다 필요하다.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `로그아웃과 로그인 양쪽에서 지운다`() {
        // 로그아웃만 지우면 앱이 강제 종료된 뒤 다른 계정으로 로그인하는 경로가
        // 남는다. Room 캐시가 이미 그 이유로 양쪽에서 지운다.
        val logout = readSource("ui/home/HomeViewModel.kt")
        val login = readSource("ui/auth/AuthViewModel.kt")

        assertTrue(
            "HomeViewModel.logout 이 LocalStores.wipeAll 을 부르지 않는다",
            logout.contains("LocalStores.wipeAll"),
        )
        assertTrue(
            "AuthViewModel 의 로그인 전 정리가 LocalStores.wipeAll 을 부르지 않는다",
            login.contains("LocalStores.wipeAll"),
        )
    }

    @Test
    fun `로그아웃은 토큰보다 저장소를 먼저 지운다`() {
        // 소유자 판정이 "지금 로그인한 계정" 을 보므로 토큰이 먼저 사라지면
        // 소유자를 모르는 상태가 되어 아무것도 지울 수 없다. 순서가 곧 정확성이다.
        val source = readSource("ui/home/HomeViewModel.kt")
        val wipeAt = source.indexOf("LocalStores.wipeAll")
        val clearAt = source.indexOf("tokenStore.clear()")

        assertTrue("LocalStores.wipeAll 호출을 찾지 못했다", wipeAt >= 0)
        assertTrue("tokenStore.clear() 호출을 찾지 못했다", clearAt >= 0)
        assertTrue(
            "토큰을 먼저 지우면 소유자를 모르게 되어 저장소가 지워지지 않는다.\n" +
                "LocalStores.wipeAll 을 tokenStore.clear() 앞에 둘 것.",
            wipeAt < clearAt,
        )
    }

    @Test
    fun `예외 목록에 근거가 비어 있지 않다`() {
        allowedUnscoped.forEach { (path, reason) ->
            assertTrue("예외에 근거가 없다: $path", reason.isNotBlank())
        }
    }

    // --- 내부 -------------------------------------------------------------

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
