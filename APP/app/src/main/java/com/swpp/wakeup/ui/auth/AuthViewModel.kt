package com.swpp.wakeup.ui.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.data.local.LastEmailStore
import com.swpp.wakeup.data.local.LocalStores
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ServerWarmup
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.repository.AuthRepository
import com.swpp.wakeup.domain.model.EmailRule
import com.swpp.wakeup.domain.model.NicknameRule
import com.swpp.wakeup.domain.model.SocialLogin
import com.swpp.wakeup.domain.model.takeCodePoints
import com.swpp.wakeup.domain.model.SocialNotice
import com.swpp.wakeup.domain.model.SocialProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 로그인·회원가입 화면 상태.
 *
 * [AndroidViewModel] 을 쓰는 이유는 [TokenStore] 가 Context 를 필요로 하기
 * 때문이다. Hilt 를 넣으면 생성자 주입으로 바꾼다(FE-P0-03 미완 항목).
 */
class AuthViewModel internal constructor(
    application: Application,
    private val repository: AuthRepository,
    /** 마지막으로 이메일 로그인한 주소(기기 단위, 로그아웃해도 남는다). 다음 로그인 칸을 채운다 */
    private val lastEmailStore: LastEmailStore,
    /** 로그인 잠금이 풀리는 시각을 남기는 곳(BE-A3). 화면을 나갔다 들어와도 남은 시간이 맞는다 */
    private val lockPrefs: SharedPreferences,
    private val warmup: Warmup = Warmup.Server,
    /** 잠금 카운트다운의 시계. 테스트가 바꿔 끼운다 */
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    /**
     * 앱이 쓰는 생성자(`viewModel()` 의 기본 팩토리가 찾는다). 저장소와 서버 깨우기를 여기서 만든다.
     * 위 생성자는 JVM 테스트(`AuthViewModelTest`)가 가짜를 넣는 자리다. (Partly AI-generated (Claude))
     */
    constructor(application: Application) : this(
        application = application,
        repository = authRepository(application),
        lastEmailStore = LastEmailStore(application),
        lockPrefs = application.getSharedPreferences(LOCK_PREFS, Context.MODE_PRIVATE),
    )

    // Partly AI-generated (Claude)
    /** 쓰기 요청 앞의 서버 깨우기. 실제는 [ServerWarmup] 이고, 테스트는 네트워크 없이 바로 돌아오는 가짜를 쓴다 */
    internal interface Warmup {
        suspend fun ensureAwake()
        fun invalidate()

        object Server : Warmup {
            override suspend fun ensureAwake() {
                ServerWarmup.ensureAwake()
            }

            override fun invalidate() = ServerWarmup.invalidate()
        }
    }

    /**
     * 어떤 화면을 보여줄지. 네비게이션 라이브러리 없이 몇 화면만 오간다. [SOCIAL_TERMS] 는 소셜로
     * 처음 가입할 때의 약관 동의(Figma ⑦ 변형 `495:9`)다.
     */
    enum class Screen { LOGIN, SIGNUP, SOCIAL_TERMS, RESET_REQUEST, RESET_CONFIRM }

    // AI-generated (Claude) — fix/iter4-he-bugs 이식(11-19)
    /**
     * 시스템 뒤로(제스처·뒤로 버튼)가 할 일. [UiState.systemBack] 이 정하고, `AuthHost` 의 BackHandler(켤지)와
     * [onSystemBack](어디로 갈지)이 함께 따른다.
     */
    enum class SystemBack {
        /** 앱이 받지 않는다. 로그인 화면이라 시스템 기본 동작이 앱을 닫는다 */
        SYSTEM,

        /** 서버를 기다리는 중이다. 화면의 ‹ 가 꺼진 것과 같이 움직이지 않는다. 받아서 버리므로 앱도 닫히지 않는다 */
        STAY,

        /** 로그인 화면으로 돌아간다. 가입 · 처음 가입 · 코드 요청 화면의 ‹ 와 같다 */
        TO_LOGIN,

        /** 코드 요청 화면으로 한 칸 돌아간다. 코드 확인 화면의 ‹ 와 같다 */
        TO_RESET_REQUEST,
    }

    // Partly AI-generated (Claude)
    /**
     * 소셜로 처음 가입하는 중. 서버가 400 `social_terms_required` 를 줬다. 같은 [idToken] 에 동의를
     * 붙여 다시 보낸다(카카오 토큰 12시간, 구글 1시간 유효).
     */
    data class SocialTermsDraft(
        val provider: SocialProvider,
        val idToken: String,
        val nickname: String,
        val agreed: Boolean = false,
    )

    // Partly AI-generated (Claude)
    data class UiState(
        val screen: Screen = Screen.LOGIN,

        val email: String = "",
        val password: String = "",
        val nickname: String = "",
        val passwordConfirm: String = "",
        /** 가입 요청에 함께 보내는 약관 동의. */
        val termsAgreed: Boolean = false,
        val passwordCheck: PasswordCheckResponse? = null,
        val passwordCheckError: String? = null,

        val loading: Boolean = false,
        /** 서버가 준 메시지를 그대로 담는다. */
        val error: String? = null,
        /** 가입 400 의 `details.nickname`. 닉네임 칸 아래에 붙이고, 닉네임을 고치면 지운다(iter4 H2-11) */
        val nicknameError: String? = null,
        /** 로그인·가입이 끝나 다음 화면으로 넘어가야 하는 상태. */
        val authenticatedNickname: String? = null,

        /**
         * 잠든 서버를 깨우는 중.
         *
         * 공용 서버(Render 무료)는 15분 무응답이면 잠들고 깨는 데 수십 초가
         * 걸린다. 그 동안 버튼만 돌고 있으면 사용자는 앱이 멈춘 줄 안다.
         * 무슨 일이 일어나는지 화면에 적는다.
         */
        val waking: Boolean = false,

        /** 소셜 로그인 중인 제공자. 그 버튼만 "확인 중…" 이고 다른 입력은 막는다 */
        val socialBusy: SocialProvider? = null,
        /** 로그인 화면 오류 자리의 카드(이미 가입한 이메일·쓸 수 없음). Figma ⑦ 변형 `496:9` */
        val socialNotice: SocialNotice? = null,
        /** 로그인 잠금이 풀리는 시각(BE-A3). 이 계정([lockedEmail])을 입력하고 있을 때만 [로그인] 을 막는다 */
        val lockedUntilMillis: Long? = null,
        val lockedEmail: String? = null,
        /** 잠금 카운트다운을 다시 그리는 시계. 잠긴 동안 1초마다 바뀐다 */
        val nowMillis: Long = System.currentTimeMillis(),
        /** 401 남은 횟수 안내 "3번 더 틀리면 15분 동안 잠겨요". 이메일 · 비밀번호를 고치면 지운다 */
        val attemptsHint: String? = null,
        /** 소셜로 처음 가입하는 중이면 그 내용 */
        val socialTerms: SocialTermsDraft? = null,

        /** 비밀번호 재설정 확인 코드(6자리 숫자) */
        val resetCode: String = "",
        /** 코드를 보낸 이메일. 코드 확인 화면의 안내에 쓴다 */
        val resetSentTo: String? = null,
        /** 코드 확인 화면의 안내 한 줄(예: "코드를 다시 보냄") */
        val resetInfo: String? = null,
    ) {
        /** 이메일 칸이 형식에 맞는가. 비어 있으면 true(오류를 보이지 않는다) */
        val emailFormatOk: Boolean get() = email.isBlank() || EmailRule.isValid(email)

        /** 무언가 서버를 기다리는 중. 이메일·소셜 버튼을 함께 막는다 */
        val busy: Boolean get() = loading || socialBusy != null

        // AI-generated (Claude) — fix/iter4-he-bugs 이식(11-19)
        /**
         * 시스템 뒤로가 할 일. 로그인 화면만 받지 않는다 — 거기서 뒤로는 앱을 닫는다.
         *
         * 서버를 기다리는 중에도 받되 움직이지 않는다([SystemBack.STAY]). 예전에는 기다리는 동안 BackHandler 를 꺼서
         * 시스템 기본 동작이 돌아 앱이 닫혔고, 진행 중인 요청의 결과(보낸 재설정 코드 등)도 잃었다. 그 밖에는 화면의 ‹ 와
         * 같은 곳으로 간다(가입 · 처음 가입 · 코드 요청 → 로그인, 코드 확인 → 코드 요청).
         */
        val systemBack: SystemBack
            get() = when (screen) {
                Screen.LOGIN -> SystemBack.SYSTEM
                Screen.SIGNUP, Screen.SOCIAL_TERMS, Screen.RESET_REQUEST ->
                    if (busy) SystemBack.STAY else SystemBack.TO_LOGIN
                Screen.RESET_CONFIRM -> if (busy) SystemBack.STAY else SystemBack.TO_RESET_REQUEST
            }

        /** 시스템 뒤로를 앱이 받는가. `AuthHost` 의 BackHandler 를 켠다 */
        val handlesSystemBack: Boolean get() = systemBack != SystemBack.SYSTEM

        /** 시스템 뒤로로 한 단계 돌아가는가. 서버를 기다리는 중에는 움직이지 않는다(화면의 ‹ 도 꺼져 있다) */
        val canGoBack: Boolean
            get() = systemBack == SystemBack.TO_LOGIN || systemBack == SystemBack.TO_RESET_REQUEST

        /** 지금 입력한 계정이 잠겨 있는가 */
        val locked: Boolean
            get() = com.swpp.wakeup.domain.model.LoginLock.isLocked(lockedUntilMillis, nowMillis) &&
                com.swpp.wakeup.domain.model.LoginLock.sameAccount(lockedEmail, email)

        val canSubmitLogin: Boolean
            get() = !busy && !locked && email.isNotBlank() && password.isNotBlank()

        val canAgreeSocialTerms: Boolean
            get() = !busy && socialTerms?.agreed == true

        /**
         * 비밀번호 확인 일치. 화면 표시(✓)용 비교라 앱에서 한다.
         * 서버도 `password_confirm` 으로 다시 확인한다.
         */
        val passwordsMatch: Boolean
            get() = passwordConfirm.isNotEmpty() && password == passwordConfirm

        /**
         * 확인 칸에 무언가 적혀 있는데 위 비밀번호와 다르다. 다 적기를 기다리지 않고 적는 동안 빨갛게 보여 준다 —
         * 끝까지 적고 버튼이 꺼진 이유를 찾게 하는 것보다 낫다. 확인 칸이 비어 있으면 아직 오류가 아니다.
         */
        val passwordConfirmMismatch: Boolean
            get() = passwordConfirm.isNotEmpty() && password != passwordConfirm

        /** 이메일 형식 · 닉네임 1~20자 · 서버 비밀번호 검사 · 확인 일치 · 약관 동의가 모두 필요하다. */
        val canSubmitSignup: Boolean
            get() = !loading && EmailRule.isValid(email) && NicknameRule.isValid(nickname) &&
                password.isNotBlank() && passwordsMatch && termsAgreed && passwordCheck?.valid == true

        /** 재설정 코드 받기: 이메일 형식이 맞아야 켜진다 */
        val canRequestReset: Boolean
            get() = !loading && EmailRule.isValid(email)

        /** 재설정 확인: 6자리 코드, 일치하는 새 비밀번호, 서버 비밀번호 검사 통과 */
        val canSubmitResetConfirm: Boolean
            get() = !loading && resetCode.length == RESET_CODE_LENGTH && passwordsMatch &&
                passwordCheck?.valid == true
    }

    private val _state = MutableStateFlow(UiState(email = lastEmailStore.email.orEmpty()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var passwordCheckJob: Job? = null

    // --- 입력 -------------------------------------------------------------

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value, error = null, socialNotice = null, attemptsHint = null) }
    fun onPasswordChange(value: String) {
        passwordCheckJob?.cancel()
        _state.update { it.copy(password = value, error = null, passwordCheck = null, passwordCheckError = null, attemptsHint = null) }
        if (!_state.value.screen.checksPassword || value.isEmpty()) return
        passwordCheckJob = viewModelScope.launch {
            val result = repository.checkPassword(value)
            _state.update {
                if (!it.screen.checksPassword || it.password != value) it
                else it.copy(passwordCheck = result.getOrNull(), passwordCheckError = result.exceptionOrNull()?.message)
            }
        }
    }
    fun onNicknameChange(value: String) = _state.update { it.copy(nickname = value, error = null, nicknameError = null) }
    fun onPasswordConfirmChange(value: String) =
        _state.update { it.copy(passwordConfirm = value, error = null) }
    // AI-generated (Claude)
    fun onTermsAgreedChange(value: Boolean) = _state.update { it.copy(termsAgreed = value) }

    // --- 화면 이동 --------------------------------------------------------

    fun goToSignup() {
        passwordCheckJob?.cancel()
        _state.update { UiState(screen = Screen.SIGNUP, email = it.email) }
    }

    fun goToLogin() {
        passwordCheckJob?.cancel()
        _state.update { UiState(screen = Screen.LOGIN, email = it.email) }
    }

    fun consumeAuthenticated() = _state.update { it.copy(authenticatedNickname = null) }

    // --- 비밀번호 재설정 (제안 계약 R3-9) — Partly AI-generated (Claude) -------------------

    /** 로그인 화면의 "비밀번호 찾기". 입력한 이메일은 그대로 가져간다 */
    fun goToResetRequest() {
        passwordCheckJob?.cancel()
        _state.update { UiState(screen = Screen.RESET_REQUEST, email = it.email) }
    }

    // AI-generated (Claude) — fix/iter4-he-bugs 이식(11-19)
    /**
     * 시스템 뒤로. 서버를 기다리는 중이면 받기만 하고 움직이지 않는다. 움직일 때는 **그 화면의 ‹ 가 부르는 함수를
     * 그대로 부른다** — 가입은 [goToLogin], 처음 가입은 [cancelSocialTerms], 재설정 두 화면은 [goBackFromReset].
     */
    fun onSystemBack() {
        val current = _state.value
        if (!current.canGoBack) return
        when (current.screen) {
            Screen.SIGNUP -> goToLogin()
            Screen.SOCIAL_TERMS -> cancelSocialTerms()
            Screen.RESET_REQUEST, Screen.RESET_CONFIRM -> goBackFromReset()
            Screen.LOGIN -> Unit
        }
    }

    /** 뒤로. 코드 확인 → 코드 요청 → 로그인. 서버를 기다리는 중에는 움직이지 않는다 */
    fun goBackFromReset() {
        passwordCheckJob?.cancel()
        val current = _state.value
        if (current.loading) return
        _state.update {
            when (it.screen) {
                Screen.RESET_CONFIRM -> UiState(screen = Screen.RESET_REQUEST, email = it.email)
                else -> UiState(screen = Screen.LOGIN, email = it.email)
            }
        }
    }

    fun onResetCodeChange(value: String) =
        _state.update { it.copy(resetCode = value.filter(Char::isDigit).take(RESET_CODE_LENGTH), error = null) }

    /** "확인 코드 받기". 성공하면 코드 확인 화면으로 */
    fun requestReset() {
        val current = _state.value
        if (!current.canRequestReset) return
        sendResetCode(current.email.trim(), resend = false)
    }

    /** "코드 다시 받기". 화면은 그대로 두고 안내 한 줄만 바꾼다 */
    fun resendReset() {
        val current = _state.value
        val target = current.resetSentTo ?: return
        if (current.loading) return
        sendResetCode(target, resend = true)
    }

    private fun sendResetCode(email: String, resend: Boolean) {
        _state.update { it.copy(loading = true, error = null, resetInfo = null, waking = false) }
        viewModelScope.launch {
            val hint = launch {
                delay(WAKE_HINT_DELAY_MILLIS)
                _state.update { it.copy(waking = true) }
            }
            warmup.ensureAwake()
            hint.cancel()
            _state.update { it.copy(waking = false) }

            when (val result = repository.requestPasswordReset(email)) {
                is AuthRepository.ResetRequestResult.Sent -> _state.update {
                    it.copy(
                        screen = Screen.RESET_CONFIRM,
                        loading = false,
                        error = null,
                        resetSentTo = email,
                        resetInfo = if (resend) "코드를 다시 보냄" else null,
                        resetCode = if (resend) "" else it.resetCode,
                    )
                }

                is AuthRepository.ResetRequestResult.Failure -> {
                    warmup.invalidate()
                    _state.update { it.copy(loading = false, error = result.message) }
                }
            }
        }
    }

    /** "비밀번호 바꾸고 로그인". 성공하면 로그인과 같이 토큰을 저장하고 다음 화면으로 */
    fun confirmReset() {
        val current = _state.value
        if (!current.canSubmitResetConfirm) return
        val target = current.resetSentTo ?: current.email
        submit {
            repository.confirmPasswordReset(
                email = target,
                code = current.resetCode,
                newPassword = current.password,
                newPasswordConfirm = current.passwordConfirm,
            )
        }
    }

    // --- 소셜 로그인 (docs/iter3/social-login-api.md) — Partly AI-generated (Claude) -------

    /**
     * 카카오·Google 버튼. 다른 요청이 진행 중이면 false — 화면은 SDK 를 띄우지 않는다. true 면 화면이
     * [SocialSignIn] 으로 토큰을 받아 [onSocialOutcome] 으로 넘긴다.
     */
    fun beginSocial(provider: SocialProvider): Boolean {
        if (_state.value.busy) return false
        _state.update { it.copy(socialBusy = provider, error = null, socialNotice = null) }
        return true
    }

    fun onSocialOutcome(provider: SocialProvider, outcome: SocialSignIn.Outcome) {
        when (outcome) {
            SocialSignIn.Outcome.Cancelled -> _state.update { it.copy(socialBusy = null) }
            SocialSignIn.Outcome.NotConfigured -> _state.update {
                it.copy(socialBusy = null, socialNotice = SocialLogin.unavailableNotice(provider))
            }
            is SocialSignIn.Outcome.Failed -> _state.update { it.copy(socialBusy = null, error = outcome.message) }
            is SocialSignIn.Outcome.Token -> sendSocial(provider, outcome.idToken, termsAgreed = false, nickname = null)
        }
    }

    fun onSocialNicknameChange(value: String) = _state.update {
        it.copy(socialTerms = it.socialTerms?.copy(nickname = value.takeCodePoints(SocialLogin.NICKNAME_MAX)), error = null)
    }

    fun onSocialTermsAgreedChange(value: Boolean) =
        _state.update { it.copy(socialTerms = it.socialTerms?.copy(agreed = value)) }

    /** 처음 가입 화면의 [동의하고 시작하기]. 같은 토큰에 동의를 붙여 다시 보낸다 */
    fun agreeSocialTerms() {
        val current = _state.value
        val draft = current.socialTerms ?: return
        if (!current.canAgreeSocialTerms) return
        _state.update { it.copy(socialBusy = draft.provider, error = null) }
        sendSocial(draft.provider, draft.idToken, termsAgreed = true, nickname = draft.nickname)
    }

    /** 처음 가입을 그만두고 로그인으로. 토큰은 버린다 */
    fun cancelSocialTerms() =
        _state.update { UiState(screen = Screen.LOGIN, email = it.email) }

    private fun sendSocial(provider: SocialProvider, idToken: String, termsAgreed: Boolean, nickname: String?) {
        viewModelScope.launch {
            val hint = launch {
                delay(WAKE_HINT_DELAY_MILLIS)
                _state.update { it.copy(waking = true) }
            }
            warmup.ensureAwake()
            hint.cancel()
            _state.update { it.copy(waking = false) }

            when (val result = repository.socialLogin(provider, idToken, termsAgreed, nickname)) {
                is AuthRepository.SocialResult.Success -> _state.update {
                    it.copy(socialBusy = null, error = null, authenticatedNickname = result.user.nickname)
                }

                AuthRepository.SocialResult.TermsRequired -> _state.update {
                    it.copy(
                        screen = Screen.SOCIAL_TERMS,
                        socialBusy = null,
                        error = if (termsAgreed) "약관 동의를 확인하지 못했어요. 다시 시도해 주세요." else null,
                        socialTerms = it.socialTerms?.takeIf { draft -> draft.idToken == idToken }
                            ?: SocialTermsDraft(
                                provider = provider,
                                idToken = idToken,
                                nickname = SocialLogin.nicknameHint(idToken).orEmpty(),
                            ),
                    )
                }

                is AuthRepository.SocialResult.EmailInUse -> _state.update {
                    it.copy(
                        screen = Screen.LOGIN,
                        socialBusy = null,
                        socialTerms = null,
                        socialNotice = SocialLogin.emailInUseNotice(provider, result.loginMethods),
                    )
                }

                AuthRepository.SocialResult.Unavailable -> _state.update {
                    it.copy(
                        screen = Screen.LOGIN,
                        socialBusy = null,
                        socialTerms = null,
                        socialNotice = SocialLogin.unavailableNotice(provider),
                    )
                }

                is AuthRepository.SocialResult.Failure -> {
                    warmup.invalidate()
                    _state.update { it.copy(socialBusy = null, error = result.message) }
                }
            }
        }
    }

    // --- 제출 -------------------------------------------------------------

    fun login() {
        val current = _state.value
        if (!current.canSubmitLogin) return
        submit { repository.login(current.email, current.password) }
    }

    // Partly AI-generated (Claude)
    /**
     * 로그인 잠금(BE-A3). 풀리는 시각을 기기에 남겨 화면을 나갔다 들어와도 남은 시간이 맞게 하고, 잠긴 동안 1초마다
     * 시계를 돌려 카운트다운을 그린다. 시각이 지나면 스스로 풀린다.
     */
    private var lockTicker: kotlinx.coroutines.Job? = null

    private fun applyLock(email: String, untilMillis: Long) {
        lockPrefs.edit().putString("email", email.trim()).putLong("until", untilMillis).apply()
        _state.update { it.copy(lockedEmail = email.trim(), lockedUntilMillis = untilMillis, nowMillis = clock()) }
        startLockTicker()
    }

    private fun restoreLock() {
        val until = lockPrefs.getLong("until", 0L)
        val email = lockPrefs.getString("email", null)
        if (email == null || until <= clock()) {
            lockPrefs.edit().clear().apply()
            return
        }
        _state.update { it.copy(lockedEmail = email, lockedUntilMillis = until, nowMillis = clock()) }
        startLockTicker()
    }

    private fun clearLock() {
        lockTicker?.cancel()
        lockPrefs.edit().clear().apply()
        _state.update { it.copy(lockedEmail = null, lockedUntilMillis = null) }
    }

    private fun startLockTicker() {
        lockTicker?.cancel()
        lockTicker = viewModelScope.launch {
            while (true) {
                val now = clock()
                val until = _state.value.lockedUntilMillis ?: break
                if (now >= until) {
                    clearLock()
                    break
                }
                _state.update { it.copy(nowMillis = now) }
                delay(1_000L - now % 1_000L)
            }
        }
    }

    // 화면을 나갔다 들어오거나 앱을 다시 켜도 남은 잠금 시간을 이어서 보인다
    init {
        restoreLock()
    }

    fun signup() {
        val current = _state.value
        // 확인 일치·약관 동의는 canSubmitSignup 이 막는다(일치는 확인 칸의 ✓ 로 보인다).
        if (!current.canSubmitSignup) return
        submit {
            repository.register(
                email = current.email,
                nickname = current.nickname,
                password = current.password,
                passwordConfirm = current.passwordConfirm,
                termsAgreed = current.termsAgreed,
            )
        }
    }

    /**
     * 깨우기 안내를 띄우기까지 기다리는 시간.
     *
     * 깨어 있는 서버는 0.1초대로 답한다. 그때도 "깨우는 중" 을 번쩍이면
     * 문제가 있는 것처럼 보인다. 이 시간을 넘겨 응답이 없을 때만 알린다.
     */
    private val WAKE_HINT_DELAY_MILLIS = 2_000L

    private val Screen.checksPassword: Boolean
        get() = this == Screen.SIGNUP || this == Screen.RESET_CONFIRM

    private companion object {
        /** 재설정 확인 코드 자릿수 */
        const val RESET_CODE_LENGTH = 6

        /** 로그인 잠금 파일 */
        const val LOCK_PREFS = "jit_login_lock"

        /**
         * 로그인 성공 직전에 앞 사용자의 캐시를 지운다.
         *
         * 이 호출은 로그인 코루틴 안에서 **기다려서** 돈다. 그래야 새 사용자의 첫
         * 조회가 캐시를 쓰기 전에 삭제가 끝난다. 디스크 삭제는 수 밀리초라 이미
         * 네트워크를 기다리는 사용자에게 보이지 않는다.
         */
        fun authRepository(application: Application) = AuthRepository(
            tokenStore = TokenStore(application),
            onBeforeAuthenticated = {
                // 소유자를 모르는 상태이므로 전부 지운다. 새 계정의 캐시는 아직 없다.
                OfflineCache(application, ownerEmail = null).wipe()
                // 앞 사용자의 알람 사본·관측 큐·아침 기록도 같이 지운다. 로그아웃이
                // 이미 지웠겠지만, 앱이 강제 종료됐으면 그 경로가 돌지 않았다.
                //
                // 여기서는 토큰이 아직 저장되기 전이라 소유자 판정이 "모름" 이다.
                // 그래서 소유자를 가리지 않고 전부 지운다 — 어느 계정의 잔재가
                // 남아 있는지 앱이 확신할 수 없다.
                LocalStores.wipeAll(application)
                // 앞 세션이 남긴 만료 표시를 내린다. 남아 있으면 로그인에 성공해도
                // MainActivity 가 곧바로 로그인 화면으로 되돌린다.
                SessionState.clearExpired()
            },
        )
    }

    private fun submit(block: suspend () -> AuthRepository.AuthResult) {
        _state.update { it.copy(loading = true, error = null, nicknameError = null, waking = false, socialNotice = null) }
        viewModelScope.launch {
            // 쓰기 요청 앞에서 서버를 깨운다. 로그인은 POST 라서
            // ColdStartRetryInterceptor 가 재시도해 주지 않는다 - 재시도하면
            // 중복 처리 위험이 있어 의도적으로 제외했다. 대신 GET 하나로
            // 먼저 깨우고, 깨어난 뒤에 자격증명을 보낸다.
            val hint = launch {
                delay(WAKE_HINT_DELAY_MILLIS)
                _state.update { it.copy(waking = true) }
            }
            warmup.ensureAwake()
            hint.cancel()
            _state.update { it.copy(waking = false) }

            when (val result = block()) {
                is AuthRepository.AuthResult.Success -> {
                    // 이메일 로그인·가입·재설정 성공만 기억한다(서버가 정리한 주소). 소셜은 submit 을 타지 않는다.
                    lastEmailStore.save(result.user.email)
                    // 재설정을 마치면 서버 잠금도 풀린다(BE-A3)
                    clearLock()
                    _state.update {
                        it.copy(
                            loading = false,
                            waking = false,
                            error = null,
                            authenticatedNickname = result.user.nickname,
                        )
                    }
                }

                is AuthRepository.AuthResult.Failure -> {
                    // 타임아웃으로 실패했으면 "깨어 있다" 는 기록을 지운다.
                    // 그러지 않으면 다음 시도에서 깨우기를 건너뛴다.
                    warmup.invalidate()
                    // 로그인 잠금 · 남은 횟수(BE-A3). 잠겼으면 서버 문구 대신 카운트다운 카드가 보인다.
                    result.lockedUntilMillis?.let { applyLock(_state.value.email, it) }
                    val attemptsHint = com.swpp.wakeup.domain.model.LoginLock.attemptsLine(result.remainingAttempts)
                    val nicknameReason = result.fields["nickname"]
                    _state.update {
                        it.copy(
                            loading = false,
                            waking = false,
                            // 닉네임 때문이면 카드에 무엇 때문인지 같이 쓴다(Figma `549:2094`). 이유는 칸 아래에도 붙는다.
                            error = when {
                                nicknameReason != null -> "가입하지 못했어요. $nicknameReason"
                                result.lockedUntilMillis != null -> null
                                else -> result.message
                            },
                            attemptsHint = attemptsHint,
                            nicknameError = nicknameReason,
                        )
                    }
                }
            }
        }
    }
}
