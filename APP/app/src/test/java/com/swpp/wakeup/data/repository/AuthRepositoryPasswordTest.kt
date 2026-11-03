// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.PasswordChangeRequest
import com.swpp.wakeup.data.remote.PasswordChangeResponse
import com.swpp.wakeup.data.remote.PasswordResetConfirmRequest
import com.swpp.wakeup.data.remote.PasswordResetRequest
import com.swpp.wakeup.data.remote.PasswordResetResponse
import com.swpp.wakeup.data.repository.AuthRepository.AuthResult
import com.swpp.wakeup.data.repository.AuthRepository.PasswordChangeResult
import com.swpp.wakeup.data.repository.AuthRepository.ResetRequestResult
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 비밀번호 재설정·변경(제안 계약 R3-9). 서버가 아직 없으면 404/405 가 온다. */
class AuthRepositoryPasswordTest {

    private val tokenStore = TokenStore(prefsContext(), FakeTokenCipher())

    private val loginBody = fromJson<AuthResponse>(
        """{"access":"new-access","refresh":"new-refresh","user":{"id":7,"email":"me@snu.ac.kr","nickname":"진호"}}"""
    )

    private fun errorBody(code: String, message: String = "서버 문구") =
        """{"error":{"code":"$code","message":"$message","details":{}}}"""

    private class ResetApi(
        private val request: (PasswordResetRequest) -> Response<PasswordResetResponse> = { httpError(404) },
        private val confirm: (PasswordResetConfirmRequest) -> Response<AuthResponse> = { httpError(404) },
        private val change: (PasswordChangeRequest) -> Response<PasswordChangeResponse> = { httpError(404) },
    ) : FakeAuthApi() {
        val requests = mutableListOf<PasswordResetRequest>()
        val confirms = mutableListOf<PasswordResetConfirmRequest>()
        val changes = mutableListOf<PasswordChangeRequest>()
        var throwOnCall: Throwable? = null

        override suspend fun requestPasswordReset(body: PasswordResetRequest): Response<PasswordResetResponse> {
            throwOnCall?.let { throw it }
            requests += body
            return request(body)
        }

        override suspend fun confirmPasswordReset(body: PasswordResetConfirmRequest): Response<AuthResponse> {
            throwOnCall?.let { throw it }
            confirms += body
            return confirm(body)
        }

        override suspend fun changePassword(body: PasswordChangeRequest): Response<PasswordChangeResponse> {
            throwOnCall?.let { throw it }
            changes += body
            return change(body)
        }
    }

    private fun repository(api: FakeAuthApi, onBefore: suspend () -> Unit = {}) =
        AuthRepository(tokenStore = tokenStore, api = api, onBeforeAuthenticated = onBefore)

    // --- 코드 요청 --------------------------------------------------------------

    @Test
    fun `코드 요청은 202 면 보냈다고 하고 이메일을 다듬어 보낸다`() = runTest {
        val api = ResetApi(request = { ok(PasswordResetResponse(sent = true, expiresIn = 600)) })

        val result = repository(api).requestPasswordReset("  me@snu.ac.kr ")

        assertEquals(ResetRequestResult.Sent(600), result)
        assertEquals("me@snu.ac.kr", api.requests.single().email)
    }

    @Test
    fun `코드 요청이 404 나 405 면 아직 지원하지 않는다고 알린다`() = runTest {
        listOf(404, 405).forEach { status ->
            val api = ResetApi(request = { httpError(status) })
            val result = repository(api).requestPasswordReset("me@snu.ac.kr")
            assertEquals(
                ResetRequestResult.Failure("아직 서버에서 지원하지 않아요"),
                result,
            )
        }
    }

    @Test
    fun `코드 요청이 429 면 요청이 많다고 알린다`() = runTest {
        val api = ResetApi(request = { httpError(429, errorBody("throttled")) })

        val result = repository(api).requestPasswordReset("me@snu.ac.kr")

        assertEquals(ResetRequestResult.Failure("요청이 많음. 잠시 뒤 다시 시도"), result)
    }

    @Test
    fun `코드 요청은 네트워크 오류도 던지지 않는다`() = runTest {
        val api = ResetApi().apply { throwOnCall = IOException("끊김") }

        val result = repository(api).requestPasswordReset("me@snu.ac.kr")

        assertEquals(ResetRequestResult.Failure(AuthRepository.MESSAGE_NETWORK), result)
    }

    // --- 코드 확인 --------------------------------------------------------------

    @Test
    fun `코드 확인이 200 이면 토큰을 저장하고 앞 사용자 정리를 먼저 돈다`() = runTest {
        var cleanedBeforeSave = false
        val api = ResetApi(confirm = { ok(loginBody) })

        val result = repository(api, onBefore = { cleanedBeforeSave = !tokenStore.isLoggedIn })
            .confirmPasswordReset(" me@snu.ac.kr ", " 123456 ", "Newpass123", "Newpass123")

        assertTrue("성공이어야 한다: $result", result is AuthResult.Success)
        assertEquals("me@snu.ac.kr", (result as AuthResult.Success).user.email)
        assertEquals("new-access", tokenStore.accessToken)
        assertEquals("new-refresh", tokenStore.refreshToken)
        assertTrue(cleanedBeforeSave)
        assertEquals(
            PasswordResetConfirmRequest("me@snu.ac.kr", "123456", "Newpass123", "Newpass123"),
            api.confirms.single(),
        )
    }

    @Test
    fun `코드 확인의 실패 코드는 문구로 바뀐다`() = runTest {
        val expected = mapOf(
            "invalid_reset_code" to "코드가 맞지 않음",
            "reset_code_expired" to "코드가 만료됨. 다시 받아야 함",
            "too_many_attempts" to "시도가 너무 많음. 코드를 다시 받아야 함",
        )
        expected.forEach { (code, message) ->
            val api = ResetApi(confirm = { httpError(400, errorBody(code)) })
            val result = repository(api).confirmPasswordReset("me@snu.ac.kr", "000000", "Newpass123", "Newpass123")
            assertEquals(AuthResult.Failure(message), result)
            assertFalse(tokenStore.isLoggedIn)
        }
    }

    @Test
    fun `코드 확인의 입력 오류는 서버 문구를 그대로 쓴다`() = runTest {
        val api = ResetApi(confirm = { httpError(400, errorBody("invalid", "비밀번호가 너무 짧다.")) })

        val result = repository(api).confirmPasswordReset("me@snu.ac.kr", "123456", "short", "short")

        assertEquals(AuthResult.Failure("비밀번호가 너무 짧다."), result)
    }

    @Test
    fun `코드 확인이 404 면 지원하지 않는다고 알린다`() = runTest {
        val result = repository(ResetApi()).confirmPasswordReset("a@b.co", "123456", "Newpass123", "Newpass123")

        assertEquals(AuthResult.Failure("아직 서버에서 지원하지 않아요"), result)
    }

    // --- 변경 -------------------------------------------------------------------

    @Test
    fun `변경이 200 이면 이 기기의 토큰을 새 것으로 바꾼다`() = runTest {
        tokenStore.save("old-access", "old-refresh", "me@snu.ac.kr", "진호")
        val api = ResetApi(change = { ok(PasswordChangeResponse("new-access", "new-refresh", revoked = 3)) })

        val result = repository(api).changePassword("Oldpass123", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Success(tokensRotated = true), result)
        assertEquals("new-access", tokenStore.accessToken)
        assertEquals("new-refresh", tokenStore.refreshToken)
        assertEquals(PasswordChangeRequest("Oldpass123", "Newpass123", "Newpass123"), api.changes.single())
    }

    @Test
    fun `변경 응답에 토큰이 없으면 성공이지만 토큰은 그대로다`() = runTest {
        tokenStore.save("old-access", "old-refresh", "me@snu.ac.kr", "진호")
        val api = ResetApi(change = { ok(PasswordChangeResponse()) })

        val result = repository(api).changePassword("Oldpass123", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Success(tokensRotated = false), result)
        assertEquals("old-access", tokenStore.accessToken)
    }

    @Test
    fun `지금 비밀번호가 틀리면 표시를 달아 돌려준다`() = runTest {
        val api = ResetApi(change = { httpError(400, errorBody("invalid_current_password")) })

        val result = repository(api).changePassword("wrong", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Failure("지금 비밀번호가 맞지 않음", currentPasswordWrong = true), result)
    }

    @Test
    fun `같은 비밀번호와 비밀번호 없는 계정은 각자 문구가 있다`() = runTest {
        val same = repository(ResetApi(change = { httpError(400, errorBody("password_unchanged")) }))
            .changePassword("Oldpass123", "Oldpass123", "Oldpass123")
        val social = repository(ResetApi(change = { httpError(409, errorBody("password_login_unavailable")) }))
            .changePassword("x", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Failure("지금 비밀번호와 같음"), same)
        assertEquals(PasswordChangeResult.Failure("이 계정은 비밀번호로 로그인하지 않음"), social)
    }

    @Test
    fun `변경이 404 면 지원하지 않는다고 알리고 토큰은 건드리지 않는다`() = runTest {
        tokenStore.save("old-access", "old-refresh", "me@snu.ac.kr", "진호")

        val result = repository(ResetApi()).changePassword("Oldpass123", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Failure("아직 서버에서 지원하지 않아요"), result)
        assertEquals("old-access", tokenStore.accessToken)
    }

    @Test
    fun `변경은 네트워크 오류도 던지지 않는다`() = runTest {
        val api = ResetApi().apply { throwOnCall = IOException("끊김") }

        val result = repository(api).changePassword("Oldpass123", "Newpass123", "Newpass123")

        assertEquals(PasswordChangeResult.Failure(AuthRepository.MESSAGE_NETWORK), result)
    }
}
