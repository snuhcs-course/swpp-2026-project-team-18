package com.swpp.wakeup.data.remote

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.data.local.TokenStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 클라이언트.
 *
 * [init] 을 [com.swpp.wakeup.JitApplication] 에서 한 번 호출한다. [TokenStore] 가
 * Context 를 필요로 해서 object 초기화 시점에 만들 수 없다.
 *
 * DI(Hilt)를 넣으면 이 object 는 모듈로 대체된다. FE-P0-03 미완 항목이다.
 */
object ApiClient {

    private lateinit var tokenStore: TokenStore

    fun init(store: TokenStore) {
        tokenStore = store
    }

    /**
     * 비밀번호를 로그에서 지운다.
     *
     * `Level.BODY` 는 API 작업에 유용하지만 회원가입·로그인 요청 본문에 비밀번호
     * 원문이 그대로 찍힌다. logcat 은 adb 로 누구나 읽을 수 있고 화면 공유·버그
     * 리포트에 섞여 나간다. 디버그 빌드라도 남길 이유가 없다.
     *
     * 값만 가리고 필드 이름은 남긴다. 어떤 필드가 갔는지는 디버깅에 필요하다.
     */
    private val redactingLogger = HttpLoggingInterceptor.Logger { message ->
        HttpLoggingInterceptor.Logger.DEFAULT.log(SECRET_FIELD.replace(message) {
            "\"${it.groupValues[1]}\":\"***\""
        })
    }

    private val logging = HttpLoggingInterceptor(redactingLogger).apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 순서가 중요하다. 인증 헤더를 먼저 붙이고 그 다음 로깅해야
            // 로그에 실제로 나간 헤더가 찍힌다.
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .authenticator(TokenRefreshAuthenticator(tokenStore) { auth })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val health: HealthApi by lazy { retrofit.create(HealthApi::class.java) }

    val auth: AuthApi by lazy { retrofit.create(AuthApi::class.java) }

    val events: EventsApi by lazy { retrofit.create(EventsApi::class.java) }

    val profile: ProfileApi by lazy { retrofit.create(ProfileApi::class.java) }

    /**
     * 실패 응답 본문에서 사용자에게 보여줄 메시지를 뽑는다.
     *
     * 서버는 back-spec 5절 공통 포맷 `{"error":{code,message,details}}` 을 준다.
     * 파싱이 실패하면 (프록시가 HTML 을 끼워넣는 경우 등) null 을 돌려주고,
     * 호출부가 상태 코드 기반 기본 문구로 떨어지게 한다.
     */
    fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            Gson().fromJson(raw, ApiErrorEnvelope::class.java)
                ?.error?.message?.takeIf { it.isNotBlank() }
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    /** `"password":"..."`, `"password_confirm":"..."` 형태를 잡는다. */
    private val SECRET_FIELD =
        Regex("\"(password|password_confirm|old_password|new_password)\"\\s*:\\s*\"[^\"]*\"")
}
