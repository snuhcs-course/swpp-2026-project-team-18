package com.swpp.wakeup.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.calendar.CalendarEvent
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.CalendarEventInput
import com.swpp.wakeup.data.remote.CalendarImportRequest
import com.swpp.wakeup.data.remote.CalendarImportResponse
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.EventsApi
import com.swpp.wakeup.data.remote.PlaceInput
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.data.remote.PrepBlockDto
import com.swpp.wakeup.data.remote.ProfileApi
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.ProfileUpdateRequest
import com.swpp.wakeup.data.remote.RouteArrivalDto
import com.swpp.wakeup.data.remote.RouteCandidateDto
import com.swpp.wakeup.data.remote.RouteSegmentDto
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.ImportCandidate
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.PrepBlockLine
import com.swpp.wakeup.domain.model.ScheduledBlock
import com.swpp.wakeup.domain.model.RouteArrival
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.sensing.GeoPoint
import retrofit2.Response
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 일정 저장소.
 *
 * 규칙 — **예외를 밖으로 던지지 않는다.** 실패는 [Result.Failure] 로 감싼다.
 * `AuthRepository` 와 같은 방침이고 back-spec 7절의 서버측 규칙과 같은 방향이다.
 *
 * 서버 → 화면 매핑이 전부 여기에 있다. 화면은 표시 문자열만 받는다.
 */
class EventRepository(
    private val api: EventsApi = ApiClient.events,
    private val profileApi: ProfileApi = ApiClient.profile,
    /**
     * 오프라인 캐시. null 이면 캐시 없이 동작한다(테스트·초기화 전).
     *
     * **정본이 아니다.** 서버 조회가 성공하면 그 결과로 덮어쓰고, 실패했을
     * 때만 읽는다. 캐시를 먼저 보여주고 나중에 갱신하는 방식(stale-while-revalidate)
     * 은 쓰지 않는다 — 알람 시각이 화면에서 한 번 바뀌면 사용자가 어느 쪽을
     * 믿어야 할지 알 수 없다.
     */
    private val cache: OfflineCache? = null,
) {

    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 홈 화면이 한 번에 필요한 것. */
    data class HomeData(
        val sections: List<EventSection>,
        val nextAlarm: UpcomingEvent?,
        val totalCount: Int,
        val hasHome: Boolean,
        val homeLabel: String?,
        /**
         * 집 좌표. 둘 다 있거나 둘 다 없다(서버 제약 `accounts_profile_home_pair`).
         *
         * 화면에 숫자로 보여 줄 값이 아니다. 출발지·도착지의 "집" 버튼이
         * [PlaceSearchItem] 을 만들려면 좌표가 필요해서 올린다 — 예전에는
         * 좌표가 저장소 안쪽(`toSchedule`)에만 있어서 UI 가 집을 고를 수 없었다.
         */
        val homeLat: Double?,
        val homeLng: Double?,
        /**
         * 사용자가 답한 평소 준비 시간(분). **null 이면 아직 답하지 않은 것이다.**
         *
         * 서버 기본값을 두지 않고 null 로 남긴다. 30 을 넣어 두면 "답한 30분" 과
         * "안 물어봐서 30분" 이 구분되지 않아, 온보딩을 보여 줄지 판단할 근거가
         * 사라진다. 추정기는 null 일 때만 30 으로 떨어진다.
         */
        val onboardingPrepMin: Int?,
        /** 알람이 계산되지 않은 일정 수. 원인 안내에 쓴다 */
        val unplannedCount: Int,
        /**
         * 실제로 `AlarmManager` 에 등록할 목록.
         *
         * 화면용 [sections] 와 따로 만든다. 등록에는 epoch 밀리초와 좌표가
         * 필요한데 표시 문자열("7:40")에서는 되돌릴 수 없다.
         */
        val schedules: List<AlarmSchedule>,

        /**
         * 이 데이터가 캐시에서 왔는가.
         *
         * **호출부는 이때 알람을 다시 등록해서는 안 된다.** `AlarmScheduler.sync`
         * 는 받은 목록으로 등록 상태를 갈아 끼우므로, 오래된 사본으로 부르면
         * 이미 맞게 걸린 알람을 취소하거나 미뤄 둔 알람을 되돌린다. 오프라인일
         * 때 등록된 알람은 이미 온라인에서 맞춰 둔 것이라 손댈 이유가 없다.
         */
        val fromCache: Boolean = false,
        /** "12분 전 정보". 캐시에서 왔을 때만 채운다 */
        val ageLabel: String? = null,
    )

    /**
     * 홈 화면 데이터. **네트워크 우선, 실패하면 캐시.**
     *
     * 캐시를 먼저 그리고 나중에 갱신하지 않는다. 알람 시각이 화면에서 한 번
     * 바뀌면 사용자는 어느 쪽을 믿어야 할지 알 수 없고, 이 앱에서 그 혼란의
     * 대가는 지각이다.
     */
    suspend fun loadHome(): Result<HomeData> {
        val online = guard { loadHomeOnline() }
        if (online is Result.Success) return online

        // 네트워크가 실패했다. 사본으로라도 화면을 채운다 — 아침에 집을 나서야
        // 하는 사용자에게 "불러오지 못했음" 만 보여주는 것은 도움이 안 된다.
        return loadHomeFromCache() ?: online
    }

    private suspend fun loadHomeOnline(): Result<HomeData> {
        val profileResponse = profileApi.get()
        val profile = unwrap(profileResponse)
            ?: return Result.Failure(errorMessage(profileResponse))
        val pageResponse = api.list()
        val page = unwrap(pageResponse)
            ?: return Result.Failure(errorMessage(pageResponse))
        val events = page.results

        // 성공한 응답만 캐시에 남긴다. 실패 응답을 쓰면 다음 오프라인에서
        // 빈 목록이 "일정 없음" 으로 보인다.
        cache?.saveProfile(profile)
        cache?.saveEvents(events)

        return Result.Success(buildHome(events, profile, fromCache = false, ageLabel = null))
    }

    /**
     * 캐시로 홈을 만든다. 캐시가 없으면 null.
     *
     * 프로필이 없으면 포기한다. 집 좌표가 없으면 이동 추적 기준점을 만들 수
     * 없고, `hasHome` 을 추측하면 "집을 설정하라" 는 안내가 잘못 뜬다.
     */
    private suspend fun loadHomeFromCache(): Result<HomeData>? {
        val store = cache ?: return null
        if (!store.isUsable) return null

        val profile = store.profile() ?: return null
        val cached = store.events()
        if (!cached.hit) return null

        return Result.Success(
            buildHome(
                events = cached.items,
                profile = profile,
                fromCache = true,
                ageLabel = cached.ageLabel,
            )
        )
    }

    /** 온라인·오프라인이 **같은 매핑**을 타게 한 곳으로 모은다. */
    private fun buildHome(
        events: List<EventDto>,
        profile: ProfileDto,
        fromCache: Boolean,
        ageLabel: String?,
    ): HomeData {
        val zone = ZoneId.systemDefault()
        val sorted = events.mapNotNull { it.toUpcoming(zone) }
            .sortedBy { it.startAtEpochSecond }

        return HomeData(
            sections = groupByDay(sorted, zone),
            nextAlarm = sorted.firstOrNull { it.alarmAt != null },
            totalCount = sorted.size,
            hasHome = profile.hasHome,
            homeLabel = profile.homeLabel?.takeIf { it.isNotBlank() },
            homeLat = profile.homeLat,
            homeLng = profile.homeLng,
            onboardingPrepMin = profile.onboardingPrepMin?.takeIf { it > 0 },
            unplannedCount = sorted.count { it.alarmAt == null },
            schedules = events.mapNotNull { it.toSchedule(zone, profile) },
            fromCache = fromCache,
            ageLabel = ageLabel,
        )
    }

    suspend fun createEvent(
        title: String,
        startAt: OffsetDateTime,
        place: PlaceSearchItem?,
        tagKey: String?,
        routeKey: String? = null,
        origin: PlaceSearchItem? = null,
    ): Result<EventDto> = guard {
        val body = EventCreateRequest(
            title = title.trim(),
            startAt = startAt.toString(),
            place = place?.let {
                PlaceInput(
                    name = it.name,
                    lat = it.lat,
                    lng = it.lng,
                    address = it.address,
                    kakaoPlaceId = it.kakaoPlaceId,
                )
            },
            tagKey = tagKey,
            routeKey = routeKey?.takeIf { it.isNotBlank() },
            // 경로 선택 때 쓴 출발지를 그대로 보낸다. 빠뜨리면 서버가 집 기준으로
            // 계산해 사용자가 본 소요시간과 달라진다.
            originLat = origin?.lat,
            originLng = origin?.lng,
            originLabel = origin?.name,
        )
        val response = api.create(body)
        unwrap(response)?.let { Result.Success(it) } ?: Result.Failure(errorMessage(response))
    }

    /**
     * 경로 후보 조회.
     *
     * 서버가 외부 API 를 최대 4번 부르므로 사용자가 "경로 고르기" 를 눌렀을
     * 때만 호출한다.
     *
     * [origin] 이 null 이면 출발지를 보내지 않고 서버가 프로필 집을 쓴다.
     * 사용자가 출발지를 바꿨으면 그 좌표를 보낸다.
     */
    suspend fun routeCandidates(
        destination: PlaceSearchItem,
        origin: PlaceSearchItem? = null,
    ): Result<RouteChoice> = guard {
        val response = api.routeCandidates(
            destLat = destination.lat,
            destLng = destination.lng,
            originLat = origin?.lat,
            originLng = origin?.lng,
            originLabel = origin?.name,
        )
        val body = unwrap(response) ?: return@guard Result.Failure(errorMessage(response))

        // 도착정보가 화면에서 줄어들려면 '언제 받은 값인지' 가 필요하다.
        // 응답을 푼 직후에 한 번만 읽어 모든 후보가 같은 기준을 갖게 한다.
        val fetchedAt = SystemClock.elapsedRealtime()
        val options = body.results.map { it.toOption(fetchedAt) }
        if (options.isEmpty()) {
            return@guard Result.Failure("경로를 찾지 못했다. 장소를 다시 확인한다.")
        }
        Result.Success(
            RouteChoice(
                originLabel = body.origin?.label?.takeIf { it.isNotBlank() }
                    ?: origin?.name ?: "집",
                destination = destination.name,
                options = options,
                selectedKey = options.firstOrNull()?.key,
                // 사용자가 고른 출발지만 좌표를 남긴다. 집을 쓴 경우 null 이라
                // 일정 생성 때 출발지를 보내지 않고 서버 기본값에 맡긴다.
                originLat = origin?.lat,
                originLng = origin?.lng,
            )
        )
    }

    /**
     * 현재 위치의 주소. 경로 선택 화면의 출발지 기본값에 쓴다.
     *
     * 주소가 없는 좌표(바다·국외)는 실패가 아니다. 그 경우 [Result.Success] 에
     * null 이 담긴다 — 화면은 "현재 위치를 쓸 수 없음" 으로 안내하고 사용자가
     * 직접 검색하게 둔다.
     */
    /**
     * 정적 지도 이미지.
     *
     * **최근 몇 장을 메모리에 들고 있는다.** 지도를 옮겼다 되돌리는 동작이
     * 잦은데 그때마다 네트워크를 타면 화면이 끊긴다. 서버도 한 시간 캐시하므로
     * 여기서 놓쳐도 카카오 호출로 이어지지는 않지만, 왕복은 여전히 느리다.
     *
     * 넉넉히 담지 않는다. 720x1000 ARGB 한 장이 약 2.9MB 라서 열 장이면
     * 30MB 다 — 알람 앱이 그만큼 들고 있을 이유가 없다.
     */
    suspend fun staticMap(
        center: GeoPoint,
        level: Int,
        widthDp: Int,
        heightDp: Int,
        markers: List<GeoPoint>,
    ): Result<Bitmap> {
        val markerParam = markers
            .take(StaticMapScale.MARKER_LIMIT)
            .joinToString(";") { "%.6f,%.6f".format(it.lat, it.lng) }
            .takeIf { it.isNotBlank() }

        val key = "%.6f,%.6f,%d,%d,%d,%s".format(
            center.lat, center.lng, level, widthDp, heightDp, markerParam.orEmpty()
        )
        mapCache[key]?.let { return Result.Success(it) }

        return guard {
            val response = api.staticMap(
                lat = center.lat,
                lng = center.lng,
                level = level,
                width = widthDp,
                height = heightDp,
                markers = markerParam,
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return@guard Result.Failure(errorMessage(response))
            }

            val bytes = body.use { it.bytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@guard Result.Failure("지도 이미지를 읽지 못했다.")

            synchronized(mapCache) {
                if (mapCache.size >= MAP_CACHE_MAX) {
                    mapCache.remove(mapCache.keys.first())
                }
                mapCache[key] = bitmap
            }
            Result.Success(bitmap)
        }
    }

    suspend fun reversePlace(lat: Double, lng: Double): Result<PlaceSearchItem?> = guard {
        val response = api.reversePlace(lat, lng)
        val body = unwrap(response) ?: return@guard Result.Failure(errorMessage(response))
        if (body.degraded) {
            return@guard Result.Failure("현재 위치의 주소를 불러오지 못했다.")
        }
        Result.Success(body.result)
    }

    suspend fun deleteEvent(id: Long): Result<Unit> = guard {
        val response = api.delete(id)
        if (response.isSuccessful) {
            // 캐시에서도 지운다. 남겨 두면 다음 오프라인 조회에서 지운 일정이
            // 되살아나고, 사용자는 삭제가 안 된 것으로 읽는다.
            cache?.deleteEvent(id)
            Result.Success(Unit)
        } else {
            Result.Failure(errorMessage(response))
        }
    }

    /**
     * 장소 검색 한 페이지.
     *
     * [near] 를 주면 결과에 거리가 붙는다. 현재 위치를 알면 항상 보낸다 —
     * 같은 이름의 지점이 여러 개일 때 거리가 유일한 구분 근거다.
     *
     * [rect] 는 지도 영역 재검색이다(`minLng,minLat,maxLng,maxLat`).
     */
    suspend fun searchPlaces(
        query: String,
        near: GeoPoint? = null,
        page: Int = 1,
        sort: String = PlaceSearchResponse.SORT_ACCURACY,
        rect: String? = null,
    ): Result<PlaceSearchResponse> = guard {
        val response = api.searchPlaces(
            query = query,
            lat = near?.lat,
            lng = near?.lng,
            page = page,
            sort = sort,
            rect = rect,
        )
        unwrap(response)?.let { Result.Success(it) } ?: Result.Failure(errorMessage(response))
    }

    suspend fun tags(): Result<List<EventTagDto>> = guard {
        unwrap(api.tags())?.let { Result.Success(it) } ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    /**
     * 집 위치를 저장한다.
     *
     * [prepMinutes] 는 기본이 null 이고, null 이면 **보내지 않는다**(Gson 이
     * null 필드를 뺀다). 준비 시간은 [setOnboardingPrep] 과 온보딩 화면이
     * 따로 담당하므로 이 경로는 좌표만 건드린다.
     *
     * 함께 보내면 곤란한 이유가 있다. 가입 직후 집을 먼저 받는데 그때 준비
     * 시간을 같이 보내면 `onboarding_prep_min` 이 채워지고, 그러면 준비 시간
     * 온보딩이 "이미 답한 것" 으로 판단해 뜨지 않는다.
     */
    suspend fun setHome(
        label: String,
        lat: Double,
        lng: Double,
        prepMinutes: Int? = null,
    ): Result<ProfileDto> = guard {
        val body = ProfileUpdateRequest(
            homeLat = lat,
            homeLng = lng,
            homeLabel = label,
            onboardingPrepMin = prepMinutes,
        )
        unwrap(profileApi.update(body))?.let { Result.Success(it) }
            ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    /**
     * 준비 시간만 저장한다. 집 좌표는 건드리지 않는다.
     *
     * [setHome] 을 재사용할 수 없다. 그쪽은 좌표를 **항상** 함께 보내므로,
     * 가입 직후(집을 아직 모르는 상태)에 쓰면 좌표를 덮어쓰거나 보낼 값이
     * 없어진다. 서버는 부분 수정을 허용하고 좌표 짝 검증도 인스턴스 현재 값을
     * 합쳐서 하므로 이 필드 하나만 보내도 통과한다(실측 확인).
     *
     * 서버는 이 값이 바뀌면 알람을 다시 계산한다. 준비 시간이 알람 시각의
     * 시작점이라 그래야 화면과 실제가 어긋나지 않는다.
     */
    suspend fun setOnboardingPrep(minutes: Int): Result<ProfileDto> = guard {
        val body = ProfileUpdateRequest(onboardingPrepMin = minutes)
        unwrap(profileApi.update(body))?.let { Result.Success(it) }
            ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    /**
     * 알람 결정 화면(Figma ④)이 필요한 상세.
     *
     * 목록을 훑어 찾지 않고 상세 엔드포인트를 부른다. 목록은 페이지 경계가
     * 있어 21번째 일정부터는 찾지 못한다.
     */
    suspend fun loadPlan(eventId: Long): Result<AlarmPlanView> {
        val online = guard {
            val response = api.get(eventId)
            val dto = unwrap(response)
                ?: return@guard Result.Failure(errorMessage(response))
            cache?.saveEvent(dto)
            dto.toPlanView(ZoneId.systemDefault())?.let { Result.Success(it) }
                ?: Result.Failure("알람이 아직 계산되지 않았다.")
        }
        if (online is Result.Success) return online

        // 근거 화면은 오프라인에서도 열려야 한다. 알람이 울린 아침에 "왜 지금
        // 일어나야 하는가" 를 확인하려는 순간이 대개 지하철·엘리베이터 안이다.
        val cached = cache?.event(eventId)?.toPlanView(ZoneId.systemDefault())
        return if (cached != null) Result.Success(cached) else online
    }

    /**
     * 이 일정만 다시 계산한다.
     *
     * **사용자가 명시적으로 요청할 때만 부른다.** 서버가 카카오 경로 API 를
     * 호출하므로 무료 쿼터(일 1,000건)를 먹는다. 루틴 블록 **정의**를 고친
     * 뒤가 대표적인 경우다 — 서버는 정의 변경에 자동 재계산을 걸지 않는다.
     */
    suspend fun recomputePlan(eventId: Long): Result<AlarmPlanView> = guard {
        val response = api.recompute(eventId)
        val dto = unwrap(response)
            ?: return@guard Result.Failure(errorMessage(response))
        cache?.saveEvent(dto)
        dto.toPlanView(ZoneId.systemDefault())?.let { Result.Success(it) }
            ?: Result.Failure("다시 계산했지만 알람을 만들 수 없었다.")
    }

    /** 서버가 돌려준 일정을 캐시에 반영한다. 블록 체크 저장 응답이 이 경로다. */
    suspend fun cacheEvent(dto: EventDto) {
        cache?.saveEvent(dto)
    }

    /**
     * 기기 캘린더에서 읽은 후보를 만든다.
     *
     * 이미 가져온 것(`external_id` 가 서버에 있는 것)은 기본 선택에서 뺀다 —
     * 앱에서 지운 일정이 다시 살아나는 것이 가장 짜증나는 경우다. 다시 보내도
     * 서버가 갱신만 하므로 위험하지는 않고, 사용자가 켜면 들어간다.
     *
     * 이미 가져온 목록은 **서버 조회가 실패하면 비운다.** 그때는 전부 "새 것"
     * 으로 보이는데, 잘못 보내도 서버가 upsert 하므로 중복이 생기지 않는다.
     */
    suspend fun buildImportCandidates(
        events: List<CalendarEvent>,
    ): Result<List<ImportCandidate>> = guard {
        val known = unwrap(api.list())
            ?.results
            ?.mapNotNull { it.externalId?.takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()

        val zone = ZoneId.systemDefault()
        Result.Success(
            events.map { event ->
                val imported = event.externalId in known
                ImportCandidate(
                    source = event,
                    selected = !imported,
                    alreadyImported = imported,
                    whenLabel = importWhenLabel(event.startAtMillis, zone),
                )
            }
        )
    }

    /**
     * 고른 후보를 서버로 보낸다.
     *
     * 응답의 일정을 캐시에 넣는다 — 가져온 직후 오프라인이 되어도 목록이 보인다.
     */
    suspend fun importCalendar(
        candidates: List<ImportCandidate>,
    ): Result<CalendarImportResponse> = guard {
        if (candidates.isEmpty()) return@guard Result.Failure("가져올 일정을 고르지 않았다.")

        val body = CalendarImportRequest(
            events = candidates.map { candidate ->
                CalendarEventInput(
                    externalId = candidate.externalId,
                    title = candidate.source.title,
                    startAt = candidate.source.startAtIso,
                    place = candidate.resolvedPlace?.let {
                        PlaceInput(
                            name = it.name,
                            lat = it.lat,
                            lng = it.lng,
                            address = it.address,
                            kakaoPlaceId = it.kakaoPlaceId,
                        )
                    },
                    // 태그는 캘린더에서 알 수 없다. 서버가 프로필 기본 τ 를 쓴다.
                    tagKey = null,
                )
            }
        )

        val response = api.importCalendar(body)
        val result = unwrap(response) ?: return@guard Result.Failure(errorMessage(response))

        result.results.forEach { cache?.saveEvent(it) }
        Log.i(
            TAG,
            "캘린더 가져오기: 추가 ${result.created} 갱신 ${result.updated} " +
                "그대로 ${result.unchanged} (재계산 ${result.recomputed})",
        )
        Result.Success(result)
    }

    /**
     * 캐시를 통째로 지운다. **로그아웃·계정 전환에서 반드시 부른다.**
     *
     * 캐시에는 집 위치와 다니는 장소가 들어 있다. 기기를 공유하거나 계정을
     * 바꿨을 때 앞 사용자의 동선이 그대로 보이면 안 된다.
     */
    suspend fun clearCache() {
        cache?.wipe()
    }

    /**
     * 서버가 준 일정을 계획 화면용으로 바꾼다.
     *
     * 블록 체크를 저장하면 서버가 갱신된 일정을 그대로 돌려준다. 그걸 쓰면
     * 재조회 왕복이 없다. 매핑 규칙이 이 파일 안에만 있어야 하므로 여기에 둔다.
     */
    fun planFrom(dto: EventDto): AlarmPlanView? = dto.toPlanView(ZoneId.systemDefault())

    // --- 내부 -------------------------------------------------------------

    /**
     * 예외를 [Result.Failure] 로 바꾼다.
     *
     * 개발 빌드에서는 예외 종류를 문구에 붙인다. 그냥 "알 수 없는 오류" 만
     * 띄우면 파싱 실패와 통신 실패를 화면에서 구분할 수 없다. 실제로 응답
     * 스키마가 어긋났을 때 이 때문에 원인 찾기가 늦어졌다.
     */
    private inline fun <T> guard(block: () -> Result<T>): Result<T> = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "network failure", e)
        Result.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        Log.e(TAG, "unexpected failure", e)
        Result.Failure(
            if (BuildConfig.DEV_TOOLS) {
                "${MESSAGE_UNKNOWN} (${e.javaClass.simpleName})"
            } else {
                MESSAGE_UNKNOWN
            }
        )
    }

    private fun <T> unwrap(response: Response<T>): T? =
        if (response.isSuccessful) response.body() else null

    private fun errorMessage(response: Response<*>): String =
        ApiClient.parseErrorMessage(response.errorBody()?.string())
            ?: when (response.code()) {
                400 -> "입력값을 확인해야 한다."
                401 -> "다시 로그인해야 한다."
                404 -> "대상을 찾을 수 없다."
                in 500..599 -> "서버에 문제가 생겼다."
                else -> MESSAGE_UNKNOWN
            }

    private companion object {
        const val TAG = "EventRepository"
        const val MESSAGE_NETWORK = "서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."
        const val MESSAGE_UNKNOWN = "알 수 없는 오류가 발생했다."

        /**
         * 메모리에 들고 있을 지도 장수.
         *
         * 720x1000 ARGB 한 장이 약 2.9MB 다. 네 장이면 12MB 로, 옮겼다 되돌리는
         * 동작을 덮으면서도 알람 앱이 들고 있을 만한 크기다.
         */
        const val MAP_CACHE_MAX = 4

        /**
         * 삽입 순서를 지키는 맵. 가장 오래된 것을 먼저 버린다.
         *
         * 인스턴스가 아니라 동반 객체에 둔다 — 저장소는 화면마다 새로 만들어질
         * 수 있고, 그때마다 지도를 다시 받으면 캐시가 없는 것과 같다.
         */
        val mapCache = linkedMapOf<String, Bitmap>()
    }
}

// ---------------------------------------------------------------------------
// 매핑
// ---------------------------------------------------------------------------

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val ALARM_FORMAT = DateTimeFormatter.ofPattern("H:mm")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

private fun dayLabel(date: LocalDate): String = DAY_NAMES[date.dayOfWeek.value - 1]

/**
 * 가져오기 후보의 시각 표시. "10월 5일 월 09:00"
 *
 * 홈 목록과 달리 **날짜를 반드시 보여준다.** 후보는 2주치가 섞여 있어서 시각만
 * 보면 언제 것인지 알 수 없다.
 */
private fun importWhenLabel(startAtMillis: Long, zone: ZoneId): String {
    val local = java.time.Instant.ofEpochMilli(startAtMillis).atZone(zone)
    return "${local.format(DATE_FORMAT)} ${dayLabel(local.toLocalDate())} " +
        local.format(TIME_FORMAT)
}

/**
 * 경로 후보를 화면용으로 바꾼다.
 *
 * 서버가 이미 `summary`("23분 · 5.1km · 환승 1회 · 1,550원")를 만들어 준다.
 * 여기서 다시 조립하면 같은 규칙이 두 곳에 생긴다. 소요시간은 카드 상단에
 * 크게 따로 쓰므로 요약에서 앞의 "N분 · " 만 떼어 쓴다.
 */
// internal 인 이유는 테스트다. Gson 이 역직렬화한 DTO 를 이 함수에 그대로
// 넣어 봐야 "키가 없을 때 터지는" 종류의 버그가 잡힌다. private 이면 테스트가
// DTO 를 손으로 만들게 되고, 그때는 Gson 을 통과하지 않아 아무것도 검증하지
// 못한다 — 실제로 그렇게 놓쳤다(NetworkDtoNullSafetyTest 상단 참고).
internal fun RouteCandidateDto.toOption(fetchedAtElapsedMs: Long = 0L): RouteOption {
    val summaryTail = (summary ?: "")
        .removePrefix("${minutes}분")
        .removePrefix(" · ")
        .trim()

    val detailLine = listOf(detail?.trim().orEmpty(), summaryTail)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    return RouteOption(
        key = key,
        mode = mode,
        minutes = minutes,
        minutesLabel = "${minutes}분",
        detailLine = detailLine.ifBlank { summary.orEmpty() },
        badge = reason?.takeIf { it.isNotBlank() },
        // segments 가 null 인 경우가 정상이다 — 구버전 서버 응답과 Gson 의
        // 기본값 무시가 겹치는 자리다. 근거는 RouteCandidateDto.segments 주석.
        segments = RouteSegments(
            segments.orEmpty().mapNotNull { it.toSegment(fetchedAtElapsedMs) }
        ),
    )
}

/**
 * 구간 DTO 를 화면용으로.
 *
 * 시간이 0 이하인 구간은 버린다. 폭이 0 인 칸을 그리면 색만 한 줄 끼어
 * 들어가 경계선처럼 보인다. 모르는 `kind` 는 버리지 않고 중립색으로 그린다 —
 * 서버가 수단을 추가했을 때 막대의 합이 소요시간과 어긋나는 것이 더 나쁘다.
 */
private fun RouteSegmentDto.toSegment(fetchedAtElapsedMs: Long = 0L): RouteSegment? {
    if (seconds <= 0) return null
    val kind = RouteSegment.Kind.from(kind)
    return RouteSegment(
        kind = kind,
        seconds = seconds,
        label = label?.takeIf { it.isNotBlank() }
            ?: vehicle?.takeIf { it.isNotBlank() }
            ?: defaultLabel(kind),
        lineName = vehicle?.trim().orEmpty(),
        busType = vehicleType?.trim().orEmpty(),
        region = region?.trim().orEmpty(),
        // Gson 은 `= emptyList()` 기본값을 무시한다. 네트워크 DTO 의 목록은
        // null 을 정상으로 받고 여기서만 빈 목록으로 바꾼다.
        stops = stops.orEmpty().map(String::trim).filter(String::isNotEmpty),
        guidance = guidance?.trim().orEmpty(),
        arrivals = arrivals.orEmpty().mapNotNull { it.toArrival(fetchedAtElapsedMs) }.take(2),
        headwayMinutes = headwayMinutes?.takeIf { it > 0 },
    )
}

/**
 * 실시간 도착 DTO. 초와 문구 중 하나라도 쓸 수 있어야 남긴다.
 *
 * [fetchedAtElapsedMs] 는 화면에서 남은 시간을 줄이는 기준이다. 여기서
 * `SystemClock` 을 직접 읽지 않는다 — 읽으면 단위 테스트가 Android 프레임워크
 * 없이 이 매핑을 검증할 수 없게 되고, 같은 응답의 도착정보들이 서로 다른
 * 기준을 갖게 된다.
 */
private fun RouteArrivalDto.toArrival(fetchedAtElapsedMs: Long = 0L): RouteArrival? {
    val safeSeconds = seconds?.takeIf { it >= 0 }
    val safeMessage = message?.trim().orEmpty()
    if (safeSeconds == null && safeMessage.isEmpty()) return null

    return RouteArrival(
        seconds = safeSeconds ?: 0,
        message = safeMessage,
        source = source?.trim().orEmpty(),
        crowding = crowding?.trim().orEmpty(),
        trainKind = trainKind?.trim().orEmpty(),
        lastTrain = lastTrain == true,
        fetchedAtElapsedMs = fetchedAtElapsedMs,
    )
}

private fun defaultLabel(kind: RouteSegment.Kind): String = when (kind) {
    RouteSegment.Kind.WALK -> "도보"
    RouteSegment.Kind.WAIT -> "대기"
    RouteSegment.Kind.BUS -> "버스"
    RouteSegment.Kind.SUBWAY -> "지하철"
    RouteSegment.Kind.CAR -> "자동차"
    RouteSegment.Kind.BICYCLE -> "자전거"
    RouteSegment.Kind.UNKNOWN -> "이동"
}

/** 서버는 ISO 8601 로 준다. 파싱 실패한 항목은 목록에서 뺀다. */
private fun EventDto.toUpcoming(zone: ZoneId): UpcomingEvent? {
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null
    val local = start.atZoneSameInstant(zone)
    val plan = alarmPlan

    val alarmLocal = plan?.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    val routeText = when {
        plan == null -> place?.address?.takeIf { it.isNotBlank() }
        plan.status == AlarmPlanDto.STATUS_OK -> buildString {
            append(place?.name ?: "장소 없음")
            plan.routeSummary?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(plan.travelMode?.takeIf(String::isNotBlank) ?: "이동")
                append(" ")
                append(it)
            }
        }
        else -> buildString {
            place?.name?.let { append(it); append(" · ") }
            append(plan.statusLabel ?: "알람 계산 불가")
        }
    }

    return UpcomingEvent(
        id = id,
        startTime = local.format(TIME_FORMAT),
        dayLabel = dayLabel(local.toLocalDate()),
        title = title,
        placeAndRoute = routeText ?: "장소 없음",
        alarmAt = alarmLocal?.format(ALARM_FORMAT),
        onTimeProbability = plan?.onTimeProbability,
        tag = tag?.label,
        planStatus = plan?.status ?: AlarmPlanDto.STATUS_NO_PLACE,
        planStatusLabel = plan?.statusLabel,
        startAtEpochSecond = start.toEpochSecond(),
        startDate = local.toLocalDate(),
    )
}

/**
 * 준비 시간 한 줄의 근거 문구.
 *
 * **5 가지를 구분해야 한다.** 예전에는 `onboarding` 과 나머지 둘로만 갈라서
 * 블록 관측으로 학습된 값까지 "고정값 · 기본 30분" 으로 적었다. 학습이 되고
 * 있는데 화면이 아니라고 말하면 사용자가 블록을 등록할 이유를 못 느낀다.
 */
private fun prepNote(plan: AlarmPlanDto, blockCount: Int): String {
    val blocks = if (blockCount > 0) "블록 ${blockCount}개" else "블록"
    return when (plan.prepSource) {
        AlarmPlanDto.PREP_OBSERVED ->
            "실측 학습값 · $blocks 의 실제 소요로 갱신됨"

        AlarmPlanDto.PREP_DECLARED_RANGE ->
            "신고 범위 기반 · $blocks. 실제 소요가 쌓이면 학습값으로 바뀜"

        AlarmPlanDto.PREP_DECLARED_POINT ->
            "신고 고정값 · $blocks 의 범위가 한 점임. 범위를 주면 확률이 계산됨"

        AlarmPlanDto.PREP_ONBOARDING ->
            "고정값 · 집 설정에서 답한 값. 루틴 블록을 등록하면 항목별로 쪼개짐"

        else ->
            "고정값 · 기본값을 씀. 루틴 블록을 등록하면 항목별로 쪼개짐"
    }
}

/** 소수 첫째 자리까지. 정수면 소수점을 뗀다 — "14분" 이 "14.0분" 보다 읽기 쉽다. */
private fun minutesLabel(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) "${rounded.toInt()}분"
    else "${rounded}분"
}

private fun PrepBlockDto.toLine(): PrepBlockLine {
    val learned = source == "observed" || observationCount > 0

    val bits = buildList {
        // 신고 범위를 먼저 적는다. 학습값이 이 범위를 벗어났을 때 그 차이가
        // 바로 보여야 한다 — 그게 학습이 일어났다는 증거다.
        if (declaredMin != null && declaredMax != null) {
            if (declaredMin == declaredMax) add("신고 ${declaredMin}분")
            else add("신고 ${declaredMin}~${declaredMax}분")
        }
        if (learned) add("관측 ${observationCount}회로 학습됨")
        else add("관측 없음")
        if (parallelizable) add("병렬 진행")
    }

    return PrepBlockLine(
        blockId = blockId,
        name = name,
        minutesLabel = minutesLabel(minutes),
        minutes = minutes,
        detail = bits.joinToString(" · "),
        learned = learned,
        parallelizable = parallelizable,
    )
}

/**
 * 확률과 그 근거.
 *
 * 확률이 null 인 이유를 `confidence_basis` 로 나눠 적는다. "학습 중" 만
 * 띄우면 사용자는 기다리는 것 말고 할 수 있는 일이 없다고 생각한다. 실제로는
 * 대개 사용자가 할 수 있는 일이 있다 — 블록에 범위를 넣거나 같은 경로를
 * 몇 번 다니는 것이다.
 */
private fun AlarmPlanDto.toConfidence(): ConfidenceView {
    val percent = onTimeProbability
    if (percent != null) {
        return ConfidenceView(
            percent = percent.coerceIn(0, 100),
            headline = "정시 도착 확률 ${percent.coerceIn(0, 100)}%",
            reason = null,
            action = null,
        )
    }

    val (reason, action) = when (confidenceBasis) {
        AlarmPlanDto.BASIS_POINT_ESTIMATE ->
            "준비·이동 둘 다 단일 추정값이라 분포가 없음" to
                "루틴 블록에 최소~최대 범위를 넣으면 확률 계산이 시작됨"

        AlarmPlanDto.BASIS_TRAVEL_UNKNOWN ->
            "준비 시간은 분포가 있지만 이동 시간은 경로 조회값 하나뿐임" to
                "같은 경로를 몇 번 다니면 이동 변동성이 쌓임"

        AlarmPlanDto.BASIS_PREP_UNKNOWN ->
            "이동 시간은 분포가 있지만 준비 시간이 고정값임" to
                "루틴 블록에 최소~최대 범위를 넣으면 됨"

        // 확률도 근거도 없는 경우. status != ok 이면 이 카드는 그려지지 않는다.
        else -> "아직 확률을 계산할 근거가 부족함" to null
    }

    return ConfidenceView(
        percent = null,
        headline = "정시 도착 확률 학습 중",
        reason = reason,
        action = action,
    )
}

private fun EventDto.toPlanView(zone: ZoneId): AlarmPlanView? {
    val plan = alarmPlan ?: return null
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null
    val startLocal = start.atZoneSameInstant(zone)

    val alarmLocal = plan.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)
    val arriveLocal = plan.arriveAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    // 근거 한 줄에 **출처**를 함께 적는다. 세 값의 신뢰도가 다르다 —
    // 이동 시간만 카카오 실측이고 준비·버퍼는 아직 고정값이다. 구분해 주지
    // 않으면 셋 다 학습된 값처럼 읽힌다.
    val prepBlocks = (plan.prepBreakdown ?: emptyList()).map { it.toLine() }

    val rows = buildList {
        plan.prepMinutes?.let {
            add(PlanRow("준비 시간", it, prepNote(plan, prepBlocks.size), PlanRow.Kind.PREP))
        }
        plan.travelMinutes?.let {
            val label = plan.travelMode?.takeIf(String::isNotBlank)?.let { m -> "$m 이동" }
                ?: "이동 시간"
            val bits = buildList {
                add("실측")
                // 집이 아닌 출발지면 **먼저** 밝힌다. 이동 시간이 그 좌표
                // 기준으로 계산됐는데 화면이 감추면 알람 시각이 설명되지 않는다.
                originLabel?.takeIf { l -> l.isNotBlank() }?.let { l -> add("${l}에서 출발") }
                plan.routeDetail?.takeIf(String::isNotBlank)?.let(::add)
                plan.routeSummary?.takeIf(String::isNotBlank)?.let(::add)
            }
            add(PlanRow(label, it, bits.joinToString(" · "), PlanRow.Kind.TRAVEL))
        }
        plan.bufferMinutes?.let {
            add(PlanRow("안전 버퍼", it, "고정값 · 문 앞에서 실제 출발까지의 여유", PlanRow.Kind.BUFFER))
        }
    }

    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(today, startLocal.toLocalDate())
    val whenLabel = when (days) {
        0L -> "오늘"
        1L -> "내일 아침"
        else -> "${days}일 뒤"
    }

    val remaining = alarmLocal?.let {
        val minutes = Duration.between(OffsetDateTime.now(zone), it).toMinutes()
        if (minutes <= 0) "지난 알람" else "${minutes / 60}시간 ${minutes % 60}분 남음"
    }

    return AlarmPlanView(
        eventId = id,
        whenLabel = whenLabel,
        dateLabel = "${startLocal.format(DATE_FORMAT)} ${dayLabel(startLocal.toLocalDate())}",
        eventTitle = "${startLocal.format(TIME_FORMAT)} $title",
        eventPlace = place?.let { p ->
            listOfNotNull(p.name, p.address?.takeIf(String::isNotBlank)).joinToString(" · ")
        } ?: "장소 없음",
        sensitivityTag = tag?.label,
        alarmAt = alarmLocal?.format(ALARM_FORMAT),
        meridiem = alarmLocal?.let { if (it.hour < 12) "AM" else "PM" } ?: "",
        remaining = remaining,
        onTimeProbability = plan.onTimeProbability,
        tauUsed = plan.tauUsed,
        confidence = plan.toConfidence(),
        breakdown = rows,
        prepBlocks = prepBlocks,
        totalMinutes = plan.totalMinutes,
        arrivalLine = arriveLocal?.let { "${it.format(ALARM_FORMAT)} 도착 예정" },
        status = plan.status,
        statusLabel = plan.statusLabel,
        routeKey = plan.routeKey?.takeIf { it.isNotBlank() },
        // false 일 때만 알린다. null(고른 적 없음)과 true(그대로 쓰임)는
        // 사용자가 알 필요가 없다.
        routeFellBack = plan.routeChoiceHonored == false,
    )
}

/**
 * 알람 등록·이동 추적에 쓸 형태로 바꾼다.
 *
 * **계산되지 않은 계획은 건너뛴다.** 알람 시각이 없으면 등록할 것이 없다.
 * 집 위치가 없거나 장소가 없어 `status != ok` 인 일정이 그렇다. 이때 임의의
 * 시각을 만들어 등록하면 엉뚱한 시간에 울린다.
 *
 * 좌표는 판별 기준점이다 — 집은 출발, 일정 장소는 도착. 둘 다 없으면 알람은
 * 울리지만 추적은 하지 않는다([AlarmSchedule.canTrack]).
 */
private fun EventDto.toSchedule(zone: ZoneId, profile: ProfileDto): AlarmSchedule? {
    val plan = alarmPlan ?: return null
    if (plan.status != AlarmPlanDto.STATUS_OK) return null

    val alarm = plan.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?: return null
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null

    val alarmLocal = alarm.atZoneSameInstant(zone)
    val startLocal = start.atZoneSameInstant(zone)
    val arriveLocal = plan.arriveAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    return AlarmSchedule(
        eventId = id,
        alarmAtMillis = alarm.toInstant().toEpochMilli(),
        departByMillis = plan.departBy
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.toInstant()?.toEpochMilli(),
        arriveAtMillis = plan.arriveAt
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.toInstant()?.toEpochMilli(),
        startAtMillis = start.toInstant().toEpochMilli(),
        alarmLabel = alarmLocal.format(ALARM_FORMAT),
        meridiem = if (alarmLocal.hour < 12) "AM" else "PM",
        eventLine = "${startLocal.format(TIME_FORMAT)} $title",
        placeName = place?.let { p ->
            listOfNotNull(p.name, p.address?.takeIf(String::isNotBlank)).joinToString(" · ")
        },
        arrivalLine = arriveLocal?.let { "${it.format(ALARM_FORMAT)} 도착 예정" },
        homeLat = profile.homeLat,
        homeLng = profile.homeLng,
        destLat = place?.lat,
        destLng = place?.lng,
        prepMinutes = plan.prepMinutes,
        // 블록 목록을 알람과 함께 저장한다. 알람이 울리는 순간 네트워크가
        // 없을 수 있고, 그때 목록을 조회하지 못하면 아침 기록을 시작할 수 없다.
        prepBlocks = (plan.prepBreakdown ?: emptyList()).mapNotNull { row ->
            val id = row.blockId ?: return@mapNotNull null
            ScheduledBlock(
                blockId = id,
                name = row.name,
                plannedMinutes = row.minutes,
                parallelizable = row.parallelizable,
            )
        },
    )
}

/** 날짜별로 묶고 오늘·내일에 이름을 붙인다. */
private fun groupByDay(events: List<UpcomingEvent>, zone: ZoneId): List<EventSection> {
    if (events.isEmpty()) return emptyList()
    val today = LocalDate.now(zone)

    return events
        .groupBy { it.startDate }
        .toSortedMap()
        .map { (date, list) ->
            val label = when (ChronoUnit.DAYS.between(today, date)) {
                0L -> "오늘"
                1L -> "내일"
                else -> "${date.format(DATE_FORMAT)} ${dayLabel(date)}"
            }
            EventSection(
                label = label,
                dateLabel = "${date.format(DATE_FORMAT)} ${dayLabel(date)}",
                events = list,
            )
        }
}
