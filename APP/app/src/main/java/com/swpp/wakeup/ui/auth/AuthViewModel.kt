package com.swpp.wakeup.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.repository.AuthRepository
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
    private val repository = AuthRepository(tokenStore)

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

    private fun submit(block: suspend () -> AuthRepository.AuthResult) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is AuthRepository.AuthResult.Success ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            authenticatedNickname = result.user.nickname,
                        )
                    }

                is AuthRepository.AuthResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }
}
