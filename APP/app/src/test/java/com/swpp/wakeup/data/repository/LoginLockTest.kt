// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.domain.model.LoginLock
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.prefsContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.OffsetDateTime

/** 로그인 잠금(C2, 서버 계약 `docs/iter4/server-login-lock.md`). 본문은 계약 문서의 실제 응답이다 */
class LoginLockTest {

    private val tokenStore = TokenStore(prefsContext(), FakeTokenCipher())

    private class LoginApi(private val reply: () -> Response<AuthResponse>) : FakeAuthApi() {
        override suspend fun login(body: LoginRequest): Response<AuthResponse> = reply()
    }

    private fun repo(reply: () -> Response<AuthResponse>) = AuthRepository(tokenStore = tokenStore, api = LoginApi(reply))

    @Test
    fun `401 은 남은 횟수를 싣는다`() = runTest {
        val r = repo {
            httpError(401, """{"error":{"code":"authentication_failed","message":"이메일 또는 비밀번호가 맞지 않아요.","details":{"remaining_attempts":4}}}""")
        }.login("a@b.com", "x") as AuthRepository.AuthResult.Failure
        assertEquals("이메일 또는 비밀번호가 맞지 않아요.", r.message)
        assertEquals(4, r.remainingAttempts)
        assertNull(r.lockedUntilMillis)
    }

    @Test
    fun `구버전 서버의 401(details 비어 있음)은 지금처럼 문구만`() = runTest {
        val r = repo {
            httpError(401, """{"error":{"code":"authentication_failed","message":"이메일 또는 비밀번호가 맞지 않아요.","details":{}}}""")
        }.login("a@b.com", "x") as AuthRepository.AuthResult.Failure
        assertNull(r.remainingAttempts)
        assertNull(LoginLock.attemptsLine(r.remainingAttempts))
    }

    @Test
    fun `423 은 locked_until 을 풀리는 시각으로 싣는다`() = runTest {
        val r = repo {
            httpError(423, """{"error":{"code":"account_locked","message":"비밀번호를 5번 틀려서 로그인할 수 없어요.","details":{"locked_until":"2030-11-15T21:51:40+09:00","retry_after_seconds":900}}}""")
        }.login("a@b.com", "x") as AuthRepository.AuthResult.Failure
        assertEquals(OffsetDateTime.parse("2030-11-15T21:51:40+09:00").toInstant().toEpochMilli(), r.lockedUntilMillis)
    }

    @Test
    fun `locked_until 이 없으면 지금 + retry_after_seconds`() {
        val now = 1_000_000L
        assertEquals(now + 900_000L, LoginLock.lockedUntil(null, 900, now))
        assertEquals(now + 900_000L, LoginLock.lockedUntil("읽을 수 없음", 900, now))
        assertNull(LoginLock.lockedUntil(null, null, now))
    }

    @Test
    fun `남은 시간 카운트다운 - 올림하고 0 이 되면 풀린다`() {
        val until = 10_000_000L
        assertEquals(872, LoginLock.remainingSeconds(until, until - 871_500))
        assertEquals("14분 32초", LoginLock.countdown(872))
        assertEquals("45초", LoginLock.countdown(45))
        assertTrue(LoginLock.isLocked(until, until - 1))
        assertFalse(LoginLock.isLocked(until, until))
        assertEquals(0, LoginLock.remainingSeconds(until, until + 5_000))
        assertEquals(
            "비밀번호를 여러 번 틀려서 잠시 잠겼어요. 14분 32초 뒤에 다시 로그인할 수 있어요",
            LoginLock.lockedLine(until, until - 871_500),
        )
    }

    @Test
    fun `남은 횟수 문구`() {
        assertEquals("3번 더 틀리면 15분 동안 잠겨요", LoginLock.attemptsLine(3))
        assertNull(LoginLock.attemptsLine(0))
        assertNull(LoginLock.attemptsLine(null))
    }

    @Test
    fun `잠금은 계정 기준 - 대소문자 · 공백 무시`() {
        assertTrue(LoginLock.sameAccount("A@b.com ", "a@B.com"))
        assertFalse(LoginLock.sameAccount("a@b.com", "c@b.com"))
        assertFalse(LoginLock.sameAccount(null, "a@b.com"))
    }
}
