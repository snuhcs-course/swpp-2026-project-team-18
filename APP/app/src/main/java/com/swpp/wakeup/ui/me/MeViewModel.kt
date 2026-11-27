package com.swpp.wakeup.ui.me

import androidx.lifecycle.ViewModel
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.repository.AuthRepository
import com.swpp.wakeup.data.repository.MeRepository
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.auth.SocialSignIn
import com.swpp.wakeup.ui.settings.EmailVerifyState
import com.swpp.wakeup.ui.settings.PasswordChangeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns settings request state; Home keeps navigation and mirrors account fields through explicit hooks. */
class MeViewModel(
    private val repository: MeRepository,
    parentScope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
) : ViewModel() {
    data class AccountState(
        val loginMethods: List<String>? = null,
        val loginMethodsFailed: Boolean = false,
        val socialLinkBusy: SocialProvider? = null,
    )

    data class Hooks(
        val openPassword: () -> Unit = {},
        val closePassword: () -> Unit = {},
        val openEmail: () -> Unit = {},
        val closeEmail: () -> Unit = {},
        val verified: (String?) -> Unit = {},
        val userChanged: (UserDto) -> Unit = {},
        val accountChanged: (AccountState) -> Unit = {},
        val notify: (String) -> Unit = {},
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _password = MutableStateFlow(PasswordChangeState())
    val password = _password.asStateFlow()
    private val _email = MutableStateFlow(EmailVerifyState())
    val email = _email.asStateFlow()
    private val _account = MutableStateFlow(AccountState())
    val account = _account.asStateFlow()
    private var checkJob: Job? = null
    private var passwordJob: Job? = null
    private var emailJob: Job? = null
    private var accountJob: Job? = null
    private var socialJob: Job? = null
    private var checkGeneration = 0L
    private var passwordGeneration = 0L
    private var emailGeneration = 0L
    private var accountGeneration = 0L
    private var profileGeneration = 0L
    private var socialGeneration = 0L
    private var closed = false

    fun openPassword() {
        if (closed) return
        // Reopening a pending write must not reset busy and allow a duplicate password change.
        if (!_password.value.busy) {
            ++checkGeneration
            checkJob?.cancel()
            _password.value = PasswordChangeState()
        }
        hooks.openPassword()
    }

    fun onCurrentPassword(value: String) {
        if (closed || _password.value.busy) return
        _password.update { it.copy(current = value, error = null, currentWrong = false) }
    }

    fun onNewPassword(value: String) {
        if (closed || _password.value.busy) return
        checkJob?.cancel()
        val ticket = ++checkGeneration
        _password.update { it.copy(newPassword = value, error = null, passwordCheck = null, passwordCheckError = null) }
        if (value.isEmpty()) return
        checkJob = scope.launch {
            val result = repository.checkPassword(value)
            if (closed || ticket != checkGeneration || _password.value.newPassword != value) return@launch
            _password.update { it.copy(passwordCheck = result.getOrNull(), passwordCheckError = result.exceptionOrNull()?.message) }
        }
    }

    fun onPasswordConfirm(value: String) {
        if (closed || _password.value.busy) return
        _password.update { it.copy(confirm = value, error = null) }
    }

    fun submitPassword() {
        val form = _password.value
        if (closed || !form.canSubmit) return
        val ticket = ++passwordGeneration
        _password.update { it.copy(busy = true, error = null, currentWrong = false) }
        passwordJob = scope.launch {
            try {
                val result = repository.changePassword(form.current, form.newPassword, form.confirm)
                if (closed || ticket != passwordGeneration) return@launch
                when (result) {
                    is AuthRepository.PasswordChangeResult.Success -> {
                        ++checkGeneration
                        checkJob?.cancel()
                        _password.value = PasswordChangeState()
                        hooks.notify(if (result.tokensRotated) "비밀번호를 바꿨어요. 다른 기기는 로그아웃됐어요"
                            else "비밀번호를 바꿨어요. 다시 로그인해야 할 수 있어요")
                        hooks.closePassword()
                    }
                    is AuthRepository.PasswordChangeResult.Failure -> _password.update {
                        it.copy(busy = false, error = result.message, currentWrong = result.currentPasswordWrong)
                    }
                }
            } finally {
                if (!closed && ticket == passwordGeneration) _password.update { it.copy(busy = false) }
            }
        }
    }

    /** Clear hidden secrets/checks; an already submitted write can still finish and report its result. */
    fun leavePassword() {
        if (closed) return
        ++checkGeneration
        checkJob?.cancel()
        _password.value = PasswordChangeState(busy = _password.value.busy)
    }

    fun startEmail(sentTo: String?) {
        if (closed || _email.value.sending || _email.value.busy) return
        emailJob?.cancel()
        val ticket = ++emailGeneration
        _email.value = EmailVerifyState(sending = true)
        emailJob = scope.launch {
            try {
                val result = repository.requestEmailVerify()
                if (closed || ticket != emailGeneration) return@launch
                when (result) {
                    is AuthRepository.EmailVerifyResult.Sent -> {
                        _email.value = EmailVerifyState(sentTo = sentTo)
                        hooks.openEmail()
                    }
                    AuthRepository.EmailVerifyResult.AlreadyVerified,
                    is AuthRepository.EmailVerifyResult.Verified -> {
                        ++profileGeneration
                        _email.value = EmailVerifyState()
                        hooks.verified((result as? AuthRepository.EmailVerifyResult.Verified)?.user?.email)
                        hooks.notify("이미 인증된 이메일이에요")
                    }
                    is AuthRepository.EmailVerifyResult.Failure -> {
                        _email.value = EmailVerifyState()
                        hooks.notify(result.message)
                    }
                }
            } finally {
                if (!closed && ticket == emailGeneration) _email.update { it.copy(sending = false) }
            }
        }
    }

    fun onEmailCode(value: String) {
        if (closed || _email.value.busy || _email.value.sending) return
        _email.update { it.copy(code = value.filter { c -> c in '0'..'9' }.take(EmailVerifyState.CODE_LENGTH), error = null, info = null) }
    }

    fun resendEmail() {
        val form = _email.value
        if (closed || form.sending || form.busy) return
        emailJob?.cancel()
        val ticket = ++emailGeneration
        _email.update { it.copy(sending = true, error = null, info = null) }
        emailJob = scope.launch {
            try {
                val result = repository.requestEmailVerify()
                if (closed || ticket != emailGeneration) return@launch
                when (result) {
                    is AuthRepository.EmailVerifyResult.Sent -> _email.update {
                        it.copy(sending = false, code = "", info = "코드를 다시 보냈어요. 이전 코드는 쓸 수 없어요")
                    }
                    AuthRepository.EmailVerifyResult.AlreadyVerified,
                    is AuthRepository.EmailVerifyResult.Verified -> finishEmail("이미 인증된 이메일이에요",
                        (result as? AuthRepository.EmailVerifyResult.Verified)?.user?.email)
                    is AuthRepository.EmailVerifyResult.Failure -> _email.update { it.copy(sending = false, error = result.message) }
                }
            } finally {
                if (!closed && ticket == emailGeneration) _email.update { it.copy(sending = false) }
            }
        }
    }

    fun confirmEmail() {
        val form = _email.value
        if (closed || !form.canSubmit) return
        emailJob?.cancel()
        val ticket = ++emailGeneration
        _email.update { it.copy(busy = true, error = null, info = null) }
        emailJob = scope.launch {
            try {
                val result = repository.confirmEmailVerify(form.code)
                if (closed || ticket != emailGeneration) return@launch
                when (result) {
                    is AuthRepository.EmailVerifyResult.Verified -> finishEmail("이메일을 인증했어요", result.user?.email)
                    AuthRepository.EmailVerifyResult.AlreadyVerified -> finishEmail("이미 인증된 이메일이에요", null)
                    is AuthRepository.EmailVerifyResult.Failure -> _email.update { it.copy(busy = false, error = result.message) }
                    is AuthRepository.EmailVerifyResult.Sent -> _email.update { it.copy(busy = false) }
                }
            } finally {
                if (!closed && ticket == emailGeneration) _email.update { it.copy(busy = false) }
            }
        }
    }

    private fun finishEmail(message: String, email: String?) {
        ++profileGeneration
        _email.value = EmailVerifyState()
        hooks.verified(email)
        hooks.notify(message)
        hooks.closeEmail()
    }

    /** Cancels verification or an initial send so leaving Settings never opens a late code screen. */
    fun leaveEmail() {
        if (closed) return
        ++emailGeneration
        emailJob?.cancel()
        _email.value = EmailVerifyState()
    }

    fun loadLoginMethods() {
        if (closed || _account.value.socialLinkBusy != null) return
        accountJob?.cancel()
        val ticket = ++accountGeneration
        val profileTicket = profileGeneration
        accountJob = scope.launch {
            val result = repository.currentUser()
            if (closed || ticket != accountGeneration) return@launch
            when (result) {
                is AuthRepository.AccountResult.Success -> applyUser(result.user, updateProfile = profileTicket == profileGeneration)
                is AuthRepository.AccountResult.Failure -> updateAccount {
                    copy(loginMethodsFailed = loginMethods == null)
                }
            }
        }
    }

    fun beginSocialLink(provider: SocialProvider): Boolean {
        if (closed || _account.value.socialLinkBusy != null) return false
        invalidateAccountRead()
        ++socialGeneration
        updateAccount { copy(socialLinkBusy = provider) }
        return true
    }

    fun onSocialOutcome(provider: SocialProvider, outcome: SocialSignIn.Outcome) {
        if (closed || _account.value.socialLinkBusy != provider || socialJob?.isActive == true) return
        when (outcome) {
            SocialSignIn.Outcome.Cancelled -> updateAccount { copy(socialLinkBusy = null) }
            SocialSignIn.Outcome.NotConfigured -> {
                updateAccount { copy(socialLinkBusy = null) }
                hooks.notify("지금은 ${provider.label} 연결을 쓸 수 없어요")
            }
            is SocialSignIn.Outcome.Failed -> {
                updateAccount { copy(socialLinkBusy = null) }
                hooks.notify(outcome.message)
            }
            is SocialSignIn.Outcome.Token -> {
                val ticket = socialGeneration
                socialJob = scope.launch {
                    try {
                        val result = repository.linkSocial(provider, outcome.idToken)
                        if (!closed && ticket == socialGeneration) applySocialResult(result,
                            "${provider.asObject} 연결했어요. 이제 ${provider.label}로도 로그인할 수 있어요")
                    } finally {
                        if (!closed && ticket == socialGeneration) updateAccount { copy(socialLinkBusy = null) }
                    }
                }
            }
        }
    }

    fun unlinkSocial(provider: SocialProvider) {
        if (!beginSocialLink(provider)) return
        val ticket = socialGeneration
        socialJob = scope.launch {
            try {
                val result = repository.unlinkSocial(provider)
                if (!closed && ticket == socialGeneration) applySocialResult(result, "${provider.label} 연결을 끊었어요")
            } finally {
                if (!closed && ticket == socialGeneration) updateAccount { copy(socialLinkBusy = null) }
            }
        }
    }

    private fun applySocialResult(result: AuthRepository.AccountResult, message: String) {
        updateAccount { copy(socialLinkBusy = null) }
        when (result) {
            is AuthRepository.AccountResult.Success -> {
                // Link/unlink owns login methods. Its user snapshot may predate a concurrent
                // email confirmation, so retain the host's more recent profile/verified state.
                applyUser(result.user, updateProfile = false)
                hooks.notify(message)
            }
            is AuthRepository.AccountResult.Failure -> {
                hooks.notify(result.message)
                loadLoginMethods()
            }
        }
    }

    private fun applyUser(user: UserDto, updateProfile: Boolean = true) {
        updateAccount { copy(loginMethods = user.loginMethods.orEmpty(), loginMethodsFailed = false) }
        if (updateProfile) hooks.userChanged(user)
    }

    private fun updateAccount(change: AccountState.() -> AccountState) {
        _account.update { it.change() }
        hooks.accountChanged(_account.value)
    }

    private fun invalidateAccountRead() {
        ++accountGeneration
        accountJob?.cancel()
    }

    /** Owner boundary: cancel every request, reject cancellation-ignoring replies and clear all secrets. */
    fun close() {
        if (closed) return
        closed = true
        ++checkGeneration
        ++passwordGeneration
        ++emailGeneration
        ++accountGeneration
        ++socialGeneration
        scope.cancel()
        _password.value = PasswordChangeState()
        _email.value = EmailVerifyState()
        _account.value = AccountState()
    }

    override fun onCleared() = close()
}
