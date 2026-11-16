package com.swpp.wakeup.ui.auth

import android.app.Application
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
import com.swpp.wakeup.domain.model.SocialLogin
import com.swpp.wakeup.domain.model.SocialNotice
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.domain.model.takeCodePoints
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
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application)

    /** 마지막으로 이메일 로그인한 주소(기기 단위, 로그아웃해도 남는다). 다음 로그인 칸을 채운다 */
    private val lastEmailStore = LastEmailStore(application)

    /**
     * 로그인 성공 직전에 앞 사용자의 캐시를 지운다.
     *
     * 이 호출은 로그인 코루틴 안에서 **기다려서** 돈다. 그래야 새 사용자의 첫
     * 조회가 캐시를 쓰기 전에 삭제가 끝난다. 디스크 삭제는 수 밀리초라 이미
     * 네트워크를 기다리는 사용자에게 보이지 않는다.
     */
    private val repository = AuthRepository(
        tokenStore = tokenStore,
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

    /**
     * 어떤 화면을 보여줄지. 네비게이션 라이브러리 없이 몇 화면만 오간다. [SOCIAL_TERMS] 는 소셜로
     * 처음 가입할 때의 약관 동의(Figma ⑦ 변형 `495:9`)다.
     */
    enum class Screen { LOGIN, SIGNUP, SOCIAL_TERMS, RESET_REQUEST, RESET_CONFIRM }

    // AI-generated (Claude)
    /**
     * 시스템 뒤로(제스처·뒤로 버튼)가 할 일. [UiState.systemBack] 이 정하고, `AuthHost` 의 BackHandler(켤지)와
     * [onSystemBack](어디로 갈지)이 함께 따른다. 홈 쪽 화면의 같은 판단은 `ui/home/SystemBack.kt` 에 있다.
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
        /** 서버가 가입을 거절하며 닉네임 칸에 붙인 이유(`details.nickname`). 닉네임을 고치면 지운다 */
        val nicknameServerError: String? = null,
        val passwordConfirm: String = "",
        /** 가입 요청에 함께 보내는 약관 동의. */
        val termsAgreed: Boolean = false,
        val passwordCheck: PasswordCheckResponse? = null,
        val passwordCheckError: String? = null,

        val loading: Boolean = false,
        /** 서버가 준 메시지를 그대로 담는다. */
        val error: String? = null,
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

        val canSubmitLogin: Boolean
            get() = !busy && email.isNotBlank() && password.isNotBlank()

        val canAgreeSocialTerms: Boolean
            get() = !busy && socialTerms?.agreed == true

        /**
         * 비밀번호 확인 일치. 화면 표시(✓)용 비교라 앱에서 한다.
         * 서버도 `password_confirm` 으로 다시 확인한다.
         */
        val passwordsMatch: Boolean
            get() = passwordConfirm.isNotEmpty() && password == passwordConfirm

        /** 닉네임이 서버 한도(20자)를 넘는다. 서버처럼 앞뒤 공백을 자르고 코드 포인트로 센다 */
        val nicknameTooLong: Boolean get() = isNicknameTooLong(nickname)

        /** 서버 비밀번호 검사·확인 일치·약관 동의가 모두 필요하다. 닉네임은 비지 않고 한도 안이어야 한다 */
        val canSubmitSignup: Boolean
            get() = !loading && EmailRule.isValid(email) && nickname.isNotBlank() && !nicknameTooLong &&
                password.isNotBlank() && passwordsMatch && termsAgreed && passwordCheck?.valid == true

        /** 재설정 코드 받기: 이메일 형식이 맞아야 켜진다 */
        val canRequestReset: Boolean
            get() = !loading && EmailRule.isValid(email)

        /** 재설정 확인: 6자리 코드, 일치하는 새 비밀번호, 서버 비밀번호 검사 통과 */
        val canSubmitResetConfirm: Boolean
            get() = !loading && resetCode.length == RESET_CODE_LENGTH && passwordsMatch &&
                passwordCheck?.valid == true

        // AI-generated (Claude)
        /**
         * 시스템 뒤로(제스처·뒤로 버튼)가 할 일. 로그인 화면만 받지 않는다 — 거기서 뒤로는 앱을 닫는다.
         *
         * 서버를 기다리는 중에도 받되 움직이지 않는다([SystemBack.STAY]). 받지 않으면 시스템 기본 동작이 돌아 앱이
         * 뒤로 숨거나 닫히고, 닫히면 진행 중인 요청의 결과(보낸 재설정 코드 등)도 잃는다. 그 밖에는 화면의 ‹ 와 같은
         * 곳으로 간다(가입 · 처음 가입 · 코드 요청 → 로그인, 코드 확인 → 코드 요청).
         */
        val systemBack: SystemBack
            get() = when (screen) {
                Screen.LOGIN -> SystemBack.SYSTEM
                Screen.SIGNUP, Screen.SOCIAL_TERMS, Screen.RESET_REQUEST ->
                    if (busy) SystemBack.STAY else SystemBack.TO_LOGIN
                Screen.RESET_CONFIRM -> if (busy) SystemBack.STAY else SystemBack.TO_RESET_REQUEST
            }

        // AI-generated (Claude)
        /** 시스템 뒤로를 앱이 받는가. `AuthHost` 의 BackHandler 를 켠다. [systemBack] 이 [SystemBack.SYSTEM] 이 아니면 받는다 */
        val handlesSystemBack: Boolean get() = systemBack != SystemBack.SYSTEM

        // AI-generated (Claude)
        /** 시스템 뒤로로 한 단계 돌아가는가. 서버를 기다리는 중에는 움직이지 않는다(화면의 ‹ 도 꺼져 있다) */
        val canGoBack: Boolean
            get() = systemBack == SystemBack.TO_LOGIN || systemBack == SystemBack.TO_RESET_REQUEST

        // AI-generated (Claude)
        /**
         * 실패한 요청을 화면 상태로. 서버가 닉네임 칸에 이유를 붙였으면(`details.nickname`) 그 칸 아래에 그리고,
         * 그것만 왔으면 아래 오류 줄에는 되풀이하지 않는다(설정의 지금 비밀번호 칸과 같은 방식). 다른 칸의
         * 이유가 함께 왔으면 서버의 대표 문구를 아래 줄에 그대로 둔다.
         */
        fun failedWith(failure: AuthRepository.AuthResult.Failure): UiState {
            val onlyNickname = failure.fields.keys == setOf(FIELD_NICKNAME)
            return copy(
                loading = false,
                waking = false,
                nicknameServerError = failure.fields[FIELD_NICKNAME],
                error = if (onlyNickname) null else failure.message,
            )
        }
    }

    private val _state = MutableStateFlow(UiState(email = lastEmailStore.email.orEmpty()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var passwordCheckJob: Job? = null

    // --- 입력 -------------------------------------------------------------

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value, error = null, socialNotice = null) }
    fun onPasswordChange(value: String) {
        passwordCheckJob?.cancel()
        _state.update { it.copy(password = value, error = null, passwordCheck = null, passwordCheckError = null) }
        if (!_state.value.screen.checksPassword || value.isEmpty()) return
        passwordCheckJob = viewModelScope.launch {
            val result = repository.checkPassword(value)
            _state.update {
                if (!it.screen.checksPassword || it.password != value) it
                else it.copy(passwordCheck = result.getOrNull(), passwordCheckError = result.exceptionOrNull()?.message)
            }
        }
    }
    // Partly AI-generated (Claude) — 닉네임을 고치면 서버가 그 칸에 붙인 이유도 지운다.
    fun onNicknameChange(value: String) =
        _state.update { it.copy(nickname = value, error = null, nicknameServerError = null) }
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

    // AI-generated (Claude)
    /**
     * 시스템 뒤로(제스처·뒤로 버튼). 서버를 기다리는 중이면 받기만 하고 움직이지 않는다([UiState.systemBack] 이
     * [SystemBack.STAY]).
     *
     * 움직일 때는 **그 화면의 ‹ 가 부르는 함수를 그대로 부른다**(`AuthHost` 의 onBackClick · onBackToLoginClick 과
     * 같다). 가입은 [goToLogin], 처음 가입은 [cancelSocialTerms], 재설정 두 화면은 [goBackFromReset] 이다. 같은 곳으로
     * 가는 함수를 따로 부르면 ‹ 쪽만 고쳤을 때 시스템 뒤로가 어긋난다. 가는 곳은 [UiState.systemBack] 이 적은
     * 곳(가입 · 처음 가입 · 코드 요청 → 로그인, 코드 확인 → 코드 요청)과 같다. 연결은 `AuthBackWiringTest` 가 고정한다.
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

    // --- 비밀번호 재설정 (제안 계약 R3-9) — Partly AI-generated (Claude) -------------------

    /** 로그인 화면의 "비밀번호 찾기". 입력한 이메일은 그대로 가져간다 */
    fun goToResetRequest() {
        passwordCheckJob?.cancel()
        _state.update { UiState(screen = Screen.RESET_REQUEST, email = it.email) }
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
            ServerWarmup.ensureAwake()
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
                    ServerWarmup.invalidate()
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

    /** 한도(20자)는 서버처럼 코드 포인트로 센다. take 는 경계에 걸친 이모지를 반만 남긴다 */
    fun onSocialNicknameChange(value: String) = _state.update {
        val nickname = value.takeCodePoints(SocialLogin.NICKNAME_MAX)
        it.copy(socialTerms = it.socialTerms?.copy(nickname = nickname), error = null)
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
            ServerWarmup.ensureAwake()
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
                        error = if (termsAgreed) "약관 동의를 확인하지 못했다. 다시 시도한다." else null,
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
                    ServerWarmup.invalidate()
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

        /** 가입 실패 본문 `details` 에서 닉네임 칸의 이름 */
        const val FIELD_NICKNAME = "nickname"
    }

    // Partly AI-generated (Claude) — 다시 보낼 때 지난 닉네임 이유를 지우고, 실패하면 칸별 이유를 그 칸으로 보낸다.
    private fun submit(block: suspend () -> AuthRepository.AuthResult) {
        _state.update {
            it.copy(loading = true, error = null, waking = false, socialNotice = null, nicknameServerError = null)
        }
        viewModelScope.launch {
            // 쓰기 요청 앞에서 서버를 깨운다. 로그인은 POST 라서
            // ColdStartRetryInterceptor 가 재시도해 주지 않는다 - 재시도하면
            // 중복 처리 위험이 있어 의도적으로 제외했다. 대신 GET 하나로
            // 먼저 깨우고, 깨어난 뒤에 자격증명을 보낸다.
            val hint = launch {
                delay(WAKE_HINT_DELAY_MILLIS)
                _state.update { it.copy(waking = true) }
            }
            ServerWarmup.ensureAwake()
            hint.cancel()
            _state.update { it.copy(waking = false) }

            when (val result = block()) {
                is AuthRepository.AuthResult.Success -> {
                    // 이메일 로그인·가입·재설정 성공만 기억한다(서버가 정리한 주소). 소셜은 submit 을 타지 않는다.
                    lastEmailStore.save(result.user.email)
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
                    ServerWarmup.invalidate()
                    // 칸별 이유(닉네임)는 그 칸 아래로 보낸다. (Partly AI-generated (Claude))
                    _state.update { it.failedWith(result) }
                }
            }
        }
    }
}
