package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 일정·장소 API. back-spec.md 5.3.
 *
 * 인증 헤더는 [AuthInterceptor] 가 자동으로 붙인다. 여기서 토큰을 받지 않는다.
 */
interface EventsApi {

    /**
     * `from`/`to` 는 ISO 8601. 생략하면 전체.
     *
     * **응답은 배열이 아니라 페이지 객체다.** 서버가 `LimitOffsetPagination`
     * 을 전역 설정으로 켜 두었다(settings REST_FRAMEWORK). `List<EventDto>` 로
     * 받으면 Gson 이 객체를 배열로 읽으려다 터진다.
     *
     * @param limit 서버 기본값은 20 이다. 홈은 다가오는 일정을 다 보여줘야
     *   하므로 넉넉히 올려 잡는다.
     */
    @GET("api/events")
    suspend fun list(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("limit") limit: Int = DEFAULT_LIMIT,
    ): Response<Paged<EventDto>>

    @GET("api/events/{id}")
    suspend fun get(@Path("id") id: Long): Response<EventDto>

    @POST("api/events")
    suspend fun create(@Body body: EventCreateRequest): Response<EventDto>

    @PATCH("api/events/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: EventUpdateRequest,
    ): Response<EventDto>

    @DELETE("api/events/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>

    @POST("api/events/{id}/recompute")
    suspend fun recompute(@Path("id") id: Long): Response<EventDto>

    @GET("api/events/tags")
    suspend fun tags(): Response<List<EventTagDto>>

    @GET("api/places/search")
    suspend fun searchPlaces(@Query("q") query: String): Response<PlaceSearchResponse>

    /**
     * 좌표 → 주소. 경로 선택 화면이 출발지 기본값으로 현재 위치를 넣을 때 쓴다.
     *
     * GPS 는 좌표만 주는데 화면에 `37.4808, 126.9526` 을 띄우면 사용자는 그게
     * 어디인지 모른다. 카카오 호출은 서버가 대신한다 — 앱에 카카오 키를 넣지
     * 않는다([searchPlaces] 와 같은 이유).
     */
    @GET("api/places/reverse")
    suspend fun reversePlace(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
    ): Response<PlaceReverseResponse>

    /**
     * 경로 후보.
     *
     * 출발지를 보내지 않으면 서버가 프로필 집 위치를 쓴다. 집이 아닌 곳에서
     * 출발할 때만 `originLat`/`originLng` 를 채운다. 집도 없고 출발지도 안
     * 보내면 409 다.
     *
     * **좌표는 둘 다 보내거나 둘 다 비워야 한다.** 하나만 보내면 400 이다 —
     * 절반만 지정된 출발지를 조용히 집으로 바꾸면 사용자가 고른 곳과 다르게
     * 계산된다.
     *
     * 외부 API 를 최대 4번 부르므로 사용자가 명시적으로 요청할 때만 호출한다.
     */
    @GET("api/routes/candidates")
    suspend fun routeCandidates(
        @Query("dest_lat") destLat: Double,
        @Query("dest_lng") destLng: Double,
        @Query("origin_lat") originLat: Double? = null,
        @Query("origin_lng") originLng: Double? = null,
        @Query("origin_label") originLabel: String? = null,
    ): Response<RouteCandidateResponse>

    companion object {
        /** 한 번에 받아올 일정 수. 서버 PAGE_SIZE(20)를 덮어쓴다. */
        const val DEFAULT_LIMIT = 200
    }
}

/**
 * DRF `LimitOffsetPagination` 응답 껍데기.
 *
 * 목록 엔드포인트는 전부 이 모양으로 내려온다. 단, `APIView` 로 직접 쓴
 * `/api/events/tags` 와 `/api/places/search` 는 페이지네이션을 타지 않는다.
 */
data class Paged<T>(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<T> = emptyList(),
)

/** 프로필. 집 위치가 알람 계산의 출발지다. */
interface ProfileApi {

    @GET("api/profile")
    suspend fun get(): Response<ProfileDto>

    @PATCH("api/profile")
    suspend fun update(@Body body: ProfileUpdateRequest): Response<ProfileDto>
}

// --- 응답 -------------------------------------------------------------------

data class EventDto(
    val id: Long,
    val title: String,
    @SerializedName("start_at") val startAt: String,
    val source: String,
    val place: PlaceDto?,
    val tag: EventTagDto?,
    @SerializedName("tau_override") val tauOverride: Double?,
    /** 사용자가 고른 경로. 비어 있으면 서버가 최단 경로를 자동으로 쓴다 */
    @SerializedName("route_key") val routeKey: String?,
    @SerializedName("alarm_plan") val alarmPlan: AlarmPlanDto?,
    @SerializedName("created_at") val createdAt: String?,
)

/**
 * 경로 후보 목록.
 *
 * 카카오는 대중교통 대안을 항상 15개 주지만 대부분 같은 버스의 다른 환승
 * 조합이다. 서버가 축별(빠름·환승없음·지하철·저렴) 대표만 추려 6개 이하로
 * 내려 준다. 앱은 받은 순서대로 보여주면 된다 — 이미 빠른 순이다.
 */
data class RouteCandidateResponse(
    val origin: RouteOriginDto?,
    val results: List<RouteCandidateDto> = emptyList(),
    val degraded: Boolean = false,
)

data class RouteOriginDto(
    val label: String?,
    val lat: Double?,
    val lng: Double?,
)

data class RouteCandidateDto(
    /** `walk` / `bicycle` / `car` / `transit:<노선 체인>` */
    val key: String,
    val kind: String?,
    /** "지하철+도보+버스" */
    val mode: String,
    val minutes: Int,
    @SerializedName("distance_m") val distanceM: Int?,
    val transfers: Int = 0,
    /**
     * 요금. **null 이 정상이다** — 카카오가 환승 요금을 계산하지 못한 후보가
     * 섞여 온다. 0 으로 바꾸면 "무료" 로 잘못 보인다.
     */
    val fare: Int?,
    /** "2호선 → 5513" */
    val detail: String?,
    /** "23분 · 5.1km · 환승 1회 · 1,550원" */
    val summary: String?,
    /** "가장 빠름" / "환승 없음" / "가장 저렴". 없으면 빈 문자열 */
    val reason: String?,
    val source: String?,
)

data class PlaceDto(
    val id: Long?,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    @SerializedName("kakao_place_id") val kakaoPlaceId: String?,
)

data class EventTagDto(
    val id: Long?,
    val key: String,
    val label: String,
    @SerializedName("default_tau") val defaultTau: Double?,
    @SerializedName("penalty_shape") val penaltyShape: String?,
)

/**
 * 알람 계획.
 *
 * **[onTimeProbability] 는 null 일 수 있다.** 관측이 쌓이기 전에는 서버가 확률을
 * 만들 수 없다. 카카오 경로 응답에도 변동성 정보가 없다. null 이면 화면이
 * "학습 중" 으로 표시한다. 절대 0 이나 임의값으로 바꿔 채우지 않는다.
 *
 * [status] 는 `ok` / `no_home` / `no_place` / `route_failed` 중 하나다.
 * `ok` 가 아니면 시각·분 필드가 전부 null 이다.
 */
data class AlarmPlanDto(
    val status: String,
    @SerializedName("status_label") val statusLabel: String?,
    @SerializedName("alarm_at") val alarmAt: String?,
    @SerializedName("depart_by") val departBy: String?,
    @SerializedName("arrive_at") val arriveAt: String?,
    @SerializedName("prep_minutes") val prepMinutes: Int?,
    @SerializedName("travel_minutes") val travelMinutes: Int?,
    @SerializedName("buffer_minutes") val bufferMinutes: Int?,
    @SerializedName("total_minutes") val totalMinutes: Int?,
    @SerializedName("tau_used") val tauUsed: Double?,
    @SerializedName("on_time_probability") val onTimeProbability: Int?,
    @SerializedName("travel_mode") val travelMode: String?,
    @SerializedName("route_summary") val routeSummary: String?,
    /** 실제 계산에 쓴 경로 key */
    @SerializedName("route_key") val routeKey: String?,
    /** "2호선 → 5513" */
    @SerializedName("route_detail") val routeDetail: String?,
    /**
     * 고른 경로가 그대로 쓰였는지.
     * - `null` — 고른 적이 없다(서버가 최단 경로를 씀)
     * - `true` — 고른 경로로 계산했다
     * - `false` — 그 경로가 사라져 대체했다. 화면이 알려야 한다
     */
    @SerializedName("route_choice_honored") val routeChoiceHonored: Boolean?,

    /**
     * 각 값의 출처. 무엇이 실측이고 무엇이 아직 고정값인지 구분한다.
     *
     * `travel_time_source` 는 `kakao_transit` 등 실측이고,
     * `prep_source` 는 `onboarding`/`default`, `buffer_source` 는 `fixed` 다.
     * 화면에서 "이건 학습된 값" 처럼 오해하지 않게 서버가 명시해 준다.
     */
    @SerializedName("prep_source") val prepSource: String?,
    @SerializedName("buffer_source") val bufferSource: String?,
    @SerializedName("travel_time_source") val travelTimeSource: String?,
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_NO_HOME = "no_home"
        const val STATUS_NO_PLACE = "no_place"
        const val STATUS_ROUTE_FAILED = "route_failed"
    }
}

data class PlaceSearchResponse(
    val results: List<PlaceSearchItem>,
    val degraded: Boolean = false,
)

/**
 * 좌표 → 주소 결과.
 *
 * [result] 가 null 인 경우가 두 가지다. [degraded] 가 true 면 카카오 호출이
 * 실패한 것이고, false 면 그 좌표에 주소가 없는 것이다(바다·국외). 화면이
 * "잠시 후 다시" 와 "여기는 주소가 없음" 을 구분해야 하므로 서버가 나눠 준다.
 */
data class PlaceReverseResponse(
    val result: PlaceSearchItem? = null,
    val degraded: Boolean = false,
)

data class PlaceSearchItem(
    @SerializedName("kakao_place_id") val kakaoPlaceId: String?,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val category: String?,
)

data class ProfileDto(
    @SerializedName("home_lat") val homeLat: Double?,
    @SerializedName("home_lng") val homeLng: Double?,
    @SerializedName("home_label") val homeLabel: String?,
    @SerializedName("has_home") val hasHome: Boolean,
    @SerializedName("default_tau") val defaultTau: Double?,
    @SerializedName("onboarding_prep_min") val onboardingPrepMin: Int?,
    val timezone: String?,
    @SerializedName("recomputed_plans") val recomputedPlans: Int? = null,
)

// --- 요청 -------------------------------------------------------------------

data class EventCreateRequest(
    val title: String,
    @SerializedName("start_at") val startAt: String,
    val place: PlaceInput? = null,
    @SerializedName("tag_key") val tagKey: String? = null,
    /**
     * 고른 경로의 key 만 보낸다. **소요시간은 보내지 않는다** — 서버가 그 값을
     * 신뢰하면 조작할 수 있고, 배차가 바뀌면 낡은 값이 된다. 서버가 key 로
     * 해당 수단을 다시 조회한다.
     */
    @SerializedName("route_key") val routeKey: String? = null,
    /**
     * 집이 아닌 곳에서 출발할 때의 출발지. null 이면 서버가 프로필 집을 쓴다.
     *
     * **경로 선택 때 쓴 출발지와 반드시 같아야 한다.** 보내지 않으면 서버가
     * 집 좌표로 [routeKey] 를 다시 풀어 다른 경로의 소요시간으로 알람을 잡는다.
     */
    @SerializedName("origin_lat") val originLat: Double? = null,
    @SerializedName("origin_lng") val originLng: Double? = null,
    @SerializedName("origin_label") val originLabel: String? = null,
)

data class EventUpdateRequest(
    val title: String? = null,
    @SerializedName("start_at") val startAt: String? = null,
    val place: PlaceInput? = null,
    @SerializedName("tag_key") val tagKey: String? = null,
    @SerializedName("route_key") val routeKey: String? = null,
)

data class PlaceInput(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    @SerializedName("kakao_place_id") val kakaoPlaceId: String? = null,
)

data class ProfileUpdateRequest(
    @SerializedName("home_lat") val homeLat: Double? = null,
    @SerializedName("home_lng") val homeLng: Double? = null,
    @SerializedName("home_label") val homeLabel: String? = null,
    @SerializedName("onboarding_prep_min") val onboardingPrepMin: Int? = null,
)
