package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

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

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/token")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/auth/token/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<RefreshResponse>

    @GET("api/auth/me")
    suspend fun me(@Header("Authorization") bearer: String): Response<UserDto>
}

// --- 요청 -------------------------------------------------------------------

data class RegisterRequest(
    val email: String,
    val nickname: String,
    val password: String,
    @SerializedName("password_confirm") val passwordConfirm: String,
)

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
)

/**
 * 회원가입과 로그인이 같은 모양을 돌려준다.
 * 회원가입만 `user_id` 를 추가로 담지만 `user.id` 와 같은 값이라 쓰지 않는다.
 */
data class AuthResponse(
    val access: String,
    val refresh: String,
    val user: UserDto,
)

data class RefreshResponse(
    val access: String,
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
