// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import java.time.OffsetDateTime

/**
 * 로그인 잠금(서버 계약 `docs/iter4/server-login-lock.md`, BE-A3). 순수 함수만 둔다(JVM 테스트 대상).
 *
 * - 401 `details.remaining_attempts`: 앞으로 몇 번 더 틀리면 잠기는지(4 → 1)
 * - 423 `account_locked`: `details.locked_until`(없으면 지금 + `retry_after_seconds`)까지 잠김. 비밀번호를 재설정하면 바로 풀린다
 */
object LoginLock {

    /** 잠기는 시간(분). 서버 `LOGIN_LOCK_SECONDS` 기본값 900 과 같다. 401 에는 이 값이 오지 않아 문구에만 쓴다 */
    const val LOCK_MINUTES = 15

    /** 풀리는 시각(epoch ms). `locked_until` 을 먼저, 읽지 못하면 지금 + [retryAfterSeconds]. 둘 다 없으면 null */
    fun lockedUntil(lockedUntilIso: String?, retryAfterSeconds: Long?, nowMillis: Long): Long? {
        lockedUntilIso?.let { raw ->
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        }
        return retryAfterSeconds?.takeIf { it > 0 }?.let { nowMillis + it * 1000 }
    }

    /** 아직 잠겨 있는가 */
    fun isLocked(untilMillis: Long?, nowMillis: Long): Boolean = untilMillis != null && nowMillis < untilMillis

    /** 남은 시간(초, 올림). 풀렸으면 0 */
    fun remainingSeconds(untilMillis: Long, nowMillis: Long): Long =
        ((untilMillis - nowMillis + 999) / 1000).coerceAtLeast(0)

    /** "14분 32초" / "45초" */
    fun countdown(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}분 ${s}초" else "${s}초"
    }

    /** 잠겼을 때 [로그인] 위의 안내 */
    fun lockedLine(untilMillis: Long, nowMillis: Long): String =
        "비밀번호를 여러 번 틀려서 잠시 잠겼어요. ${countdown(remainingSeconds(untilMillis, nowMillis))} 뒤에 다시 로그인할 수 있어요"

    /** 잠금을 바로 푸는 방법 */
    const val UNLOCK_HINT = "비밀번호를 재설정하면 바로 풀려요"

    /** 401 의 남은 횟수 안내. 값이 없거나(구버전 서버) 0 이하면 null */
    fun attemptsLine(remainingAttempts: Int?): String? =
        remainingAttempts?.takeIf { it > 0 }?.let { "${it}번 더 틀리면 ${LOCK_MINUTES}분 동안 잠겨요" }

    /** 잠금은 계정(이메일) 기준이다. 대소문자 · 앞뒤 공백을 무시하고 비교한다 */
    fun sameAccount(a: String?, b: String?): Boolean =
        a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)
}
