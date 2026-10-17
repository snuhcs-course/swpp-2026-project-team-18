package com.swpp.wakeup.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.gson.Gson
import com.swpp.wakeup.data.remote.AuthApi
import com.swpp.wakeup.data.remote.AuthResponse
import com.swpp.wakeup.data.remote.BlockObservationBatchRequest
import com.swpp.wakeup.data.remote.BlockObservationBatchResponse
import com.swpp.wakeup.data.remote.BlockSelectionRequest
import com.swpp.wakeup.data.remote.CalendarImportRequest
import com.swpp.wakeup.data.remote.CalendarImportResponse
import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.EventBlocksResponse
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.EventUpdateRequest
import com.swpp.wakeup.data.remote.EventsApi
import com.swpp.wakeup.data.remote.LiveRouteRequest
import com.swpp.wakeup.data.remote.LiveRouteResponse
import com.swpp.wakeup.data.remote.LoginRequest
import com.swpp.wakeup.data.remote.Paged
import com.swpp.wakeup.data.remote.PasswordCheckRequest
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.PlaceReverseResponse
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.data.remote.ProfileApi
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.ProfileUpdateRequest
import com.swpp.wakeup.data.remote.RefreshRequest
import com.swpp.wakeup.data.remote.RefreshResponse
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.remote.ReportsApi
import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.data.remote.RoutineBlockDto
import com.swpp.wakeup.data.remote.RoutineBlockWriteRequest
import com.swpp.wakeup.data.remote.RoutinesApi
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/*
 * Repository 단위 테스트용 가짜들.
 *
 * ## 왜 손으로 만든 Fake 인가
 *
 * 저장소는 생성자로 API 인터페이스를 받으므로 그 자리에 가짜를 넣으면 서버 없이
 * JVM 에서 돈다. 목킹 라이브러리 없이 인터페이스를 직접 구현한 이유는 두 가지다.
 *
 * 1. 테스트가 부르지 않을 메서드는 [unexpected] 로 **크게 실패**한다. 목 라이브러리의
 *    느슨한 기본값은 "부르면 안 되는 API 를 불렀다" 를 조용히 통과시킨다.
 * 2. 던지는 것이 `AssertionError`(Error)라 저장소의 `catch (e: Exception)` 에
 *    삼켜지지 않는다. 삼켜지면 "알 수 없는 오류" 로 바뀌어 테스트가 엉뚱한
 *    이유로 통과할 수 있다.
 *
 * 각 테스트는 `object : FakeEventsApi() { override suspend fun list(...) = ... }`
 * 처럼 필요한 메서드만 덮어쓴다.
 */

/** 이 테스트가 부르지 않아야 할 API 를 불렀다. */
fun unexpected(name: String): Nothing = throw AssertionError("예상하지 않은 API 호출: $name")

// --- 응답 ------------------------------------------------------------------

fun <T> ok(body: T): Response<T> = Response.success(body)

/**
 * 실패 응답. [json] 은 서버 공통 에러 포맷
 * `{"error":{"code":..,"message":..,"details":{..}}}` 을 그대로 쓴다.
 */
fun <T> httpError(code: Int, json: String = ""): Response<T> =
    Response.error(code, json.toResponseBody("application/json".toMediaType()))

/** 서버 에러 본문. `details` 는 필드별 메시지 배열이다. */
fun errorJson(message: String, details: Map<String, String> = emptyMap()): String {
    val detailJson = details.entries.joinToString(",") { (k, v) -> "\"$k\":[\"$v\"]" }
    return """{"error":{"code":"invalid","message":"$message","details":{$detailJson}}}"""
}

private val gson = Gson()

/**
 * 서버 응답 JSON 을 DTO 로. 픽스처를 생성자 대신 JSON 으로 만드는 이유:
 * 실제 앱도 Gson 으로 받으므로 필드 이름(`@SerializedName`)까지 함께 검증된다.
 * 생성자로 만들면 필드 이름이 틀려도 테스트는 통과한다.
 */
inline fun <reified T> fromJson(json: String): T = parse(json, T::class.java)

fun <T> parse(json: String, type: Class<T>): T = gson.fromJson(json, type)

// --- 안드로이드 -------------------------------------------------------------

/** 메모리 SharedPreferences. `TokenStore` 를 JVM 에서 쓰기 위한 것이다. */
class InMemoryPrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? =
        values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as MutableSet<String>? ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = PendingEdit()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** 실제처럼 apply/commit 전에는 반영하지 않는다. */
    private inner class PendingEdit : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearFirst = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFirst) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}

/**
 * SharedPreferences 만 돌려주는 Context.
 *
 * Robolectric 이 없으므로 `ContextWrapper` 를 상속해 필요한 두 메서드만 채운다.
 * 나머지 메서드는 안드로이드 스텁이 기본값을 돌려준다(build.gradle.kts 의
 * `isReturnDefaultValues = true`).
 */
fun prefsContext(prefs: SharedPreferences = InMemoryPrefs()): Context =
    object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

// --- API 가짜 ---------------------------------------------------------------

open class FakeAuthApi : AuthApi {
    override suspend fun checkPassword(body: PasswordCheckRequest): Response<PasswordCheckResponse> =
        unexpected("checkPassword")

    override suspend fun register(body: RegisterRequest): Response<AuthResponse> =
        unexpected("register")

    override suspend fun login(body: LoginRequest): Response<AuthResponse> = unexpected("login")
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> =
        unexpected("refresh")

    override suspend fun me(bearer: String): Response<UserDto> = unexpected("me")
}

open class FakeEventsApi : EventsApi {
    override suspend fun list(from: String?, to: String?, limit: Int): Response<Paged<EventDto>> =
        unexpected("list")

    override suspend fun get(id: Long): Response<EventDto> = unexpected("get")
    override suspend fun create(body: EventCreateRequest): Response<EventDto> = unexpected("create")
    override suspend fun update(id: Long, body: EventUpdateRequest): Response<EventDto> =
        unexpected("update")

    override suspend fun delete(id: Long): Response<Unit> = unexpected("delete")
    override suspend fun recompute(id: Long): Response<EventDto> = unexpected("recompute")
    override suspend fun importCalendar(body: CalendarImportRequest): Response<CalendarImportResponse> =
        unexpected("importCalendar")

    override suspend fun tags(): Response<List<EventTagDto>> = unexpected("tags")
    override suspend fun searchPlaces(
        query: String,
        lat: Double?,
        lng: Double?,
        page: Int,
        sort: String?,
        rect: String?,
    ): Response<PlaceSearchResponse> = unexpected("searchPlaces")

    override suspend fun staticMap(
        lat: Double,
        lng: Double,
        level: Int,
        width: Int,
        height: Int,
        markers: String?,
    ): Response<ResponseBody> = unexpected("staticMap")

    override suspend fun reversePlace(lat: Double, lng: Double): Response<PlaceReverseResponse> =
        unexpected("reversePlace")

    override suspend fun routeCandidates(
        destLat: Double,
        destLng: Double,
        originLat: Double?,
        originLng: Double?,
        originLabel: String?,
        destLabel: String?,
    ): Response<RouteCandidateResponse> = unexpected("routeCandidates")

    override suspend fun riskOptions(id: Long): Response<RiskOptionsDto> = unexpected("riskOptions")

    override suspend fun liveRoute(body: LiveRouteRequest): Response<LiveRouteResponse> =
        unexpected("liveRoute")
}

open class FakeProfileApi : ProfileApi {
    override suspend fun get(): Response<ProfileDto> = unexpected("profile.get")
    override suspend fun update(body: ProfileUpdateRequest): Response<ProfileDto> =
        unexpected("profile.update")
}

open class FakeRoutinesApi : RoutinesApi {
    override suspend fun blocks(): Response<List<RoutineBlockDto>> = unexpected("blocks")
    override suspend fun createBlock(body: RoutineBlockWriteRequest): Response<RoutineBlockDto> =
        unexpected("createBlock")

    override suspend fun updateBlock(id: Long, body: RoutineBlockWriteRequest): Response<RoutineBlockDto> =
        unexpected("updateBlock")

    override suspend fun deleteBlock(id: Long): Response<Unit> = unexpected("deleteBlock")
    override suspend fun eventBlocks(eventId: Long): Response<EventBlocksResponse> =
        unexpected("eventBlocks")

    override suspend fun setEventBlocks(eventId: Long, body: BlockSelectionRequest): Response<EventDto> =
        unexpected("setEventBlocks")

    override suspend fun uploadObservations(
        body: BlockObservationBatchRequest,
    ): Response<BlockObservationBatchResponse> = unexpected("uploadObservations")
}

open class FakeReportsApi : ReportsApi {
    override suspend fun weekly(week: String?): Response<WeeklyReportDto> = unexpected("weekly")
    override suspend fun calibration(): Response<CalibrationDto> = unexpected("calibration")
}
