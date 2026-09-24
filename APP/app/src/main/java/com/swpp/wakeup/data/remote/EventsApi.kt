package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
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

    /**
     * 기기 캘린더 일정을 가져온다.
     *
     * **멱등하다.** `external_id` 로 upsert 하므로 같은 요청을 다시 보내도 행이
     * 늘지 않는다. 서버는 제목·시각·장소가 그대로인 일정을 재계산하지 않는다 —
     * 그게 없으면 동기화마다 카카오 쿼터를 일정 수만큼 먹는다.
     *
     * **전부 통과하거나 전부 실패한다.** 한 건이 잘못되면 400 이고 아무것도
     * 들어가지 않는다. 절반만 반영되면 사용자가 무엇이 들어갔는지 알 수 없다.
     */
    @POST("api/events/import")
    suspend fun importCalendar(
        @Body body: CalendarImportRequest,
    ): Response<CalendarImportResponse>

    @GET("api/events/tags")
    suspend fun tags(): Response<List<EventTagDto>>

    /**
     * 장소 검색.
     *
     * [lat]·[lng] 를 주면 결과에 **거리가 붙는다.** 카카오는 기준 좌표를 함께
     * 받았을 때만 거리를 채운다. "레드포스PC" 가 셋이나 나올 때 어느 것을
     * 고를지는 거리로 갈린다.
     *
     * 한 페이지는 15건이고 [page] 3에서 끝난다(총 45건). 그 이상은 카카오가
     * 내려 주지 않는다.
     *
     * [rect] 는 지도 영역 재검색이다. `minLng,minLat,maxLng,maxLat` 순서이고
     * 순서를 틀리면 서버가 무시한다.
     */
    @GET("api/places/search")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("page") page: Int = 1,
        @Query("sort") sort: String? = null,
        @Query("rect") rect: String? = null,
    ): Response<PlaceSearchResponse>

    /**
     * 좌표 → 주소. 경로 선택 화면이 출발지 기본값으로 현재 위치를 넣을 때 쓴다.
     *
     * GPS 는 좌표만 주는데 화면에 `37.4808, 126.9526` 을 띄우면 사용자는 그게
     * 어디인지 모른다. 카카오 호출은 서버가 대신한다 — 앱에 카카오 키를 넣지
     * 않는다([searchPlaces] 와 같은 이유).
     */
    /**
     * 정적 지도 이미지.
     *
     * **지도 SDK 를 쓰지 않는 이유.** 카카오지도 안드로이드 SDK 는 네이티브 앱
     * 키를 APK 에 넣고 서명 키 해시를 등록해야 한다. 이 경로는 서버가 가진
     * 키로 같은 지도를 만들어 주므로 그 절차가 전부 사라진다.
     *
     * [markers] 는 `lat,lng` 를 세미콜론으로 이은 목록이다. 카카오가 한 번에
     * 다섯 개까지만 그린다.
     *
     * 응답은 PNG 바이트다. 서버가 한 시간 캐시하므로 같은 화면을 다시 그려도
     * 카카오 호출은 늘지 않는다.
     */
    @GET("api/places/staticmap")
    suspend fun staticMap(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("lv") level: Int,
        @Query("w") width: Int,
        @Query("h") height: Int,
        @Query("markers") markers: String? = null,
    ): Response<ResponseBody>

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
    /** `manual` / `calendar` / `nlp` */
    val source: String,
    /**
     * 캘린더에서 가져온 일정의 원본 식별자. 직접 입력이면 null.
     *
     * 가져오기 화면이 **이미 가져온 일정**을 알아야 기본 선택에서 뺄 수 있다.
     * 그러지 않으면 앱에서 지운 일정이 다음 가져오기에서 되살아난다.
     */
    @SerializedName("external_id") val externalId: String? = null,
    val place: PlaceDto?,
    val tag: EventTagDto?,
    @SerializedName("tau_override") val tauOverride: Double?,
    /** 사용자가 고른 경로. 비어 있으면 서버가 최단 경로를 자동으로 쓴다 */
    @SerializedName("route_key") val routeKey: String?,

    /**
     * 이 일정에 저장된 출발지. **null 이면 프로필 집에서 출발한다.**
     *
     * 화면이 이걸 읽어야 하는 이유: 집이 아닌 곳에서 출발하도록 만든 일정은
     * 이동 시간이 그 좌표 기준으로 계산된다. 화면이 출발지를 감추면 사용자는
     * 집 기준이라고 믿고, 알람 시각이 왜 이런지 설명되지 않는다.
     */
    @SerializedName("origin_lat") val originLat: Double? = null,
    @SerializedName("origin_lng") val originLng: Double? = null,
    @SerializedName("origin_label") val originLabel: String? = null,

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
    /**
     * 구간 목록. 앱이 가로 막대로 그린다.
     *
     * **타입이 nullable 인 것이 의도다.** Gson 은 Kotlin 기본값을 모른다 —
     * `= emptyList()` 를 써도 JSON 에 `segments` 키가 없으면 이 필드는 null 로
     * 남고, 컴파일러는 non-null 이라 믿고 널 검사를 지운다. 그러면 첫 접근에서
     * `NullPointerException` 이 난다.
     *
     * 실제로 그렇게 터졌다. 배포 서버가 아직 이 필드를 내리지 않는 상태에서
     * 경로 선택 화면이 "알 수 없는 오류(NullPointerException)" 로 죽었다.
     * 같은 함정을 디스크 쪽에서는 [com.swpp.wakeup.data.local.DiskCompat] 이
     * 다루는데, 네트워크 DTO 는 그 보호 밖에 있었다.
     *
     * 그래서 기본값 대신 **null 을 타입으로 인정한다.** 그러면 컴파일러가
     * 쓰는 자리마다 처리를 강제한다. 구버전 서버 응답과 `steps` 가 없는
     * 후보가 모두 이 경로로 들어온다.
     */
    val segments: List<RouteSegmentDto>? = null,
)

/**
 * 이동 구간 하나.
 *
 * 색을 서버가 정하지 않는다. [kind] 와 [vehicleType] 만 받아서 앱이 고른다 —
 * 다크 모드 색을 손볼 때마다 서버를 배포해야 하면 못 고친다.
 */
data class RouteSegmentDto(
    /**
     * `walk` / `wait` / `bus` / `subway` / `car` / `bicycle`
     *
     * nullable 인 이유는 [RouteCandidateDto.segments] 와 같다 — Gson 은 키가
     * 없으면 non-null 선언을 무시하고 null 을 넣는다.
     */
    val kind: String?,
    val seconds: Int,
    /** "도보" / "대기" / "5511" / "2호선" */
    val label: String?,
    /** 노선명. "5511", "2호선". 도보·대기면 없다 */
    val vehicle: String?,
    /**
     * 버스 종류. "지선" / "간선" / "광역" / "순환" / "마을".
     *
     * 이것이 버스 색을 가른다. 지하철은 [vehicle] 의 노선명이 색을 정하므로
     * 비어 있다.
     */
    @SerializedName("vehicle_type") val vehicleType: String?,
    /**
     * 도시철도 권역. `metro_seoul` / `metro_busan` / `metro_daegu` …
     *
     * 카카오는 서울 1호선과 부산 1호선을 둘 다 "1호선" 으로만 준다. 이 값이
     * 없으면 부산 1호선(주황)을 서울 1호선(파랑)으로 칠하게 된다.
     */
    val region: String?,
    /** 승차역부터 하차역까지 순서대로. 첫 항목이 승차, 마지막이 하차다. */
    val stops: List<String>?,
    /** "2호선 (신림 > 강남)" */
    val guidance: String?,
    /** 다음 차량, 그다음 차량. 실시간 API가 실패하면 null/빈 목록이다. */
    val arrivals: List<RouteArrivalDto>?,
    /** 버스의 평상시 배차간격. 지하철·미제공이면 null. */
    @SerializedName("headway_minutes") val headwayMinutes: Int?,
)

/** 실시간 차량 도착 하나. */
data class RouteArrivalDto(
    /** 정류장·역 도착까지 남은 초. */
    val seconds: Int?,
    /** "3분 20초 뒤 도착" */
    val message: String?,
    /** `seoul_subway` / `seoul_bus` */
    val source: String?,
    /** 버스만 "여유" / "보통" / "혼잡". 미제공이면 null. */
    val crowding: String?,
    /**
     * 지하철만 "급행" / "특급". 일반이거나 알 수 없으면 null.
     *
     * 급행이 사용자의 하차역을 지나치는지는 **서버도 모른다** — 서울 열린데이터
     * 응답에 정차 패턴이 없다. 그래서 이 값은 판단 재료일 뿐이고, 이것으로
     * 목록에서 열차를 지우면 안 된다.
     */
    @SerializedName("train_kind") val trainKind: String?,
    /** 막차인가. 지하철만. */
    @SerializedName("last_train") val lastTrain: Boolean?,
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
    /**
     * [onTimeProbability] 가 null 인 **이유**. 화면이 구체적으로 안내해야
     * 사용자가 할 행동이 정해진다. 값마다 사용자가 할 일이 다르다.
     *
     * | 값 | 뜻 | 사용자가 할 일 |
     * | --- | --- | --- |
     * | `observed` | 준비·이동 둘 다 관측 기반. 확률이 있다 | — |
     * | `point_estimate` | 둘 다 점추정 | 루틴 블록을 등록한다 |
     * | `travel_variance_unknown` | 준비만 변동성 있음 | 같은 경로를 몇 번 다닌다 |
     * | `prep_variance_unknown` | 이동만 변동성 있음 | 블록에 범위를 넣는다 |
     *
     * `status != ok` 면 빈 문자열이다.
     */
    @SerializedName("confidence_basis") val confidenceBasis: String?,
    @SerializedName("travel_mode") val travelMode: String?,
    @SerializedName("route_summary") val routeSummary: String?,
    /** 실제 계산에 쓴 경로 key */
    @SerializedName("route_key") val routeKey: String?,
    /** "2호선 → 5513" */
    @SerializedName("route_detail") val routeDetail: String?,
    /**
     * 경로 폴리라인. `[[lat, lng], ...]` 이고 최대 600점이다.
     *
     * 지도에 경로선을 그리고, 이동한 거리 비율로 진행률을 계산한다. 카카오
     * 정적 지도에는 선을 그리는 파라미터가 없어서(실측) 앱이 이 좌표를 화면에
     * 투영해 직접 그린다.
     *
     * 좌표를 못 받았으면 `null` 이거나 빈 배열이다. 그때는 지도와 진행률을
     * 그리지 않고 안내만 띄운다 — 알람 계산 자체는 그대로 성립한다.
     */
    @SerializedName("route_path") val routePath: List<List<Double>>?,
    /** `routePath` 를 따라간 길이(m). 좌표가 없으면 null */
    @SerializedName("route_distance_m") val routeDistanceM: Int?,
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

    /**
     * 준비 시간을 블록별로 쪼갠 내역. 루틴 블록이 없으면 **빈 배열**이다.
     *
     * 합이 [prepMinutes] 와 다를 수 있다. 병렬 블록은 합이 아니라 max 로
     * 들어가고, 각 행은 반올림된 값이다. 화면에서 합을 다시 계산하지 않는다.
     */
    @SerializedName("prep_breakdown") val prepBreakdown: List<PrepBlockDto>? = null,
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_NO_HOME = "no_home"
        const val STATUS_NO_PLACE = "no_place"
        const val STATUS_ROUTE_FAILED = "route_failed"

        /** [confidenceBasis] 의 값. 서버 `apps/planning/estimators.py` 와 짝이다. */
        const val BASIS_OBSERVED = "observed"
        const val BASIS_POINT_ESTIMATE = "point_estimate"
        const val BASIS_TRAVEL_UNKNOWN = "travel_variance_unknown"
        const val BASIS_PREP_UNKNOWN = "prep_variance_unknown"

        /** [AlarmPlanDto.prepSource] 의 값. */
        const val PREP_OBSERVED = "observed"
        const val PREP_DECLARED_RANGE = "declared_range"
        const val PREP_DECLARED_POINT = "declared_point"
        const val PREP_ONBOARDING = "onboarding"
        const val PREP_FIXED = "fixed"
    }
}

/**
 * 준비 시간 블록 한 줄. 서버 `estimators.py` 의 breakdown 항목과 짝이다.
 *
 * [minutes] 는 **분포의 평균**이라 소수다. 신고한 범위([declaredMin]~[declaredMax])
 * 와 다를 수 있다 — 관측이 쌓이면 베이지안 갱신으로 평균이 이동한다.
 * 그 차이가 "학습이 되고 있다" 는 증거라서 화면이 둘을 나란히 보여준다.
 */
data class PrepBlockDto(
    @SerializedName("block_id") val blockId: Long?,
    val name: String,
    val minutes: Double,
    @SerializedName("declared_min") val declaredMin: Int?,
    @SerializedName("declared_max") val declaredMax: Int?,
    val parallelizable: Boolean = false,
    @SerializedName("drop_cost") val dropCost: String? = null,
    /** 이 블록에 쌓인 관측 수. 0 이면 신고값만 쓰고 있다 */
    @SerializedName("observation_count") val observationCount: Int = 0,
    /** `observed` 또는 `declared` */
    val source: String? = null,
)

/** 캘린더 가져오기 요청. 서버 `CalendarImportSerializer` 와 짝이다. */
data class CalendarImportRequest(
    val events: List<CalendarEventInput>,
) {
    companion object {
        /** 서버 배치 상한. 넘기면 400 이다 */
        const val MAX_ITEMS = 50
    }
}

data class CalendarEventInput(
    /**
     * 중복 방지의 축. 같은 값을 다시 보내면 서버가 갱신만 한다.
     *
     * 앱은 `"<캘린더 이벤트 id>:<시작 밀리초>"` 를 쓴다. 반복 일정은 발생분마다
     * 같은 이벤트 id 를 공유하므로 id 만으로는 매주 수업이 한 건으로 합쳐진다.
     */
    @SerializedName("external_id") val externalId: String,
    val title: String,
    /** ISO 8601 (오프셋 포함) */
    @SerializedName("start_at") val startAt: String,
    /** 좌표로 해석된 장소. 없으면 서버 계획이 `no_place` 가 된다 */
    val place: PlaceInput? = null,
    @SerializedName("tag_key") val tagKey: String? = null,
)

data class CalendarImportResponse(
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    /**
     * 실제로 알람을 다시 계산한 건수.
     *
     * 이 값이 곧 소모한 카카오 경로 쿼터다. 화면에 보여 줄 필요는 없지만
     * 로그로 남겨야 "왜 쿼터가 말랐는지" 를 나중에 설명할 수 있다.
     */
    val recomputed: Int = 0,
    val results: List<EventDto> = emptyList(),
)

/**
 * 장소 검색 한 페이지.
 *
 * 전부 기본값을 둔다. Gson 은 응답에 없는 필드를 그냥 두므로, 서버가 옛
 * 버전이면(키가 없으면) 기본값으로 떨어져야 한다 — non-null 로 선언하고
 * 기본값을 안 주면 null 이 박혀 다음 접근에서 죽는다.
 */
data class PlaceSearchResponse(
    val results: List<PlaceSearchItem> = emptyList(),
    val page: Int = 1,
    /**
     * 카카오가 말하는 전체 건수.
     *
     * **화면에 이 숫자를 그대로 쓰면 안 된다.** "카페" 는 14만이 오는데 실제로
     * 받아 볼 수 있는 것은 [reachableCount] 까지다. 목록이 45건에서 끝나는데
     * 옆에 14만이 적히면 사용자는 앱이 고장난 것으로 읽는다.
     */
    @SerializedName("total_count") val totalCount: Int = 0,
    /** 실제로 받아 볼 수 있는 건수. 카카오는 45건까지만 페이지로 준다 */
    @SerializedName("reachable_count") val reachableCount: Int = 0,
    /** 다음 페이지가 없다. "더 보기" 를 그릴지 판단하는 값 */
    @SerializedName("is_end") val isEnd: Boolean = true,
    /**
     * 실제로 적용된 정렬.
     *
     * 거리순을 요청해도 기준 좌표가 없으면 서버가 정확도순으로 내린다. 화면이
     * 고른 것과 다를 수 있으므로 응답을 따른다.
     */
    val sort: String = SORT_ACCURACY,
    val degraded: Boolean = false,
) {
    companion object {
        const val SORT_ACCURACY = "accuracy"
        const val SORT_DISTANCE = "distance"
    }
}

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
    /** 업종 전체 경로. 예 "가정,생활 > 여가시설 > 게임방,PC방" */
    val category: String?,
    /**
     * 업종 한 마디. 목록의 칩에 쓴다. 예 "게임방,PC방"
     *
     * 전체 경로는 이름과 한 줄에 들어가지 못한다. 서버가 짧은 쪽을 만들어
     * 준다 — 카카오의 `category_group_name` 이 자주 비어서 전체 경로의
     * 마지막 조각으로 보완한 값이다.
     */
    @SerializedName("category_group") val categoryGroup: String? = null,
    /**
     * 기준 좌표에서의 거리(m). 좌표를 보내지 않았으면 null.
     *
     * 같은 이름의 지점이 여러 개일 때 이것으로 고른다. "레드포스PC" 가 셋이면
     * 이름만으로는 구분할 수 없다.
     */
    @SerializedName("distance_m") val distanceM: Int? = null,
    /** 지번 주소. 도로명으로 못 찾는 곳이 있어 함께 받는다 */
    @SerializedName("jibun_address") val jibunAddress: String? = null,
    val phone: String? = null,
    /**
     * 카카오맵 장소 페이지.
     *
     * **평점·사진·영업시간이 있는 유일한 곳이다.** 카카오 로컬 API 응답에는
     * 그 값들이 아예 없다(응답 필드 12개를 확인했다). 별을 지어내는 대신
     * 이 링크로 보낸다.
     */
    @SerializedName("place_url") val placeUrl: String? = null,
) {
    /** 목록에 쓸 거리 문구. 거리를 모르면 null */
    val distanceLabel: String?
        get() {
            val m = distanceM ?: return null
            return if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000.0)
        }
}

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
