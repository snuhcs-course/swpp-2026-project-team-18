package com.swpp.wakeup.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.calendar.DeviceCalendar
import com.swpp.wakeup.data.local.LocalStores
import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.data.local.MorningSessionStore
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.data.local.TokenStore
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.data.remote.BlockObservationInput
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.data.remote.ServerVersion
import com.swpp.wakeup.data.remote.ServerWarmup
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.ReportRepository
import com.swpp.wakeup.data.repository.RoutineRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.ImportCandidate
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.domain.model.MorningSession
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.sensing.CurrentLocation
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.MapCameraMath
import com.swpp.wakeup.domain.model.RouteMapProjection
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.sensing.TripGeofence
import com.swpp.wakeup.sensing.TripLiveState
import com.swpp.wakeup.sensing.TripObservationQueue
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.cos
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 홈 화면 상태와 `MainActivity` 안의 화면 이동.
 *
 * **표본 데이터를 쓰지 않는다.** 모든 일정은 `/api/events` 에서 온다. 새 계정은
 * 일정이 없으므로 빈 목록이고, 사용자가 추가한 만큼만 보인다.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application)

    /**
     * 오프라인 캐시. **로그인한 계정으로 범위가 묶인다.**
     *
     * 이메일을 넘기지 않으면 캐시가 아무 일도 하지 않는다. 소유자 없는 행은
     * 다음 사용자가 읽을 수 있고, 캐시에는 집 위치와 다니는 장소가 들어 있다.
     */
    private val cache = OfflineCache(application, tokenStore.email)

    private val repository = EventRepository(cache = cache)
    private val routines = RoutineRepository(cache = cache)

    /**
     * 리포트는 캐시하지 않는다.
     *
     * 지난 주를 되돌아보는 화면이라 아침에 급히 열지 않는다. 오프라인에서 열리지
     * 않아도 손실이 작고, 대신 오래된 정시율을 지금 값처럼 보여주는 위험을
     * 피한다 — 이 화면의 숫자가 사용자의 여유 설정을 바꾸므로 낡은 값이 더
     * 위험하다.
     */
    private val reports = ReportRepository()

    /** 블록 관측 큐. 아침 기록이 여기로 들어가고 WorkManager 가 올린다. */
    private val blockQueue = BlockObservationQueue(application)

    /**
     * 진행 중인 아침 기록의 디스크 저장소.
     *
     * **[init] 보다 위에 있어야 한다.** Kotlin 은 프로퍼티와 `init` 블록을 선언
     * 순서대로 초기화한다. 이 선언이 `init` 아래에 있으면 `init` 이 부르는
     * `reloadMorning()` 이 아직 null 인 이 값을 읽고 **앱을 켤 때마다 죽는다.**
     * 실제로 그렇게 깨져 있었다 — 컴파일러도 lint 도 이 순서를 잡아 주지 않는다.
     * `HomeViewModelInitOrderTest` 가 그 순서를 강제한다.
     */
    private val morningStore = MorningSessionStore(application)

    /** 서비스가 화면이 없는 동안 갱신한 현재-위치 경로를 복원한다. */
    private val liveRouteStore = LiveRouteStore(application)

    /**
     * 진행 중인 아침 기록. 없으면 null.
     *
     * 생성 시점에 디스크를 한 번 읽는다.
     * [com.swpp.wakeup.alarm.AlarmActivity] 가 세션을 만들고 이 화면으로 보낸다.
     *
     * 위 [morningStore] 와 같은 이유로 [init] 보다 위에 있어야 한다.
     */
    private val _morning = MutableStateFlow(morningStore.current())
    val morning: StateFlow<MorningSession?> = _morning.asStateFlow()

    /** 현재 위치 조회에 쓴다. [AndroidViewModel] 이라 누수 걱정이 없다. */
    private val appContext: android.content.Context = application.applicationContext

    data class UiState(
        val nickname: String,
        val loading: Boolean = true,
        val error: String? = null,

        val sections: List<EventSection> = emptyList(),
        val nextAlarm: UpcomingEvent? = null,
        val totalCount: Int = 0,

        /** 집 위치 미설정이면 알람을 계산할 수 없다. 안내를 띄운다 */
        val hasHome: Boolean = true,
        val homeLabel: String? = null,
        /**
         * 저장된 집을 장소 하나로 본 것. 설정하지 않았으면 null.
         *
         * 좌표를 따로 들고 다니지 않는 이유는 쓰는 쪽이 전부 [PlaceSearchItem]
         * 을 요구하기 때문이다 — 출발지·도착지의 "집" 버튼이 그대로 넘긴다.
         * `kakaoPlaceId` 는 없다. 카카오가 준 장소가 아니라 사용자가 고른
         * 좌표라서 그 자리에 넣을 값이 없다.
         */
        val homePlace: PlaceSearchItem? = null,
        val unplannedCount: Int = 0,

        /**
         * 실제로 `AlarmManager` 에 등록된 알람 수.
         *
         * 계산된 알람 수와 다를 수 있다 — 지난 알람과 7일 밖의 알람은 등록하지
         * 않는다. 실기기에서 "등록됐나" 를 확인할 유일한 창구다.
         */
        val registeredAlarms: Int = 0,
        /** 가장 이른 등록 알람. 예 "7:40" */
        val nextRegisteredLabel: String? = null,
        /** 아직 서버로 올리지 못한 관측 수. 0 이 정상이다 */
        val pendingObservations: Int = 0,

        /**
         * 지금 보이는 목록이 캐시에서 온 것인가.
         *
         * 표시하지 않으면 사용자는 세 시간 전 알람 시각을 지금 값으로 믿는다.
         * 알람 시각은 교통 상황에 따라 바뀌는 값이라 그 오해가 지각이 된다.
         */
        val offline: Boolean = false,
        /** "12분 전 정보". [offline] 일 때만 채운다 */
        val offlineAgeLabel: String? = null,

        /**
         * 진행 중인 아침 기록에서 아직 안 마친 항목 수.
         *
         * null 이면 진행 중인 기록이 없다. 0 이면 전부 마쳤지만 "끝내기" 를
         * 누르지 않은 상태다 — 그때도 입구를 보여줘야 큐에 남은 것이 정리된다.
         */
        val morningBlocksLeft: Int? = null,
    ) {
        val isEmpty: Boolean get() = !loading && error == null && totalCount == 0
        val todayLine: String
            get() {
                val today = LocalDate.now()
                val count = sections
                    .firstOrNull { it.events.firstOrNull()?.startDate == today }
                    ?.events?.size ?: 0
                val date = today.format(DateTimeFormatter.ofPattern("M월 d일"))
                val day = listOf("월", "화", "수", "목", "금", "토", "일")[today.dayOfWeek.value - 1]
                return "$date $day · 오늘 ${count}개"
            }
    }

    private val _state = MutableStateFlow(UiState(nickname = tokenStore.nickname ?: "사용자"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _nav = MutableStateFlow(NavState())
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    // --- 장소 검색 --------------------------------------------------------
    //
    // 일정 추가(목적지)·집 주소·경로 선택(출발지)·지도 화면이 **같은 상태와 같은
    // 엔진**을 쓴다. 예전에는 세 곳이 각자 query/results/searching 을 들고
    // 있었고, 그래서 결과가 세 개만 보이던 문제도 한 곳만 고쳐서는 낫지
    // 않았다. 페이지·정렬·거리를 세 번 구현할 이유가 없다.

    /** 장소 검색 한 화면의 상태. */
    data class PlaceSearch(
        val query: String = "",
        val searching: Boolean = false,
        /** 다음 페이지를 받는 중. 첫 검색과 구분해야 스피너 자리가 다르다 */
        val loadingMore: Boolean = false,
        val results: List<PlaceSearchItem> = emptyList(),
        val page: Int = 1,
        val isEnd: Boolean = true,
        val totalCount: Int = 0,
        val reachableCount: Int = 0,
        val sort: String = PlaceSearchResponse.SORT_ACCURACY,
        val error: String? = null,
        /** 한 번이라도 검색했는가. "결과 없음" 을 검색 전에 띄우지 않으려고 본다 */
        val searched: Boolean = false,
        /**
         * 거리를 받았는가.
         *
         * 위치 권한이 없거나 아직 좌표를 못 구하면 거리가 비어 온다. 그때
         * 거리 정렬 칩을 눌러도 정확도순으로 떨어지므로 칩을 감춘다.
         */
        val hasDistances: Boolean = false,
    ) {
        val canLoadMore: Boolean get() = !isEnd && !searching && !loadingMore

        /**
         * "45건 중 12건" 같은 문구. 검색 전이면 null.
         *
         * **`totalCount` 를 쓰지 않는다.** 카카오는 "카페" 에 14만을 주는데
         * 받아 볼 수 있는 것은 45건이다. 큰 숫자를 적어 두면 목록이 45건에서
         * 끝나는 것이 고장으로 보인다.
         */
        val countLabel: String?
            get() {
                if (!searched) return null
                if (results.isEmpty()) return null
                val reachable = reachableCount.takeIf { it > 0 } ?: results.size
                return if (results.size >= reachable) "${results.size}건"
                else "${reachable}건 중 ${results.size}건"
            }

        /** 검색어를 바꾸면 페이지와 결과를 버린다. 섞이면 엉뚱한 목록이 된다 */
        fun withQuery(next: String) = copy(query = next)
    }

    /**
     * 검색을 돌려 상태를 갱신한다.
     *
     * 상태를 읽고 쓰는 방법만 받는다. 호출부가 어느 화면인지 이 함수는 알
     * 필요가 없다 — 그래서 네 화면이 같은 코드를 쓴다.
     *
     * [page] 가 1보다 크면 결과를 **이어 붙인다.** 갈아 끼우면 "더 보기" 가
     * 목록을 위로 되돌린다.
     */
    private fun runPlaceSearch(
        get: () -> PlaceSearch,
        set: (PlaceSearch) -> Unit,
        page: Int = 1,
        sort: String? = null,
        rect: String? = null,
        near: GeoPoint? = null,
    ) {
        val current = get()
        val query = current.query.trim()
        if (query.isBlank()) return

        val wantedSort = sort ?: current.sort
        val origin = near ?: rememberedLocation(MAP_LOCATION_MAX_AGE_MS)?.point

        set(
            current.copy(
                searching = page == 1,
                loadingMore = page > 1,
                error = null,
                sort = wantedSort,
            )
        )

        viewModelScope.launch {
            val result = repository.searchPlaces(
                query = query,
                near = origin,
                page = page,
                sort = wantedSort,
                rect = rect,
            )
            val before = get()
            when (result) {
                is EventRepository.Result.Success -> {
                    val body = result.data
                    val merged =
                        if (page > 1) before.results + body.results else body.results
                    set(
                        before.copy(
                            searching = false,
                            loadingMore = false,
                            results = merged,
                            page = body.page,
                            isEnd = body.isEnd,
                            totalCount = body.totalCount,
                            reachableCount = body.reachableCount,
                            // 서버가 실제로 적용한 정렬을 따른다. 좌표가 없으면
                            // 거리순 요청이 정확도순으로 내려온다.
                            sort = body.sort,
                            hasDistances = merged.any { it.distanceM != null },
                            searched = true,
                            error = null,
                        )
                    )
                }

                is EventRepository.Result.Failure -> set(
                    before.copy(
                        searching = false,
                        loadingMore = false,
                        searched = true,
                        error = result.message,
                    )
                )
            }
        }
    }

    /**
     * 마지막으로 확인한 현재 위치. 검색에 거리를 붙이는 데 쓴다.
     *
     * 검색할 때마다 GPS 를 켜서 기다리지 않는다 — 그러면 검색 버튼을 누르고
     * 몇 초를 기다리게 된다. 경로 화면이 위치를 잡을 때 여기에 적어 두고,
     * 그 값이 있으면 검색에 얹는다. 없으면 거리 없이 검색한다.
     */
    private var lastKnownPoint: GeoPoint? = null
    private var lastKnownAccuracyM: Float? = null
    private var lastKnownAtMillis: Long? = null
    private var currentLocationPrimeJob: Job? = null

    /** 지도에 표시할 위치는 좌표뿐 아니라 오차와 측정 시각까지 한 덩어리다. */
    data class MapLocationFix(
        val point: GeoPoint,
        val accuracyM: Float,
        val atMillis: Long,
    )

    private fun rememberedLocation(
        maxAgeMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): MapLocationFix? {
        val point = lastKnownPoint ?: return null
        val atMillis = lastKnownAtMillis ?: return null
        if (maxAgeMillis != null && nowMillis - atMillis !in 0..maxAgeMillis) return null
        return MapLocationFix(
            point = point,
            accuracyM = lastKnownAccuracyM ?: Float.MAX_VALUE,
            atMillis = atMillis,
        )
    }

    private fun rememberLocation(
        point: GeoPoint,
        accuracyM: Float,
        atMillis: Long,
    ): MapLocationFix {
        val previousAt = lastKnownAtMillis
        val previousAccuracy = lastKnownAccuracyM
        if (previousAt != null &&
            (atMillis < previousAt ||
                (atMillis == previousAt && previousAccuracy != null &&
                    accuracyM >= previousAccuracy) ||
                (atMillis - previousAt in 0..LOCATION_QUALITY_GRACE_MS &&
                    previousAccuracy != null && accuracyM > previousAccuracy * 1.5f))
        ) {
            return rememberedLocation()!!
        }
        lastKnownPoint = point
        lastKnownAccuracyM = accuracyM
        lastKnownAtMillis = atMillis
        return MapLocationFix(point, accuracyM, atMillis)
    }

    private fun rememberLocation(location: android.location.Location): MapLocationFix =
        rememberLocation(
            point = GeoPoint(location.latitude, location.longitude),
            accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            atMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )

    /** 알람 결정 화면이 보고 있는 계획. */
    private val _plan = MutableStateFlow<AlarmPlanView?>(null)
    val plan: StateFlow<AlarmPlanView?> = _plan.asStateFlow()
    /** 빠르게 A→B 화면을 열 때 A의 늦은 응답이 B를 덮지 못하게 한다. */
    private var planLoadGeneration: Long = 0L

    /**
     * 알람 결정 화면의 경로 지도 상태.
     *
     * [MapPickState] 와 따로 둔다. 장소를 고르는 지도는 중심을 사용자가 옮기고
     * 결과 마커를 바꾸지만, 이 지도는 **정해진 경로 하나**를 보여 주는 것이고
     * 되돌아갈 기준(경로 전체)이 있다. 한 상태로 합치면 두 화면이 서로의
     * 중심·마커를 덮어쓴다.
     */
    data class RouteMapState(
        val eventId: Long,
        /** 화면에 실제로 그릴 주 경로. 이동 중에는 현재 위치 기준 경로다. */
        val path: List<GeoPoint>,
        /** 이동이 끝났을 때 되돌릴 일정의 원래 출발지 기준 경로. */
        val plannedPath: List<GeoPoint> = path,
        val center: GeoPoint,
        val level: Int,
        val summary: String? = null,
        val plannedSummary: String? = summary,
        /** [path]가 백그라운드에서 갱신된 현재 위치 기준 경로인가. */
        val pathFromCurrent: Boolean = false,
        /** 경로 유무와 별개로 실제 이동 중인가. 갱신 대기 중에도 true를 유지한다. */
        val inTransit: Boolean = false,
        /**
         * 보라 실선으로 [path] 아래에 까는 경로.
         *
         * 고른 경로가 주(主)다. 겹치는 구간에서는 고른 경로의 색이 보여야
         * 사용자가 "내가 갈 길" 을 잃지 않는다.
         *
         * 출발 전 계획에 담긴 대안만 이 필드를 쓴다. 이동 중 `routes/live`가 준
         * 현재 위치 기준 최단선은 보조선이 아니라 [path] 자체를 교체한다.
         */
        val altPath: List<GeoPoint> = emptyList(),
        /**
         * "9호선 → 2호선 · 4분 빠름" 또는 "여기서부터 9호선 · 3분 빠름".
         * 대안이 없으면 null.
         */
        val altSummary: String? = null,
        val image: Bitmap? = null,
        /** [image]가 실제로 나타내는 카메라. 원하는 center/level과 다를 수 있다. */
        val imageCenter: GeoPoint? = null,
        val imageLevel: Int? = null,
        val imageWidthDp: Int = 0,
        val imageHeightDp: Int = 0,
        val imageLoading: Boolean = false,
        val imageError: String? = null,
        val currentLocation: MapLocationFix? = null,
        val locating: Boolean = false,
        val locationError: String? = null,
        /** 손으로 지도를 살핀 뒤 1분 경로 갱신이 카메라를 강제로 되돌리지 않는다. */
        val cameraMode: RouteCameraMode = RouteCameraMode.FIT_ROUTE,
        /** 손가락을 떼기 전까지 끈 거리(px) */
        val pendingShift: Offset = Offset.Zero,
    ) {
        val hasAltPath: Boolean get() = altPath.size >= 2 && altSummary != null

        val canZoomIn: Boolean get() = level > StaticMapScale.MIN_LEVEL

        // 장소 고르기와 달리 ROUTE_MAX_LEVEL 을 쓴다. `RouteMapProjection.fit`
        // 이 고를 수 있는 범위와 같아야 조작이 튀지 않는다.
        val canZoomOut: Boolean get() = level < StaticMapScale.ROUTE_MAX_LEVEL
    }

    enum class RouteCameraMode { FIT_ROUTE, FREE }

    private val _routeMap = MutableStateFlow<RouteMapState?>(null)
    val routeMap: StateFlow<RouteMapState?> = _routeMap.asStateFlow()

    /** 추적 중인 여정의 실시간 위치. 서비스가 [TripLiveState] 로 내보낸다 */
    val tripLive: StateFlow<TripLiveState.Snapshot?> = TripLiveState.snapshot

    /**
     * 이번 실행에서 준비 시간 온보딩을 이미 띄웠는가.
     *
     * [refresh] 는 화면을 되돌아올 때마다 불린다. 이 플래그가 없으면 값을
     * 저장하기 전까지 매번 화면을 다시 밀어 넣어, 사용자가 뒤로 나갈 수 없다.
     *
     * **`init` 보다 위에 선언한다.** `init` → [refresh] 가 이 값을 읽으므로,
     * 아래에 두면 초기화 순서상 아직 false 로도 세팅되지 않은 값을 읽는다.
     * `HomeViewModelInitOrderTest` 가 이 규칙을 고정한다.
     */
    private var prepOnboardingAsked = false

    /**
     * 집 주소 온보딩을 이 세션에서 이미 띄웠는지.
     *
     * [prepOnboardingAsked] 와 같은 이유로 `init` 위에 둔다 — 아래에 두면
     * `init` 의 `refresh()` 가 초기화보다 먼저 읽어 false 가 다시 덮인다.
     */
    private var homeOnboardingAsked = false

    init {
        // 화면보다 오래 사는 서비스가 저장한 최신 성공 결과를 먼저 복원한다.
        // 이후 두 Flow 수집기는 Activity가 백그라운드여도 ViewModel이 살아 있는
        // 동안 계속 상태를 맞추고, ViewModel이 새로 생겨도 이 사본에서 이어진다.
        TripLiveState.restoreLiveRoute(liveRouteStore.current())
        viewModelScope.launch {
            TripLiveState.snapshot.collect { snapshot ->
                if (snapshot == null) clearLiveRoutePath()
                else onTrackedPoint(snapshot)
            }
        }
        viewModelScope.launch {
            TripLiveState.liveRoute.collect { snapshot ->
                if (snapshot == null) clearLiveRoutePath()
                else applyLiveRoute(snapshot)
            }
        }

        refresh()
        // 알람 액티비티가 세션을 만들어 두었을 수 있다. 홈 카드가 보이려면
        // 여기서 한 번 읽어야 한다.
        reloadMorning()
        // 로그아웃이 정기 작업을 취소했으므로 다시 로그인했으면 되살려야 한다.
        // Application.onCreate 는 프로세스당 한 번이라 로그아웃→로그인을 같은
        // 프로세스에서 하면 그 경로만으로는 복구되지 않는다. KEEP 이라 중복
        // 등록이 되지 않는다.
        JitWork.ensurePeriodicSync(getApplication())
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.loadHome()) {
                is EventRepository.Result.Success -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            sections = result.data.sections,
                            nextAlarm = result.data.nextAlarm,
                            totalCount = result.data.totalCount,
                            hasHome = result.data.hasHome,
                            homeLabel = result.data.homeLabel,
                            homePlace = result.data.toHomePlace(),
                            unplannedCount = result.data.unplannedCount,
                            offline = result.data.fromCache,
                            offlineAgeLabel = result.data.ageLabel,
                        )
                    }
                    maybeAskOnboarding(result.data)
                    syncAlarms(result.data.schedules, fromCache = result.data.fromCache)
                }

                is EventRepository.Result.Failure -> _state.update {
                    // 배포가 뒤처졌다고 확인된 상태면 그 사실을 함께 말한다.
                    // 그러지 않으면 "불러오지 못했습니다" 만 남고, 그 증상은 앱
                    // 버그와 구별되지 않는다. 실제로 팀이 그 방향으로 시간을 썼다.
                    val message = if (ServerWarmup.serverBehind) {
                        result.message + "\n\n" +
                            ServerVersion.behindMessage(
                                BuildConfig.VERSION_NAME,
                                ServerWarmup.serverVersion,
                            )
                    } else {
                        result.message
                    }
                    it.copy(loading = false, error = message)
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * 서버 계획을 실제 알람 등록에 반영하고, 밀린 관측을 올린다.
     *
     * 홈을 새로 읽을 때마다 한다. 이 지점이 "계산된 알람" 과 "울리는 알람" 을
     * 잇는 유일한 곳이다 — 여기가 빠지면 서버는 7:40 을 알고 있지만 기기는
     * 아무것도 모른다.
     *
     * 알람 등록이 실패해도 화면은 살아 있어야 한다. 등록 실패로 목록까지
     * 못 보게 만들 이유가 없다.
     */
    private fun syncAlarms(schedules: List<AlarmSchedule>, fromCache: Boolean) {
        val app = getApplication<Application>()
        val scheduler = AlarmScheduler(app)
        val queue = TripObservationQueue(app)

        // **캐시로 그린 화면에서는 등록을 건드리지 않는다.** `sync` 는 받은
        // 목록으로 등록 상태를 갈아 끼우므로, 오래된 사본으로 부르면 이미 맞게
        // 걸린 알람을 취소하거나 사용자가 미뤄 둔 알람을 되돌린다. 오프라인에서
        // 등록된 알람은 온라인일 때 맞춰 둔 것이라 그대로 두는 것이 옳다.
        if (fromCache) {
            Log.i(TAG, "캐시로 화면을 그렸다. 알람 등록은 건드리지 않는다")
        } else {
            runCatching { scheduler.sync(schedules) }
                .onFailure { Log.e(TAG, "알람 등록 실패", it) }
        }

        val registered = runCatching { scheduler.registered() }.getOrDefault(emptyList())

        _state.update {
            it.copy(
                registeredAlarms = registered.size,
                nextRegisteredLabel = registered.firstOrNull()?.alarmLabel,
                pendingObservations = pendingObservationCount(),
            )
        }

        // 지하철에서 판정된 관측이 큐에 남아 있을 수 있다. 연결됐으니 올린다.
        // 아침 기록도 같이 올린다 — 사용자는 씻는 동안 앱을 떠나 있었다.
        viewModelScope.launch {
            queue.flush()
            runCatching { blockQueue.flush() }
            _state.update { it.copy(pendingObservations = pendingObservationCount()) }
        }
    }

    /**
     * 로그아웃.
     *
     * **캐시를 반드시 지운다.** 캐시에는 집 위치·목적지·일정 제목이 들어 있다.
     * 기기를 공유하거나 계정을 바꿨을 때 앞 사용자의 동선이 보이면 안 된다.
     *
     * 삭제를 `viewModelScope` 로 하면 **지워지지 않는다.** 로그아웃 직후
     * 액티비티가 끝나고 ViewModel 이 정리되면서 그 코루틴이 취소된다. 그래서
     * 화면 수명과 분리된 [OfflineCache.wipeDetached] 를 쓴다.
     *
     * 교차 계정 노출은 이 삭제가 아니라 조회의 소유자 범위가 막는다. 여기서
     * 다루는 것은 기기에 남는 **보존 기간**이다.
     */
    fun logout() {
        // 순서가 있다. **토큰보다 먼저 지운다** — 지우기 판정이 "지금 로그인한
        // 계정" 을 보므로, 토큰이 먼저 사라지면 소유자를 모르는 상태가 되어
        // 아무것도 지울 수 없다.
        LocalStores.wipeAll(getApplication())
        tokenStore.clear()
        // 세션 종료 표시를 내린다. 남겨 두면 다음 로그인 직후 MainActivity 가
        // 그것을 보고 로그인 화면으로 되돌린다 — 로그인이 안 되는 것처럼 보인다.
        SessionState.clearExpired()
        OfflineCache.wipeDetached(getApplication())
        // 배경 작업도 거둔다. 워커가 로그인 여부를 확인해 아무 일도 하지 않지만,
        // 로그아웃한 기기를 6시간마다 깨울 이유가 없다.
        JitWork.cancelAll(getApplication())

        // 화면에 남은 아침 기록도 즉시 지운다. 지우지 않으면 로그아웃 뒤에도
        // 홈 카드가 남은 블록 수를 보여 준다.
        _morning.value = null
        _state.update { it.copy(morningBlocksLeft = null) }
    }

    /** 아바타에 쓸 두 글자. */
    fun avatarInitials(): String {
        val n = _state.value.nickname
        return if (n.length <= 2) n else n.takeLast(2)
    }

    // --- 화면 이동 --------------------------------------------------------

    fun openAlarmDecision(eventId: Long) {
        _nav.update { it.copy(stack = it.stack + AppRoute.AlarmDecision(eventId), forward = true) }
        loadPlan(eventId)
    }

    fun openRiskChoice(eventId: Long) {
        _nav.update { it.copy(stack = it.stack + AppRoute.RiskChoice(eventId), forward = true) }
    }

    fun openAddEvent() {
        // 장소 검색이 있는 화면은 들어올 때 좌표를 미리 잡는다. 이것을 빼먹으면
        // 검색이 좌표 없이 나가고, 거리·거리순 정렬이 사라지고 결과가 전국에서
        // 온다. 실기기에서 "GS25" 를 검색했더니 전북 익산·남양주가 상위에 왔다.
        primeCurrentLocation()
        _nav.update { it.copy(stack = it.stack + AppRoute.AddEvent, forward = true) }
    }

    /** 설정 화면. 가입할 때 정한 집 주소·준비 시간을 여기서 고친다 */
    fun openSettings() {
        _nav.update { it.copy(stack = it.stack + AppRoute.Settings, forward = true) }
    }

    fun openHomeSetup() {
        primeCurrentLocation()
        _nav.update { it.copy(stack = it.stack + AppRoute.HomeSetup, forward = true) }
    }

    // --- 아침 기록 --------------------------------------------------------
    //
    // 저장소와 상태 선언은 이 위 `init` 블록보다 앞에 있다. `init` 이
    // reloadMorning() 을 부르므로 여기 두면 앱을 켤 때마다 죽는다.

    /** 화면에 들어올 때마다 디스크에서 다시 읽는다. 알람 액티비티가 만들었을 수 있다. */
    fun reloadMorning() {
        val session = morningStore.current()
        _morning.value = session
        _state.update { it.copy(morningBlocksLeft = session?.remaining?.size) }
    }

    fun openMorning() {
        reloadMorning()
        if (_morning.value == null) return
        _nav.update { it.copy(stack = it.stack + AppRoute.MorningProgress, forward = true) }
    }

    /**
     * 블록 하나를 마쳤다고 기록한다.
     *
     * **디스크 기록과 큐 적재가 먼저다.** 업로드는 WorkManager 가 연결되는
     * 순간 한다. 아침에는 네트워크가 없을 수 있고, 사용자는 곧 앱을 떠난다 —
     * 여기서 네트워크를 기다리면 그 기록이 사라진다.
     *
     * 소요와 여유는 [MorningSession] 이 계산한다. 여유는 **블록 시작 시점**
     * 기준이다 — 끝난 뒤로 재면 오래 걸린 블록일수록 여유가 적게 나와
     * `slack_coef` 학습의 상관이 뒤집힌다.
     */
    fun markBlockDone(blockId: Long) {
        val session = _morning.value ?: return
        val block = session.blocks.firstOrNull { it.blockId == blockId } ?: return
        if (block.doneAtMillis != null) return

        val now = System.currentTimeMillis()
        val duration = session.durationOf(blockId, now)
        val slack = session.slackAtStartOf(blockId)

        val next = session.mark(blockId, now)
        morningStore.save(next)
        _morning.value = next
        _state.update { it.copy(morningBlocksLeft = next.remaining.size) }

        blockQueue.enqueue(
            BlockObservationInput(
                block = blockId,
                event = session.eventId,
                observedOn = LocalDate.now().toString(),
                durationMinutes = duration,
                slackMinutes = slack,
                wasParallel = block.parallelizable,
                // 멱등 키를 **여기서** 만든다. 재전송할 때 새로 만들면 서버가
                // 중복을 걸러내지 못해 같은 아침이 두 번 학습된다.
                clientUuid = "blk-${session.eventId}-$blockId-${session.startedAtMillis}",
                clientRecordedAt = OffsetDateTime.now().toString(),
            )
        )

        _state.update { it.copy(pendingObservations = pendingObservationCount()) }
    }

    /**
     * 마지막 기록을 되돌린다.
     *
     * **서버에 올라간 것은 지우지 않는다.** 되돌리기는 화면의 진행 상태만
     * 고친다 — 삭제 API 가 없고, 있더라도 이미 학습에 들어갔을 수 있다. 대신
     * 다시 마치면 같은 `client_uuid` 로 올라가 서버가 중복으로 무시한다.
     * 즉 되돌린 뒤 고쳐 마친 값은 반영되지 않는다. 그 한계를 화면이 적는다.
     */
    fun undoLastBlock() {
        val session = _morning.value ?: return
        val next = session.undoLast()
        morningStore.save(next)
        _morning.value = next
        _state.update { it.copy(morningBlocksLeft = next.remaining.size) }
    }

    /** 기록을 끝낸다. 남은 블록은 기록하지 않는다 — 안 한 것과 같다. */
    fun finishMorning() {
        morningStore.clear()
        _morning.value = null
        _state.update { it.copy(morningBlocksLeft = null) }
        viewModelScope.launch {
            // 떠나기 전에 한 번 올려 본다. 실패해도 큐에 남는다.
            runCatching { blockQueue.flush() }
            _state.update { it.copy(pendingObservations = pendingObservationCount()) }
        }
        goBack()
    }

    /** 두 큐를 합쳐 센다. 화면은 "올리지 못한 기록" 하나로 보여준다. */
    private fun pendingObservationCount(): Int =
        runCatching {
            TripObservationQueue(getApplication()).pendingCount + blockQueue.pendingCount
        }.getOrDefault(0)

    // --- 주간 리포트 ------------------------------------------------------

    /**
     * 리포트 화면 상태.
     *
     * [longTerm] 은 전체 기간(90일) 캘리브레이션이다. 주간 리포트 안에도 같은
     * 구조가 있지만 한 주는 표본이 적어 대개 "표본 부족" 이 된다. 모델이
     * 과신하는지는 긴 창으로 봐야 알 수 있어서 둘을 함께 받는다.
     */
    data class ReportState(
        val loading: Boolean = true,
        val error: String? = null,
        val weekly: WeeklyReportView? = null,
        val longTerm: CalibrationView? = null,
        /** 보고 있는 주. null 이면 지난 주 */
        val week: LocalDate? = null,
    ) {
        val hasData: Boolean get() = weekly != null
    }

    private val _report = MutableStateFlow(ReportState())
    val report: StateFlow<ReportState> = _report.asStateFlow()

    fun openReport() {
        _nav.update { it.copy(stack = it.stack + AppRoute.WeeklyReport, forward = true) }
        loadReport(_report.value.week)
    }

    fun loadReport(week: LocalDate? = null) {
        _report.update { it.copy(loading = true, error = null, week = week) }
        viewModelScope.launch {
            when (val result = reports.weekly(week)) {
                is ReportRepository.ReportResult.Success -> _report.update {
                    it.copy(loading = false, error = null, weekly = result.data)
                }

                is ReportRepository.ReportResult.Failure -> _report.update {
                    it.copy(loading = false, error = result.message)
                }
            }

            // 긴 창 캘리브레이션은 실패해도 화면을 막지 않는다. 주간 리포트만
            // 있어도 볼 것이 있다.
            when (val long = reports.calibration()) {
                is ReportRepository.ReportResult.Success ->
                    _report.update { it.copy(longTerm = long.data) }

                is ReportRepository.ReportResult.Failure ->
                    Log.w(TAG, "전체 캘리브레이션 조회 실패: ${long.message}")
            }
        }
    }

    /** 한 주 앞뒤로 이동한다. 기준이 없으면 지난 주에서 출발한다. */
    fun shiftReportWeek(weeks: Long) {
        val base = _report.value.week ?: LocalDate.now().minusWeeks(1)
        loadReport(base.plusWeeks(weeks))
    }

    // --- 캘린더 가져오기 --------------------------------------------------

    private val _calendarImport = MutableStateFlow(CalendarImportState())
    val calendarImport: StateFlow<CalendarImportState> = _calendarImport.asStateFlow()

    fun openCalendarImport() {
        _nav.update { it.copy(stack = it.stack + AppRoute.CalendarImport, forward = true) }
        _calendarImport.value = CalendarImportState(loading = true)
        loadCalendarCandidates()
    }

    /**
     * 캘린더를 읽어 후보를 만든다.
     *
     * 권한이 없으면 **읽지 않고** 그 사실을 상태에 담는다. 권한 없음과 "앞으로
     * 일정이 없음" 은 사용자가 할 일이 완전히 다르다.
     */
    fun loadCalendarCandidates() {
        val app = getApplication<Application>()
        if (!DeviceCalendar.hasPermission(app)) {
            _calendarImport.value = CalendarImportState(permissionDenied = true)
            return
        }

        _calendarImport.update {
            it.copy(loading = true, permissionDenied = false, error = null)
        }
        viewModelScope.launch {
            // ContentResolver 질의는 디스크를 탄다. 메인 스레드에서 하면 목록이
            // 큰 캘린더에서 프레임이 끊긴다.
            val events = withContext(Dispatchers.IO) { DeviceCalendar.read(app) }

            when (val result = repository.buildImportCandidates(events)) {
                is EventRepository.Result.Success -> {
                    _calendarImport.update {
                        it.copy(loading = false, error = null, candidates = result.data)
                    }
                    resolvePlaces(result.data)
                }

                is EventRepository.Result.Failure -> _calendarImport.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    /**
     * 장소 문자열을 좌표로 찾아 본다.
     *
     * **확정하지 않는다.** 결과를 화면에 보여 사용자가 확인·해제하게 한다.
     * 첫 검색 결과를 말없이 쓰면 엉뚱한 좌표로 알람이 잡히고, 사용자는 알람이
     * 틀린 뒤에야 안다.
     *
     * 선택된 것만, 그리고 상한까지만 찾는다. 후보 200건을 전부 검색하면 서버의
     * 카카오 검색 호출이 200번이다.
     */
    private fun resolvePlaces(candidates: List<ImportCandidate>) {
        val targets = candidates
            .filter { it.selected && it.source.location != null }
            .take(MAX_PLACE_LOOKUPS)
        if (targets.isEmpty()) return

        viewModelScope.launch {
            targets.forEach { candidate ->
                val query = candidate.source.location ?: return@forEach
                markResolving(candidate.externalId, true)
                val found = when (val r = repository.searchPlaces(query, near = lastKnownPoint)) {
                    is EventRepository.Result.Success -> r.data.results.firstOrNull()
                    is EventRepository.Result.Failure -> null
                }
                _calendarImport.update { state ->
                    state.copy(
                        candidates = state.candidates.map {
                            if (it.externalId == candidate.externalId) {
                                it.copy(resolvedPlace = found, resolving = false)
                            } else {
                                it
                            }
                        }
                    )
                }
            }
        }
    }

    private fun markResolving(externalId: String, resolving: Boolean) {
        _calendarImport.update { state ->
            state.copy(
                candidates = state.candidates.map {
                    if (it.externalId == externalId) it.copy(resolving = resolving) else it
                }
            )
        }
    }

    fun toggleImportCandidate(externalId: String) {
        _calendarImport.update { state ->
            state.copy(
                error = null,
                candidates = state.candidates.map {
                    if (it.externalId == externalId) it.copy(selected = !it.selected) else it
                },
            )
        }
    }

    /** 권한 요청 결과를 받는다. 허용됐으면 바로 읽는다. */
    fun onCalendarPermissionResult(granted: Boolean) {
        if (granted) loadCalendarCandidates()
        else _calendarImport.update { it.copy(permissionDenied = true, loading = false) }
    }

    fun submitCalendarImport() {
        val state = _calendarImport.value
        if (!state.canImport) return
        val selected = state.candidates.filter { it.selected }

        _calendarImport.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.importCalendar(selected)) {
                is EventRepository.Result.Success -> {
                    val data = result.data
                    _calendarImport.update {
                        it.copy(
                            importing = false,
                            resultLabel = buildString {
                                append("${data.created}건 추가")
                                if (data.updated > 0) append(", ${data.updated}건 갱신")
                                if (data.unchanged > 0) append(", ${data.unchanged}건 그대로")
                            },
                            done = true,
                        )
                    }
                    // 알람 등록까지 갱신한다. 가져온 일정의 알람이 걸려야 의미가 있다.
                    refresh()
                }

                is EventRepository.Result.Failure -> _calendarImport.update {
                    it.copy(importing = false, error = result.message)
                }
            }
        }
    }

    fun resetCalendarImport() {
        _calendarImport.value = CalendarImportState()
    }

    // --- 루틴 블록 --------------------------------------------------------

    private val _routine = MutableStateFlow(RoutineEditorState())
    val routine: StateFlow<RoutineEditorState> = _routine.asStateFlow()

    /**
     * 블록 **정의** 편집 화면을 연다.
     *
     * 일정 문맥이 없으므로 저장해도 알람이 다시 계산되지 않는다. 화면이 그
     * 사실을 안내한다.
     */
    fun openRoutineEditor() {
        _nav.update { it.copy(stack = it.stack + AppRoute.RoutineEditor, forward = true) }
        _routine.value = RoutineEditorState(loading = true)
        loadBlocks(eventId = null)
    }

    /**
     * 일정 하나의 블록 체크 화면을 연다.
     *
     * 저장하면 **그 일정만** 즉시 재계산된다.
     */
    fun openEventBlocks(eventId: Long) {
        val title = _plan.value?.eventTitle
            ?: _state.value.sections
                .flatMap { it.events }
                .firstOrNull { it.id == eventId }
                ?.let { "${it.startTime} ${it.title}" }

        _nav.update {
            it.copy(stack = it.stack + AppRoute.EventBlocks(eventId), forward = true)
        }
        _routine.value = RoutineEditorState(
            loading = true,
            eventId = eventId,
            eventTitle = title,
        )
        loadBlocks(eventId)
    }

    private fun loadBlocks(eventId: Long?) {
        viewModelScope.launch {
            val result = if (eventId == null) {
                routines.blocks()
            } else {
                routines.eventBlocks(eventId)
            }
            when (result) {
                is RoutineRepository.RoutineResult.Success -> _routine.update {
                    it.copy(
                        loading = false,
                        error = null,
                        blocks = result.data,
                        // 저장할 때 바뀐 것만 보내기 위한 기준선.
                        original = result.data.associate { b -> b.id to b.checked },
                    )
                }

                is RoutineRepository.RoutineResult.Failure -> _routine.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    fun clearRoutineMessages() = _routine.update { it.copy(error = null, notice = null) }

    /**
     * 체크를 바꾼다. **서버로 보내지 않는다.**
     *
     * 일정별 모드에서 저장 한 번에 묶어 보내야 한다 — 체크마다 요청하면
     * 재계산이 그만큼 돌고 카카오 경로 쿼터(일 1,000건)를 먹는다. 정의 모드에서는
     * 기본 포함값을 바꾸는 것이므로 [toggleIncludedByDefault] 가 따로 처리한다.
     */
    fun toggleBlockChecked(id: Long) {
        _routine.update { state ->
            state.copy(
                notice = null,
                blocks = state.blocks.map {
                    if (it.id == id) it.copy(checked = !it.checked) else it
                },
            )
        }
    }

    /**
     * 기본 포함값을 바꾼다. 정의 모드 전용이고 **즉시 저장**한다.
     *
     * 이건 재계산을 유발하지 않으므로(서버가 정의 변경에 자동 재계산을 걸지
     * 않는다) 요청마다 보내도 쿼터 문제가 없다.
     */
    fun toggleIncludedByDefault(id: Long) {
        val block = _routine.value.blocks.firstOrNull { it.id == id } ?: return
        val next = !block.includedByDefault

        // 낙관적 갱신. 실패하면 되돌린다.
        _routine.update { state ->
            state.copy(
                notice = null,
                blocks = state.blocks.map {
                    if (it.id == id) it.copy(includedByDefault = next, checked = next) else it
                },
            )
        }

        viewModelScope.launch {
            when (val r = routines.setIncludedByDefault(id, next)) {
                is RoutineRepository.RoutineResult.Success -> _routine.update { state ->
                    state.copy(
                        blocks = state.blocks.map { if (it.id == id) r.data else it },
                        original = state.original + (id to r.data.checked),
                        notice = DEFINITION_NOTICE,
                    )
                }

                is RoutineRepository.RoutineResult.Failure -> _routine.update { state ->
                    state.copy(
                        error = r.message,
                        blocks = state.blocks.map {
                            if (it.id == id) block else it
                        },
                    )
                }
            }
        }
    }

    /**
     * 일정별 체크를 저장하고 알람을 다시 받는다.
     *
     * 서버가 갱신된 일정을 그대로 돌려주므로 재조회가 필요 없다. 알람 시각이
     * 바뀌었으니 홈도 새로 읽어 **기기 알람 등록까지** 갱신한다 — 이걸 빼면
     * 서버는 새 시각을 알지만 기기는 옛 시각으로 울린다.
     */
    fun saveEventBlocks() {
        val state = _routine.value
        val eventId = state.eventId ?: return
        if (!state.dirty) {
            _routine.update { it.copy(notice = "바뀐 항목이 없다.") }
            return
        }

        _routine.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = routines.setEventBlocks(eventId, state.changes)) {
                is RoutineRepository.RoutineResult.Success -> {
                    val plan = repository.planFrom(r.data)
                    if (plan != null) _plan.value = plan

                    _routine.update {
                        it.copy(
                            saving = false,
                            // 저장된 상태가 새 기준선이다.
                            original = it.blocks.associate { b -> b.id to b.checked },
                            notice = plan?.alarmAt?.let { at -> "저장함 · 알람 $at" }
                                ?: "저장함 · 알람을 계산할 수 없다",
                        )
                    }
                    refresh()
                }

                is RoutineRepository.RoutineResult.Failure -> _routine.update {
                    it.copy(saving = false, error = r.message)
                }
            }
        }
    }

    // --- 루틴 블록: 추가·수정 --------------------------------------------

    fun startNewBlock() {
        _routine.update {
            it.copy(editing = BlockDraft(), error = null, notice = null)
        }
    }

    fun startEditBlock(id: Long) {
        val block = _routine.value.blocks.firstOrNull { it.id == id } ?: return
        _routine.update {
            it.copy(
                error = null,
                notice = null,
                editing = BlockDraft(
                    id = block.id,
                    name = block.name,
                    minText = block.minMinutes.toString(),
                    maxText = block.maxMinutes.toString(),
                    dropCost = block.dropCost,
                    parallelizable = block.parallelizable,
                    includedByDefault = block.includedByDefault,
                ),
            )
        }
    }

    fun dismissBlockDraft() = _routine.update { it.copy(editing = null) }

    /** 입력 중에는 오류를 지운다. 타이핑하는데 빨간 글씨가 남아 있으면 거슬린다. */
    private fun editDraft(transform: (BlockDraft) -> BlockDraft) {
        _routine.update { state ->
            val draft = state.editing ?: return@update state
            state.copy(editing = transform(draft).copy(errors = emptyMap()))
        }
    }

    fun onDraftName(v: String) = editDraft { it.copy(name = v.take(BlockDraft.MAX_NAME)) }

    fun onDraftMin(v: String) = editDraft { it.copy(minText = v.filter(Char::isDigit).take(3)) }

    fun onDraftMax(v: String) = editDraft { it.copy(maxText = v.filter(Char::isDigit).take(3)) }

    fun onDraftDropCost(cost: DropCost) = editDraft { it.copy(dropCost = cost) }

    fun onDraftParallel(v: Boolean) = editDraft { it.copy(parallelizable = v) }

    fun onDraftIncluded(v: Boolean) = editDraft { it.copy(includedByDefault = v) }

    /**
     * 블록을 저장한다.
     *
     * 클라이언트 검증을 먼저 돌려 왕복을 아끼고, 서버만 아는 검증(이름 중복)은
     * 응답의 필드 오류를 입력칸 아래로 되돌린다.
     */
    fun saveBlockDraft() {
        val draft = _routine.value.editing ?: return
        val checked = draft.validate()
        if (checked.errors.isNotEmpty()) {
            _routine.update { it.copy(editing = checked) }
            return
        }

        _routine.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = if (checked.isNew) {
                routines.createBlock(checked)
            } else {
                routines.updateBlock(checked)
            }
            when (result) {
                is RoutineRepository.RoutineResult.Success -> {
                    _routine.update { it.copy(saving = false, editing = null) }
                    // 목록을 다시 읽는다. 관측 통계와 정렬이 서버 계산이라
                    // 응답 하나를 끼워 넣는 것보다 확실하다.
                    loadBlocks(_routine.value.eventId)
                    _routine.update { it.copy(notice = DEFINITION_NOTICE) }
                }

                is RoutineRepository.RoutineResult.Failure -> {
                    val fieldErrors = RoutineRepository.toDraftErrors(result.fieldErrors)
                    _routine.update {
                        it.copy(
                            saving = false,
                            // 필드 오류가 있으면 칸 아래에 붙인다. 없으면
                            // 화면 상단에 한 줄로 알린다.
                            error = if (fieldErrors.isEmpty()) result.message else null,
                            editing = checked.copy(errors = fieldErrors),
                        )
                    }
                }
            }
        }
    }

    fun deleteBlock(id: Long) {
        _routine.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = routines.deleteBlock(id)) {
                is RoutineRepository.RoutineResult.Success -> {
                    _routine.update { it.copy(saving = false, editing = null) }
                    loadBlocks(_routine.value.eventId)
                    _routine.update { it.copy(notice = DEFINITION_NOTICE) }
                }

                is RoutineRepository.RoutineResult.Failure -> _routine.update {
                    it.copy(saving = false, error = r.message)
                }
            }
        }
    }

    /**
     * 이 일정의 알람을 다시 계산한다.
     *
     * 블록 **정의**를 고친 뒤 지금 반영하고 싶을 때 쓴다. 서버가 카카오 경로
     * API 를 부르므로 사용자가 명시적으로 눌렀을 때만 호출한다.
     */
    fun recomputePlan(eventId: Long) {
        viewModelScope.launch {
            when (val r = repository.recomputePlan(eventId)) {
                is EventRepository.Result.Success -> {
                    _plan.value = r.data
                    refresh()
                }

                is EventRepository.Result.Failure ->
                    _state.update { it.copy(error = r.message) }
            }
        }
    }

    // --- 경로 선택 (Figma ⑬) ---------------------------------------------

    /**
     * 경로 선택 화면 상태.
     *
     * 출발지 관련 필드가 붙어 있다. 집에서만 출발한다는 가정이 틀리기 때문이다 —
     * 학교에서 다음 수업으로 가거나 외출 중에 일정을 넣는 경우가 있다.
     *
     * [origin] 이 null 이면 서버가 프로필 집을 쓴 상태다. [locating] 은 현재
     * 위치를 측정하는 중, [originNotice] 는 그게 실패했을 때의 안내다 — 실패를
     * 조용히 삼키면 사용자는 집이 출발지로 쓰인 걸 모른다.
     */
    data class RouteState(
        val loading: Boolean = true,
        val error: String? = null,
        val choice: RouteChoice? = null,

        /** 사용자가 고른 출발지. null 이면 프로필 집 */
        val origin: PlaceSearchItem? = null,
        /** 현재 위치 측정 중 */
        val locating: Boolean = false,
        /** 출발지 기본값을 못 채운 이유 */
        val originNotice: String? = null,

        /** 출발지 검색 상태. 목적지 검색과 독립이지만 같은 구현을 쓴다 */
        val originPlace: PlaceSearch = PlaceSearch(),
        /** 출발지 검색창을 펼친 상태인지 */
        val originEditing: Boolean = false,
    )

    private val _routeChoice = MutableStateFlow<RouteState?>(null)
    val routeChoice: StateFlow<RouteState?> = _routeChoice.asStateFlow()

    /**
     * 경로 후보를 받아 ⑬ 으로 이동한다.
     *
     * 서버가 외부 API 를 최대 4번 부르므로 이미 받아 둔 후보가 있으면 다시
     * 부르지 않는다. 장소를 바꾸면 [onAddPlaceSelected] 가 캐시를 버린다.
     *
     * 출발지 기본값은 **현재 위치**다. 집을 기본으로 두면 대부분의 경우 맞지만,
     * 틀렸을 때 사용자가 알아채기 어렵다 — 집에서 출발하는 게 기본값이라는 걸
     * 모르면 알람이 왜 이 시각인지 설명되지 않는다. 현재 위치를 못 구하면
     * 집으로 돌아가고 [RouteState.originNotice] 로 알린다.
     */
    fun openRouteChoice() {
        val place = _add.value.selectedPlace ?: return
        // 출발지 검색도 거리를 쓴다. 아래 흐름이 현재 위치를 따로 구하지만
        // 그것이 실패해도 검색은 좌표를 갖도록 여기서 한 번 더 선점한다.
        primeCurrentLocation()
        _nav.update { it.copy(stack = it.stack + AppRoute.RouteChoice, forward = true) }

        val cached = _routeChoice.value?.choice
        if (cached != null) return

        _routeChoice.value = RouteState(loading = true, locating = true)
        viewModelScope.launch {
            val origin = detectCurrentPlace()
            _routeChoice.update {
                (it ?: RouteState()).copy(
                    locating = false,
                    origin = origin,
                    originNotice = if (origin == null) {
                        "현재 위치를 확인할 수 없어 집에서 출발하는 기준으로 계산함"
                    } else {
                        null
                    },
                )
            }
            loadCandidates(place, origin, keepSelection = true)
        }
    }

    /**
     * 현재 위치를 장소로 바꾼다. 실패하면 null.
     *
     * 좌표만으로는 화면에 띄울 수 없어 서버 역지오코딩을 거친다. 주소가 없는
     * 좌표(바다·국외)도 null 이다.
     */
    private suspend fun detectCurrentPlace(): PlaceSearchItem? {
        val fix = CurrentLocation.get(appContext) ?: return null
        // 좌표는 역지오코딩이 실패해도 쓸 데가 있다 — 장소 검색에 거리를 붙이고
        // 지도의 "내 위치" 를 찍는다. 주소를 못 얻었다고 버리지 않는다.
        rememberLocation(fix)
        return when (val r = repository.reversePlace(fix.latitude, fix.longitude)) {
            is EventRepository.Result.Success -> r.data
            is EventRepository.Result.Failure -> null
        }
    }

    /**
     * 현재 위치를 미리 한 번 잡아 둔다.
     *
     * 장소 검색에 거리를 붙이려면 좌표가 필요한데, 검색 버튼을 누른 뒤 GPS 를
     * 기다리면 결과가 몇 초 늦는다. 검색 화면에 들어올 때 미리 받아 두고
     * 실패하면 거리 없이 검색한다 — 거리는 있으면 좋은 것이고 없으면 검색을
     * 막을 이유가 없다.
     */
    fun primeCurrentLocation() {
        val recent = rememberedLocation(MAP_LOCATION_MAX_AGE_MS)
        if (recent != null) {
            _mapPick.update { state -> state?.copy(currentLocation = recent) }
            return
        }
        if (currentLocationPrimeJob?.isActive == true) return
        currentLocationPrimeJob = viewModelScope.launch {
            val fix = CurrentLocation.get(appContext) ?: return@launch
            val remembered = rememberLocation(fix)
            _mapPick.update { state ->
                state?.copy(currentLocation = remembered)
            }
        }
    }

    // --- 지도에서 고르기 ---------------------------------------------------

    /**
     * 지도 화면 상태.
     *
     * **지도 SDK 를 쓰지 않는다.** 서버가 카카오 정적 지도 이미지를 만들어
     * 주므로 앱은 그 이미지를 그릴 뿐이다. 네이티브 앱 키를 APK 에 넣고 서명
     * 키 해시를 등록하는 절차가 전부 없어진다.
     *
     * 그 대신 지도를 끌 때 이미지를 다시 받아야 한다. [pendingShift] 는 손가락을
     * 떼기 전까지의 이동량이고, 떼는 순간 중심을 옮겨 새 이미지를 받는다 —
     * 끄는 동안 매번 받으면 하루 호출 한도를 태운다.
     */
    data class MapPickState(
        /** 어느 검색을 위한 지도인지. 고른 결과를 되돌려 줄 곳이다 */
        val target: MapTarget = MapTarget.DESTINATION,
        val center: GeoPoint = SEOUL_CENTER,
        val level: Int = StaticMapScale.DEFAULT_LEVEL,
        /** 지도와 목록에 함께 표시할 결과. 앱이 같은 목록으로 마커를 그린다. */
        val markers: List<PlaceSearchItem> = emptyList(),
        val selected: PlaceSearchItem? = null,
        /** 영역 재검색 중 */
        val searching: Boolean = false,
        val error: String? = null,
        /** 손가락을 떼기 전까지 끈 거리(px). 이미지를 그만큼 밀어 보여 준다 */
        val pendingShift: Offset = Offset.Zero,
        /** 지도를 옮긴 뒤 아직 재검색하지 않았다 */
        val moved: Boolean = false,
        /** 지금 그릴 지도 이미지. 아직 못 받았으면 null */
        val image: Bitmap? = null,
        /** [image]가 실제로 나타내는 카메라와 요청 크기(overscan 포함). */
        val imageCenter: GeoPoint? = null,
        val imageLevel: Int? = null,
        val imageWidthDp: Int = 0,
        val imageHeightDp: Int = 0,
        val imageLoading: Boolean = false,
        /**
         * 지도를 못 받은 이유.
         *
         * 이것 때문에 화면을 막지 않는다. 아래 목록으로 계속 고를 수 있으므로
         * 지도 자리에만 문구를 띄운다.
         */
        val imageError: String? = null,
        /** 최초 진입에서 목록의 마커를 모두 보이게 맞췄는가. */
        val fittedMarkers: Boolean = false,
        val currentLocation: MapLocationFix? = null,
        val locating: Boolean = false,
    ) {
        val canZoomIn: Boolean get() = level > StaticMapScale.MIN_LEVEL
        val canZoomOut: Boolean get() = level < StaticMapScale.MAX_LEVEL

        fun isSelected(place: PlaceSearchItem): Boolean =
            placeStableKey(place) == selected?.let(::placeStableKey)

        fun withSearchResults(results: List<PlaceSearchItem>): MapPickState {
            val nextMarkers = results.take(StaticMapScale.MARKER_LIMIT)
            val selectedKey = selected?.let(::placeStableKey)
            return copy(
                searching = false,
                error = null,
                markers = nextMarkers,
                selected = nextMarkers.firstOrNull {
                    placeStableKey(it) == selectedKey
                } ?: nextMarkers.firstOrNull(),
                moved = false,
                fittedMarkers = true,
            )
        }
    }

    /** 지도에서 고른 장소를 어디로 되돌릴지. */
    enum class MapTarget { DESTINATION, HOME, ORIGIN }

    private val _mapPick = MutableStateFlow<MapPickState?>(null)
    val mapPick: StateFlow<MapPickState?> = _mapPick.asStateFlow()
    private var mapPickGeneration = 0L
    private var mapImageJob: Job? = null

    /**
     * 지도 화면을 연다.
     *
     * 중심은 **첫 결과**다. 현재 위치를 중심으로 두면 검색 결과가 화면 밖에
     * 있을 수 있고, 그러면 지도를 열자마자 아무 핀도 보이지 않는다. 결과가
     * 없으면 현재 위치, 그것도 없으면 서울 중심으로 떨어진다.
     */
    fun openMapPick(target: MapTarget) {
        val place = placeSearchOf(target)
        val results = place.results
        val previouslySelected = when (target) {
            MapTarget.DESTINATION -> _add.value.selectedPlace
            MapTarget.HOME -> _homeSetup.value.selected
            MapTarget.ORIGIN -> _routeChoice.value?.origin
        }
        val markers = (listOfNotNull(previouslySelected) + results)
            .distinctBy(::placeStableKey)
            .take(StaticMapScale.MARKER_LIMIT)
        val selected = previouslySelected
            ?.takeIf { wanted -> markers.any { placeStableKey(it) == placeStableKey(wanted) } }
            ?: markers.firstOrNull()
        val center = markers.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
            ?: rememberedLocation(MAP_LOCATION_MAX_AGE_MS)?.point
            ?: SEOUL_CENTER

        mapPickGeneration += 1L
        lastMapSignature = null
        mapImageJob?.cancel()
        _mapPick.value = MapPickState(
            target = target,
            center = center,
            markers = markers,
            selected = selected,
            currentLocation = rememberedLocation(MAP_LOCATION_MAX_AGE_MS),
        )
        _nav.update { it.copy(stack = it.stack + AppRoute.MapPick, forward = true) }
        // 화면 진입 자체가 GPS 고정밀 측정을 강제하지는 않는다. 값이 없으면
        // 배터리 부담이 작은 선점만 하고, 위치 버튼을 누른 순간 getFresh로
        // 정확한 fix를 받아 같은 탭 안에서 이동한다.
        primeCurrentLocation()
    }

    fun closeMapPick() {
        mapPickGeneration += 1L
        mapImageJob?.cancel()
        lastMapSignature = null
        _mapPick.value = null
    }

    /** 지도/목록에서 핀 하나를 고른다. 카메라는 그대로 두어 위치 관계를 보존한다. */
    fun onMapPlaceSelected(item: PlaceSearchItem) {
        _mapPick.update {
            val current = it ?: return@update it
            if (current.markers.none { marker ->
                    placeStableKey(marker) == placeStableKey(item)
                }
            ) current else current.copy(selected = item, error = null)
        }
    }

    /**
     * pan/pinch가 끝난 카메라를 한 번에 확정한다. 손짓 중에는 Composable이
     * 마지막 정상 bitmap과 overlay를 함께 변환하므로 StateFlow를 매 프레임
     * 갱신하지 않는다.
     */
    fun onMapGestureEnd(pan: Offset, zoom: Float, metersPerPixel: Double) {
        val state = _mapPick.value ?: return
        val camera = MapCameraMath.finishGesture(
            center = state.center,
            level = state.level,
            panPx = pan,
            zoomScale = zoom,
            metersPerPixel = metersPerPixel,
            minLevel = StaticMapScale.MIN_LEVEL,
            maxLevel = StaticMapScale.MAX_LEVEL,
        )
        if (camera.center == state.center && camera.level == state.level) return
        _mapPick.update {
            it?.copy(
                center = camera.center,
                level = camera.level,
                pendingShift = Offset.Zero,
                moved = true,
            )
        }
    }

    /** 새 고정밀 fix를 받은 같은 탭 안에서 지도를 내 위치로 옮긴다. */
    fun onMapRecenter() {
        currentLocationPrimeJob?.cancel()
        val generation = mapPickGeneration
        refreshMapLocation(recenter = true, generation = generation)
    }

    private fun refreshMapLocation(recenter: Boolean, generation: Long) {
        if (_mapPick.value?.locating == true) return
        _mapPick.update { it?.copy(locating = true, error = null) }
        viewModelScope.launch {
            val measured = CurrentLocation.getFresh(appContext)?.let(::rememberLocation)
            if (generation != mapPickGeneration) return@launch
            _mapPick.update { current ->
                current ?: return@update current
                val fix = measured
                    ?: current.currentLocation?.takeIf {
                        System.currentTimeMillis() - it.atMillis in 0..MAP_LOCATION_MAX_AGE_MS
                    }
                    ?: rememberedLocation(MAP_LOCATION_MAX_AGE_MS)
                current.copy(
                    center = if (recenter && fix != null) fix.point else current.center,
                    currentLocation = fix,
                    locating = false,
                    moved = if (recenter && fix != null) true else current.moved,
                    pendingShift = if (recenter && fix != null) Offset.Zero else current.pendingShift,
                    error = if (recenter && measured == null) {
                        if (fix == null) "정확한 현재 위치를 확인할 수 없음"
                        else "새 위치를 못 받아 마지막 위치를 표시함"
                    } else current.error,
                )
            }
        }
    }

    /**
     * 지금 보이는 영역을 다시 검색한다.
     *
     * 화면에 담긴 범위를 `rect` 로 만들어 보낸다. 순서는
     * `minLng,minLat,maxLng,maxLat` 이고, 틀리면 서버가 무시한다.
     */
    fun researchMapArea(viewWidthPx: Int, viewHeightPx: Int, metersPerPixel: Double) {
        val state = _mapPick.value ?: return
        val target = state.target
        val generation = mapPickGeneration
        val query = placeSearchOf(target).query.trim()
        if (query.isBlank()) return

        val halfLatDeg =
            (viewHeightPx / 2.0 * metersPerPixel) / StaticMapScale.METERS_PER_DEGREE
        val halfLngDeg = (viewWidthPx / 2.0 * metersPerPixel) /
            (StaticMapScale.METERS_PER_DEGREE *
                cos(Math.toRadians(state.center.lat)).coerceAtLeast(0.01))

        val rect = listOf(
            state.center.lng - halfLngDeg,
            state.center.lat - halfLatDeg,
            state.center.lng + halfLngDeg,
            state.center.lat + halfLatDeg,
        ).joinToString(",") { "%.6f".format(Locale.ROOT, it) }

        _mapPick.update { it?.copy(searching = true, error = null) }
        runPlaceSearch(
            get = { placeSearchOf(target) },
            set = { next ->
                if (generation != mapPickGeneration || _mapPick.value?.target != target) {
                    return@runPlaceSearch
                }
                setPlaceSearch(target, next)
                _mapPick.update { current ->
                    if (current?.target != target) return@update current
                    if (next.searching || next.loadingMore) {
                        return@update current.copy(searching = true, error = null)
                    }
                    if (next.error != null) {
                        // 실패했다고 마지막 정상 목록·선택을 지우지 않는다.
                        // 사용자는 그대로 고르거나 같은 영역을 다시 시도할 수 있다.
                        return@update current.copy(
                            searching = false,
                            error = next.error,
                        )
                    }
                    // 새 결과에도 있으면 사용자가 고른 핀을 유지한다. 없으면
                    // 첫 핀으로 옮기고, 0건이면 선택도 반드시 비운다.
                    current.withSearchResults(next.results)
                }
            },
            page = 1,
            rect = rect,
            // 지도 중심에서 가까운 것부터 보는 것이 지도의 뜻에 맞는다.
            sort = PlaceSearchResponse.SORT_DISTANCE,
            near = state.center,
        )
    }

    /** 지도에서 고른 것을 원래 검색으로 되돌리고 닫는다. */
    fun confirmMapPick() {
        val state = _mapPick.value ?: return
        val picked = state.selected ?: return
        when (state.target) {
            MapTarget.DESTINATION -> onAddPlaceSelected(picked)
            MapTarget.HOME -> onHomePlaceSelected(picked)
            MapTarget.ORIGIN -> onOriginSelected(picked)
        }
        _mapPick.value = null
        goBack()
    }

    /**
     * 화면 크기를 알았으니 지도 이미지를 받는다.
     *
     * 중심·줌·마커·크기가 같으면 다시 받지 않는다. 화면이 재구성될 때마다
     * 부르면 같은 이미지를 반복해서 요청한다.
     */
    fun loadMapImage(widthDp: Int, heightDp: Int) {
        var state = _mapPick.value ?: return
        if (widthDp <= 0 || heightDp <= 0) return

        // 최초 진입에서는 목록의 다섯 마커가 모두 보이도록 한 번만 맞춘다.
        if (!state.fittedMarkers && state.markers.isNotEmpty()) {
            val fit = RouteMapProjection.fit(
                path = state.markers.map { GeoPoint(it.lat, it.lng) },
                requestUnits = widthDp,
                requestHeightUnits = heightDp,
                marginUnits = 42,
            )
            val fitted = state.copy(
                center = fit?.first ?: state.center,
                level = fit?.second
                    ?.coerceIn(StaticMapScale.MIN_LEVEL, StaticMapScale.MAX_LEVEL)
                    ?: state.level,
                fittedMarkers = true,
            )
            _mapPick.value = fitted
            state = fitted
        }

        val requestWidth = MapCameraMath.bufferedRequestUnits(widthDp, STATIC_MAP_MAX_WIDTH)
        val requestHeight = MapCameraMath.bufferedRequestUnits(heightDp, STATIC_MAP_MAX_HEIGHT)
        val generation = mapPickGeneration
        val signature = "$generation,${state.target},${state.center.lat},${state.center.lng}," +
            "${state.level},$requestWidth,$requestHeight"

        val alreadyDisplayed = state.image != null &&
            state.imageCenter == state.center && state.imageLevel == state.level &&
            state.imageWidthDp == requestWidth && state.imageHeightDp == requestHeight
        if (alreadyDisplayed) {
            // B를 받는 중 다시 A(현재 frame)로 돌아오면 B의 늦은 응답은
            // 버려지지만 loading만 남을 수 있다. 요청도 함께 취소하고 A를
            // 현재 signature로 확정한다.
            if (state.imageLoading || signature != lastMapSignature) {
                mapImageJob?.cancel()
                lastMapSignature = signature
                _mapPick.update { current ->
                    if (current?.center == state.center && current.level == state.level) {
                        current.copy(imageLoading = false, imageError = null)
                    } else current
                }
            }
            return
        }
        if (signature == lastMapSignature && state.imageLoading) return

        lastMapSignature = signature
        mapImageJob?.cancel()
        _mapPick.update { current ->
            if (current == null || current.target != state.target) current
            else current.copy(imageLoading = true, imageError = null)
        }
        mapImageJob = viewModelScope.launch {
            // 연속 손짓은 마지막 카메라 한 장만 요청한다. 경로 API와 공유하는
            // 호출 한도를 지키고, 이미 끝난 손짓의 bitmap이 끼어들지 않게 한다.
            delay(MAP_REQUEST_DEBOUNCE_MS)
            val result = repository.staticMap(
                center = state.center,
                level = state.level,
                widthDp = requestWidth,
                heightDp = requestHeight,
                // 핀은 목록과 같은 state.markers를 Compose가 그린다. PNG에
                // 구워 넣으면 선택색과 목록 동기화를 제어할 수 없다.
                markers = emptyList(),
            )
            _mapPick.update { current ->
                current ?: return@update current
                if (generation != mapPickGeneration || signature != lastMapSignature ||
                    current.target != state.target || current.center != state.center ||
                    current.level != state.level
                ) return@update current
                when (result) {
                    is EventRepository.Result.Success ->
                        current.copy(
                            image = result.data,
                            imageCenter = state.center,
                            imageLevel = state.level,
                            imageWidthDp = requestWidth,
                            imageHeightDp = requestHeight,
                            imageLoading = false,
                            imageError = null,
                        )

                    is EventRepository.Result.Failure -> {
                        lastMapSignature = null
                        current.copy(imageLoading = false, imageError = result.message)
                    }
                }
            }
        }
    }

    /** 방금 받은 지도의 조건. 같은 조건이면 다시 받지 않는다 */
    private var lastMapSignature: String? = null

    /** 경로 지도의 마지막 요청 조합. 같으면 다시 받지 않는다 */
    private var lastRouteMapSignature: String? = null
    private var routeMapImageJob: Job? = null

    private fun placeSearchOf(target: MapTarget): PlaceSearch = when (target) {
        MapTarget.DESTINATION -> _add.value.place
        MapTarget.HOME -> _homeSetup.value.place
        MapTarget.ORIGIN -> _routeChoice.value?.originPlace ?: PlaceSearch()
    }

    private fun setPlaceSearch(target: MapTarget, next: PlaceSearch) = when (target) {
        MapTarget.DESTINATION -> _add.update { it.copy(place = next) }
        MapTarget.HOME -> _homeSetup.update { it.copy(place = next) }
        MapTarget.ORIGIN -> _routeChoice.update { (it ?: RouteState()).copy(originPlace = next) }
    }

    /** 후보 조회 한 곳. 최초 진입·재시도·출발지 변경이 모두 이걸 쓴다. */
    private suspend fun loadCandidates(
        place: PlaceSearchItem,
        origin: PlaceSearchItem?,
        keepSelection: Boolean,
    ) {
        _routeChoice.update { (it ?: RouteState()).copy(loading = true, error = null) }
        when (val result = repository.routeCandidates(place, origin)) {
            is EventRepository.Result.Success -> _routeChoice.update { current ->
                val base = current ?: RouteState()
                val choice = if (keepSelection) {
                    result.data.copy(
                        selectedKey = _add.value.routeKey ?: result.data.selectedKey
                    )
                } else {
                    result.data
                }
                base.copy(loading = false, error = null, choice = choice)
            }

            is EventRepository.Result.Failure -> _routeChoice.update { current ->
                (current ?: RouteState()).copy(loading = false, error = result.message)
            }
        }
    }

    fun onRouteSelected(key: String) {
        _routeChoice.update { current ->
            val choice = current?.choice ?: return@update current
            current.copy(choice = choice.copy(selectedKey = key))
        }
    }

    fun retryRouteChoice() {
        val place = _add.value.selectedPlace ?: return
        val origin = _routeChoice.value?.origin
        viewModelScope.launch { loadCandidates(place, origin, keepSelection = false) }
    }

    // --- 경로 선택: 출발지 -------------------------------------------------

    /** 출발지 검색창을 펼치거나 접는다. */
    fun onOriginEditToggle(editing: Boolean) {
        _routeChoice.update { current ->
            (current ?: RouteState()).copy(
                originEditing = editing,
                // 접으면 검색을 버린다. 남겨 두면 다시 펼쳤을 때 예전 검색어와
                // 결과가 그대로 있어 방금 고친 출발지와 어긋나 보인다.
                originPlace = if (editing) {
                    current?.originPlace ?: PlaceSearch()
                } else {
                    PlaceSearch()
                },
            )
        }
    }

    fun onOriginQueryChange(v: String) = _routeChoice.update {
        (it ?: RouteState()).let { s -> s.copy(originPlace = s.originPlace.withQuery(v)) }
    }

    fun searchOriginPlaces() = searchOriginPlaces(page = 1)

    fun loadMoreOriginPlaces() {
        val place = _routeChoice.value?.originPlace ?: return
        if (place.canLoadMore) searchOriginPlaces(page = place.page + 1)
    }

    fun onOriginSortChange(sort: String) = searchOriginPlaces(page = 1, sort = sort)

    private fun searchOriginPlaces(page: Int, sort: String? = null) = runPlaceSearch(
        get = { _routeChoice.value?.originPlace ?: PlaceSearch() },
        set = { next ->
            _routeChoice.update { (it ?: RouteState()).copy(originPlace = next) }
        },
        page = page,
        sort = sort,
    )

    /**
     * 출발지를 바꾼다. 후보를 다시 받아야 한다.
     *
     * 출발지가 달라지면 소요시간과 노선이 전부 달라지므로 이전 선택을 버린다.
     * 남겨 두면 없는 노선 key 를 들고 일정을 만들어 `route_failed` 가 된다.
     */
    fun onOriginSelected(item: PlaceSearchItem?) {
        val place = _add.value.selectedPlace ?: return
        _routeChoice.update { current ->
            (current ?: RouteState()).copy(
                origin = item,
                originEditing = false,
                originPlace = PlaceSearch(),
                originNotice = null,
            )
        }
        _add.update { it.copy(routeKey = null, routeLabel = null) }
        viewModelScope.launch { loadCandidates(place, item, keepSelection = false) }
    }

    /** 출발지를 현재 위치로 다시 잡는다. */
    fun useCurrentLocationAsOrigin() {
        val place = _add.value.selectedPlace ?: return
        _routeChoice.update { (it ?: RouteState()).copy(locating = true, originNotice = null) }
        viewModelScope.launch {
            val origin = detectCurrentPlace()
            _routeChoice.update { current ->
                (current ?: RouteState()).copy(
                    locating = false,
                    origin = origin ?: current?.origin,
                    originEditing = false,
                    originNotice = if (origin == null) {
                        "현재 위치를 확인할 수 없음. 출발지를 직접 검색할 것"
                    } else {
                        null
                    },
                )
            }
            if (origin != null) {
                _add.update { it.copy(routeKey = null, routeLabel = null) }
                loadCandidates(place, origin, keepSelection = false)
            }
        }
    }

    /** 고른 경로를 일정 추가 폼에 반영하고 돌아간다. */
    fun confirmRoute() {
        val current = _routeChoice.value ?: return
        val choice = current.choice ?: return
        val option = choice.options.firstOrNull { it.key == choice.selectedKey }
        _add.update {
            it.copy(
                routeKey = option?.key,
                routeLabel = option?.let { o -> "${o.minutesLabel} · ${o.mode}" },
                // 경로를 고른 출발지를 함께 들고 간다. 빠뜨리면 서버가 집 기준으로
                // 계산해 화면에 보인 소요시간과 달라진다.
                origin = current.origin,
                originLabel = current.origin?.name ?: choice.originLabel,
                error = null,
            )
        }
        goBack()
    }

    fun goBack() {
        _nav.update {
            if (!it.canGoBack) it
            else it.copy(stack = it.stack.dropLast(1), forward = false)
        }
    }

    fun goHome() {
        _nav.update { NavState(stack = listOf(AppRoute.Home), forward = false) }
    }

    private fun loadPlan(eventId: Long) {
        val previousMap = _routeMap.value?.takeIf { it.eventId == eventId }
        val generation = ++planLoadGeneration
        _plan.value = null
        // 다른 일정의 지도가 남아 있으면 안 된다. 계획을 받기 전에 비운다.
        _routeMap.value = null
        primeCurrentLocation()
        viewModelScope.launch {
            when (val result = repository.loadPlan(eventId)) {
                is EventRepository.Result.Success -> {
                    if (generation != planLoadGeneration) return@launch
                    _plan.value = result.data
                    initRouteMap(
                        plan = result.data,
                        // 같은 일정을 잠깐 닫았다 다시 연 경우, 1분 게이트는 지키되
                        // 이미 받은 현재 위치 기준 선은 빈 화면으로 만들지 않는다.
                        preservedLive = previousMap?.takeIf { it.pathFromCurrent },
                    )
                }

                is EventRepository.Result.Failure -> {
                    if (generation != planLoadGeneration) return@launch
                    _state.update { it.copy(error = result.message) }
                }
            }
        }
    }

    // --- 알람 결정 화면의 경로 지도 ----------------------------------------

    /** 경로 좌표가 있으면 지도를 경로 전체가 보이는 상태로 시작한다. */
    private fun initRouteMap(
        plan: AlarmPlanView,
        preservedLive: RouteMapState? = null,
    ) {
        if (!plan.hasRoutePath) return

        val plannedSummary = listOfNotNull(
            plan.routeDetail?.takeIf { it.isNotBlank() },
            plan.routeDistanceM?.let { "%.1fkm".format(it / 1000.0) },
            plan.breakdown
                .firstOrNull { it.kind == PlanRow.Kind.TRAVEL }
                ?.let { "${it.minutes}분" },
        ).joinToString(" · ").takeIf { it.isNotBlank() }

        // 디스크 사본은 프로세스가 재생성돼 위치 Flow가 아직 비어 있어도 쓴다.
        // 이 사본은 서비스가 이동 중에만 만들고 5분 뒤 폐기하므로, 새 GPS fix를
        // 기다리며 옛 출발지 경로를 다시 보일 이유가 없다.
        val tracked = TripLiveState.pointFor(plan.eventId)
        val cachedSnapshot = TripLiveState.liveRouteFor(plan.eventId)
        val cachedRoute = cachedSnapshot?.usableLiveRoute()
        val moving = when {
            tracked != null -> tracked.phase == TripGeofence.Phase.IN_TRANSIT
            cachedRoute != null -> true
            else -> stageOf(plan) == TripStage.IN_TRANSIT
        }
        val livePath = when {
            cachedRoute != null -> cachedRoute.path
            moving && preservedLive?.pathFromCurrent == true -> preservedLive.path
            else -> emptyList()
        }
        val displayPath = if (moving) livePath else plan.routePath
        val displaySummary = if (moving) {
            cachedRoute?.summary
                ?: preservedLive?.summary
                ?: "현재 위치에서 가장 빠른 경로 확인 중"
        } else {
            plannedSummary
        }
        val altPath = if (!moving && plan.hasAltRoute) plan.altRoutePath else emptyList()
        val altSummary = if (!moving) {
            plan.altFasterMinutes
                ?.takeIf { plan.hasAltRoute }
                ?.let { minutes ->
                    listOfNotNull(plan.altRouteLabel, "${minutes}분 빠름")
                        .joinToString(" · ")
                }
        } else null

        val currentLocation = tracked?.toUsableMapLocation()
            ?: rememberedLocation(MAP_LOCATION_MAX_AGE_MS)
        val fitPath = (displayPath + altPath).ifEmpty {
            listOfNotNull(
                currentLocation?.point ?: cachedSnapshot?.origin?.takeIf { cachedRoute != null },
                plan.routePath.lastOrNull(),
            )
        }

        val fit = RouteMapProjection.fit(
            path = fitPath,
            requestUnits = ROUTE_MAP_FIT_WIDTH,
            requestHeightUnits = ROUTE_MAP_FIT_HEIGHT,
        ) ?: return
        _routeMap.value = RouteMapState(
            eventId = plan.eventId,
            path = displayPath,
            plannedPath = plan.routePath,
            center = fit.first,
            level = fit.second,
            summary = displaySummary,
            plannedSummary = plannedSummary,
            pathFromCurrent = moving && displayPath.size >= 2,
            inTransit = moving,
            altPath = altPath,
            altSummary = altSummary,
            currentLocation = currentLocation,
        )

        // 서비스가 이미 백그라운드에서 받은 경로가 있으면 이 호출이 즉시
        // 적용한다. 네트워크 요청은 화면이 아니라 서비스가 담당한다.
        tracked?.let { onTrackedPoint(it) }
    }

    /** 경로 전체가 보이는 상태로 되돌린다. */
    fun fitRouteMap() {
        val state = _routeMap.value ?: return
        val fit = RouteMapProjection.fit(
            path = (state.path + state.altPath).ifEmpty {
                listOfNotNull(state.currentLocation?.point, state.plannedPath.lastOrNull())
            },
            requestUnits = ROUTE_MAP_FIT_WIDTH,
            requestHeightUnits = ROUTE_MAP_FIT_HEIGHT,
        ) ?: return
        _routeMap.update {
            val current = it ?: return@update it
            current.copy(
                center = fit.first,
                level = fit.second,
                pendingShift = Offset.Zero,
                imageError = null,
                cameraMode = RouteCameraMode.FIT_ROUTE,
            )
        }
    }

    /** 실제 경로 지도의 pan/pinch를 끝날 때 한 번만 확정한다. */
    fun onRouteMapGestureEnd(pan: Offset, zoom: Float, metersPerPixel: Double) {
        val state = _routeMap.value ?: return
        val camera = MapCameraMath.finishGesture(
            center = state.center,
            level = state.level,
            panPx = pan,
            zoomScale = zoom,
            metersPerPixel = metersPerPixel,
            minLevel = StaticMapScale.MIN_LEVEL,
            maxLevel = StaticMapScale.ROUTE_MAX_LEVEL,
        )
        if (camera.center == state.center && camera.level == state.level) return
        _routeMap.update {
            val current = it ?: return@update it
            current.copy(
                center = camera.center,
                level = camera.level,
                pendingShift = Offset.Zero,
                imageError = null,
                cameraMode = RouteCameraMode.FREE,
            )
        }
    }

    /** 실제 경로 지도에서 최신의 신뢰할 수 있는 GPS 위치로 이동한다. */
    fun onRouteMapRecenter() {
        val state = _routeMap.value ?: return
        currentLocationPrimeJob?.cancel()
        _routeMap.update { current ->
            if (current?.eventId != state.eventId) current
            else current.copy(locating = true, locationError = null)
        }
        viewModelScope.launch {
            val measured = CurrentLocation.getFresh(appContext)?.let(::rememberLocation)
            _routeMap.update { current ->
                if (current?.eventId != state.eventId) return@update current
                val now = System.currentTimeMillis()
                val fix = measured
                    ?: TripLiveState.pointFor(state.eventId)?.toUsableMapLocation(now)
                    ?: current.currentLocation?.takeIf {
                        now - it.atMillis in 0..MAP_LOCATION_MAX_AGE_MS
                    }
                    ?: rememberedLocation(MAP_LOCATION_MAX_AGE_MS, now)
                current.copy(
                    center = fix?.point ?: current.center,
                    currentLocation = fix,
                    locating = false,
                    locationError = if (measured == null) {
                        if (fix == null) "정확한 현재 위치를 확인할 수 없음"
                        else "새 위치를 못 받아 마지막 위치를 표시함"
                    } else null,
                    cameraMode = if (fix != null) RouteCameraMode.FREE else current.cameraMode,
                    pendingShift = if (fix != null) Offset.Zero else current.pendingShift,
                )
            }
        }
    }

    // --- 이동 중이면 여기서부터 다시 본다 ---------------------------------

    /**
     * 현재 위치 경로가 사라졌을 때 화면을 실제 이동 단계와 맞춘다.
     *
     * `snapshot`과 `liveRoute`는 별도 Flow라서 하나가 먼저 null이 될 수 있다.
     * 아직 이동 중이라면 그 짧은 틈에도 출발지 기준 [RouteMapState.plannedPath]를
     * 복원하지 않는다. 유효한 현재 위치 경로가 남아 있으면 그것을 다시 적용하고,
     * 없으면 새 경로가 올 때까지 선을 비운다.
     */
    private fun clearLiveRoutePath(inTransit: Boolean? = null) {
        val map = _routeMap.value ?: return
        val tracked = TripLiveState.pointFor(map.eventId)
        val moving = inTransit
            ?: tracked?.let { it.phase == TripGeofence.Phase.IN_TRANSIT }
            ?: map.inTransit

        val freshSnapshot = if (moving) {
            TripLiveState.liveRouteFor(map.eventId)
                ?.takeIf { it.usableLiveRoute() != null }
        } else null
        if (freshSnapshot != null) {
            applyLiveRoute(freshSnapshot)
            return
        }

        _routeMap.update { current ->
            if (current == null || current.eventId != map.eventId) current else {
                val next = current.withLiveRouteDisplay(
                    inTransit = moving,
                    liveRoute = null,
                )
                val fit = if (current.cameraMode == RouteCameraMode.FIT_ROUTE) {
                    RouteMapProjection.fit(
                        path = if (moving) {
                            listOfNotNull(
                                current.currentLocation?.point,
                                current.plannedPath.lastOrNull(),
                            )
                        } else {
                            current.plannedPath
                        },
                        requestUnits = ROUTE_MAP_FIT_WIDTH,
                        requestHeightUnits = ROUTE_MAP_FIT_HEIGHT,
                    )
                } else null
                next.copy(
                    center = fit?.first ?: current.center,
                    level = fit?.second ?: current.level,
                    pendingShift = if (fit != null) Offset.Zero else current.pendingShift,
                )
            }
        }
    }

    private fun TripLiveState.Snapshot.toUsableMapLocation(
        nowMillis: Long = System.currentTimeMillis(),
    ): MapLocationFix? {
        val age = nowMillis - atMillis
        if (!accuracyM.isFinite() || accuracyM > TripGeofence.MAX_ACCURACY_M) return null
        if (age !in 0..MAP_LOCATION_MAX_AGE_MS) return null
        return MapLocationFix(point, accuracyM, atMillis)
    }

    /**
     * 위치 Flow 는 단계 전환과 현재 마커만 반영한다. 네트워크 호출은 하지 않는다.
     * 화면이 백그라운드일 때도 돌아야 하는 작업은 [TripTrackingService] 소유다.
     */
    private fun onTrackedPoint(snapshot: TripLiveState.Snapshot) {
        val map = _routeMap.value ?: return
        if (snapshot.eventId != map.eventId) return

        val moving = snapshot.phase == TripGeofence.Phase.IN_TRANSIT
        _routeMap.update { current ->
            if (current?.eventId != snapshot.eventId) current
            else current.copy(inTransit = moving)
        }

        // 오차가 큰 raw fix는 판정에도 쓰이지 않는다. 지도에도 정확한 현재
        // 위치처럼 내보내지 않고 마지막 신뢰 가능한 점을 유지한다.
        snapshot.toUsableMapLocation()?.let { fix ->
            rememberLocation(fix.point, fix.accuracyM, fix.atMillis)
            _routeMap.update { current ->
                if (current?.eventId != snapshot.eventId) current
                else current.copy(currentLocation = fix, locationError = null)
            }
        }

        // 이동 중이 끝났으면 몇 분 전 좌표 기준의 선도 끝낸다.
        if (!moving) {
            clearLiveRoutePath(inTransit = false)
            return
        }

        // 출발한 순간부터 출발지 기준 대안은 거짓 정보다. 첫 현재 위치 조회가
        // 실패하더라도 그 선을 그대로 두지 않는다.
        if (!map.pathFromCurrent && (map.path.isNotEmpty() || map.hasAltPath)) {
            _routeMap.update { current ->
                if (current == null || current.eventId != map.eventId) current
                else current.copy(
                    path = emptyList(),
                    summary = "현재 위치에서 가장 빠른 경로 확인 중",
                    altPath = emptyList(),
                    altSummary = null,
                )
            }
        }

        TripLiveState.liveRouteFor(map.eventId)?.let(::applyLiveRoute)
    }

    /** 서비스가 검증·저장한 최신 결과를 현재 지도에 투영한다. */
    private fun applyLiveRoute(snapshot: LiveRouteStore.Snapshot) {
        val tracked = TripLiveState.pointFor(snapshot.eventId)
        // 새 GPS fix 전(프로세스 재생성 직후)에는 저장 사본을 허용한다. 다만
        // 도착/출발 전이라는 새 판정이 이미 있으면 오래된 사본이 이길 수 없다.
        if (tracked != null && tracked.phase != TripGeofence.Phase.IN_TRANSIT) return
        val live = snapshot.usableLiveRoute()
        if (live == null) {
            // 이 오래됐거나 깨진 사본을 검사하는 사이 서비스가 새 결과를 게시했을 수
            // 있다. 시각까지 같은 사본만 지워 새 경로를 실수로 없애지 않는다.
            TripLiveState.clearLiveRoute(
                eventId = snapshot.eventId,
                expectedFetchedAtMillis = snapshot.fetchedAtMillis,
            )
            clearLiveRoutePath()
            return
        }

        _routeMap.update { current ->
            // 다른 일정의 저장 사본이나 늦은 서비스 응답은 절대 섞지 않는다.
            if (current == null || current.eventId != snapshot.eventId) return@update current

            val fit = if (current.cameraMode == RouteCameraMode.FIT_ROUTE) {
                RouteMapProjection.fit(
                    path = live.path,
                    requestUnits = ROUTE_MAP_FIT_WIDTH,
                    requestHeightUnits = ROUTE_MAP_FIT_HEIGHT,
                )
            } else null
            val nextCenter = fit?.first ?: current.center
            val nextLevel = fit?.second ?: current.level
            current.withLiveRouteDisplay(
                inTransit = true,
                liveRoute = live,
            ).copy(
                center = nextCenter,
                level = nextLevel,
                pendingShift = if (fit != null) Offset.Zero else current.pendingShift,
                // 새 배경이 올 때까지 마지막 정상 frame을 같은 좌표 변환으로
                // 유지한다. 따라서 1분 갱신 때 빈 카드가 번쩍이지 않는다.
                imageError = null,
            )
        }
    }

    /** 화면 크기를 알았으니 지도 이미지를 받는다. 같은 조합이면 다시 받지 않는다. */
    fun loadRouteMapImage(widthDp: Int, heightDp: Int) {
        val state = _routeMap.value ?: return
        if (widthDp <= 0 || heightDp <= 0) return

        val requestWidth = MapCameraMath.bufferedRequestUnits(widthDp, STATIC_MAP_MAX_WIDTH)
        val requestHeight = MapCameraMath.bufferedRequestUnits(heightDp, STATIC_MAP_MAX_HEIGHT)
        val signature = "${state.eventId},${state.center.lat},${state.center.lng}," +
            "${state.level},$requestWidth,$requestHeight"
        val alreadyDisplayed = state.image != null &&
            state.imageCenter == state.center && state.imageLevel == state.level &&
            state.imageWidthDp == requestWidth && state.imageHeightDp == requestHeight
        if (alreadyDisplayed) {
            if (state.imageLoading || signature != lastRouteMapSignature) {
                routeMapImageJob?.cancel()
                lastRouteMapSignature = signature
                _routeMap.update { current ->
                    if (current?.eventId == state.eventId &&
                        current.center == state.center && current.level == state.level
                    ) current.copy(imageLoading = false, imageError = null)
                    else current
                }
            }
            return
        }
        if (signature == lastRouteMapSignature && state.imageLoading) return
        lastRouteMapSignature = signature
        routeMapImageJob?.cancel()

        _routeMap.update { current ->
            if (current == null || current.eventId != state.eventId ||
                current.center != state.center || current.level != state.level
            ) current
            else current.copy(imageLoading = true, imageError = null)
        }
        routeMapImageJob = viewModelScope.launch {
            delay(MAP_REQUEST_DEBOUNCE_MS)
            // 마커는 보내지 않는다. 출발·도착 표식을 앱이 경로선과 같은 좌표계로
            // 그리므로, 카카오가 그린 마커와 겹치면 두 번 표시된다.
            val result = repository.staticMap(
                center = state.center,
                level = state.level,
                widthDp = requestWidth,
                heightDp = requestHeight,
                markers = emptyList(),
            )
            _routeMap.update { current ->
                current ?: return@update current
                // 중심·줌·크기 중 하나라도 바뀐 뒤 끝난 예전 응답은 버린다.
                if (signature != lastRouteMapSignature ||
                    current.eventId != state.eventId ||
                    current.center != state.center || current.level != state.level
                ) return@update current
                when (result) {
                    is EventRepository.Result.Success ->
                        current.copy(
                            image = result.data,
                            imageCenter = state.center,
                            imageLevel = state.level,
                            imageWidthDp = requestWidth,
                            imageHeightDp = requestHeight,
                            imageLoading = false,
                            imageError = null,
                        )

                    is EventRepository.Result.Failure -> {
                        lastRouteMapSignature = null
                        current.copy(imageLoading = false, imageError = result.message)
                    }
                }
            }
        }
    }

    /**
     * 지금 어느 단계인가.
     *
     * 추적 중이면 판정기의 단계를 쓰고, 아니면 알람·일정 시각으로 가른다.
     * 판정기는 "알람 전" 과 "준비 중" 을 구분하지 않는다 — 판정에는 같지만
     * (둘 다 집에 있다) 사용자에게는 전혀 다른 상태다.
     *
     * 시각 판정은 [AlarmPlanView.alarmPassed]·[AlarmPlanView.eventPassed] 가 이미
     * 담고 있다. 여기서 시계를 다시 읽으면 화면에 보이는 문구("지난 알람")와
     * 단계가 서로 다른 시점을 근거로 삼게 된다.
     */
    fun stageOf(plan: AlarmPlanView): TripStage = TripStage.of(
        tracked = TripLiveState.pointFor(plan.eventId)?.let { live ->
            when (live.phase) {
                TripGeofence.Phase.BEFORE_DEPARTURE -> TripStage.PREPARING
                TripGeofence.Phase.IN_TRANSIT -> TripStage.IN_TRANSIT
                TripGeofence.Phase.ARRIVED -> TripStage.ARRIVED
            }
        } ?: TripLiveState.liveRouteFor(plan.eventId)
            ?.takeIf { it.usableLiveRoute() != null }
            ?.let { TripStage.IN_TRANSIT },
        alarmPassed = plan.alarmPassed,
        eventPassed = plan.eventPassed,
        prepApplies = plan.prepApplies,
    )

    // --- 일정 추가 --------------------------------------------------------

    data class AddState(
        val title: String = "",
        val date: LocalDate = LocalDate.now().plusDays(1),
        val hour: Int = 9,
        val minute: Int = 0,
        /** null 이면 "기타" — 서버에서 태그를 비우고 프로필 기본 τ 를 쓴다 */
        val tagKey: String? = "class",
        /** 목적지 검색. 집 주소·출발지와 같은 상태를 쓴다 */
        val place: PlaceSearch = PlaceSearch(),
        val selectedPlace: PlaceSearchItem? = null,

        /** ⑬ 경로 선택에서 고른 값. 비어 있으면 서버가 최단 경로를 쓴다 */
        val routeKey: String? = null,
        /** 고른 경로 요약. "23분 · 지하철+도보+버스" */
        val routeLabel: String? = null,

        /**
         * ⑬ 에서 고른 출발지. null 이면 서버가 프로필 집을 쓴다.
         *
         * [routeKey] 와 **짝으로 움직여야 한다.** 경로는 이 출발지 기준으로 고른
         * 것이므로 따로 보내면 서버가 다른 경로를 계산한다.
         */
        val origin: PlaceSearchItem? = null,
        /** 출발지 표시명. 일정 추가 화면에 "○○에서 출발" 로 보여준다 */
        val originLabel: String? = null,

        val submitting: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    ) {
        val canSubmit: Boolean get() = !submitting && title.isNotBlank()

        /** 경로를 고를 수 있는 조건. 장소가 있어야 목적지가 정해진다 */
        val canPickRoute: Boolean get() = !submitting && selectedPlace != null
    }

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()

    fun resetAdd() {
        _add.value = AddState()
        _routeChoice.value = null
    }

    fun onAddTitleChange(v: String) = _add.update { it.copy(title = v, error = null) }
    fun onAddDateChange(v: LocalDate) = _add.update { it.copy(date = v) }
    fun onAddTimeChange(h: Int, m: Int) = _add.update { it.copy(hour = h, minute = m) }
    fun onAddTagChange(key: String?) = _add.update { it.copy(tagKey = key) }
    fun onAddQueryChange(v: String) =
        _add.update { it.copy(place = it.place.withQuery(v)) }

    /**
     * 장소가 바뀌면 고른 경로를 버린다.
     *
     * 목적지가 달라졌는데 이전 경로 key 를 들고 있으면 서버가 그 노선을 찾지
     * 못해 `route_failed` 가 되거나, 운 나쁘면 엉뚱한 노선에 맞아 버린다.
     */
    fun onAddPlaceSelected(item: PlaceSearchItem?) {
        _add.update {
            it.copy(
                selectedPlace = item,
                place = it.place.copy(results = emptyList(), query = item?.name ?: ""),
                routeKey = null,
                routeLabel = null,
                // 출발지도 함께 버린다. 경로를 다시 고를 때 현재 위치를 새로
                // 잡아야 하고, 남겨 두면 어느 출발지로 고른 경로인지 흐려진다.
                origin = null,
                originLabel = null,
            )
        }
        _routeChoice.value = null
    }

    fun searchPlaces() = searchAddPlaces(page = 1)

    fun loadMoreAddPlaces() {
        if (_add.value.place.canLoadMore) searchAddPlaces(page = _add.value.place.page + 1)
    }

    fun onAddSortChange(sort: String) = searchAddPlaces(page = 1, sort = sort)

    private fun searchAddPlaces(page: Int, sort: String? = null) = runPlaceSearch(
        get = { _add.value.place },
        set = { next -> _add.update { it.copy(place = next) } },
        page = page,
        sort = sort,
    )

    fun submitAdd() {
        val current = _add.value
        if (!current.canSubmit) return

        val startAt = OffsetDateTime.of(
            current.date,
            java.time.LocalTime.of(current.hour, current.minute),
            ZoneId.systemDefault().rules.getOffset(
                current.date.atTime(current.hour, current.minute)
            ),
        )

        _add.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = repository.createEvent(
                title = current.title,
                startAt = startAt,
                place = current.selectedPlace,
                tagKey = current.tagKey,
                routeKey = current.routeKey,
                origin = current.origin,
            )
            when (result) {
                is EventRepository.Result.Success -> {
                    _add.update { it.copy(submitting = false, done = true) }
                    refresh()
                }

                is EventRepository.Result.Failure ->
                    _add.update { it.copy(submitting = false, error = result.message) }
            }
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteEvent(id)) {
                is EventRepository.Result.Success -> {
                    goHome()
                    refresh()
                }

                is EventRepository.Result.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    // --- 준비 시간 온보딩 (Figma ⑭) ---------------------------------------

    /**
     * 가입 직후 평소 준비 시간을 받는 화면의 상태.
     *
     * **[minutes] 를 비워 둔 채 시작한다.** "30" 을 미리 채우면 대부분이 그대로
     * 저장하고 넘어가서 묻는 의미가 없어진다. 지금까지 전원이 30분이던 이유가
     * 정확히 그것이다. 빠른 선택 버튼으로 손은 덜게 하되, 값은 반드시 사용자가
     * 고른 것이어야 한다.
     */
    data class PrepOnboardingState(
        val minutes: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    ) {
        /** 범위를 벗어난 값은 없는 것으로 본다. 5분 미만·4시간 초과는 입력 실수다. */
        val parsed: Int? get() = minutes.toIntOrNull()?.takeIf { it in PREP_MIN..PREP_MAX }
        val canSubmit: Boolean get() = !submitting && parsed != null
    }

    private val _prepOnboarding = MutableStateFlow(PrepOnboardingState())
    val prepOnboarding: StateFlow<PrepOnboardingState> = _prepOnboarding.asStateFlow()

    /**
     * 준비 시간 화면을 직접 연다. 설정에서 다시 고칠 때 쓴다.
     *
     * 온보딩 경로([maybeAskOnboarding])와 달리 한 번만 띄우는 제한이 없다.
     * 사용자가 고치겠다고 들어온 것이라 막을 이유가 없다.
     */
    fun openPrepOnboarding() {
        _nav.update { it.copy(stack = it.stack + AppRoute.PrepOnboarding, forward = true) }
    }

    fun resetPrepOnboarding() {
        _prepOnboarding.value = PrepOnboardingState()
    }

    fun onPrepOnboardingChange(v: String) =
        _prepOnboarding.update { it.copy(minutes = v.filter(Char::isDigit).take(3), error = null) }

    /**
     * ± 버튼. 값이 비어 있으면 [PREP_SEED] 에서 시작한다.
     *
     * 비었을 때 아무 일도 안 하게 두면 버튼이 고장난 것처럼 보인다. 그렇다고
     * 처음부터 채워 두면 그 값이 기준점이 되어 버려서, 누르는 순간에만 기준을
     * 만든다.
     */
    fun onPrepOnboardingStep(delta: Int) = _prepOnboarding.update {
        val base = it.minutes.toIntOrNull() ?: PREP_SEED
        it.copy(minutes = (base + delta).coerceIn(PREP_MIN, PREP_MAX).toString(), error = null)
    }

    fun submitPrepOnboarding() {
        val minutes = _prepOnboarding.value.parsed ?: return
        _prepOnboarding.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.setOnboardingPrep(minutes)) {
                is EventRepository.Result.Success -> {
                    _prepOnboarding.update { it.copy(submitting = false, done = true) }
                    // 준비 시간이 바뀌면 서버가 알람을 다시 계산한다. 홈의 시각을
                    // 갱신하지 않으면 방금 답한 값이 반영되지 않은 화면이 남는다.
                    refresh()
                }

                is EventRepository.Result.Failure ->
                    _prepOnboarding.update { it.copy(submitting = false, error = result.message) }
            }
        }
    }

    /**
     * "나중에 입력".
     *
     * 저장하지 않고 닫는다. 네트워크가 죽은 상태에서 이 화면이 앱의 입구를
     * 막아 버리면 안 된다. 다음에 앱을 열면 다시 묻는다 — 프로필이 여전히
     * null 이기 때문이고, 별도 플래그를 두지 않는 이유다.
     */
    fun skipPrepOnboarding() {
        _prepOnboarding.update { it.copy(done = true) }
    }

    /**
     * 아직 받지 못한 온보딩 화면을 밀어 넣는다. 집 주소 → 준비 시간 순이다.
     *
     * 가입 직후만이 아니라 **답하지 않은 동안** 띄운다. 가입 여부를 인텐트
     * 플래그로 넘기면 앱을 껐다 켠 사용자는 영원히 묻지 않게 되고, 그러면
     * 다시 전원이 30분으로 돌아간다. 서버 상태를 근거로 삼으면 그 구멍이 없다.
     *
     * **밀어 넣는 순서가 보이는 순서의 역이다.** [NavState.current] 는
     * `stack.last()` 라서 나중에 넣은 것이 먼저 보인다. 집 주소를 먼저 보여
     * 주려면 준비 시간을 먼저 넣어야 하고, 그러면 뒤로 가기가 그 순서를
     * 되짚어 집 주소 → 준비 시간 → 홈이 된다.
     *
     * 집을 먼저 묻는 이유는 준비 시간이 "문을 나서기까지" 를 묻는 값이어서다.
     * 어디서 나서는지를 모르는 채로 물으면 답할 기준이 없다.
     */
    private fun maybeAskOnboarding(data: EventRepository.HomeData) {
        // 오프라인 사본으로는 판단하지 않는다. 캐시에 값이 없을 뿐인데
        // 물어보면, 이미 답한 사용자에게 같은 질문을 다시 하게 된다.
        if (data.fromCache) return

        val pending = buildList {
            if (data.onboardingPrepMin == null && !prepOnboardingAsked) {
                prepOnboardingAsked = true
                add(AppRoute.PrepOnboarding)
            }
            if (!data.hasHome && !homeOnboardingAsked) {
                homeOnboardingAsked = true
                add(AppRoute.HomeSetup)
            }
        }
        if (pending.isEmpty()) return

        // 온보딩으로 여는 것이라 화면 문구가 달라진다. 밀어 넣기 전에 정한다.
        //
        // `_homeSetup` 선언이 이 함수보다 아래에 있어도 괜찮다. 이 함수는
        // `refresh()` 가 띄운 코루틴 안에서만 불리고, 코루틴 본문은 생성자가
        // 끝난 뒤에 돈다. **동기 호출로 바꾸면 그때 깨진다** — 근거는
        // `HomeViewModelInitOrderTest` 에 있다.
        if (pending.contains(AppRoute.HomeSetup)) {
            _homeSetup.value = HomeSetupState(onboarding = true)
            // 이 경로는 openHomeSetup() 을 거치지 않고 스택에 직접 넣는다.
            // 좌표 선점도 여기서 따로 해야 온보딩 검색에 거리가 붙는다.
            primeCurrentLocation()
        }
        _nav.update { it.copy(stack = it.stack + pending, forward = true) }
    }

    /**
     * 저장된 집을 장소 하나로 만든다. 좌표가 없으면 null.
     *
     * 좌표 둘 중 하나만 있는 상태는 서버 제약(`accounts_profile_home_pair`)이
     * 막지만, 여기서도 둘 다 확인한다 — 한쪽만 들어온 값으로 경로를 계산하면
     * 엉뚱한 곳에서 출발한 것이 된다.
     */
    private fun EventRepository.HomeData.toHomePlace(): PlaceSearchItem? {
        val lat = homeLat ?: return null
        val lng = homeLng ?: return null
        return PlaceSearchItem(
            kakaoPlaceId = null,
            name = homeLabel?.takeIf { it.isNotBlank() } ?: "집",
            address = null,
            lat = lat,
            lng = lng,
            category = null,
        )
    }

    // --- 집 위치 설정 -----------------------------------------------------

    data class HomeSetupState(
        val place: PlaceSearch = PlaceSearch(),
        val selected: PlaceSearchItem? = null,
        /**
         * 가입 직후 온보딩으로 열렸는가.
         *
         * 문구와 건너뛰기 여부가 달라진다. 설정에서 들어온 경우에는 이미 집이
         * 있으므로 "나중에 입력" 이 뜻을 갖지 않는다 — 헤더의 뒤로가기가
         * 그 역할을 한다.
         */
        val onboarding: Boolean = false,
        val submitting: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
        /**
         * 닫힌 이유가 저장인가.
         *
         * 건너뛰기도 [done] 을 세우므로 이것 없이는 구분할 수 없다. 구분하지
         * 않으면 건너뛴 사용자에게 "저장했습니다" 를 띄운다.
         */
        val saved: Boolean = false,
    ) {
        val canSubmit: Boolean get() = !submitting && selected != null
    }

    private val _homeSetup = MutableStateFlow(HomeSetupState())
    val homeSetup: StateFlow<HomeSetupState> = _homeSetup.asStateFlow()

    fun resetHomeSetup() {
        _homeSetup.value = HomeSetupState()
    }

    fun onHomeQueryChange(v: String) =
        _homeSetup.update { it.copy(place = it.place.withQuery(v)) }

    fun onHomePlaceSelected(item: PlaceSearchItem?) = _homeSetup.update {
        it.copy(
            selected = item,
            place = it.place.copy(results = emptyList(), query = item?.name ?: ""),
        )
    }

    /**
     * 집 주소를 저장하지 않고 닫는다.
     *
     * 온보딩에서만 쓴다. 네트워크가 죽은 상태에서 이 화면이 앱의 입구를 막아
     * 버리면 안 된다 — [skipPrepOnboarding] 과 같은 이유다. 다음에 앱을 열면
     * 프로필에 여전히 집이 없으므로 다시 묻는다.
     */
    fun skipHomeSetup() {
        _homeSetup.update { it.copy(done = true, saved = false) }
    }

    fun searchHomePlaces() {
        searchHomePlaces(page = 1)
    }

    fun loadMoreHomePlaces() {
        if (_homeSetup.value.place.canLoadMore) {
            searchHomePlaces(page = _homeSetup.value.place.page + 1)
        }
    }

    fun onHomeSortChange(sort: String) = searchHomePlaces(page = 1, sort = sort)

    private fun searchHomePlaces(page: Int, sort: String? = null) = runPlaceSearch(
        get = { _homeSetup.value.place },
        set = { next -> _homeSetup.update { it.copy(place = next) } },
        page = page,
        sort = sort,
    )

    fun submitHomeSetup() {
        val current = _homeSetup.value
        val place = current.selected ?: return

        _homeSetup.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // 준비 시간은 보내지 않는다. 함께 보내면 준비 시간 온보딩이
            // "이미 답했다" 고 판단해 뜨지 않는다 — 근거는 [EventRepository.setHome].
            val result = repository.setHome(
                label = place.name,
                lat = place.lat,
                lng = place.lng,
            )
            when (result) {
                is EventRepository.Result.Success -> {
                    _homeSetup.update { it.copy(submitting = false, done = true, saved = true) }
                    // 집 위치가 정해지면 서버가 기존 일정 알람을 다시 계산한다.
                    refresh()
                }

                is EventRepository.Result.Failure ->
                    _homeSetup.update { it.copy(submitting = false, error = result.message) }
            }
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /**
         * 지도를 열 때 기준점이 하나도 없을 때의 중심(서울시청).
         *
         * 검색 결과도 없고 현재 위치도 못 잡은 상태다. 좌표 0,0 으로 두면
         * 아프리카 앞바다가 뜨고 사용자는 앱이 고장난 것으로 읽는다.
         */
        val SEOUL_CENTER = GeoPoint(37.5665, 126.9780)

        /**
         * 경로 전체 보기를 계산할 때 가정하는 지도 크기(요청 단위).
         *
         * 실제 화면 크기는 [loadRouteMapImage] 가 알려 주지만, 줌을 고르는
         * 시점에는 아직 모른다. 피그마 ④-a 의 360x260 을 쓴다 — 화면이 조금
         * 넓으면 여백이 늘어날 뿐 경로가 잘리지는 않는다.
         */
        const val ROUTE_MAP_FIT_WIDTH = 360
        const val ROUTE_MAP_FIT_HEIGHT = 260

        /** 카카오 정적 지도 REST API가 받는 최대 요청 단위. */
        const val STATIC_MAP_MAX_WIDTH = 2048
        const val STATIC_MAP_MAX_HEIGHT = 1024

        /** 연속 pan/pinch에서 마지막 카메라만 네트워크로 보낸다. */
        const val MAP_REQUEST_DEBOUNCE_MS = 180L

        /** 지도에 '현재'라고 표시할 추적 위치의 최대 나이. */
        const val MAP_LOCATION_MAX_AGE_MS = 2 * 60 * 1000L

        /** 거의 동시에 끝난 측정은 시각보다 정확도를 우선하는 구간. */
        const val LOCATION_QUALITY_GRACE_MS = 10_000L

        /** 준비 시간으로 받아들이는 범위(분). 밖의 값은 입력 실수로 본다. */
        const val PREP_MIN = 5
        const val PREP_MAX = 240

        /** ± 버튼을 빈 칸에서 처음 눌렀을 때의 기준점(분). */
        const val PREP_SEED = 30

        /**
         * 블록 **정의**를 고쳤을 때의 안내.
         *
         * 서버가 정의 변경에 자동 재계산을 걸지 않는다(카카오 쿼터). 그 사실을
         * 알리지 않으면 사용자는 알람 시각이 그대로인 것을 고장으로 여긴다.
         */
        const val DEFINITION_NOTICE =
            "저장함 · 이미 계산된 알람은 그대로다. 알람 화면의 \"다시 계산\" 으로 반영한다"

        /**
         * 가져오기 화면에서 장소를 찾아 볼 최대 건수.
         *
         * 후보 하나마다 서버가 카카오 검색을 한 번 부른다. 2주치 캘린더가
         * 빽빽한 사용자는 후보가 100건을 넘을 수 있어서 전부 찾으면 검색
         * 쿼터를 한 화면에서 태운다. 선택된 것부터 이만큼만 찾고, 나머지는
         * 사용자가 켜면 다음 조회에서 찾는다.
         */
        const val MAX_PLACE_LOOKUPS = 20
    }
}

/** 카카오 id가 없을 때도 목록과 마커가 같은 장소를 가리키게 하는 안정 키. */
private fun placeStableKey(place: PlaceSearchItem): String =
    place.kakaoPlaceId?.takeIf { it.isNotBlank() }
        ?: "${place.lat.toBits()}:${place.lng.toBits()}:${place.name}"

/**
 * 이동 단계와 현재 위치 경로의 유효성을 별개로 다루는 지도 표시 reducer.
 *
 * 이동 중인데 쓸 수 있는 live route가 없으면 계획 당시 출발지 경로로
 * fallback하지 않는다. 다음 백그라운드 갱신이 올 때까지 선을 비워 두는 것이
 * 현재 위치와 무관한 경로를 잠깐 보여 주는 것보다 정확하다.
 */
internal fun HomeViewModel.RouteMapState.withLiveRouteDisplay(
    inTransit: Boolean,
    liveRoute: LiveRoute?,
): HomeViewModel.RouteMapState {
    val usable = liveRoute?.takeIf { it.isUsableForMap() }
    return when {
        inTransit && usable != null -> copy(
            path = usable.path,
            summary = usable.summary,
            pathFromCurrent = true,
            inTransit = true,
            altPath = emptyList(),
            altSummary = null,
        )

        inTransit -> copy(
            path = emptyList(),
            summary = "현재 위치에서 가장 빠른 경로 확인 중",
            pathFromCurrent = false,
            inTransit = true,
            altPath = emptyList(),
            altSummary = null,
        )

        else -> copy(
            path = plannedPath,
            summary = plannedSummary,
            pathFromCurrent = false,
            inTransit = false,
            altPath = emptyList(),
            altSummary = null,
        )
    }
}

/** 메모리 Flow로 들어온 값도 디스크 복원과 같은 조건으로 한 번 더 검증한다. */
internal fun LiveRouteStore.Snapshot.usableLiveRoute(
    nowMillis: Long = System.currentTimeMillis(),
): LiveRoute? = route.takeIf {
    eventId > 0L &&
        origin.lat.isFinite() && origin.lng.isFinite() &&
        fetchedAtMillis > 0L && isFresh(nowMillis) &&
        it.isUsableForMap()
}

private fun LiveRoute.isUsableForMap(): Boolean =
    minutes > 0 && path.size >= 2 &&
        path.all { it.lat.isFinite() && it.lng.isFinite() }
