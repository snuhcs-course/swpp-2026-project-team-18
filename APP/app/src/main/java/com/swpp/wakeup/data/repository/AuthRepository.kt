package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.local.TokenStorageException
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ApiErrorBody
import com.swpp.wakeup.data.remote.ApiErrorEnvelope
import com.swpp.wakeup.data.remote.AuthApi
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.data.remote.PasswordChangeRequest
import com.swpp.wakeup.data.remote.PasswordCheckRequest
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.EmailVerifyConfirmRequest
import com.swpp.wakeup.data.remote.PasswordResetConfirmRequest
import com.swpp.wakeup.data.remote.PasswordResetRequest
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.remote.SocialLinkRequest
import com.swpp.wakeup.data.remote.SocialLoginRequest
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.domain.model.SocialLogin
import com.swpp.wakeup.domain.model.SocialProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * 인증 저장소. back-spec.md 5.1.
 *
 * 규칙 — **예외를 밖으로 던지지 않는다.** 모든 실패를 [AuthResult.Failure] 로
 * 감싸 돌려준다. 화면 쪽에서 try/catch 를 반복하지 않게 하려는 것이고,
 * back-spec 7절이 서버측 외부 호출에 요구하는 규칙과 같은 방향이다.
 */
class AuthRepository(
    private val tokenStore: TokenStore,
    private val api: AuthApi = ApiClient.auth,
    /**
     * 로그인 성공 시 **토큰을 저장하기 전에** 부른다.
     *
     * 앞 사용자의 캐시를 지운다. 로그아웃 시에도 지우지만 그 경로는 앱이 강제
     * 종료되면 돌지 않는다. 여기서 한 번 더 지우면 그 구멍이 막힌다.
     *
     * **저장 전**인 것이 중요하다. 저장 후에 지우면 새 사용자의 첫 조회 결과와
     * 경쟁해서 방금 받은 캐시를 날릴 수 있다.
     */
    private val onBeforeAuthenticated: suspend () -> Unit = {},
) {

    sealed interface AuthResult {
        data class Success(val user: UserDto) : AuthResult
        data class Failure(val message: String) : AuthResult
    }

    // Partly AI-generated (Claude)
    /** 소셜 로그인 결과(social-login-api.md §2). 성공이면 토큰까지 저장했다 */
    sealed interface SocialResult {
        data class Success(val user: UserDto) : SocialResult

        /** 새 계정인데 약관 동의가 없다. 아무것도 만들지 않았다. 같은 토큰에 동의를 붙여 다시 보낸다 */
        data object TermsRequired : SocialResult

        /** 그 이메일로 가입한 계정이 있다. 자동으로 합치지 않는다. [loginMethods] 는 원래 방법 */
        data class EmailInUse(val loginMethods: List<String>) : SocialResult

        /** 서버에 그 제공자 키가 없다(503 `social_not_configured`) */
        data object Unavailable : SocialResult

        data class Failure(val message: String) : SocialResult
    }

    /** 내 계정 조회·연결·끊기 결과. 성공이면 서버가 돌려준 사용자(`login_methods` 포함) */
    sealed interface AccountResult {
        data class Success(val user: UserDto) : AccountResult
        data class Failure(val message: String) : AccountResult
    }

    // Partly AI-generated (Claude)
    /** 비밀번호 재설정 코드 요청 결과(제안 계약 R3-9) */
    sealed interface ResetRequestResult {
        data class Sent(val expiresInSeconds: Int?) : ResetRequestResult
        data class Failure(val message: String) : ResetRequestResult
    }

    // Partly AI-generated (Claude)
    /** 이메일 인증 코드 요청·확인 결과(R3-10). 인증은 로그인을 막지 않는다 */
    sealed interface EmailVerifyResult {
        data class Sent(val expiresInSeconds: Int?) : EmailVerifyResult
        data class Verified(val user: UserDto?) : EmailVerifyResult
        data object AlreadyVerified : EmailVerifyResult
        data class Failure(val message: String) : EmailVerifyResult
    }

    // Partly AI-generated (Claude)
    /** 비밀번호 변경 결과. 성공이면 서버가 준 새 토큰을 이미 저장했다 */
    sealed interface PasswordChangeResult {
        data class Success(val tokensRotated: Boolean) : PasswordChangeResult
        data class Failure(val message: String, val currentPasswordWrong: Boolean = false) : PasswordChangeResult
    }

    suspend fun register(
        email: String,
        nickname: String,
        password: String,
        passwordConfirm: String,
        termsAgreed: Boolean,
    ): AuthResult = call {
        api.register(
            RegisterRequest(
                email = email.trim(),
                nickname = nickname.trim(),
                password = password,
                passwordConfirm = passwordConfirm,
                termsAgreed = termsAgreed,
            )
        )
    }

    suspend fun checkPassword(password: String): Result<PasswordCheckResponse> = try {
        val response = api.checkPassword(PasswordCheckRequest(password))
        val body = response.body()
        if (response.isSuccessful && body?.minLength != null &&
            body.lettersAndDigits != null && body.messages != null
        ) Result.success(body)
        else Result.failure(IOException("비밀번호 조건을 확인하지 못했다. 잠시 후 다시 입력한다."))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(IOException(MESSAGE_NETWORK))
    }

    suspend fun login(email: String, password: String): AuthResult = call {
        api.login(LoginRequest(email = email.trim(), password = password))
    }

    fun logout() = tokenStore.clear()

    // Partly AI-generated (Claude)
    /** 가입한 이메일로 확인 코드를 보낸다(`POST /api/auth/password/reset`). 없는 이메일도 같은 202 라고 한다 */
    suspend fun requestPasswordReset(email: String): ResetRequestResult = try {
        val response = api.requestPasswordReset(PasswordResetRequest(email = email.trim()))
        if (response.isSuccessful) {
            ResetRequestResult.Sent(response.body()?.expiresIn)
        } else {
            ResetRequestResult.Failure(passwordFlowMessage(response.code(), errorBodyOf(response.errorBody()?.string())))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ResetRequestResult.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        ResetRequestResult.Failure(MESSAGE_UNKNOWN)
    }

    // Partly AI-generated (Claude)
    /** 코드와 새 비밀번호로 바꾸고 로그인한다(`POST /api/auth/password/reset/confirm`). 응답은 로그인과 같다 */
    suspend fun confirmPasswordReset(
        email: String,
        code: String,
        newPassword: String,
        newPasswordConfirm: String,
    ): AuthResult = call(errorMessage = ::passwordFlowMessage) {
        api.confirmPasswordReset(
            PasswordResetConfirmRequest(
                email = email.trim(),
                resetCode = code.trim(),
                newPassword = newPassword,
                newPasswordConfirm = newPasswordConfirm,
            )
        )
    }

    // Partly AI-generated (Claude)
    /**
     * 로그인한 사용자의 비밀번호 변경(`POST /api/auth/me/password`). 서버가 모든 기기의 토큰을 취소하고
     * 이 기기의 새 토큰을 준다. 받으면 바로 저장해서 이 기기는 로그인된 채로 남는다.
     */
    suspend fun changePassword(
        current: String,
        newPassword: String,
        newPasswordConfirm: String,
    ): PasswordChangeResult = try {
        val response = api.changePassword(PasswordChangeRequest(current, newPassword, newPasswordConfirm))
        if (response.isSuccessful) {
            val access = response.body()?.access?.trim().orEmpty()
            val refresh = response.body()?.refresh?.trim().orEmpty()
            if (access.isNotEmpty() && refresh.isNotEmpty()) {
                tokenStore.updateTokens(access, refresh)
                PasswordChangeResult.Success(tokensRotated = true)
            } else {
                PasswordChangeResult.Success(tokensRotated = false)
            }
        } else {
            val error = errorBodyOf(response.errorBody()?.string())
            PasswordChangeResult.Failure(
                message = passwordFlowMessage(response.code(), error),
                currentPasswordWrong = error?.code == "invalid_current_password",
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        PasswordChangeResult.Failure(MESSAGE_NETWORK)
    } catch (e: TokenStorageException) {
        // 비밀번호는 바뀌었지만 새 토큰을 못 저장했다. 옛 토큰은 서버가 취소했으니 다시 로그인해야 한다.
        tokenStore.clear()
        PasswordChangeResult.Failure(MESSAGE_STORAGE)
    } catch (e: Exception) {
        PasswordChangeResult.Failure(MESSAGE_UNKNOWN)
    }

    // Partly AI-generated (Claude)
    /**
     * 카카오·구글 ID 토큰으로 로그인한다(`POST /api/auth/social/{provider}`). 이미 연결된 사용자면
     * 바로 로그인, 처음이면 [termsAgreed] 가 있어야 가입한다. 성공하면 이메일 로그인과 같이 토큰을
     * 저장한다. 예외를 밖으로 던지지 않는다(코루틴 취소만 다시 던진다).
     */
    suspend fun socialLogin(
        provider: SocialProvider,
        idToken: String,
        termsAgreed: Boolean = false,
        nickname: String? = null,
    ): SocialResult = try {
        val response = api.socialLogin(
            provider.path,
            SocialLoginRequest(
                idToken = idToken,
                termsAgreed = true.takeIf { termsAgreed },
                nickname = nickname?.trim()?.take(SocialLogin.NICKNAME_MAX)?.takeIf { it.isNotEmpty() },
            ),
        )
        if (response.isSuccessful) {
            when (val stored = store(response.body())) {
                is AuthResult.Success -> SocialResult.Success(stored.user)
                is AuthResult.Failure -> SocialResult.Failure(stored.message)
            }
        } else {
            val error = errorBodyOf(response.errorBody()?.string())
            when (error?.code) {
                "social_terms_required" -> SocialResult.TermsRequired
                "social_email_in_use" -> SocialResult.EmailInUse(loginMethodsOf(error.details))
                "social_not_configured" -> SocialResult.Unavailable
                "social_token_invalid" ->
                    SocialResult.Failure("${provider.label} 로그인을 확인하지 못했다. 다시 시도한다.")
                "social_unavailable" ->
                    SocialResult.Failure("${provider.label} 확인 서버에 닿지 못했다. 잠시 뒤 다시 시도한다.")
                else -> SocialResult.Failure(
                    error?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(response.code())
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        SocialResult.Failure(MESSAGE_NETWORK)
    } catch (e: TokenStorageException) {
        tokenStore.clear()
        SocialResult.Failure(MESSAGE_STORAGE)
    } catch (e: Exception) {
        SocialResult.Failure(MESSAGE_UNKNOWN)
    }

    /** 로그인한 사용자(`GET /api/auth/me`). 설정의 "로그인 연결" 이 `login_methods` 를 쓴다 */
    suspend fun currentUser(): AccountResult = account(provider = null) { api.currentUser() }

    /** 로그인한 계정에 소셜 계정을 잇는다(`POST /api/auth/me/social/{provider}`) */
    suspend fun linkSocial(provider: SocialProvider, idToken: String): AccountResult =
        account(provider) { api.linkSocial(provider.path, SocialLinkRequest(idToken)).map { it?.user } }

    /** 끊는다(`DELETE /api/auth/me/social/{provider}`). 유일한 로그인 방법이면 서버가 거절한다 */
    suspend fun unlinkSocial(provider: SocialProvider): AccountResult =
        account(provider) { api.unlinkSocial(provider.path).map { it?.user } }

    private suspend fun account(
        provider: SocialProvider?,
        block: suspend () -> retrofit2.Response<UserDto>,
    ): AccountResult = try {
        val response = block()
        val user = response.body()
        if (response.isSuccessful && user != null) {
            AccountResult.Success(user)
        } else {
            val error = errorBodyOf(response.errorBody()?.string())
            val label = provider?.label ?: "소셜"
            AccountResult.Failure(
                when (error?.code) {
                    "social_already_linked" -> "이 $label 계정은 다른 JustInTime 계정에 연결돼 있음"
                    "social_provider_linked" -> "이미 다른 $label 계정이 연결돼 있음. 먼저 끊어야 함"
                    "social_last_login_method" -> "유일한 로그인 방법이라 끊을 수 없음"
                    "social_token_invalid" -> "$label 확인에 실패함. 다시 시도"
                    "social_not_configured" -> "지금은 $label 연결을 쓸 수 없음"
                    else -> when {
                        response.code() == 404 && provider != null -> "연결된 $label 계정이 없음"
                        else -> error?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(response.code())
                    }
                }
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        AccountResult.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        AccountResult.Failure(MESSAGE_UNKNOWN)
    }

    /**
     * register / login 이 같은 응답 모양을 쓰므로 공통 처리한다.
     * 성공하면 토큰을 저장하는 것까지가 이 함수의 책임이다.
     */
    private suspend fun call(
        errorMessage: ((Int, ApiErrorBody?) -> String)? = null,
        block: suspend () -> retrofit2.Response<com.swpp.wakeup.data.remote.AuthResponse>,
    ): AuthResult {
        return try {
            val response = block()
            val body = response.body()
            if (!response.isSuccessful && errorMessage != null) {
                AuthResult.Failure(errorMessage(response.code(), errorBodyOf(response.errorBody()?.string())))
            } else if (!response.isSuccessful || body == null) {
                val serverMessage = ApiClient.parseErrorMessage(response.errorBody()?.string())
                AuthResult.Failure(serverMessage ?: defaultMessage(response.code()))
            } else {
                store(body)
            }
        } catch (e: IOException) {
            // 서버가 꺼져 있거나 에뮬레이터가 호스트에 못 닿는 경우가 대부분이다.
            AuthResult.Failure(MESSAGE_NETWORK)
        } catch (e: TokenStorageException) {
            // 기기 보안 저장소(Keystore)가 토큰을 받지 못했다. 로그인한 상태로 두지 않는다.
            tokenStore.clear()
            AuthResult.Failure(MESSAGE_STORAGE)
        } catch (e: Exception) {
            AuthResult.Failure(MESSAGE_UNKNOWN)
        }
    }

    /**
     * 받은 토큰을 저장한다. 이메일·소셜 로그인이 같이 쓴다.
     *
     * 2xx 인데 토큰이 없으면 **성공으로 취급하지 않는다.** 저장해 봐도 `TokenStore.isLoggedIn` 이
     * false 라서, 화면만 넘어가고 첫 인증 요청이 401 을 받아 세션 만료로 로그인 화면으로 되돌아온다.
     * 사용자에게는 "가입했는데 다시 로그인 화면" 으로 보이고, 원인은 어디에도 남지 않는다. 여기서
     * 끊어야 그 경로가 생기지 않는다.
     */
    private suspend fun store(body: AuthResponse?): AuthResult {
        val access = body?.access?.trim().orEmpty()
        val refresh = body?.refresh?.trim().orEmpty()
        val user = body?.user
        if (access.isEmpty() || refresh.isEmpty() || user == null) return AuthResult.Failure(MESSAGE_NO_TOKEN)
        // 토큰을 쓰기 전에 앞 사용자의 흔적을 지운다. 순서가 반대면 새 사용자의 첫 캐시와 경쟁한다.
        onBeforeAuthenticated()
        tokenStore.save(
            access = access,
            refresh = refresh,
            email = user.email,
            nickname = user.nickname,
        )
        return AuthResult.Success(user)
    }

    // Partly AI-generated (Claude)
    /** 내 이메일로 인증 코드를 보낸다(`POST /api/auth/me/email/verify`). 이미 인증했으면 [EmailVerifyResult.AlreadyVerified] */
    suspend fun requestEmailVerify(): EmailVerifyResult = emailVerifyCall { api.requestEmailVerify() }
        .let { (response, failure) ->
            if (response?.isSuccessful == true) EmailVerifyResult.Sent(response.body()?.expiresIn) else failure!!
        }

    // Partly AI-generated (Claude)
    /** 코드를 확인한다(`POST /api/auth/me/email/verify/confirm`). 코드는 앞뒤 공백을 자른다 */
    suspend fun confirmEmailVerify(code: String): EmailVerifyResult =
        emailVerifyCall { api.confirmEmailVerify(EmailVerifyConfirmRequest(code.trim())) }
            .let { (response, failure) ->
                if (response?.isSuccessful == true) EmailVerifyResult.Verified(response.body()?.user) else failure!!
            }

    /** 호출하고 (성공 응답, null) 또는 (null, 실패 결과) 로 돌려준다. 예외를 밖으로 던지지 않는다 */
    private suspend fun <T> emailVerifyCall(
        block: suspend () -> retrofit2.Response<T>,
    ): Pair<retrofit2.Response<T>?, EmailVerifyResult?> = try {
        val response = block()
        if (response.isSuccessful) {
            response to null
        } else {
            val error = errorBodyOf(response.errorBody()?.string())
            null to when {
                response.code() == 409 && error?.code == "email_already_verified" -> EmailVerifyResult.AlreadyVerified
                else -> EmailVerifyResult.Failure(emailVerifyMessage(response.code(), error))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null to EmailVerifyResult.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        null to EmailVerifyResult.Failure(MESSAGE_UNKNOWN)
    }

    private fun emailVerifyMessage(status: Int, error: ApiErrorBody?): String = when {
        status == 404 || status == 405 -> MESSAGE_UNSUPPORTED
        status == 429 || error?.code == "throttled" -> "요청이 많음. 잠시 뒤 다시 시도"
        else -> when (error?.code) {
            "email_not_deliverable" -> "이 주소로는 메일을 보낼 수 없음"
            "invalid_verify_code" -> "코드가 맞지 않음"
            "verify_code_expired" -> "코드가 만료됨. 다시 받아야 함"
            "too_many_attempts" -> "시도가 너무 많음. 코드를 다시 받아야 함"
            else -> error?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(status)
        }
    }

    // Partly AI-generated (Claude)
    /** 재설정·변경 호출의 실패를 사용자 문구로. 서버가 아직 없으면(404/405) 그렇게 알린다 */
    private fun passwordFlowMessage(status: Int, error: ApiErrorBody?): String = when {
        status == 404 || status == 405 -> MESSAGE_UNSUPPORTED
        status == 429 || error?.code == "throttled" -> "요청이 많음. 잠시 뒤 다시 시도"
        else -> when (error?.code) {
            "invalid_reset_code" -> "코드가 맞지 않음"
            "reset_code_expired" -> "코드가 만료됨. 다시 받아야 함"
            "too_many_attempts" -> "시도가 너무 많음. 코드를 다시 받아야 함"
            "invalid_current_password" -> "지금 비밀번호가 맞지 않음"
            "password_unchanged" -> "지금 비밀번호와 같음"
            "password_login_unavailable" -> "이 계정은 비밀번호로 로그인하지 않음"
            else -> error?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(status)
        }
    }

    private fun defaultMessage(code: Int): String = when (code) {
        400 -> "입력값을 확인해야 한다."
        401 -> "이메일 또는 비밀번호가 올바르지 않다."
        409 -> "이미 가입된 이메일이다."
        in 500..599 -> "서버에 문제가 생겼다. 잠시 후 다시 시도한다."
        else -> MESSAGE_UNKNOWN
    }

    internal companion object {
        const val MESSAGE_NETWORK = "서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."
        const val MESSAGE_UNKNOWN = "알 수 없는 오류가 발생했다."

        /** 재설정·변경 API 가 서버에 아직 없을 때(404/405). 계약은 backend-requests.md R3-9 */
        const val MESSAGE_UNSUPPORTED = "아직 서버에서 지원하지 않아요"

        /** 2xx 인데 토큰이 비어 있을 때. 서버 배포 버전이 낮으면 이렇게 온다. */
        const val MESSAGE_NO_TOKEN = "서버가 토큰을 주지 않았다. 서버 버전을 확인한다."

        /** 기기 보안 저장소에 토큰을 쓰지 못했을 때 */
        const val MESSAGE_STORAGE = "이 기기에 로그인 정보를 안전하게 저장하지 못했다. 다시 시도한다."
    }
}

// Partly AI-generated (Claude)
/** 실패 본문의 `error`. 못 읽으면 null */
private fun errorBodyOf(raw: String?): ApiErrorBody? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Gson().fromJson(raw, ApiErrorEnvelope::class.java)?.error }.getOrNull()
}

/** 409 `social_email_in_use` 의 `details.login_methods`. 문자열만 남긴다 */
internal fun loginMethodsOf(details: Map<String, Any?>?): List<String> =
    (details?.get("login_methods") as? List<*>)
        ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()

/** 성공 본문을 다른 모양으로. 상태 코드와 실패 본문은 그대로 둔다 */
private inline fun <T, R> retrofit2.Response<T>.map(transform: (T?) -> R?): retrofit2.Response<R> =
    if (isSuccessful) {
        retrofit2.Response.success(code(), transform(body()))
    } else {
        @Suppress("UNCHECKED_CAST")
        this as retrofit2.Response<R>
    }
