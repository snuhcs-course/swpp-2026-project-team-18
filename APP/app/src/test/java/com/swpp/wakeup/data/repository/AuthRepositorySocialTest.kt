// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.SocialLinkRequest
import com.swpp.wakeup.data.remote.SocialLoginRequest
import com.swpp.wakeup.data.remote.SocialUserResponse
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.repository.AuthRepository.AccountResult
import com.swpp.wakeup.data.repository.AuthRepository.SocialResult
import com.swpp.wakeup.domain.model.SocialProvider
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** 카카오·구글 로그인과 설정의 연결·끊기(docs/iter3/social-login-api.md §2·§3). */
class AuthRepositorySocialTest {

    private val tokenStore = TokenStore(prefsContext(), FakeTokenCipher())

    private val authBody = fromJson<AuthResponse>(
        """{"user_id":7,"created":true,"access":"a-token","refresh":"r-token",
            "user":{"id":7,"email":"me@kakao.com","nickname":"수연","login_methods":["kakao"]}}"""
    )

    private fun error(code: Int, errorCode: String, details: String = "{}") =
        """{"error":{"code":"$errorCode","message":"서버 문구","details":$details}}""".let { httpError<AuthResponse>(code, it) }

    private class SocialApi(private val respond: (SocialLoginRequest) -> Response<AuthResponse>) : FakeAuthApi() {
        val sent = mutableListOf<Pair<String, SocialLoginRequest>>()
        override suspend fun socialLogin(provider: String, body: SocialLoginRequest): Response<AuthResponse> {
            sent += provider to body
            return respond(body)
        }
    }

    private fun repository(api: FakeAuthApi, onBefore: suspend () -> Unit = {}) =
        AuthRepository(tokenStore = tokenStore, api = api, onBeforeAuthenticated = onBefore)

    // --- 로그인 -----------------------------------------------------------------

    @Test
    fun `연결된 계정이면 토큰을 저장하고 로그인한다`() = runTest {
        var cleaned = false
        val api = SocialApi { ok(authBody) }
        val result = repository(api, onBefore = { cleaned = !tokenStore.isLoggedIn })
            .socialLogin(SocialProvider.KAKAO, "kakao-id-token")

        assertTrue("성공이어야 한다: $result", result is SocialResult.Success)
        assertEquals(listOf("kakao"), (result as SocialResult.Success).user.loginMethods)
        assertTrue(tokenStore.isLoggedIn)
        assertEquals("a-token", tokenStore.accessToken)
        assertEquals("r-token", tokenStore.refreshToken)
        assertTrue("앞 사용자 정리는 저장 전에 돈다", cleaned)
        assertEquals("kakao", api.sent.single().first)
        assertEquals(SocialLoginRequest(idToken = "kakao-id-token"), api.sent.single().second)
    }

    @Test
    fun `동의는 동의했을 때만 보내고 닉네임은 다듬어 보낸다`() = runTest {
        val api = SocialApi { ok(authBody) }
        repository(api).socialLogin(SocialProvider.GOOGLE, "g", termsAgreed = true, nickname = "  진호  ")
        repository(api).socialLogin(SocialProvider.GOOGLE, "g", termsAgreed = true, nickname = "   ")
        repository(api).socialLogin(SocialProvider.GOOGLE, "g", termsAgreed = true, nickname = "가".repeat(30))

        assertEquals(SocialLoginRequest("g", termsAgreed = true, nickname = "진호"), api.sent[0].second)
        assertEquals("빈 닉네임은 빼서 서버가 토큰의 이름을 쓰게 한다", null, api.sent[1].second.nickname)
        assertEquals("가".repeat(20), api.sent[2].second.nickname)
        assertEquals("google", api.sent[0].first)
    }

    @Test
    fun `처음이면 약관 동의를 요구하고 아무것도 저장하지 않는다`() = runTest {
        val result = repository(SocialApi { error(400, "social_terms_required") })
            .socialLogin(SocialProvider.KAKAO, "t")
        assertEquals(SocialResult.TermsRequired, result)
        assertFalse(tokenStore.isLoggedIn)
    }

    @Test
    fun `같은 이메일의 계정이 있으면 원래 로그인 방법을 알려 준다`() = runTest {
        val result = repository(
            SocialApi { error(409, "social_email_in_use", """{"login_methods":["password"," ",3]}""") }
        ).socialLogin(SocialProvider.KAKAO, "t")
        assertEquals(SocialResult.EmailInUse(listOf("password")), result)
        assertFalse(tokenStore.isLoggedIn)
    }

    @Test
    fun `서버에 키가 없으면 쓸 수 없다고 한다`() = runTest {
        assertEquals(
            SocialResult.Unavailable,
            repository(SocialApi { error(503, "social_not_configured") }).socialLogin(SocialProvider.GOOGLE, "t"),
        )
    }

    @Test
    fun `토큰이 틀렸거나 연결이 끊기면 실패를 말한다`() = runTest {
        val invalid = repository(SocialApi { error(401, "social_token_invalid") })
            .socialLogin(SocialProvider.KAKAO, "t")
        assertEquals(SocialResult.Failure("카카오 로그인을 확인하지 못했다. 다시 시도한다."), invalid)

        val offline = repository(SocialApi { throw IOException("비행기 모드") })
            .socialLogin(SocialProvider.KAKAO, "t")
        assertEquals(SocialResult.Failure(AuthRepository.MESSAGE_NETWORK), offline)

        val other = repository(SocialApi { error(429, "throttled") }).socialLogin(SocialProvider.KAKAO, "t")
        assertEquals("모르는 실패는 서버 문구", SocialResult.Failure("서버 문구"), other)
    }

    @Test
    fun `2xx 인데 토큰이 없으면 실패다`() = runTest {
        val partial = fromJson<AuthResponse>("""{"user":{"id":7,"email":"a@b.c","nickname":"n"}}""")
        val result = repository(SocialApi { ok(partial) }).socialLogin(SocialProvider.KAKAO, "t")
        assertEquals(SocialResult.Failure(AuthRepository.MESSAGE_NO_TOKEN), result)
        assertFalse(tokenStore.isLoggedIn)
    }

    // --- 연결·끊기 ----------------------------------------------------------------

    private val linkedUser = fromJson<UserDto>(
        """{"id":7,"email":"me@snu.ac.kr","nickname":"수연","login_methods":["password","kakao"]}"""
    )

    private fun linkError(code: Int, errorCode: String) =
        httpError<SocialUserResponse>(code, """{"error":{"code":"$errorCode","message":"m","details":{}}}""")

    @Test
    fun `연결하면 서버가 준 로그인 방법을 돌려준다`() = runTest {
        var sent: Pair<String, SocialLinkRequest>? = null
        val api = object : FakeAuthApi() {
            override suspend fun linkSocial(provider: String, body: SocialLinkRequest) =
                ok(SocialUserResponse(linkedUser)).also { sent = provider to body }
        }
        val result = repository(api).linkSocial(SocialProvider.KAKAO, "tok")
        assertEquals(AccountResult.Success(linkedUser), result)
        assertEquals("kakao" to SocialLinkRequest("tok"), sent)
        assertFalse("연결은 로그인 토큰을 바꾸지 않는다", tokenStore.isLoggedIn)
    }

    @Test
    fun `연결 실패 이유를 말한다`() = runTest {
        fun api(code: Int, errorCode: String) = object : FakeAuthApi() {
            override suspend fun linkSocial(provider: String, body: SocialLinkRequest) = linkError(code, errorCode)
        }
        assertEquals(
            AccountResult.Failure("이 카카오 계정은 다른 JustInTime 계정에 연결돼 있음"),
            repository(api(409, "social_already_linked")).linkSocial(SocialProvider.KAKAO, "t"),
        )
        assertEquals(
            AccountResult.Failure("이미 다른 Google 계정이 연결돼 있음. 먼저 끊어야 함"),
            repository(api(409, "social_provider_linked")).linkSocial(SocialProvider.GOOGLE, "t"),
        )
    }

    @Test
    fun `마지막 로그인 방법은 끊지 못하고 없는 연결은 그렇게 말한다`() = runTest {
        fun api(code: Int, errorCode: String) = object : FakeAuthApi() {
            override suspend fun unlinkSocial(provider: String) = linkError(code, errorCode)
        }
        assertEquals(
            AccountResult.Failure("유일한 로그인 방법이라 끊을 수 없음"),
            repository(api(400, "social_last_login_method")).unlinkSocial(SocialProvider.KAKAO),
        )
        val missing = object : FakeAuthApi() {
            override suspend fun unlinkSocial(provider: String) = httpError<SocialUserResponse>(404)
        }
        assertEquals(
            AccountResult.Failure("연결된 카카오 계정이 없음"),
            repository(missing).unlinkSocial(SocialProvider.KAKAO),
        )
    }

    @Test
    fun `내 계정의 로그인 방법을 받는다`() = runTest {
        val api = object : FakeAuthApi() {
            override suspend fun currentUser() = ok(linkedUser)
        }
        assertEquals(AccountResult.Success(linkedUser), repository(api).currentUser())

        val old = object : FakeAuthApi() {
            override suspend fun currentUser() = ok(fromJson<UserDto>("""{"id":1,"email":"a@b.c","nickname":"n"}"""))
        }
        val result = repository(old).currentUser() as AccountResult.Success
        assertNull("구버전 서버는 login_methods 가 없다", result.user.loginMethods)
    }

    @Test
    fun `로그인 방법 칸은 문자열만 남긴다`() {
        assertEquals(listOf("password", "kakao"), loginMethodsOf(mapOf("login_methods" to listOf("password", 1, " kakao "))))
        assertEquals(emptyList<String>(), loginMethodsOf(mapOf("login_methods" to "password")))
        assertEquals(emptyList<String>(), loginMethodsOf(null))
    }
}
