package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.data.remote.PasswordCheckRequest
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.repository.AuthRepository.AuthResult
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인증 저장소.
 *
 * ## 이 테스트가 지키는 것
 *
 * - **로그인 성공 = 토큰이 저장된 상태**다. 2xx 인데 토큰이 비어 있으면 실패로
 *   돌려야 한다. 성공으로 보내면 화면만 넘어가고 첫 요청이 401 을 받아 "가입했는데
 *   다시 로그인 화면" 이 된다.
 * - 앞 사용자 캐시 정리([AuthRepository] 의 `onBeforeAuthenticated`)는 **토큰 저장
 *   전에** 돈다. 반대면 새 사용자의 첫 캐시와 경쟁한다.
 * - 예외를 밖으로 던지지 않는다. 네트워크 실패는 네트워크 문구로 바뀐다.
 */
class AuthRepositoryTest {

    private val tokenStore = TokenStore(prefsContext())

    private val authBody = fromJson<AuthResponse>(
        """{"access":"a-token","refresh":"r-token",
            "user":{"id":7,"email":"me@snu.ac.kr","nickname":"수연"}}"""
    )

    private fun repository(
        api: FakeAuthApi,
        onBefore: suspend () -> Unit = {},
    ) = AuthRepository(tokenStore = tokenStore, api = api, onBeforeAuthenticated = onBefore)

    // --- 로그인 -------------------------------------------------------------

    @Test
    fun `로그인에 성공하면 토큰과 사용자 정보를 저장한다`() = runTest {
        // Given 서버가 토큰과 사용자를 준다
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(authBody)
        }

        // When
        val result = repository(api).login("me@snu.ac.kr", "pw12345678")

        // Then
        assertTrue(result is AuthResult.Success)
        assertEquals(7L, (result as AuthResult.Success).user.id)
        assertTrue(tokenStore.isLoggedIn)
        assertEquals("a-token", tokenStore.accessToken)
        assertEquals("r-token", tokenStore.refreshToken)
        assertEquals("수연", tokenStore.nickname)
    }

    @Test
    fun `로그인 이메일 앞뒤 공백을 지워서 보낸다`() = runTest {
        var sent: LoginRequest? = null
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(authBody).also { sent = body }
        }

        repository(api).login("  me@snu.ac.kr \n", " pw ")

        assertEquals("me@snu.ac.kr", sent!!.email)
        // 비밀번호는 공백도 비밀번호의 일부다. 손대지 않는다.
        assertEquals(" pw ", sent!!.password)
    }

    @Test
    fun `앞 사용자 정리는 토큰을 저장하기 전에 돈다`() = runTest {
        var loggedInDuringCleanup: Boolean? = null
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(authBody)
        }

        repository(api, onBefore = { loggedInDuringCleanup = tokenStore.isLoggedIn })
            .login("me@snu.ac.kr", "pw")

        assertEquals(false, loggedInDuringCleanup)
        assertTrue(tokenStore.isLoggedIn)
    }

    @Test
    fun `2xx 인데 토큰이 없으면 실패이고 아무것도 저장하지 않는다`() = runTest {
        // 서버 배포 버전이 낮으면 user 만 오고 토큰이 빠진다.
        val partial = fromJson<AuthResponse>(
            """{"user":{"id":7,"email":"me@snu.ac.kr","nickname":"수연"}}"""
        )
        var cleaned = false
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(partial)
        }

        val result = repository(api, onBefore = { cleaned = true }).login("me@snu.ac.kr", "pw")

        assertEquals(AuthResult.Failure(AuthRepository.MESSAGE_NO_TOKEN), result)
        assertFalse(tokenStore.isLoggedIn)
        assertFalse("저장하지 않을 거면 앞 사용자 캐시도 지우지 않는다", cleaned)
    }

    @Test
    fun `공백뿐인 토큰도 없는 것으로 본다`() = runTest {
        val blank = fromJson<AuthResponse>(
            """{"access":"  ","refresh":"r","user":{"id":1,"email":"a@b.c","nickname":"n"}}"""
        )
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(blank)
        }

        val result = repository(api).login("a@b.c", "pw")

        assertEquals(AuthResult.Failure(AuthRepository.MESSAGE_NO_TOKEN), result)
    }

    @Test
    fun `서버 오류 본문의 메시지를 그대로 보여준다`() = runTest {
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) =
                httpError<AuthResponse>(401, errorJson("이메일 또는 비밀번호를 확인한다."))
        }

        val result = repository(api).login("a@b.c", "wrong")

        assertEquals(AuthResult.Failure("이메일 또는 비밀번호를 확인한다."), result)
        assertFalse(tokenStore.isLoggedIn)
    }

    @Test
    fun `오류 본문이 없으면 상태 코드로 문구를 고른다`() = runTest {
        val cases = mapOf(
            400 to "입력값을 확인해야 한다.",
            401 to "이메일 또는 비밀번호가 올바르지 않다.",
            409 to "이미 가입된 이메일이다.",
            503 to "서버에 문제가 생겼다. 잠시 후 다시 시도한다.",
            418 to AuthRepository.MESSAGE_UNKNOWN,
        )
        cases.forEach { (code, expected) ->
            val api = object : FakeAuthApi() {
                override suspend fun login(body: LoginRequest) = httpError<AuthResponse>(code)
            }
            assertEquals("HTTP $code", AuthResult.Failure(expected), repository(api).login("a@b.c", "pw"))
        }
    }

    @Test
    fun `HTML 오류 페이지는 파싱하지 않고 상태 코드 문구로 떨어진다`() = runTest {
        // 프록시·Render 가 깨어나는 중이면 JSON 대신 HTML 이 온다.
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) =
                httpError<AuthResponse>(502, "<html><body>Bad Gateway</body></html>")
        }

        val result = repository(api).login("a@b.c", "pw")

        assertEquals(AuthResult.Failure("서버에 문제가 생겼다. 잠시 후 다시 시도한다."), result)
    }

    @Test
    fun `네트워크가 끊기면 예외 대신 네트워크 문구를 돌려준다`() = runTest {
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest): retrofit2.Response<AuthResponse> =
                throw IOException("unreachable")
        }

        val result = repository(api).login("a@b.c", "pw")

        assertEquals(AuthResult.Failure(AuthRepository.MESSAGE_NETWORK), result)
    }

    // --- 가입 -------------------------------------------------------------

    @Test
    fun `가입은 이메일과 닉네임을 다듬고 약관 동의를 함께 보낸다`() = runTest {
        var sent: RegisterRequest? = null
        val api = object : FakeAuthApi() {
            override suspend fun register(body: RegisterRequest) = ok(authBody).also { sent = body }
        }

        val result = repository(api).register(
            email = " me@snu.ac.kr ",
            nickname = " 수연 ",
            password = "pw12345678",
            passwordConfirm = "pw12345678",
            termsAgreed = true,
        )

        assertTrue(result is AuthResult.Success)
        assertEquals("me@snu.ac.kr", sent!!.email)
        assertEquals("수연", sent!!.nickname)
        assertTrue(sent!!.termsAgreed)
        assertTrue("가입도 성공하면 바로 로그인 상태다", tokenStore.isLoggedIn)
    }

    // --- 비밀번호 검사 ------------------------------------------------------

    @Test
    fun `비밀번호 검사 결과를 그대로 돌려준다`() = runTest {
        val verdict = fromJson<PasswordCheckResponse>(
            """{"min_length":true,"letters_and_digits":false,"messages":["영문과 숫자를 함께 쓴다."]}"""
        )
        val api = object : FakeAuthApi() {
            override suspend fun checkPassword(body: PasswordCheckRequest) = ok(verdict)
        }

        val result = repository(api).checkPassword("abcdefgh")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().valid)
    }

    @Test
    fun `비밀번호 검사 응답에 필드가 빠지면 실패로 본다`() = runTest {
        // 필드가 없는데 성공으로 보내면 화면이 조건을 전부 "통과" 로 칠할 수 있다.
        val partial = fromJson<PasswordCheckResponse>("""{"min_length":true}""")
        val api = object : FakeAuthApi() {
            override suspend fun checkPassword(body: PasswordCheckRequest) = ok(partial)
        }

        assertTrue(repository(api).checkPassword("abcdefgh").isFailure)
    }

    @Test
    fun `비밀번호 검사 중 네트워크가 끊겨도 예외를 던지지 않는다`() = runTest {
        val api = object : FakeAuthApi() {
            override suspend fun checkPassword(
                body: PasswordCheckRequest,
            ): retrofit2.Response<PasswordCheckResponse> = throw IOException("offline")
        }

        val result = repository(api).checkPassword("abcdefgh")

        assertEquals(AuthRepository.MESSAGE_NETWORK, result.exceptionOrNull()?.message)
    }

    // --- 로그아웃 -----------------------------------------------------------

    @Test
    fun `로그아웃하면 토큰과 사용자 정보가 모두 지워진다`() = runTest {
        val api = object : FakeAuthApi() {
            override suspend fun login(body: LoginRequest) = ok(authBody)
        }
        val repository = repository(api)
        repository.login("me@snu.ac.kr", "pw")

        repository.logout()

        assertFalse(tokenStore.isLoggedIn)
        assertNull(tokenStore.email)
        assertNull(tokenStore.nickname)
    }
}
