package com.swpp.wakeup.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.data.local.LocalStores
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ServerWarmup
import com.swpp.wakeup.data.repository.AuthRepository
import kotlinx.coroutines.delay
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

    /** 어떤 화면을 보여줄지. 네비게이션 라이브러리 없이 두 화면만 오간다. */
    enum class Screen { LOGIN, SIGNUP }

    data class UiState(
        val screen: Screen = Screen.LOGIN,

        val email: String = "",
        val password: String = "",
        val nickname: String = "",
        val passwordConfirm: String = "",

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
    ) {
        val canSubmitLogin: Boolean
            get() = !loading && email.isNotBlank() && password.isNotBlank()

        val canSubmitSignup: Boolean
            get() = !loading && email.isNotBlank() && nickname.isNotBlank() &&
                password.isNotBlank() && passwordConfirm.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // --- 입력 -------------------------------------------------------------

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onNicknameChange(value: String) = _state.update { it.copy(nickname = value, error = null) }
    fun onPasswordConfirmChange(value: String) =
        _state.update { it.copy(passwordConfirm = value, error = null) }

    // --- 화면 이동 --------------------------------------------------------

    fun goToSignup() = _state.update {
        // 이메일은 넘겨준다. 로그인 시도 후 계정이 없어 가입으로 넘어오는 흐름이 흔하다.
        UiState(screen = Screen.SIGNUP, email = it.email)
    }

    fun goToLogin() = _state.update {
        UiState(screen = Screen.LOGIN, email = it.email)
    }

    fun consumeAuthenticated() = _state.update { it.copy(authenticatedNickname = null) }

    // --- 제출 -------------------------------------------------------------

    fun login() {
        val current = _state.value
        if (!current.canSubmitLogin) return
        submit { repository.login(current.email, current.password) }
    }

    fun signup() {
        val current = _state.value
        if (!current.canSubmitSignup) return
        // 서버도 같은 검사를 하지만, 왕복 없이 즉시 알려주는 편이 낫다.
        if (current.password != current.passwordConfirm) {
            _state.update { it.copy(error = "비밀번호가 일치하지 않는다.") }
            return
        }
        submit {
            repository.register(
                email = current.email,
                nickname = current.nickname,
                password = current.password,
                passwordConfirm = current.passwordConfirm,
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

    private fun submit(block: suspend () -> AuthRepository.AuthResult) {
        _state.update { it.copy(loading = true, error = null, waking = false) }
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
                is AuthRepository.AuthResult.Success ->
                    _state.update {
                        it.copy(
                            loading = false,
                            waking = false,
                            error = null,
                            authenticatedNickname = result.user.nickname,
                        )
                    }

                is AuthRepository.AuthResult.Failure -> {
                    // 타임아웃으로 실패했으면 "깨어 있다" 는 기록을 지운다.
                    // 그러지 않으면 다음 시도에서 깨우기를 건너뛴다.
                    ServerWarmup.invalidate()
                    _state.update {
                        it.copy(loading = false, waking = false, error = result.message)
                    }
                }
            }
        }
    }
}
