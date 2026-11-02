package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * back-spec.md 5.1 인증.
 *
 * 서버는 snake_case 를 쓴다. 코틀린 쪽 이름은 camelCase 로 두고 [SerializedName]
 * 으로 매핑한다. Gson 의 FieldNamingPolicy 를 전역으로 바꾸지 않는 이유는,
 * 다른 응답에 camelCase 필드가 섞여 들어올 때 전역 설정이 조용히 깨지기 때문이다.
 *
 * 모든 함수가 [Response] 를 돌려준다. 실패 본문(`{"error":{...}}`)을 읽어야 하므로
 * 예외로 던지게 두면 서버가 준 메시지를 잃는다.
 */
interface AuthApi {

    @POST("api/auth/password/check")
    suspend fun checkPassword(@Body body: PasswordCheckRequest): Response<PasswordCheckResponse>

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/token")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/auth/token/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<RefreshResponse>

    @GET("api/auth/me")
    suspend fun me(@Header("Authorization") bearer: String): Response<UserDto>

    /**
     * 이 기기 로그아웃. refresh 를 서버 취소 목록에 넣는다(docs/iter3/token-security-api.md §5).
     * 인증 헤더 없이 부른다([AuthInterceptor] 공개 경로). 이미 취소된 토큰도 204.
     */
    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    // --- 비밀번호 재설정·변경 (제안 계약 R3-9) — Partly AI-generated (Claude) ---

    /** 가입한 이메일로 6자리 코드를 보낸다. 인증 없이 부른다. 202. 서버가 아직 없으면 404/405 */
    @POST("api/auth/password/reset")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): Response<PasswordResetResponse>

    /** 코드와 새 비밀번호로 바꾸고 로그인 응답을 받는다. 인증 없이 부른다 */
    @POST("api/auth/password/reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirmRequest): Response<AuthResponse>

    /** 로그인한 사용자의 비밀번호 변경. 서버가 모든 기기를 로그아웃시키고 이 기기의 새 토큰을 준다 */
    @POST("api/auth/me/password")
    suspend fun changePassword(@Body body: PasswordChangeRequest): Response<PasswordChangeResponse>

    /** 모든 기기 로그아웃. Bearer 가 필요하다. 응답 `{"revoked": n}` */
    @POST("api/auth/me/logout-all")
    suspend fun logoutAll(): Response<LogoutAllResponse>

    // --- 소셜 로그인 (docs/iter3/social-login-api.md) — Partly AI-generated (Claude) ---

    /**
     * 카카오·구글 ID 토큰으로 로그인(없으면 가입). 인증 없이 부른다([AuthInterceptor] 공개 경로).
     * 200 기존 사용자, 201 새 계정. 400 `social_terms_required`, 409 `social_email_in_use`,
     * 401 `social_token_invalid`, 503 `social_not_configured`·`social_unavailable`.
     */
    @POST("api/auth/social/{provider}")
    suspend fun socialLogin(
        @Path("provider") provider: String,
        @Body body: SocialLoginRequest,
    ): Response<AuthResponse>

    /** 로그인한 사용자. `login_methods` 를 설정 화면에 쓴다. 인증 헤더는 인터셉터가 붙인다 */
    @GET("api/auth/me")
    suspend fun currentUser(): Response<UserDto>

    /** 로그인한 계정에 소셜 계정을 잇는다. 409 `social_already_linked`·`social_provider_linked` */
    @POST("api/auth/me/social/{provider}")
    suspend fun linkSocial(
        @Path("provider") provider: String,
        @Body body: SocialLinkRequest,
    ): Response<SocialUserResponse>

    /** 끊는다. 404 연결 없음, 400 `social_last_login_method` */
    @DELETE("api/auth/me/social/{provider}")
    suspend fun unlinkSocial(@Path("provider") provider: String): Response<SocialUserResponse>
}

data class LogoutAllResponse(val revoked: Int? = null)

// Partly AI-generated (Claude)
data class PasswordResetRequest(val email: String)

/** 응답 필드는 모두 nullable. 구버전·다른 모양에도 죽지 않는다 */
data class PasswordResetResponse(
    val sent: Boolean? = null,
    @SerializedName("expires_in") val expiresIn: Int? = null,
)

data class PasswordResetConfirmRequest(
    val email: String,
    @SerializedName("reset_code") val resetCode: String,
    @SerializedName("new_password") val newPassword: String,
    @SerializedName("new_password_confirm") val newPasswordConfirm: String,
)

data class PasswordChangeRequest(
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String,
    @SerializedName("new_password_confirm") val newPasswordConfirm: String,
)

data class PasswordChangeResponse(
    val access: String? = null,
    val refresh: String? = null,
    val revoked: Int? = null,
)

/**
 * 소셜 로그인 본문. [termsAgreed] 는 새 계정일 때만 `true` 로 보낸다(null 이면 빠진다).
 * [nickname] 은 새 계정의 닉네임(20자). 없으면 토큰의 닉네임을 쓴다.
 */
data class SocialLoginRequest(
    @SerializedName("id_token") val idToken: String,
    @SerializedName("terms_agreed") val termsAgreed: Boolean? = null,
    val nickname: String? = null,
)

data class SocialLinkRequest(
    @SerializedName("id_token") val idToken: String,
)

/** 연결·끊기 응답 `{"user": {...}}` */
data class SocialUserResponse(val user: UserDto? = null)

// --- 요청 -------------------------------------------------------------------

data class RegisterRequest(
    val email: String,
    val nickname: String,
    val password: String,
    @SerializedName("password_confirm") val passwordConfirm: String,
    @SerializedName("terms_agreed") val termsAgreed: Boolean,
)

data class PasswordCheckRequest(val password: String)

data class PasswordCheckResponse(
    @SerializedName("min_length") val minLength: Boolean?,
    @SerializedName("letters_and_digits") val lettersAndDigits: Boolean?,
    val messages: List<String>?,
) {
    val valid: Boolean
        get() = minLength == true && lettersAndDigits == true && messages?.isEmpty() == true
}

data class LoginRequest(
    val email: String,
    val password: String,
)

data class RefreshRequest(
    val refresh: String,
)

// --- 응답 -------------------------------------------------------------------

data class UserDto(
    val id: Long,
    val email: String,
    val nickname: String,
    @SerializedName("date_joined") val dateJoined: String? = null,
    /**
     * 가진 로그인 방법. `password`·`kakao`·`google`(social-login-api.md §4). 구버전 서버는 없다.
     * (Partly AI-generated (Claude))
     */
    @SerializedName("login_methods") val loginMethods: List<String>? = null,
)

/**
 * 회원가입과 로그인이 같은 모양을 돌려준다.
 * 회원가입만 `user_id` 를 추가로 담지만 `user.id` 와 같은 값이라 쓰지 않는다.
 *
 * **전부 nullable 이다.** Gson 은 Kotlin 의 non-null 선언을 검사하지 않으므로,
 * 응답에 필드가 없으면 `String` 으로 선언해 둬도 null 이 들어온다. 그러면
 * 토큰을 null 로 저장한 채 "성공" 으로 진행하고, 첫 인증 요청이 401 을 받아
 * 세션 만료로 로그인 화면으로 튕긴다 — 사용자에게는 "가입했는데 다시 로그인
 * 화면" 으로 보인다. 같은 부류를 `RouteSegmentDto` 에서 이미 겪었다.
 *
 * 비어 있는지는 [com.swpp.wakeup.data.repository.AuthRepository] 가 판단한다.
 */
data class AuthResponse(
    val access: String? = null,
    val refresh: String? = null,
    val user: UserDto? = null,
)

/**
 * 갱신 응답. 서버가 refresh 회전을 켜면(`JWT_ROTATE_REFRESH_TOKENS=1`) [refresh] 가 온다.
 * 그때는 새 refresh 를 저장해야 한다 — 옛 것은 취소돼 다음 갱신에서 로그아웃된다.
 */
data class RefreshResponse(
    val access: String? = null,
    val refresh: String? = null,
)

// --- 에러 -------------------------------------------------------------------

/** back-spec.md 5절 공통 에러 포맷 `{"error": {code, message, details}}` */
data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
)

data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: Map<String, Any?>? = null,
)
