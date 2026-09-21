package com.swpp.wakeup.data.remote

import android.os.Build
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
            // 재시도는 로깅 뒤에 둔다. 앞에 두면 재시도한 요청이 로그에
            // 한 번만 찍혀 몇 번 시도했는지 알 수 없다.
            .addInterceptor(ColdStartRetryInterceptor())
            .authenticator(TokenRefreshAuthenticator(tokenStore) { auth })
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 재시도까지 포함한 전체 상한. 이게 없으면 느린 망에서 요청이
            // 무한정 매달려 화면이 영영 로딩 상태로 남는다.
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 실제로 쓸 서버 주소.
     *
     * 기본은 **팀 공용 서버**다(`gradle.properties` 의 `jitApiBaseUrl`, 커밋됨).
     * 레포를 clone 한 사람이 아무 설정 없이 같은 서버·같은 데이터에 붙는다.
     *
     * 백엔드를 고치는 사람은 `local.properties` 에 `devServerHost` 를 적어
     * 자기 PC 로 돌린다. 그때만 주소가 `http://<호스트>:8000` 이 된다.
     *
     * **에뮬레이터 분기는 로컬 백엔드 모드에서만 의미가 있다.** 에뮬레이터는
     * 호스트를 10.0.2.2 로 보고 개발 PC 의 LAN IP 에는 닿지 못한다. 공용 서버를
     * 쓸 때는 두 값이 같으므로 분기해도 결과가 같다. 빌드 시점에 고를 수 없는
     * 값이라 런타임에 고르고, 덕분에 한 APK 로 실기기와 에뮬레이터가 모두 된다.
     */
    val baseUrl: String
        get() = if (BuildConfig.DEBUG && isEmulator) {
            BuildConfig.EMULATOR_BASE_URL
        } else {
            BuildConfig.BASE_URL
        }

    /**
     * 에뮬레이터인지.
     *
     * `Build.HARDWARE` 가 가장 확실하다 — 현대 AVD 는 `ranchu`, 구형은
     * `goldfish` 다. 나머지는 클라우드 에뮬레이터·구형 이미지 대비 보조 판정이다.
     */
    private val isEmulator: Boolean by lazy {
        Build.HARDWARE in setOf("goldfish", "ranchu", "gce_x86", "cutf_cvm") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.contains("sdk_gphone")
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val health: HealthApi by lazy { retrofit.create(HealthApi::class.java) }

    val auth: AuthApi by lazy { retrofit.create(AuthApi::class.java) }

    val events: EventsApi by lazy { retrofit.create(EventsApi::class.java) }

    val profile: ProfileApi by lazy { retrofit.create(ProfileApi::class.java) }

    val observations: ObservationsApi by lazy {
        retrofit.create(ObservationsApi::class.java)
    }

    /** 초기화됐는지. 서비스·리시버는 Application 보다 먼저 깨어날 수 있다. */
    val isReady: Boolean get() = ::tokenStore.isInitialized

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

    // ---------------------------------------------------------------------
    // 타임아웃
    // ---------------------------------------------------------------------
    // **콜드 스타트를 견디는 값이다.** 공용 서버는 Render 무료 플랜이고,
    // 15분 동안 요청이 없으면 잠든다. 다시 깨는 데 측정값 11초, 문서상 최대
    // 1분이 걸린다. 종전 값(연결 10초 / 읽기 15초)으로는 첫 요청이 그
    // 안에 안 끝나 "서버에 연결할 수 없다" 가 떴다 - 실제로 측정해 확인했다.
    //
    // 깨어 있을 때 응답은 0.14초다. 타임아웃을 늘려도 정상 상태의 체감은
    // 달라지지 않는다. 늘리는 비용은 "서버가 죽었을 때 오래 기다린다" 뿐이고,
    // 그건 callTimeout 이 막는다.

    /** 연결 수립. Render 엣지는 잠들어 있어도 비교적 빨리 받는다. */
    private const val CONNECT_TIMEOUT_SECONDS = 20L

    /** 응답 대기. 콜드 스타트 지연이 여기에 실린다. */
    private const val READ_TIMEOUT_SECONDS = 60L

    private const val WRITE_TIMEOUT_SECONDS = 20L

    /** 재시도를 포함한 전체 상한. 이걸 넘으면 포기하고 화면에 알린다. */
    private const val CALL_TIMEOUT_SECONDS = 90L
}
