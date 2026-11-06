// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.EmailVerifyConfirmRequest
import com.swpp.wakeup.data.remote.PasswordResetResponse
import com.swpp.wakeup.data.remote.SocialUserResponse
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.repository.AuthRepository.EmailVerifyResult
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 이메일 인증(R3-10, password-api.md §4). 서버가 아직 없으면 404/405 가 온다. */
class AuthRepositoryEmailVerifyTest {
    private val tokenStore = TokenStore(prefsContext(), FakeTokenCipher())

    private fun errorBody(code: String, message: String = "서버 문구") =
        """{"error":{"code":"$code","message":"$message","details":{}}}"""

    private class VerifyApi(
        private val request: () -> Response<PasswordResetResponse> = { httpError(404) },
        private val confirm: (EmailVerifyConfirmRequest) -> Response<SocialUserResponse> = { httpError(404) },
    ) : FakeAuthApi() {
        var requestCalls = 0
        val confirms = mutableListOf<EmailVerifyConfirmRequest>()
        var throwOnCall: Throwable? = null

        override suspend fun requestEmailVerify(): Response<PasswordResetResponse> {
            throwOnCall?.let { throw it }
            requestCalls++
            return request()
        }

        override suspend fun confirmEmailVerify(body: EmailVerifyConfirmRequest): Response<SocialUserResponse> {
            throwOnCall?.let { throw it }
            confirms += body
            return confirm(body)
        }
    }

    private fun repository(api: FakeAuthApi) = AuthRepository(tokenStore = tokenStore, api = api)

    // --- 코드 요청 --------------------------------------------------------------

    @Test
    fun `코드 요청은 202 면 보냈다고 하고 만료 시간을 준다`() = runTest {
        val api = VerifyApi(request = { ok(PasswordResetResponse(sent = true, expiresIn = 600)) })
        assertEquals(EmailVerifyResult.Sent(600), repository(api).requestEmailVerify())
        assertEquals(1, api.requestCalls)
    }

    @Test
    fun `이미 인증한 계정은 409 로 알려 준다`() = runTest {
        val api = VerifyApi(request = { httpError(409, errorBody("email_already_verified")) })
        assertEquals(EmailVerifyResult.AlreadyVerified, repository(api).requestEmailVerify())
    }

    @Test
    fun `메일을 받을 수 없는 주소는 이유를 알린다`() = runTest {
        val api = VerifyApi(request = { httpError(409, errorBody("email_not_deliverable")) })
        assertEquals(
            EmailVerifyResult.Failure("이 주소로는 메일을 보낼 수 없음"),
            repository(api).requestEmailVerify(),
        )
    }

    @Test
    fun `404 나 405 면 아직 지원하지 않는다고 알린다`() = runTest {
        listOf(404, 405).forEach { status ->
            val request = VerifyApi(request = { httpError(status) })
            assertEquals(
                EmailVerifyResult.Failure("아직 서버에서 지원하지 않아요"),
                repository(request).requestEmailVerify(),
            )
            val confirm = VerifyApi(confirm = { httpError(status) })
            assertEquals(
                EmailVerifyResult.Failure("아직 서버에서 지원하지 않아요"),
                repository(confirm).confirmEmailVerify("123456"),
            )
        }
    }

    @Test
    fun `429 나 throttled 면 요청이 많다고 알린다`() = runTest {
        val tooMany = VerifyApi(request = { httpError(429, errorBody("throttled")) })
        assertEquals(
            EmailVerifyResult.Failure("요청이 많음. 잠시 뒤 다시 시도"),
            repository(tooMany).requestEmailVerify(),
        )
        val coded = VerifyApi(confirm = { httpError(400, errorBody("throttled")) })
        assertEquals(
            EmailVerifyResult.Failure("요청이 많음. 잠시 뒤 다시 시도"),
            repository(coded).confirmEmailVerify("123456"),
        )
    }

    @Test
    fun `네트워크 오류는 던지지 않고 실패로 돌려준다`() = runTest {
        val api = VerifyApi().apply { throwOnCall = IOException("끊김") }
        assertEquals(
            EmailVerifyResult.Failure(AuthRepository.MESSAGE_NETWORK),
            repository(api).requestEmailVerify(),
        )
        assertEquals(
            EmailVerifyResult.Failure(AuthRepository.MESSAGE_NETWORK),
            repository(api).confirmEmailVerify("123456"),
        )
        api.throwOnCall = IllegalStateException("예상 밖")
        assertEquals(
            EmailVerifyResult.Failure(AuthRepository.MESSAGE_UNKNOWN),
            repository(api).confirmEmailVerify("123456"),
        )
    }

    // --- 코드 확인 --------------------------------------------------------------

    @Test
    fun `확인은 코드의 공백을 자르고 성공하면 인증된 사용자를 준다`() = runTest {
        val user = fromJson<SocialUserResponse>(
            """{"user":{"id":7,"email":"me@snu.ac.kr","nickname":"진호","email_verified":true}}"""
        )
        val api = VerifyApi(confirm = { ok(user) })
        val result = repository(api).confirmEmailVerify(" 123456 ")
        assertEquals("""{"code":"123456"}""", com.google.gson.Gson().toJson(api.confirms.single()))
        val verified = result as EmailVerifyResult.Verified
        assertEquals(true, (verified.user as UserDto).emailVerified)
    }

    @Test
    fun `확인 실패의 각 코드를 문구로 바꾼다`() = runTest {
        suspend fun message(status: Int, code: String): EmailVerifyResult =
            repository(VerifyApi(confirm = { httpError(status, errorBody(code)) })).confirmEmailVerify("111111")
        assertEquals(EmailVerifyResult.Failure("코드가 맞지 않음"), message(400, "invalid_verify_code"))
        assertEquals(EmailVerifyResult.Failure("코드가 만료됨. 다시 받아야 함"), message(400, "verify_code_expired"))
        assertEquals(
            EmailVerifyResult.Failure("시도가 너무 많음. 코드를 다시 받아야 함"),
            message(400, "too_many_attempts"),
        )
        assertEquals(EmailVerifyResult.AlreadyVerified, message(409, "email_already_verified"))
    }

    @Test
    fun `알 수 없는 코드는 서버 문구를 그대로 쓰고 없으면 기본 문구다`() = runTest {
        val withMessage = VerifyApi(confirm = { httpError(400, errorBody("weird", "서버가 준 문구")) })
        assertEquals(EmailVerifyResult.Failure("서버가 준 문구"), repository(withMessage).confirmEmailVerify("1"))
        val bare = VerifyApi(confirm = { httpError(500) })
        val failure = repository(bare).confirmEmailVerify("1")
        assertTrue(failure is EmailVerifyResult.Failure)
        assertEquals("서버에 문제가 생겼다. 잠시 후 다시 시도한다.", (failure as EmailVerifyResult.Failure).message)
    }
}