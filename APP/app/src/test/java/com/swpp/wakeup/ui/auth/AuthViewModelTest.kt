package com.swpp.wakeup.ui.auth

// Partly AI-generated (Claude)

import android.app.Application
import com.swpp.wakeup.data.local.LastEmailStore
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.data.remote.PasswordCheckRequest
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.PasswordResetConfirmRequest
import com.swpp.wakeup.data.remote.PasswordResetRequest
import com.swpp.wakeup.data.remote.PasswordResetResponse
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.remote.SocialLoginRequest
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.repository.AuthRepository
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import com.swpp.wakeup.ui.auth.AuthViewModel.Screen
import com.swpp.wakeup.ui.auth.AuthViewModel.SystemBack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * 로그인 · 가입 · 비밀번호 재설정 · 소셜 첫 가입 화면의 상태 전이.
 *
 * 서버는 [FakeAuthApi] 로, 서버 깨우기는 바로 돌아오는 가짜로 바꾼다. 시계는 테스트 스케줄러의 가상
 * 시간에 묶어 잠금 카운트다운이 실제 시간을 기다리지 않게 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    /** 저장소가 잠금 시각을 실제 시계(+retry_after)로 계산하므로 가상 시계도 지금에서 시작한다 */
    private val base = System.currentTimeMillis()

    /** 서버 응답을 테스트마다 바꿔 끼우는 가짜 */
    private inner class Api : FakeAuthApi() {
        var onLogin: (LoginRequest) -> Response<AuthResponse> = { error("login 응답을 정하지 않았다") }
        var onRegister: (RegisterRequest) -> Response<AuthResponse> = { error("register 응답을 정하지 않았다") }
        var onCheck: (PasswordCheckRequest) -> Response<PasswordCheckResponse> = { ok(validPassword) }
        var onResetRequest: (PasswordResetRequest) -> Response<PasswordResetResponse> =
            { ok(PasswordResetResponse(sent = true, expiresIn = 600)) }
        var onResetConfirm: (PasswordResetConfirmRequest) -> Response<AuthResponse> = { ok(authOk()) }
        var onSocial: (String, SocialLoginRequest) -> Response<AuthResponse> = { _, _ -> ok(authOk()) }
        val socialCalls = mutableListOf<SocialLoginRequest>()
        var registered: RegisterRequest? = null
        var confirmed: PasswordResetConfirmRequest? = null
        var resetEmails = mutableListOf<String>()

        override suspend fun login(body: LoginRequest) = onLogin(body)
        override suspend fun register(body: RegisterRequest): Response<AuthResponse> {
            registered = body
            return onRegister(body)
        }
        override suspend fun checkPassword(body: PasswordCheckRequest) = onCheck(body)
        override suspend fun requestPasswordReset(body: PasswordResetRequest): Response<PasswordResetResponse> {
            resetEmails += body.email
            return onResetRequest(body)
        }
        override suspend fun confirmPasswordReset(body: PasswordResetConfirmRequest): Response<AuthResponse> {
            confirmed = body
            return onResetConfirm(body)
        }
        override suspend fun socialLogin(provider: String, body: SocialLoginRequest): Response<AuthResponse> {
            socialCalls += body
            return onSocial(provider, body)
        }
    }

    private class CountingWarmup(private val wait: Long = 0) : AuthViewModel.Warmup {
        var awakened = 0
        var invalidated = 0
        override suspend fun ensureAwake() {
            awakened++
            if (wait > 0) delay(wait)
        }
        override fun invalidate() {
            invalidated++
        }
    }

    private val validPassword = PasswordCheckResponse(
        minLength = true,
        lettersAndDigits = true,
        messages = emptyList(),
    )

    private fun authOk(email: String = "me@snu.ac.kr", nickname: String = "수연") = AuthResponse(
        access = "access",
        refresh = "refresh",
        user = UserDto(id = 1, email = email, nickname = nickname),
    )

    private val api = Api()
    private val prefs = InMemoryPrefs()
    private val lockPrefs = InMemoryPrefs()
    private val context = prefsContext(prefs)
    private val tokenStore = TokenStore(context, FakeTokenCipher())
    private val lastEmail = LastEmailStore(context)
    private var warmup = CountingWarmup()

    private fun TestScope.viewModel(): AuthViewModel = AuthViewModel(
        application = Application(),
        repository = AuthRepository(tokenStore = tokenStore, api = api),
        lastEmailStore = lastEmail,
        lockPrefs = lockPrefs,
        warmup = warmup,
        clock = { base + testScheduler.currentTime },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // --- 처음 상태 · 입력 ----------------------------------------------------------

    @Test
    fun `처음에는 로그인 화면이고 마지막 이메일을 채운다`() = test {
        lastEmail.save("me@snu.ac.kr")

        val state = viewModel().state.value

        assertEquals(Screen.LOGIN, state.screen)
        assertEquals("me@snu.ac.kr", state.email)
        assertEquals(SystemBack.SYSTEM, state.systemBack)
        assertFalse(state.canSubmitLogin)
    }

    @Test
    fun `이메일과 비밀번호를 모두 적어야 로그인할 수 있다`() = test {
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        assertFalse(vm.state.value.canSubmitLogin)

        vm.onPasswordChange("pw12345678")
        assertTrue(vm.state.value.canSubmitLogin)
    }

    @Test
    fun `로그인 화면에서는 비밀번호 조건을 서버에 묻지 않는다`() = test {
        api.onCheck = { error("로그인 화면에서 검사하면 안 된다") }
        val vm = viewModel()

        vm.onPasswordChange("pw")
        advanceUntilIdle()

        assertNull(vm.state.value.passwordCheck)
    }

    // --- 로그인 -------------------------------------------------------------------

    @Test
    fun `로그인 성공 - 토큰을 저장하고 이메일을 기억하고 다음 화면으로 넘긴다`() = test {
        api.onLogin = { ok(authOk(email = "Me@snu.ac.kr")) }
        val vm = viewModel()
        vm.onEmailChange(" me@snu.ac.kr ")
        vm.onPasswordChange("pw12345678")

        vm.login()
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("수연", state.authenticatedNickname)
        assertEquals(1, warmup.awakened)
        assertTrue(tokenStore.isLoggedIn)
        assertEquals("Me@snu.ac.kr", lastEmail.email)

        vm.consumeAuthenticated()
        assertNull(vm.state.value.authenticatedNickname)
    }

    @Test
    fun `로그인 실패 - 서버 문구를 보이고 깨우기 기록을 지운다`() = test {
        api.onLogin = {
            httpError(401, """{"error":{"code":"authentication_failed","message":"이메일 또는 비밀번호가 맞지 않아요.","details":{"remaining_attempts":3}}}""")
        }
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.onPasswordChange("wrong")

        vm.login()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("이메일 또는 비밀번호가 맞지 않아요.", state.error)
        assertEquals("3번 더 틀리면 15분 동안 잠겨요", state.attemptsHint)
        assertEquals(1, warmup.invalidated)
        assertFalse(tokenStore.isLoggedIn)
    }

    @Test
    fun `남은 횟수 안내와 오류는 입력을 고치면 지운다`() = test {
        api.onLogin = { httpError(401, """{"error":{"message":"틀렸어요","details":{"remaining_attempts":2}}}""") }
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.onPasswordChange("wrong")
        vm.login()
        advanceUntilIdle()
        assertNotNull(vm.state.value.attemptsHint)

        vm.onPasswordChange("wrong2")

        assertNull(vm.state.value.attemptsHint)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `서버가 늦으면 깨우는 중이라고 알리고 끝나면 내린다`() = test {
        warmup = CountingWarmup(wait = 5_000)
        api.onLogin = { ok(authOk()) }
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.onPasswordChange("pw12345678")

        vm.login()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(vm.state.value.waking)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(vm.state.value.waking)

        advanceUntilIdle()
        assertFalse(vm.state.value.waking)
    }

    @Test
    fun `423 잠금 - 카운트다운 동안 그 계정은 로그인할 수 없고 시각이 지나면 풀린다`() = test {
        api.onLogin = {
            httpError(423, """{"error":{"code":"account_locked","message":"잠겼어요","details":{"retry_after_seconds":60}}}""")
        }
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.onPasswordChange("wrong")

        vm.login()
        runCurrent()

        val locked = vm.state.value
        assertNull("잠겼으면 서버 문구 대신 카운트다운 카드를 보인다", locked.error)
        assertEquals("me@snu.ac.kr", locked.lockedEmail)
        assertTrue(locked.locked)
        assertFalse(locked.canSubmitLogin)
        assertEquals("me@snu.ac.kr", lockPrefs.getString("email", null))

        // 다른 계정은 막지 않는다
        vm.onEmailChange("other@snu.ac.kr")
        assertFalse(vm.state.value.locked)
        assertTrue(vm.state.value.canSubmitLogin)
        vm.onEmailChange(" ME@snu.ac.kr")
        assertTrue(vm.state.value.locked)

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(vm.state.value.locked)
        assertTrue(vm.state.value.nowMillis > locked.nowMillis)

        advanceUntilIdle()
        assertFalse(vm.state.value.locked)
        assertNull(vm.state.value.lockedUntilMillis)
        assertNull(lockPrefs.getString("email", null))
    }

    @Test
    fun `남은 잠금은 화면을 다시 열어도 이어진다`() = test {
        lockPrefs.edit().putString("email", "me@snu.ac.kr").putLong("until", base + 120_000).apply()

        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")

        assertTrue(vm.state.value.locked)
        assertEquals(base + 120_000, vm.state.value.lockedUntilMillis)
        advanceUntilIdle()
        assertFalse(vm.state.value.locked)
    }

    @Test
    fun `이미 지난 잠금 기록은 버린다`() = test {
        lockPrefs.edit().putString("email", "me@snu.ac.kr").putLong("until", base - 1).apply()

        val vm = viewModel()

        assertNull(vm.state.value.lockedUntilMillis)
        assertNull(lockPrefs.getString("email", null))
    }

    // --- 화면 이동 · 시스템 뒤로 ------------------------------------------------------

    @Test
    fun `가입으로 갔다가 돌아오면 이메일만 남고 나머지는 비운다`() = test {
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.onPasswordChange("pw")

        vm.goToSignup()
        assertEquals(Screen.SIGNUP, vm.state.value.screen)
        assertEquals("me@snu.ac.kr", vm.state.value.email)
        assertEquals("", vm.state.value.password)
        assertEquals(SystemBack.TO_LOGIN, vm.state.value.systemBack)

        vm.onNicknameChange("수연")
        vm.goToLogin()
        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertEquals("", vm.state.value.nickname)
    }

    @Test
    fun `시스템 뒤로 - 로그인 화면에서는 아무것도 하지 않는다`() = test {
        val vm = viewModel()
        vm.onSystemBack()
        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertFalse(vm.state.value.handlesSystemBack)
    }

    @Test
    fun `시스템 뒤로 - 가입 화면에서 로그인으로 돌아간다`() = test {
        val vm = viewModel()
        vm.goToSignup()
        assertTrue(vm.state.value.handlesSystemBack)

        vm.onSystemBack()

        assertEquals(Screen.LOGIN, vm.state.value.screen)
    }

    @Test
    fun `시스템 뒤로 - 코드 확인에서 코드 요청으로 한 칸 돌아간다`() = test {
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.goToResetRequest()
        vm.requestReset()
        advanceUntilIdle()
        assertEquals(Screen.RESET_CONFIRM, vm.state.value.screen)
        assertEquals(SystemBack.TO_RESET_REQUEST, vm.state.value.systemBack)

        vm.onSystemBack()
        assertEquals(Screen.RESET_REQUEST, vm.state.value.screen)

        vm.onSystemBack()
        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertEquals("me@snu.ac.kr", vm.state.value.email)
    }

    @Test
    fun `시스템 뒤로 - 서버를 기다리는 동안은 받기만 하고 움직이지 않는다`() = test {
        warmup = CountingWarmup(wait = 10_000)
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.goToResetRequest()
        vm.requestReset()
        runCurrent()
        assertEquals(SystemBack.STAY, vm.state.value.systemBack)
        assertTrue(vm.state.value.handlesSystemBack)

        vm.onSystemBack()
        vm.goBackFromReset()
        assertEquals(Screen.RESET_REQUEST, vm.state.value.screen)

        advanceUntilIdle()
        assertEquals(Screen.RESET_CONFIRM, vm.state.value.screen)
    }

    @Test
    fun `시스템 뒤로 - 소셜 첫 가입에서는 토큰을 버리고 로그인으로`() = test {
        api.onSocial = { _, _ -> httpError(400, """{"error":{"code":"social_terms_required","message":"약관"}}""") }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()
        assertEquals(Screen.SOCIAL_TERMS, vm.state.value.screen)

        vm.onSystemBack()

        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertNull(vm.state.value.socialTerms)
    }

    // --- 가입 ---------------------------------------------------------------------

    private fun TestScope.filledSignup(): AuthViewModel {
        val vm = viewModel()
        vm.goToSignup()
        vm.onEmailChange("new@snu.ac.kr")
        vm.onNicknameChange("새사람")
        vm.onPasswordChange("abc12345")
        vm.onPasswordConfirmChange("abc12345")
        vm.onTermsAgreedChange(true)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `가입 화면은 비밀번호 조건을 서버에 묻고 결과를 붙인다`() = test {
        val vm = filledSignup()

        assertEquals(true, vm.state.value.passwordCheck?.valid)
        assertTrue(vm.state.value.canSubmitSignup)
    }

    @Test
    fun `비밀번호 조건 검사가 실패하면 이유를 붙이고 가입을 막는다`() = test {
        api.onCheck = { httpError(500) }
        val vm = filledSignup()

        assertNull(vm.state.value.passwordCheck)
        assertNotNull(vm.state.value.passwordCheckError)
        assertFalse(vm.state.value.canSubmitSignup)
    }

    @Test
    fun `늦게 온 비밀번호 검사 결과는 지금 입력과 다르면 버린다`() = test {
        val asked = mutableListOf<String>()
        api.onCheck = { asked += it.password; ok(if (it.password == "abc12345") validPassword else validPassword.copy(minLength = false)) }
        val vm = viewModel()
        vm.goToSignup()
        vm.onPasswordChange("abc")
        vm.onPasswordChange("abc12345")
        advanceUntilIdle()

        assertEquals(listOf("abc12345"), asked)
        assertEquals(true, vm.state.value.passwordCheck?.valid)
    }

    @Test
    fun `확인 칸이 다르면 적는 동안 바로 빨갛게 보이고 가입을 막는다`() = test {
        val vm = filledSignup()
        vm.onPasswordConfirmChange("abc1234")

        assertTrue(vm.state.value.passwordConfirmMismatch)
        assertFalse(vm.state.value.passwordsMatch)
        assertFalse(vm.state.value.canSubmitSignup)
    }

    @Test
    fun `약관에 동의하지 않으면 가입할 수 없다`() = test {
        val vm = filledSignup()
        vm.onTermsAgreedChange(false)

        vm.signup()
        assertFalse(vm.state.value.loading)
        assertNull(api.registered)
    }

    @Test
    fun `가입 성공 - 입력값을 보내고 다음 화면으로 넘긴다`() = test {
        api.onRegister = { ok(authOk(email = "new@snu.ac.kr", nickname = "새사람")) }
        val vm = filledSignup()

        vm.signup()
        advanceUntilIdle()

        assertEquals("새사람", vm.state.value.authenticatedNickname)
        assertEquals(true, api.registered?.termsAgreed)
        assertEquals("new@snu.ac.kr", api.registered?.email)
        assertEquals("new@snu.ac.kr", lastEmail.email)
    }

    @Test
    fun `가입 400 의 닉네임 이유는 칸 아래와 카드에 함께 붙고 닉네임을 고치면 지운다`() = test {
        api.onRegister = {
            httpError(400, """{"error":{"code":"validation_error","message":"입력을 확인해 주세요","details":{"nickname":"이미 쓰는 닉네임이에요"}}}""")
        }
        val vm = filledSignup()

        vm.signup()
        advanceUntilIdle()

        assertEquals("이미 쓰는 닉네임이에요", vm.state.value.nicknameError)
        assertEquals("가입하지 못했어요. 이미 쓰는 닉네임이에요", vm.state.value.error)

        vm.onNicknameChange("다른사람")
        assertNull(vm.state.value.nicknameError)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `가입 실패에 칸 이유가 없으면 서버 문구를 그대로 보인다`() = test {
        api.onRegister = { httpError(400, """{"error":{"message":"이미 가입한 이메일이에요","details":{}}}""") }
        val vm = filledSignup()

        vm.signup()
        advanceUntilIdle()

        assertEquals("이미 가입한 이메일이에요", vm.state.value.error)
        assertNull(vm.state.value.nicknameError)
    }

    // --- 비밀번호 재설정 ------------------------------------------------------------

    @Test
    fun `코드 받기는 이메일 형식이 맞아야 보낸다`() = test {
        val vm = viewModel()
        vm.goToResetRequest()
        vm.onEmailChange("not-an-email")

        vm.requestReset()
        advanceUntilIdle()

        assertTrue(api.resetEmails.isEmpty())
        assertEquals(Screen.RESET_REQUEST, vm.state.value.screen)
    }

    @Test
    fun `코드를 보내면 확인 화면으로 가고 보낸 주소를 기억한다`() = test {
        val vm = viewModel()
        vm.goToResetRequest()
        vm.onEmailChange(" me@snu.ac.kr ")

        vm.requestReset()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(Screen.RESET_CONFIRM, state.screen)
        assertEquals("me@snu.ac.kr", state.resetSentTo)
        assertNull(state.resetInfo)
        assertEquals(listOf("me@snu.ac.kr"), api.resetEmails)
    }

    @Test
    fun `코드 요청이 실패하면 화면을 두고 문구를 보인다`() = test {
        api.onResetRequest = { httpError(429, """{"error":{"code":"throttled","message":"잠시 뒤에"}}""") }
        val vm = viewModel()
        vm.goToResetRequest()
        vm.onEmailChange("me@snu.ac.kr")

        vm.requestReset()
        advanceUntilIdle()

        assertEquals(Screen.RESET_REQUEST, vm.state.value.screen)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertEquals(1, warmup.invalidated)
    }

    @Test
    fun `코드 다시 받기는 같은 주소로 보내고 적던 코드를 비운다`() = test {
        val vm = viewModel()
        vm.goToResetRequest()
        vm.onEmailChange("me@snu.ac.kr")
        vm.requestReset()
        advanceUntilIdle()
        vm.onResetCodeChange("12")

        vm.resendReset()
        advanceUntilIdle()

        assertEquals("코드를 다시 보냄", vm.state.value.resetInfo)
        assertEquals("", vm.state.value.resetCode)
        assertEquals(listOf("me@snu.ac.kr", "me@snu.ac.kr"), api.resetEmails)
    }

    @Test
    fun `보낸 적이 없으면 다시 받기는 아무것도 하지 않는다`() = test {
        val vm = viewModel()
        vm.resendReset()
        advanceUntilIdle()
        assertTrue(api.resetEmails.isEmpty())
    }

    @Test
    fun `코드 칸은 숫자 여섯 자리만 받는다`() = test {
        val vm = viewModel()
        vm.onResetCodeChange("12a3-4567 89")
        assertEquals("123456", vm.state.value.resetCode)
    }

    @Test
    fun `새 비밀번호로 바꾸면 로그인과 같이 다음 화면으로 넘긴다`() = test {
        val vm = viewModel()
        vm.goToResetRequest()
        vm.onEmailChange("me@snu.ac.kr")
        vm.requestReset()
        advanceUntilIdle()
        vm.onResetCodeChange("123456")
        vm.onPasswordChange("new12345")
        vm.onPasswordConfirmChange("new12345")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSubmitResetConfirm)

        vm.confirmReset()
        advanceUntilIdle()

        assertEquals("수연", vm.state.value.authenticatedNickname)
        assertEquals("123456", api.confirmed?.resetCode)
        assertEquals("me@snu.ac.kr", api.confirmed?.email)
    }

    @Test
    fun `코드가 모자라면 바꾸지 않는다`() = test {
        val vm = viewModel()
        vm.onResetCodeChange("123")
        vm.confirmReset()
        advanceUntilIdle()
        assertNull(api.confirmed)
    }

    // --- 소셜 ---------------------------------------------------------------------

    @Test
    fun `소셜 버튼은 다른 요청이 진행 중이면 SDK 를 띄우지 않는다`() = test {
        val vm = viewModel()
        assertTrue(vm.beginSocial(SocialProvider.GOOGLE))
        assertEquals(SocialProvider.GOOGLE, vm.state.value.socialBusy)
        assertTrue(vm.state.value.busy)

        assertFalse(vm.beginSocial(SocialProvider.KAKAO))
    }

    @Test
    fun `소셜 취소는 버튼만 되돌리고 실패는 문구를 보인다`() = test {
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Cancelled)
        assertNull(vm.state.value.socialBusy)
        assertNull(vm.state.value.error)

        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Failed("카카오 실패"))
        assertNull(vm.state.value.socialBusy)
        assertEquals("카카오 실패", vm.state.value.error)
    }

    @Test
    fun `설정되지 않은 소셜은 쓸 수 없다는 카드를 띄운다`() = test {
        val vm = viewModel()
        vm.beginSocial(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.NotConfigured)

        assertNotNull(vm.state.value.socialNotice)
        assertNull(vm.state.value.socialBusy)
    }

    @Test
    fun `소셜 로그인 성공은 다음 화면으로 넘긴다`() = test {
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()

        assertEquals("수연", vm.state.value.authenticatedNickname)
        assertNull(vm.state.value.socialBusy)
        assertNull(api.socialCalls.single().termsAgreed)
    }

    @Test
    fun `처음 가입이면 약관 화면으로 가고 동의해야 같은 토큰으로 다시 보낸다`() = test {
        api.onSocial = { _, body ->
            if (body.termsAgreed == true) ok(authOk(nickname = "카카오사람"))
            else httpError(400, """{"error":{"code":"social_terms_required","message":"약관"}}""")
        }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("tok-1"))
        advanceUntilIdle()

        val terms = vm.state.value
        assertEquals(Screen.SOCIAL_TERMS, terms.screen)
        assertEquals("tok-1", terms.socialTerms?.idToken)
        assertFalse(terms.canAgreeSocialTerms)

        vm.agreeSocialTerms()
        assertEquals(1, api.socialCalls.size)

        vm.onSocialNicknameChange("카".repeat(30))
        assertEquals(20, vm.state.value.socialTerms?.nickname?.length)
        vm.onSocialNicknameChange("카카오사람")
        vm.onSocialTermsAgreedChange(true)
        assertTrue(vm.state.value.canAgreeSocialTerms)

        vm.agreeSocialTerms()
        assertEquals(SocialProvider.KAKAO, vm.state.value.socialBusy)
        advanceUntilIdle()

        assertEquals("카카오사람", vm.state.value.authenticatedNickname)
        val retry = api.socialCalls.last()
        assertEquals("tok-1", retry.idToken)
        assertEquals(true, retry.termsAgreed)
        assertEquals("카카오사람", retry.nickname)
    }

    @Test
    fun `동의하고 보냈는데 또 약관을 요구하면 다시 시도하라고 한다`() = test {
        api.onSocial = { _, _ -> httpError(400, """{"error":{"code":"social_terms_required","message":"약관"}}""") }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()
        vm.onSocialTermsAgreedChange(true)

        vm.agreeSocialTerms()
        advanceUntilIdle()

        assertEquals(Screen.SOCIAL_TERMS, vm.state.value.screen)
        assertEquals("약관 동의를 확인하지 못했어요. 다시 시도해 주세요.", vm.state.value.error)
        assertEquals("tok", vm.state.value.socialTerms?.idToken)
        assertTrue("동의 표시는 남는다", vm.state.value.socialTerms?.agreed == true)
    }

    @Test
    fun `이미 이메일로 가입한 주소면 로그인 화면에 안내 카드를 띄운다`() = test {
        api.onSocial = { _, _ ->
            httpError(400, """{"error":{"code":"social_email_in_use","message":"이미 가입","details":{"login_methods":["email"]}}}""")
        }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()

        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertNotNull(vm.state.value.socialNotice)
        assertNull(vm.state.value.socialBusy)

        vm.onEmailChange("me@snu.ac.kr")
        assertNull("이메일을 고치면 카드를 내린다", vm.state.value.socialNotice)
    }

    @Test
    fun `서버에 소셜 설정이 없으면 쓸 수 없다는 카드를 띄운다`() = test {
        api.onSocial = { _, _ -> httpError(503, """{"error":{"code":"social_not_configured","message":"없음"}}""") }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()

        assertNotNull(vm.state.value.socialNotice)
        assertEquals(Screen.LOGIN, vm.state.value.screen)
    }

    @Test
    fun `소셜 실패는 문구를 보이고 깨우기 기록을 지운다`() = test {
        api.onSocial = { _, _ -> httpError(401, """{"error":{"code":"social_token_invalid","message":"x"}}""") }
        val vm = viewModel()
        vm.beginSocial(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("tok"))
        advanceUntilIdle()

        assertEquals("카카오 로그인을 확인하지 못했어요. 다시 시도해 주세요.", vm.state.value.error)
        assertNull(vm.state.value.socialBusy)
        assertEquals(1, warmup.invalidated)
    }

    @Test
    fun `소셜 처음 가입을 그만두면 로그인 화면으로 돌아간다`() = test {
        val vm = viewModel()
        vm.onEmailChange("me@snu.ac.kr")
        vm.cancelSocialTerms()
        assertEquals(Screen.LOGIN, vm.state.value.screen)
        assertEquals("me@snu.ac.kr", vm.state.value.email)
    }
}
