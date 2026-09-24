package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.AuthApi
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.remote.UserDto
import java.io.IOException

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

    suspend fun register(
        email: String,
        nickname: String,
        password: String,
        passwordConfirm: String,
    ): AuthResult = call {
        api.register(
            RegisterRequest(
                email = email.trim(),
                nickname = nickname.trim(),
                password = password,
                passwordConfirm = passwordConfirm,
            )
        )
    }

    suspend fun login(email: String, password: String): AuthResult = call {
        api.login(LoginRequest(email = email.trim(), password = password))
    }

    fun logout() = tokenStore.clear()

    /**
     * register / login 이 같은 응답 모양을 쓰므로 공통 처리한다.
     * 성공하면 토큰을 저장하는 것까지가 이 함수의 책임이다.
     */
    private suspend fun call(
        block: suspend () -> retrofit2.Response<com.swpp.wakeup.data.remote.AuthResponse>,
    ): AuthResult {
        return try {
            val response = block()
            val body = response.body()
            val access = body?.access?.trim().orEmpty()
            val refresh = body?.refresh?.trim().orEmpty()
            val user = body?.user
            when {
                !response.isSuccessful || body == null -> {
                    val serverMessage = ApiClient.parseErrorMessage(response.errorBody()?.string())
                    AuthResult.Failure(serverMessage ?: defaultMessage(response.code()))
                }

                // 2xx 인데 토큰이 없으면 **성공으로 취급하지 않는다.**
                //
                // 저장해 봐도 `TokenStore.isLoggedIn` 이 false 라서, 화면만
                // 넘어가고 첫 인증 요청이 401 을 받아 세션 만료로 로그인
                // 화면으로 되돌아온다. 사용자에게는 "가입했는데 다시 로그인
                // 화면" 으로 보이고, 원인은 어디에도 남지 않는다. 여기서
                // 끊어야 그 경로가 생기지 않는다.
                access.isEmpty() || refresh.isEmpty() || user == null ->
                    AuthResult.Failure(MESSAGE_NO_TOKEN)

                else -> {
                    // 토큰을 쓰기 전에 앞 사용자의 흔적을 지운다. 순서가 반대면
                    // 새 사용자의 첫 캐시와 경쟁한다.
                    onBeforeAuthenticated()
                    tokenStore.save(
                        access = access,
                        refresh = refresh,
                        email = user.email,
                        nickname = user.nickname,
                    )
                    AuthResult.Success(user)
                }
            }
        } catch (e: IOException) {
            // 서버가 꺼져 있거나 에뮬레이터가 호스트에 못 닿는 경우가 대부분이다.
            AuthResult.Failure(MESSAGE_NETWORK)
        } catch (e: Exception) {
            AuthResult.Failure(MESSAGE_UNKNOWN)
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

        /** 2xx 인데 토큰이 비어 있을 때. 서버 배포 버전이 낮으면 이렇게 온다. */
        const val MESSAGE_NO_TOKEN = "서버가 토큰을 주지 않았다. 서버 버전을 확인한다."
    }
}
