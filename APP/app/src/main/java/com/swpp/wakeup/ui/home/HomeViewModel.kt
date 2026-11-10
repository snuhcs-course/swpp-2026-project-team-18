package com.swpp.wakeup.ui.home

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.alarm.AlarmLogQueue
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.calendar.DeviceCalendar
import com.swpp.wakeup.data.local.CalendarLinkStore
import com.swpp.wakeup.data.local.LocalStores
import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.data.local.MorningSessionStore
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.data.local.TokenStore
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.data.remote.BlockObservationInput
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.ui.events.tagsRenamed
import com.swpp.wakeup.ui.events.tagsWith
import com.swpp.wakeup.ui.events.tagsWithout
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ServerVersion
import com.swpp.wakeup.data.remote.ServerWarmup
import com.swpp.wakeup.data.repository.ArrivalRepository
import com.swpp.wakeup.data.repository.AuthRepository
import com.swpp.wakeup.data.repository.EventEditPatch
import com.swpp.wakeup.data.repository.EventEditValues
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.ReportRepository
import com.swpp.wakeup.data.repository.RoutineRepository
import com.swpp.wakeup.data.repository.ServerLogout
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ArrivalLedger
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.domain.model.SavedHomes
import com.swpp.wakeup.domain.model.SAVED_HOMES_UPLOADING_NOTICE
import com.swpp.wakeup.domain.model.savedHomesFirstUploadRunning
import com.swpp.wakeup.domain.model.SocialLogin
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.auth.SocialSignIn
import com.swpp.wakeup.data.local.SavedHomesStore
import com.swpp.wakeup.data.remote.SavedPlaceInput
import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.createIncludePrep
import com.swpp.wakeup.data.repository.SavedPlacesResult
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.CalendarOnboardingState
import com.swpp.wakeup.domain.model.CalendarPermission
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.HomeList
import com.swpp.wakeup.domain.model.HomeRefresh
import com.swpp.wakeup.domain.model.ImportCandidate
import com.swpp.wakeup.domain.model.CheckpointBasis
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.domain.model.MorningSession
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.sensing.ArrivalRecords
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.sensing.CurrentLocation
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.KakaoMapProjection
import com.swpp.wakeup.domain.model.MapCameraMath
import com.swpp.wakeup.domain.model.RouteMapProjection
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.sensing.LocationPermissions
import com.swpp.wakeup.sensing.MorningClose
import com.swpp.wakeup.sensing.TripGeofence
import com.swpp.wakeup.sensing.TripLiveState
import com.swpp.wakeup.sensing.TripObservationQueue
import com.swpp.wakeup.sensing.TripTrackingService
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RiskOptionsView
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState
import com.swpp.wakeup.ui.settings.EmailVerifyState
import com.swpp.wakeup.ui.settings.PasswordChangeState
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

    /** 내 계정·로그인 연결(social-login-api.md §3). 토큰은 저장하지 않는다 */
    private val authRepository = AuthRepository(tokenStore = tokenStore)

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
     * 기기에만 둔 다른 집(todo §2 "집 여러 개"). `init` 이 [reloadSavedHomes] 로 읽으므로 `init`
     * 위에 둔다. (Partly AI-generated (Claude))
     */
    private val savedHomesStore = SavedHomesStore(application)

    /** 저장한 집 서버 API(B2). `init` 이 [syncSavedHomes] 를 부르므로 `init` 위에 둔다 */
    private val savedPlaces = SavedPlacesRepository()

    /**
     * 다른 집을 서버에 두는가(B2). UNKNOWN 은 아직 서버 응답을 못 본 것으로, 기기에 "서버가 확인함" 표시가
     * 있으면 서버로 본다. DEVICE 는 서버가 이 API 를 모르는 것(404·405)이라 기기 목록만 쓴다.
     */
    private enum class SavedPlacesMode { UNKNOWN, SERVER, DEVICE }

    private var savedPlacesMode = SavedPlacesMode.UNKNOWN
    private var savedHomesSyncJob: Job? = null

    private fun useSavedPlacesServer(): Boolean = savedPlacesMode == SavedPlacesMode.SERVER ||
        (savedPlacesMode == SavedPlacesMode.UNKNOWN && runCatching { savedHomesStore.synced() }.getOrDefault(false))

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
        val alarmUpdatingId: Long? = null,
        /** "안 감" 표시·되돌리기를 저장하는 중인 일정. 그동안 다른 표시·알람 스위치를 막는다 */
        val skipUpdatingId: Long? = null,
        /** 이동 중 경로를 바꾸는 중인 일정. 그동안 [이 경로로 바꾸기] 를 막는다 */
        val rerouteUpdatingId: Long? = null,
        /** 알람 전에 일정의 경로를 바꾸는 중인 일정. 그동안 [이 경로로 바꾸기] 를 막는다 */
        val switchRouteBusyId: Long? = null,
        /**
         * 마지막으로 경로를 바꾼 일정과 그 시각. 그보다 먼저 받은 현재-위치 경로로는 카드를
         * 내놓지 않는다 — 바꾸기 전 경로를 기준으로 계산한 제안이다([AppliedReroute.hides]).
         */
        val rerouteApplied: AppliedReroute? = null,

        /** 아래 목록 값은 지금 시각으로 다시 그린 것이다([HomeList]) */
        val sections: List<EventSection> = emptyList(),
        val nextAlarm: UpcomingEvent? = null,
        val totalCount: Int = 0,
        /** 목록을 그린 날. 머리글의 "오늘 n개" 와 "오늘" 묶음이 같은 날을 본다 */
        val today: LocalDate = LocalDate.now(),
        /** 행에 "안 감으로 표시" 를 내놓는 일정 id */
        val markableIds: Set<Long> = emptySet(),
        /** 한 번 띄우고 지우는 안내. 화면이 스낵바로 띄운다 */
        val notice: HomeNotice? = null,
        /** 모든 기기 로그아웃을 서버에 요청하는 중 */
        val logoutAllBusy: Boolean = false,
        /** "다시 계산" 을 부른 일정. 계산 중 버튼을 막는다(A6) */
        val recomputeBusyId: Long? = null,

        /** 설정 계정 카드에 보일 이메일. 소셜 자리 표시 주소(`@social.invalid`)면 null */
        val accountEmail: String? = null,
        /** 이메일 인증을 마쳤는가(R3-10). 구버전 서버·아직 모르면 null — 설정 줄을 그리지 않는다 */
        val emailVerified: Boolean? = null,
        /**
         * 가진 로그인 방법(`password`·`kakao`·`google`). 아직 모르면 null — 설정을 열 때 받는다
         * (social-login-api.md §4). (Partly AI-generated (Claude))
         */
        val loginMethods: List<String>? = null,
        /** 로그인 방법을 받지 못했다. "로그인 연결" 에 다시 시도를 둔다 */
        val loginMethodsFailed: Boolean = false,
        /** 연결·끊기 중인 제공자 */
        val socialLinkBusy: SocialProvider? = null,

        /** 기기에만 둔 다른 집(최근 것이 앞). 설정과 일정 출발지에 보인다 */
        val savedHomes: List<SavedHome> = emptyList(),
        /** 다른 집이 계정(서버)에 저장되는 중이면 true. 안내 문구가 바뀐다(B2) */
        val savedHomesOnServer: Boolean = false,
        /** "기본으로" 를 서버에 저장하는 중인 다른 집 */
        val savedHomeBusyId: String? = null,

        /** 집 위치 미설정이면 알람을 계산할 수 없다. 안내를 띄운다 */
        val hasHome: Boolean = true,
        val homeLabel: String? = null,
        val onboardingPrepMin: Int? = null,
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

        /**
         * 울리지 않고 지나갔고 아직 출발 전인 알람. 없으면 null.
         *
         * 강제 중지가 알람을 지운 아침에 사용자가 앱을 열면 여기가 채워진다.
         * 알람 해제가 추적의 시작점이라, 이 안내가 없으면 그 아침의 이동 기록이
         * 아무 말 없이 사라진다.
         */
        val missedAlarm: MissedAlarmNotice? = null,
    ) {
        val isEmpty: Boolean get() = !loading && error == null && totalCount == 0
        val todayLine: String
            get() {
                val count = sections
                    .firstOrNull { it.events.firstOrNull()?.startDate == today }
                    ?.events?.size ?: 0
                val date = today.format(DateTimeFormatter.ofPattern("M월 d일"))
                val day = listOf("월", "화", "수", "목", "금", "토", "일")[today.dayOfWeek.value - 1]
                return "$date $day · 오늘 ${count}개"
            }
    }

    // Partly AI-generated (Claude)
    /**
     * 한 번 띄우는 안내. [id] 가 달라 같은 문구도 두 번 뜬다.
     *
     * @property undoSkipEventId 있으면 "되돌리기" 를 단다. 누르면 그 일정의 "안 감" 표시를 되돌린다
     */
    data class HomeNotice(val id: Long, val message: String, val undoSkipEventId: Long? = null)

    // Partly AI-generated (Claude)
    /** 이동 중 경로를 바꾼 일정과 시각(벽시계 ms) */
    data class AppliedReroute(val eventId: Long, val atMillis: Long, val routeKey: String? = null) {
        /** 이 현재-위치 경로가 바꾸기 전에 받은 것인가. 그러면 그 제안·지각 분은 옛 경로 기준이다 */
        fun hides(snapshot: LiveRouteStore.Snapshot): Boolean = hidesFetchedAt(snapshot.eventId, snapshot.fetchedAtMillis)
        /** [hides] 와 같은 규칙. 스냅샷 대신 일정 id 와 받은 시각만 있을 때 쓴다 */
        fun hidesFetchedAt(eventId: Long, fetchedAtMillis: Long): Boolean =
            this.eventId == eventId && fetchedAtMillis <= atMillis
    }

    // Partly AI-generated (Claude)
    /**
     * 놓친 알람 안내 한 건.
     *
     * @property canTrack 지금 추적을 시작할 수 있는가. 출발·도착 기준점과 정밀 위치
     *   권한이 둘 다 있어야 한다([AlarmSchedule.canTrack], [LocationPermissions]).
     */
    data class MissedAlarmNotice(
        val schedule: AlarmSchedule,
        val canTrack: Boolean,
    ) {
        /** "8:25 AM" */
        val alarmLine: String get() = "${schedule.alarmLabel} ${schedule.meridiem}"

        /** 계획된 출발 시각 "9:25". 계산 못 했으면 null */
        val departLabel: String?
            get() = schedule.departByMillis?.let {
                java.time.Instant.ofEpochMilli(it)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("H:mm"))
            }
    }

    private val _state = MutableStateFlow(
        UiState(
            nickname = tokenStore.nickname ?: "사용자",
            accountEmail = SocialLogin.visibleEmail(tokenStore.email),
        )
    )
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

    /** 추적 종료 후 화면에 표시할 도착 결과. 현재 위치를 재사용하지 않는다. 일정 id 로 찾는다 */
    val tripArrivals: StateFlow<Map<Long, TripLiveState.Arrival>> = TripLiveState.arrivals

    /**
     * 서비스가 받은 최신 현재-위치 경로. 늦을 때의 경로 바꾸기 카드가 읽는다
     * ([com.swpp.wakeup.domain.model.RerouteAlert.banner]). (Partly AI-generated (Claude))
     */
    val liveRoute: StateFlow<LiveRouteStore.Snapshot?> = TripLiveState.liveRoute
    /** 출발 전 "지금 나가도 늦나" 확인 결과(추적 서비스가 최대 두 번 채운다). 준비 중 카드의 재료 */
    val preDeparture: StateFlow<TripLiveState.PreDepartureSnapshot?> = TripLiveState.preDeparture

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

    // Partly AI-generated (Claude)
    /**
     * ⑱ 캘린더 연동을 이 세션에서 이미 띄웠는지. 저장소의 [CalendarLinkStore.wasAsked] 는
     * 저장을 끝냈을 때 켜지므로, 화면을 띄운 직후의 [refresh] 가 또 밀어 넣지 않게 따로 둔다.
     *
     * 아래 셋은 [prepOnboardingAsked] 와 같은 이유로 `init` 위에 둔다 — `init` 의
     * `refresh()` 가 띄운 코루틴의 [maybeAskOnboarding] 이 읽는다.
     */
    private var calendarOnboardingAsked = false

    /** 연동한 기기 캘린더. 서버가 아니라 기기에 둔다(캘린더 id 가 기기마다 다르다) */
    private val calendarLinkStore = CalendarLinkStore(application)

    private val _calendarLink = MutableStateFlow(CalendarLinkState())
    val calendarLink: StateFlow<CalendarLinkState> = _calendarLink.asStateFlow()

    private val _calendarOnboarding = MutableStateFlow(CalendarOnboardingState())
    val calendarOnboarding: StateFlow<CalendarOnboardingState> = _calendarOnboarding.asStateFlow()

    // --- 홈 목록 새로고침 (todo P0 "다가오는 일정 갱신") ---------------------
    //
    // 아래 선언은 모두 `init` 위에 둔다. `init` 이 [refresh]·[renderHome]·
    // [onMorningClosedByTrip] 을 부르고 그 함수들이 이 값을 읽는다
    // (`HomeViewModelInitOrderTest`).

    /** 마지막으로 받은 홈 데이터(시각으로 거르지 않은 목록). 화면은 [renderHome] 이 그린다 */
    private var homeData: EventRepository.HomeData? = null

    /** 서버가 아는 도착. 이 기기에 도착 사본이 없을 때만 묻는다(todo §2 "앱 재시작 후 도착 판정 복원") */
    private val arrivalRepository = ArrivalRepository()

    /** 일정별 마지막 서버 도착 확인 시각(벽시계 ms). 홈을 열 때마다 같은 일정을 다시 묻지 않는다 */
    private val arrivalCheckedAt = mutableMapOf<Long, Long>()

    /** 마지막으로 시작한 홈 불러오기. 늦게 끝난 옛 응답이 새 상태를 덮지 못하게 한다 */
    private var homeLoadGeneration = 0L
    private var refreshJob: Job? = null

    /** 마지막 [refresh] 시작 시각(`SystemClock.elapsedRealtime`). 화면 복귀 때 다시 받을지 정한다 */
    private var lastRefreshStartedElapsed: Long? = null

    /** 다음 경계(일정 시작·마감, 알람, 자정)에 목록을 다시 거르는 타이머 */
    private var boundaryJob: Job? = null

    /** 화면이 앞에 있는가. 뒤에 있으면 경계 타이머를 돌리지 않는다 */
    private var foreground = false

    private var noticeCounter = 0L

    // Partly AI-generated (Claude)
    /** 저장 실패 snackbar 번호. 같은 문구도 다시 띄우려고 올린다 */
    private var addNoticeSeq = 0L

    /**
     * 이 ViewModel 을 만들 때의 로그인 계정. 세션이 끝나면 토큰 저장소가 이메일을 지우고 나서 [onCleared] 가
     * 불리므로 미리 들고 있는다. init 이 부르는 함수가 쓰는 속성은 init 위에 있어야 한다.
     */
    private val draftOwner: String? = tokenStore.email

    /**
     * 화면 밖에 잠시 맡겨 둔 쓰다 만 일정. 로그인 만료로 액티비티가 끝났다 돌아온 경우와, 수정 화면을 열며
     * 밀려난 경우다. "일정 추가" 를 누르면 이어 쓸지 묻는다(A4).
     */
    private var pendingAddDraft: AddState? = AddDraftHolder.take(tokenStore.email)

    /** 지금 추적 중인 일정. 아침 기록 화면이 "저절로 끝남" 을 말해도 되는지 본다 */
    val trackingEventId: StateFlow<Long?> = TripLiveState.trackingEventId

    init {
        // 화면보다 오래 사는 서비스가 저장한 최신 성공 결과를 먼저 복원한다.
        // 이후 두 Flow 수집기는 Activity가 백그라운드여도 ViewModel이 살아 있는
        // 동안 계속 상태를 맞추고, ViewModel이 새로 생겨도 이 사본에서 이어진다.
        TripLiveState.restoreLiveRoute(liveRouteStore.current())
        // 앱 프로세스가 다시 시작돼도 이미 도착한 일정은 도착으로 남긴다(todo §2).
        ArrivalRecords.restore(application)
        // Partly AI-generated (Claude)
        // 알림에서 바꾼 경로도 화면에 반영한다. null(추적 종료·로그아웃)이면 지금처럼 그대로 둔다.
        viewModelScope.launch {
            TripLiveState.rerouted.collect { r ->
                if (r != null) {
                    _state.update { it.copy(rerouteApplied = AppliedReroute(r.eventId, r.atMillis, r.routeKey)) }
                }
            }
        }
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
        // 시작 뒤에 도착하면 그 행을 바로 뺀다.
        viewModelScope.launch {
            TripLiveState.arrivals.collect { renderHome() }
        }
        // 서비스가 출발·도착으로 아침 기록을 닫았다. 화면 없이 닫혔을 수도 있다.
        viewModelScope.launch {
            TripLiveState.morningClosed.collect { closed -> closed?.let(::onMorningClosedByTrip) }
        }

        refresh()
        // 알람 액티비티가 세션을 만들어 두었을 수 있다. 홈 카드가 보이려면
        // 여기서 한 번 읽어야 한다.
        reloadMorning()
        reloadSavedHomes()
        syncSavedHomes()
        // 서버 응답을 기다리지 않는다. 놓친 알람은 디스크 사본만으로 판별되고,
        // 오프라인이어도 출발 전에 알려야 한다.
        reloadMissedAlarm()
        // 로그아웃이 정기 작업을 취소했으므로 다시 로그인했으면 되살려야 한다.
        // Application.onCreate 는 프로세스당 한 번이라 로그아웃→로그인을 같은
        // 프로세스에서 하면 그 경로만으로는 복구되지 않는다. KEEP 이라 중복
        // 등록이 되지 않는다.
        JitWork.ensurePeriodicSync(getApplication())
    }

    /**
     * 홈을 서버에서 다시 받는다.
     *
     * @param silent 목록이 이미 보이면 로딩 줄을 띄우지 않는다. 화면 복귀 때 쓴다
     */
    fun refresh(silent: Boolean = false) {
        if (_state.value.alarmUpdatingId != null || _state.value.skipUpdatingId != null) return
        lastRefreshStartedElapsed = SystemClock.elapsedRealtime()
        val gen = ++homeLoadGeneration
        val quiet = silent && homeData != null
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            when (val result = repository.loadHome()) {
                is EventRepository.Result.Success -> {
                    applyHome(result.data, gen)
                }

                is EventRepository.Result.Failure -> _state.update {
                    if (gen != homeLoadGeneration) return@update it.copy(loading = false)
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

    // Partly AI-generated (Claude)
    /**
     * 받은 홈을 반영한다. [gen] 이 마지막으로 시작한 불러오기가 아니면 버린다.
     *
     * 화면 복귀 새로고침이 조용히 도는 동안 알람 스위치·"안 감" 을 누르면, 먼저 시작한
     * 응답이 나중에 끝나 스위치를 되돌리거나 방금 끈 알람을 다시 걸 수 있다.
     */
    private fun applyHome(data: EventRepository.HomeData, gen: Long) {
        if (gen != homeLoadGeneration) {
            Log.i(TAG, "홈 응답(세대 $gen)이 더 새 요청에 밀려 버린다")
            _state.update { it.copy(loading = false) }
            return
        }
        homeData = data
        _state.update {
            it.copy(
                loading = false,
                error = null,
                hasHome = data.hasHome,
                homeLabel = data.homeLabel,
                onboardingPrepMin = data.onboardingPrepMin,
                homePlace = data.toHomePlace(),
                offline = data.fromCache,
                offlineAgeLabel = data.ageLabel,
            )
        }
        renderHome()
        maybeAskOnboarding(data)
        syncAlarms(data.schedules, fromCache = data.fromCache)
        if (!data.fromCache) {
            reconcileArrivals(
                data.events.map { ArrivalCandidate(it.id, it.startAtMillis, it.alarmAtMillis, it.skipped) }
            )
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 지금 시각으로 목록을 다시 그린다. 네트워크를 쓰지 않는다.
     *
     * 화면이 앞에 있으면 다음 경계(일정 시작·마감, 알람, 자정)에 다시 그리도록 타이머를
     * 건다. 그래서 시작 시각이 지난 일정이 앱을 다시 켜지 않아도 빠진다.
     */
    private fun renderHome() {
        val data = homeData ?: return
        val now = System.currentTimeMillis()
        val view = data.viewAt(now, arrivedEventIds(), deviceAlarms())
        _state.update {
            it.copy(
                sections = view.sections,
                nextAlarm = view.nextAlarm,
                totalCount = view.totalCount,
                unplannedCount = view.unplannedCount,
                today = view.today,
                markableIds = view.markable,
            )
        }
        boundaryJob?.cancel()
        boundaryJob = null
        if (foreground) {
            val wait = HomeList.recheckDelayMillis(view.nextChangeMillis, now)
            boundaryJob = viewModelScope.launch {
                delay(wait)
                // 다음 renderHome 이 이 타이머를 취소하지 않도록 먼저 비운다.
                boundaryJob = null
                renderHome()
            }
        }
    }

    /**
     * 이 기기가 아는 도착. 도착한 일정은 시작 뒤에 목록에서 빠진다. 디스크 사본과 서버 확인으로
     * 앱을 다시 켜도 남는다([ArrivalRecords]).
     */
    private fun arrivedEventIds(): Set<Long> = TripLiveState.arrivals.value.keys

    // Partly AI-generated (Claude)
    /**
     * 이동이 있었을 법한 일정 중 이 기기가 도착을 모르는 것을 서버에 묻는다(재설치·다른 기기).
     * 찾으면 [TripLiveState.arrivals] 가 바뀌어 홈이 다시 그려진다. 실패는 조용히 넘긴다.
     */
    private fun reconcileArrivals(candidates: List<ArrivalCandidate>) {
        val now = System.currentTimeMillis()
        val due = candidates.filter {
            ArrivalLedger.shouldAskServer(
                startAtMillis = it.startAtMillis,
                alarmAtMillis = it.alarmAtMillis,
                skipped = it.skipped,
                knownArrival = TripLiveState.arrivalFor(it.eventId) != null,
                nowMillis = now,
                lastCheckedAtMillis = arrivalCheckedAt[it.eventId],
            )
        }
        if (due.isEmpty()) return
        due.forEach { arrivalCheckedAt[it.eventId] = now }
        val app = getApplication<Application>()
        viewModelScope.launch {
            due.forEach { candidate ->
                arrivalRepository.serverArrival(candidate.eventId)?.let {
                    Log.i(TAG, "서버에서 도착을 확인했다: 일정 ${candidate.eventId}")
                    ArrivalRecords.restoreFromServer(app, candidate.eventId, it)
                }
            }
        }
    }

    /** 서버에 도착을 물어볼지 정하는 데 필요한 것 */
    private data class ArrivalCandidate(
        val eventId: Long,
        val startAtMillis: Long,
        val alarmAtMillis: Long?,
        val skipped: Boolean,
    )

    /** 기기에 등록된 앞으로의 알람. 미룬 알람을 "다음 알람" 에 그대로 보이게 한다. */
    private fun deviceAlarms(): Map<Long, AlarmSchedule> =
        runCatching { AlarmScheduler(getApplication()).registered().associateBy { it.eventId } }
            .onFailure { Log.w(TAG, "등록된 알람을 읽지 못했다", it) }
            .getOrDefault(emptyMap())

    // Partly AI-generated (Claude)
    /**
     * 화면이 앞으로 왔다. 지금 시각으로 다시 그리고, 마지막 요청에서 1분이 지났으면
     * 조용히 서버에서도 다시 받는다(다른 기기의 수정, 경로 갱신 워커, 밤 학습).
     */
    fun onForeground() {
        foreground = true
        reloadMorning()
        renderHome()
        val state = _state.value
        val shouldRefresh = HomeRefresh.shouldRefreshOnResume(
            nowElapsed = SystemClock.elapsedRealtime(),
            lastStartElapsed = lastRefreshStartedElapsed,
            inFlight = refreshJob?.isActive == true,
            mutating = state.alarmUpdatingId != null || state.skipUpdatingId != null,
        )
        if (shouldRefresh) refresh(silent = true)
    }

    /** 화면이 뒤로 갔다. 경계 타이머를 멈춘다. */
    fun onBackground() {
        foreground = false
        boundaryJob?.cancel()
        boundaryJob = null
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun setAlarmEnabled(id: Long, enabled: Boolean) {
        val current = _state.value
        if (current.loading || current.offline || current.alarmUpdatingId != null ||
            current.skipUpdatingId != null
        ) return
        _state.update { it.copy(alarmUpdatingId = id, error = null) }
        val gen = ++homeLoadGeneration
        viewModelScope.launch {
            when (val result = repository.setAlarmEnabled(id, enabled)) {
                is EventRepository.Result.Success -> applyHome(result.data, gen)
                is EventRepository.Result.Failure -> _state.update { it.copy(error = result.message) }
            }
            _state.update { it.copy(alarmUpdatingId = null) }
        }
    }

    // --- "안 감" 표시 (skip-event-api.md §6) --------------------------------

    // Partly AI-generated (Claude)
    /**
     * "도착했어요"(수동 도착, todo §2). 추적 중이면 서비스가 마지막 위치를 붙여 기록한다.
     * 화면은 [TripLiveState.arrival] 이 바뀌면서 "도착" 으로 넘어가고 홈 행도 다시 그려진다.
     */
    fun markArrived(eventId: Long) {
        runCatching { TripTrackingService.markArrived(getApplication(), eventId) }
            .onFailure { Log.e(TAG, "수동 도착 기록 실패", it) }
        _state.update { it.copy(pendingObservations = pendingObservationCount()) }
    }

    // Partly AI-generated (Claude)
    /**
     * 이동 중 [이 경로로 바꾸기](todo §2 "이동 중 늦으면 경로 바꾸기", reroute-api.md). 이번 이동만
     * 바꾼다. 성공하면 추적 서비스가 다음 위치에서 바꾼 경로로 새 선을 받는다.
     */
    fun reroute(eventId: Long, routeKey: String) {
        if (_state.value.rerouteUpdatingId != null) return
        _state.update { it.copy(rerouteUpdatingId = eventId) }
        val app = getApplication<Application>()
        viewModelScope.launch {
            when (val result = repository.reroute(eventId, routeKey, fromSuggestion = true)) {
                is EventRepository.Result.Success -> {
                    // 화면 상태는 init 의 수집기가 TripLiveState.rerouted 에서 채운다(알림 경로와 같은 원천).
                    TripLiveState.recordReroute(eventId, routeKey)
                    runCatching { TripTrackingService.refreshLiveRoute(app, eventId) }
                        .onFailure { Log.w(TAG, "경로 바꾼 뒤 추적 갱신 요청 실패", it) }
                    notify("경로를 바꿨습니다. 곧 바꾼 경로로 다시 그립니다")
                }

                is EventRepository.Result.Failure -> notify("경로를 바꾸지 못했습니다. ${result.message}")
            }
            _state.update { it.copy(rerouteUpdatingId = null) }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 알람 전 [이 경로로 바꾸기](홈 다음 알람 카드, 알람 결정 화면). 일정에 저장한 경로를 바꾸고 알람을
     * 다시 맞춘다. **알람이 이미 울렸으면 바꾸지 않는다** — 울린 시점의 계획이 학습 기준이라서다.
     * 화면을 그린 뒤에 알람이 울렸을 수 있으므로 누른 시점에 한 번 더 확인한다.
     */
    fun applyFasterRoute(eventId: Long, routeKey: String, alarmAtMillis: Long?) {
        if (_state.value.switchRouteBusyId != null) return
        if (alarmAtMillis == null || System.currentTimeMillis() >= alarmAtMillis) {
            notify("알람이 이미 울려서 경로를 바꾸지 않았어요")
            refresh(silent = true)
            return
        }
        _state.update { it.copy(switchRouteBusyId = eventId) }
        viewModelScope.launch {
            when (val result = repository.switchRoute(eventId, routeKey)) {
                is EventRepository.Result.Success -> {
                    if (_nav.value.current == AppRoute.AlarmDecision(eventId)) {
                        ++planLoadGeneration // 먼저 보낸 상세 요청이 늦게 와서 덮지 않게 한다
                        _plan.value = result.data
                    }
                    val homeGen = ++homeLoadGeneration
                    when (val home = repository.loadHome()) {
                        is EventRepository.Result.Success ->
                            if (!home.data.fromCache) {
                                applyHome(home.data, homeGen) // 목록과 기기 알람을 함께 갱신한다
                                notify("경로를 바꾸고 알람을 다시 맞췄어요")
                            } else {
                                notify("경로는 바꿨지만 알람 목록을 갱신하지 못했어요")
                            }
                        is EventRepository.Result.Failure ->
                            notify("경로는 바꿨지만 알람 목록을 갱신하지 못했어요")
                    }
                }
                is EventRepository.Result.Failure -> notify("경로를 바꾸지 못했어요. ${result.message}")
            }
            _state.update { it.copy(switchRouteBusyId = null) }
        }
    }
    /** 홈 행·알람 결정 화면·스낵바 "되돌리기" */
    fun setSkipped(eventId: Long, skipped: Boolean) = runSkip(eventId, skipped) {}

    /** 아침 기록 화면의 "오늘 안 가요" */
    fun skipToday() {
        _morning.value?.eventId?.let { setSkipped(it, true) }
    }

    /** ⑨ 상세 리포트. 그날 일정이 더 남지 않으면 ⑧ 로 돌아간다 */
    fun skipFromReport(date: LocalDate, eventId: Long) =
        runSkip(eventId, true) { popReportDayIfEmpty(date, eventId) }

    // Partly AI-generated (Claude)
    /**
     * 표시를 저장하고, 표시했으면 그 일정의 알람·추적·아침 기록을 끝낸 뒤 홈을 다시 받는다.
     *
     * 홈을 다시 받는 이유: 표시하면 서버가 같은 날 다음 일정을 첫 알람으로 켤 수 있다
     * (되돌리면 다시 끈다). 동기화하지 않으면 그 알람이 울리지 않는다.
     */
    private fun runSkip(eventId: Long, skipped: Boolean, afterSave: () -> Unit) {
        val current = _state.value
        if (current.skipUpdatingId != null || current.alarmUpdatingId != null) return
        _state.update { it.copy(skipUpdatingId = eventId) }
        val app = getApplication<Application>()
        viewModelScope.launch {
            val saved = repository.setSkipped(eventId, skipped)
            if (saved is EventRepository.Result.Failure) {
                _state.update { it.copy(skipUpdatingId = null) }
                notify(
                    if (skipped) "안 감으로 표시하지 못했습니다. ${saved.message}"
                    else "되돌리지 못했습니다. ${saved.message}"
                )
                return@launch
            }
            val result = (saved as EventRepository.Result.Success).data

            if (skipped) {
                // 하나가 실패해도 나머지는 한다. 다시 받기가 실패해도 이 알람은 울리면 안 된다.
                runCatching { AlarmScheduler(app).cancelEvent(eventId) }
                    .onFailure { Log.e(TAG, "안 감 일정의 알람 해제 실패", it) }
                reloadMissedAlarm()
                if (TripLiveState.trackingEventId.value == eventId) {
                    runCatching { TripTrackingService.stop(app) }
                        .onFailure { Log.w(TAG, "안 감 일정의 추적 중지 실패", it) }
                }
                runCatching { morningStore.closeFor(eventId) }
                    .onFailure { Log.w(TAG, "안 감 일정의 아침 기록 정리 실패", it) }
                reloadMorning()
            }

            if (_nav.value.current == AppRoute.AlarmDecision(eventId) && result.plan != null) {
                ++planLoadGeneration
                _plan.value = result.plan
            }
            afterSave()
            val stack = _nav.value.stack
            if (stack.any { it == AppRoute.WeeklyReport || it is AppRoute.ReportDay }) {
                reportNavigator.reload()
            }

            val gen = ++homeLoadGeneration
            when (val home = repository.loadHome()) {
                is EventRepository.Result.Success -> if (!home.data.fromCache) {
                    applyHome(home.data, gen)
                    notify(
                        if (skipped) "안 감으로 표시했습니다" else "안 감 표시를 되돌렸습니다",
                        undoSkipEventId = eventId.takeIf { skipped },
                    )
                } else {
                    repository.cacheEvent(result.event)
                    applyHome(home.data.withSkipped(eventId, skipped), gen)
                    onSkipSavedOffline(app, eventId, skipped)
                }

                is EventRepository.Result.Failure -> {
                    repository.cacheEvent(result.event)
                    homeData?.let { applyHome(it.withSkipped(eventId, skipped), gen) }
                    onSkipSavedOffline(app, eventId, skipped)
                }
            }
            _state.update { it.copy(skipUpdatingId = null) }
        }
    }

    /** 표시는 저장했지만 목록을 다시 못 받았다. 연결되면 알람을 맞추게 하고 알린다. */
    private fun onSkipSavedOffline(app: Application, eventId: Long, skipped: Boolean) {
        JitWork.requestPlanSync(app)
        notify(
            if (skipped) "표시는 저장했지만 알람 목록을 갱신하지 못했습니다. 연결되면 자동으로 맞춥니다"
            else "되돌렸지만 알람 목록을 갱신하지 못했습니다. 연결되면 자동으로 맞춥니다",
            undoSkipEventId = eventId.takeIf { skipped },
        )
    }

    /** ⑨ 에서 그날 마지막 일정을 표시했으면 ⑧ 로 돌아간다. 이미 ⑨ 를 떠났으면 그대로 둔다. */
    private fun popReportDayIfEmpty(date: LocalDate, eventId: Long) {
        val day = report.value.weekly?.days?.firstOrNull { it.date == date }
        if (day != null && !day.isEmptyWithout(eventId)) return
        _nav.update {
            if ((it.current as? AppRoute.ReportDay)?.date == date) {
                it.copy(stack = it.stack.dropLast(1), forward = false)
            } else {
                it
            }
        }
    }

    // Partly AI-generated (Claude)
    /** 한 번 띄울 안내를 건다. 화면이 띄운 뒤 [consumeNotice] 로 지운다. */
    private fun notify(message: String, undoSkipEventId: Long? = null) {
        val notice = HomeNotice(++noticeCounter, message, undoSkipEventId)
        _state.update { it.copy(notice = notice) }
    }

    fun consumeNotice(notice: HomeNotice) {
        _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }
    }

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

        // 동기화가 지난 알람을 사본에서 옮겼을 수 있다. 다시 읽는다.
        reloadMissedAlarm()

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
            // Partly AI-generated (Claude) — 알람 울림·끄기 기록(db-schema.md A3)도 함께 올린다.
            runCatching { AlarmLogQueue(app).flush() }
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
        // 서버에 이 refresh 를 취소하게 한다(token-security-api.md §5). 기기 토큰은 결과와
        // 상관없이 아래에서 지운다 — 서버 호출은 화면과 떼어 뒤에서 한다([ServerLogout]).
        val refresh = runCatching { tokenStore.refreshToken }.getOrNull()
        // 쓰다 만 일정은 이 계정의 것이다. 로그아웃하면 남기지 않는다(onCleared 가 맡기지도 않게 비운다).
        AddDraftHolder.clear()
        pendingAddDraft = null
        _add.value = AddState()
        _addResumePrompt.value = false
        // 종류 목록은 계정마다 다르다. 다음 계정에 이 계정의 종류가 잠깐이라도 보이지 않게 비운다.
        _eventTags.value = emptyList()
        _tagAdd.value = TagAddState()
        _tagEdit.value = TagEditState()
        // 순서가 있다. **토큰보다 먼저 지운다** — 지우기 판정이 "지금 로그인한
        // 계정" 을 보므로, 토큰이 먼저 사라지면 소유자를 모르는 상태가 되어
        // 아무것도 지울 수 없다.
        LocalStores.wipeAll(getApplication())
        tokenStore.clear()
        ServerLogout.revoke(refresh)
        // 카카오·Google SDK 쪽 로그인도 끝낸다. 다음 로그인 때 계정 선택이 다시 뜬다(계약 §5).
        ServerLogout.detached { SocialSignIn.signOutQuietly(getApplication()) }
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
        _checkpoints.value = null
        _state.update { it.copy(morningBlocksLeft = null) }
    }

    // Partly AI-generated (Claude)
    /**
     * 모든 기기에서 로그아웃(`POST /api/auth/me/logout-all`). 서버가 이 계정의 refresh 를 전부
     * 취소한 뒤에 이 기기도 로그아웃한다.
     *
     * 이 기기만 로그아웃과 달리 **서버가 받아야 의미가 있다.** 연결되지 않으면 기기에 그대로
     * 머물고 알린다. 401(이미 세션이 끝남)이면 이 기기만 정리한다.
     */
    fun logoutAll(onLoggedOut: () -> Unit) {
        if (_state.value.logoutAllBusy) return
        _state.update { it.copy(logoutAllBusy = true) }
        viewModelScope.launch {
            val response = runCatching { withContext(Dispatchers.IO) { ApiClient.auth.logoutAll() } }
            _state.update { it.copy(logoutAllBusy = false) }
            val code = response.getOrNull()?.code()
            when {
                code != null && (code in 200..299 || code == 401) -> {
                    Log.i(TAG, "모든 기기 로그아웃: 응답 $code, 취소 ${response.getOrNull()?.body()?.revoked}건")
                    logout()
                    onLoggedOut()
                }
                code == 404 -> notify("아직 서버에서 지원하지 않아요. 이 기기만 로그아웃할 수 있어요")
                code != null -> notify("모든 기기를 로그아웃하지 못했습니다. 잠시 후 다시 시도해 주세요")
                else -> notify("서버에 연결되지 않아 다른 기기를 로그아웃하지 못했습니다")
            }
        }
    }

    // --- 비밀번호 변경 (제안 계약 R3-9, Figma F-41) — Partly AI-generated (Claude) -----

    private val _passwordChange = MutableStateFlow(PasswordChangeState())
    val passwordChange: StateFlow<PasswordChangeState> = _passwordChange.asStateFlow()
    private var passwordChangeCheckJob: Job? = null

    /** 설정의 "비밀번호" 줄. 입력을 비우고 화면을 연다 */
    fun openChangePassword() {
        passwordChangeCheckJob?.cancel()
        _passwordChange.value = PasswordChangeState()
        _nav.update { it.copy(stack = it.stack + AppRoute.ChangePassword, forward = true) }
    }

    fun onCurrentPasswordChange(value: String) =
        _passwordChange.update { it.copy(current = value, error = null, currentWrong = false) }

    fun onNewPasswordChange(value: String) {
        passwordChangeCheckJob?.cancel()
        _passwordChange.update {
            it.copy(newPassword = value, error = null, passwordCheck = null, passwordCheckError = null)
        }
        if (value.isEmpty()) return
        // 가입 화면과 같다: 입력마다 서버 규칙 검사를 하고, 앞 검사는 취소한다.
        passwordChangeCheckJob = viewModelScope.launch {
            val result = authRepository.checkPassword(value)
            _passwordChange.update {
                if (it.newPassword != value) it
                else it.copy(passwordCheck = result.getOrNull(), passwordCheckError = result.exceptionOrNull()?.message)
            }
        }
    }

    fun onNewPasswordConfirmChange(value: String) =
        _passwordChange.update { it.copy(confirm = value, error = null) }

    fun submitPasswordChange() {
        val form = _passwordChange.value
        if (!form.canSubmit) return
        _passwordChange.update { it.copy(busy = true, error = null, currentWrong = false) }
        viewModelScope.launch {
            when (val result = authRepository.changePassword(form.current, form.newPassword, form.confirm)) {
                is AuthRepository.PasswordChangeResult.Success -> {
                    _passwordChange.value = PasswordChangeState()
                    notify(
                        if (result.tokensRotated) "비밀번호를 바꿨어요. 다른 기기는 로그아웃됐어요"
                        else "비밀번호를 바꿨어요. 다시 로그인해야 할 수 있어요"
                    )
                    // 열린 화면일 때만 닫는다. 기다리는 동안 뒤로 갔다면 그대로 둔다.
                    if (_nav.value.current is AppRoute.ChangePassword) goBack()
                }

                is AuthRepository.PasswordChangeResult.Failure -> _passwordChange.update {
                    it.copy(busy = false, error = result.message, currentWrong = result.currentPasswordWrong)
                }
            }
        }
    }

    // --- 이메일 인증 (R3-10, Figma F-53) — Partly AI-generated (Claude) -----------------
    private val _emailVerify = MutableStateFlow(EmailVerifyState())
    val emailVerify: StateFlow<EmailVerifyState> = _emailVerify.asStateFlow()

    /** 설정의 "이메일 인증" 줄. 코드를 보내고, 보냈으면 입력 화면을 연다. 인증은 로그인을 막지 않는다 */
    fun startEmailVerify() {
        if (_emailVerify.value.sending) return
        _emailVerify.value = EmailVerifyState(sending = true)
        viewModelScope.launch {
            when (val result = authRepository.requestEmailVerify()) {
                is AuthRepository.EmailVerifyResult.Sent -> {
                    _emailVerify.value = EmailVerifyState(sentTo = _state.value.accountEmail)
                    _nav.update { it.copy(stack = it.stack + AppRoute.VerifyEmail, forward = true) }
                }
                is AuthRepository.EmailVerifyResult.AlreadyVerified,
                is AuthRepository.EmailVerifyResult.Verified -> {
                    _emailVerify.value = EmailVerifyState()
                    _state.update { it.copy(emailVerified = true) }
                    notify("이미 인증된 이메일이에요")
                }
                is AuthRepository.EmailVerifyResult.Failure -> {
                    _emailVerify.value = EmailVerifyState()
                    notify(result.message)
                }
            }
        }
    }

    /** 숫자만 6자리까지 받는다 */
    fun onEmailVerifyCodeChange(value: String) =
        _emailVerify.update {
            it.copy(code = value.filter(Char::isDigit).take(EmailVerifyState.CODE_LENGTH), error = null, info = null)
        }

    /** "코드 다시 받기". 서버가 이전 코드를 무효로 만든다 */
    fun resendEmailVerify() {
        val form = _emailVerify.value
        if (form.sending || form.busy) return
        _emailVerify.update { it.copy(sending = true, error = null, info = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestEmailVerify()) {
                is AuthRepository.EmailVerifyResult.Sent -> _emailVerify.update {
                    it.copy(sending = false, code = "", info = "코드를 다시 보냈음 · 이전 코드는 쓸 수 없음")
                }
                is AuthRepository.EmailVerifyResult.AlreadyVerified,
                is AuthRepository.EmailVerifyResult.Verified -> finishEmailVerify("이미 인증된 이메일이에요", null)
                is AuthRepository.EmailVerifyResult.Failure -> _emailVerify.update {
                    it.copy(sending = false, error = result.message)
                }
            }
        }
    }

    /** "인증하기". 6자리가 찼을 때만 */
    fun confirmEmailVerify() {
        val form = _emailVerify.value
        if (!form.canSubmit) return
        _emailVerify.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            when (val result = authRepository.confirmEmailVerify(form.code)) {
                is AuthRepository.EmailVerifyResult.Verified -> finishEmailVerify("이메일을 인증했어요", result.user?.email)
                is AuthRepository.EmailVerifyResult.AlreadyVerified -> finishEmailVerify("이미 인증된 이메일이에요", null)
                is AuthRepository.EmailVerifyResult.Failure -> _emailVerify.update {
                    it.copy(busy = false, error = result.message)
                }
                is AuthRepository.EmailVerifyResult.Sent -> _emailVerify.update { it.copy(busy = false) }
            }
        }
    }

    private fun finishEmailVerify(message: String, email: String?) {
        _emailVerify.value = EmailVerifyState()
        _state.update {
            it.copy(
                emailVerified = true,
                accountEmail = email?.let(SocialLogin::visibleEmail) ?: it.accountEmail,
            )
        }
        notify(message)
        // 열린 화면일 때만 닫는다. 기다리는 동안 뒤로 갔다면 그대로 둔다.
        if (_nav.value.current is AppRoute.VerifyEmail) goBack()
    }
    // --- 로그인 연결 (social-login-api.md §3) — Partly AI-generated (Claude) ---------

    /** 설정의 "로그인 연결" 이 쓰는 로그인 방법을 받는다(`GET /api/auth/me`) */
    fun loadLoginMethods() {
        viewModelScope.launch {
            when (val result = authRepository.currentUser()) {
                is AuthRepository.AccountResult.Success -> _state.update {
                    it.copy(
                        loginMethods = result.user.loginMethods.orEmpty(),
                        loginMethodsFailed = false,
                        accountEmail = SocialLogin.visibleEmail(result.user.email),
                        emailVerified = result.user.emailVerified,
                    )
                }

                is AuthRepository.AccountResult.Failure -> {
                    Log.w(TAG, "로그인 방법을 받지 못했다: ${result.message}")
                    _state.update { it.copy(loginMethodsFailed = it.loginMethods == null) }
                }
            }
        }
    }

    /**
     * "연결". 다른 연결·끊기가 진행 중이면 false — 화면은 SDK 를 띄우지 않는다. true 면 화면이
     * [com.swpp.wakeup.ui.auth.SocialSignIn] 으로 토큰을 받아 [onSocialLinkOutcome] 으로 넘긴다.
     */
    fun beginSocialLink(provider: SocialProvider): Boolean {
        if (_state.value.socialLinkBusy != null) return false
        _state.update { it.copy(socialLinkBusy = provider) }
        return true
    }

    fun onSocialLinkOutcome(provider: SocialProvider, outcome: SocialSignIn.Outcome) {
        when (outcome) {
            SocialSignIn.Outcome.Cancelled -> _state.update { it.copy(socialLinkBusy = null) }
            SocialSignIn.Outcome.NotConfigured -> {
                _state.update { it.copy(socialLinkBusy = null) }
                notify("지금은 ${provider.label} 연결을 쓸 수 없습니다")
            }
            is SocialSignIn.Outcome.Failed -> {
                _state.update { it.copy(socialLinkBusy = null) }
                notify(outcome.message)
            }
            is SocialSignIn.Outcome.Token -> viewModelScope.launch {
                applyLinkResult(
                    provider,
                    authRepository.linkSocial(provider, outcome.idToken),
                    done = "${provider.asObject} 연결했습니다. 이제 ${provider.label}로도 로그인할 수 있습니다",
                )
            }
        }
    }

    /** "끊기". 유일한 로그인 방법이면 화면이 버튼을 숨기고, 서버도 거절한다 */
    fun unlinkSocial(provider: SocialProvider) {
        if (_state.value.socialLinkBusy != null) return
        _state.update { it.copy(socialLinkBusy = provider) }
        viewModelScope.launch {
            applyLinkResult(provider, authRepository.unlinkSocial(provider), done = "${provider.label} 연결을 끊었습니다")
        }
    }

    private fun applyLinkResult(provider: SocialProvider, result: AuthRepository.AccountResult, done: String) {
        when (result) {
            is AuthRepository.AccountResult.Success -> {
                _state.update {
                    it.copy(
                        socialLinkBusy = null,
                        loginMethods = result.user.loginMethods.orEmpty(),
                        loginMethodsFailed = false,
                    )
                }
                notify(done)
            }

            is AuthRepository.AccountResult.Failure -> {
                _state.update { it.copy(socialLinkBusy = null) }
                notify(result.message)
                // 서버와 화면이 어긋났을 수 있다(다른 기기에서 끊음 등). 다시 받아 맞춘다.
                loadLoginMethods()
            }
        }
        Log.i(TAG, "${provider.path} 연결 결과: ${result.javaClass.simpleName}")
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

    // Partly AI-generated (Claude)
    /**
     * 계산하지 못한 알람(`route_failed`)을 다시 계산한다(`POST /api/events/{id}/recompute`, A6).
     * 서버가 카카오 경로를 한 번 더 부르므로 **사용자가 누를 때만** 부르고, 계산 중에는 막는다.
     * 계산에 성공하면 홈과 기기 알람을 다시 맞춘다([refresh]).
     */
    fun recomputePlan(eventId: Long) {
        if (_state.value.recomputeBusyId != null) return
        _state.update { it.copy(recomputeBusyId = eventId) }
        viewModelScope.launch {
            when (val r = repository.recomputePlan(eventId)) {
                is EventRepository.Result.Success -> {
                    if (_nav.value.current == AppRoute.AlarmDecision(eventId)) {
                        ++planLoadGeneration
                        _plan.value = r.data
                    }
                    refresh()
                    notify(
                        if (r.data.isComputed) "알람을 다시 계산했어요"
                        else "다시 계산했지만 경로를 찾지 못했어요. 출발지·도착지를 확인해 주세요"
                    )
                }

                is EventRepository.Result.Failure -> notify("다시 계산하지 못했어요. ${r.message}")
            }
            _state.update { it.copy(recomputeBusyId = null) }
        }
    }

    // Partly AI-generated (Claude)
    /** 이동 중 알림에서 그 일정 화면으로. 이미 그 화면이면 다시 쌓지 않는다 */
    fun openTrip(eventId: Long) {
        if (_nav.value.current == AppRoute.AlarmDecision(eventId)) return
        openAlarmDecision(eventId)
    }

    fun openRiskChoice(eventId: Long) {
        if (_nav.value.current == AppRoute.RiskChoice(eventId)) return
        _risk.value = RiskState()
        _nav.update { it.copy(stack = it.stack + AppRoute.RiskChoice(eventId), forward = true) }
        loadRiskOptions(eventId)
    }

    data class RiskState(
        val eventId: Long? = null,
        val loading: Boolean = false,
        val saving: Boolean = false,
        val options: RiskOptionsView? = null,
        val error: String? = null,
    )

    // Partly AI-generated (Claude)
    /**
     * 이동 중 "경로 정류장" 카드의 구간. [routeKey] 경로의 정류장은 계획·실시간 응답에 없어서
     * 경로 후보 API 로 한 번 받아 둔다(이 프로세스 동안 일정·기준 경로당 한 번).
     */
    data class CheckpointState(
        val eventId: Long,
        val routeKey: String,
        val segments: RouteSegments? = null,
        val loading: Boolean = true,
    )
    private val _checkpoints = MutableStateFlow<CheckpointState?>(null)
    val checkpoints: StateFlow<CheckpointState?> = _checkpoints.asStateFlow()
    private var checkpointJob: Job? = null
    /**
     * 같은 일정·경로를 이미 받았거나 받는 중이면 아무것도 하지 않는다. 실패해도 이 프로세스에서는
     * 다시 부르지 않는다(카카오 호출 한도). 대중교통이 아닌 경로에는 정류장이 없어 부르지 않는다.
     */
    fun loadCheckpointsIfNeeded(eventId: Long, routeKey: String?) {
        if (routeKey == null || !CheckpointBasis.hasStops(routeKey)) return
        val current = _checkpoints.value
        if (current != null && current.eventId == eventId && current.routeKey == routeKey) return
        _checkpoints.value = CheckpointState(eventId, routeKey)
        checkpointJob?.cancel()
        checkpointJob = viewModelScope.launch {
            val segments = when (val result = repository.baseRouteSegments(eventId, routeKey)) {
                is EventRepository.Result.Success -> result.data
                is EventRepository.Result.Failure -> null
            }
            _checkpoints.update {
                if (it?.eventId == eventId && it.routeKey == routeKey) {
                    it.copy(segments = segments, loading = false)
                } else {
                    it
                }
            }
        }
    }
    private val _risk = MutableStateFlow(RiskState())
    val risk: StateFlow<RiskState> = _risk.asStateFlow()
    private var riskJob: Job? = null
    private var riskGeneration = 0

    fun loadRiskOptions(eventId: Long) {
        if (_risk.value.saving) return
        riskJob?.cancel()
        val generation = ++riskGeneration
        _risk.value = RiskState(eventId = eventId, loading = true)
        riskJob = viewModelScope.launch {
            val result = repository.loadRiskOptions(eventId)
            if (generation != riskGeneration || _nav.value.current != AppRoute.RiskChoice(eventId)) return@launch
            _risk.value = when (result) {
                is EventRepository.Result.Success -> RiskState(eventId, options = result.data)
                is EventRepository.Result.Failure -> RiskState(eventId, error = result.message)
            }
        }
    }

    fun saveRiskOption(eventId: Long, key: String) {
        val state = _risk.value
        if (_nav.value.current != AppRoute.RiskChoice(eventId)
            || state.eventId != eventId || state.loading || state.saving) return
        val selected = state.options?.option(key) ?: return
        val generation = riskGeneration
        _risk.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val error = when (val saved = repository.saveRiskOption(eventId, selected.tau)) {
                is EventRepository.Result.Failure -> saved.message
                is EventRepository.Result.Success -> {
                    if (_nav.value.current == AppRoute.RiskChoice(eventId)
                        || _nav.value.current == AppRoute.AlarmDecision(eventId)) {
                        ++planLoadGeneration // Ignore an older in-flight detail request.
                        _plan.value = saved.data
                    }
                    val homeGen = ++homeLoadGeneration
                    when (val home = repository.loadHome()) {
                        is EventRepository.Result.Success -> {
                            if (!home.data.fromCache) {
                                applyHome(home.data, homeGen) // Refresh home and device alarms together.
                                null
                            } else "선택은 저장됐지만 알람 목록을 갱신하지 못했습니다. 다시 계속하기를 눌러 주세요."
                        }
                        is EventRepository.Result.Failure ->
                            "선택은 저장됐지만 알람 목록을 갱신하지 못했습니다. ${home.message}"
                    }
                }
            }
            if (generation != riskGeneration || _nav.value.current != AppRoute.RiskChoice(eventId)) return@launch
            _risk.update { it.copy(saving = false, error = error) }
            if (error == null) goBack()
        }
    }

    // Partly AI-generated (Claude)
    fun openAddEvent() {
        // 장소 검색이 있는 화면은 들어올 때 좌표를 미리 잡는다. 이것을 빼먹으면
        // 검색이 좌표 없이 나가고, 거리·거리순 정렬이 사라지고 결과가 전국에서
        // 온다. 실기기에서 "GS25" 를 검색했더니 전북 익산·남양주가 상위에 왔다.
        primeCurrentLocation()
        loadEventTags()
        _nav.update { it.copy(stack = it.stack + AppRoute.AddEvent, forward = true) }
    }

    // AI-generated (Claude)
    /**
     * 일정 종류 목록(`GET /api/events/tags`). 이름과 τ 는 서버 값을 쓴다.
     *
     * 한 번 받으면 다시 부르지 않는다. 실패하면 비워 두고 다음에 일정 추가에
     * 들어올 때 다시 시도한다 — 목록이 없어도 "기타" 로 일정은 만들 수 있다.
     */
    private val _eventTags = MutableStateFlow<List<EventTagDto>>(emptyList())
    // AI-generated (Claude)
    val eventTags: StateFlow<List<EventTagDto>> = _eventTags.asStateFlow()
    // AI-generated (Claude)
    private var eventTagsJob: Job? = null

    // Partly AI-generated (Claude)
    /**
     * 추가·수정 화면을 열 때마다 다시 받는다(B1). 다른 기기에서 만든 종류가 보이게 하려는 것이고,
     * 받는 동안과 실패했을 때는 가진 목록을 그대로 둔다.
     */
    private fun loadEventTags() {
        if (eventTagsJob?.isActive == true) return
        eventTagsJob = viewModelScope.launch {
            val result = repository.tags()
            if (result is EventRepository.Result.Success) _eventTags.value = result.data
        }
    }

    // Partly AI-generated (Claude)
    /**
     * "+ 추가" 대화상자(B1, Figma F-49b `528:78`). 보낼 때까지 입력한 이름을 그대로 둔다.
     *
     * @property busy 보내는 중. 닫기·중복 전송을 막는다
     * @property error 대화상자 안의 빨간 줄
     */
    data class TagAddState(
        val open: Boolean = false,
        val label: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _tagAdd = MutableStateFlow(TagAddState())
    val tagAdd: StateFlow<TagAddState> = _tagAdd.asStateFlow()

    // Partly AI-generated (Claude)
    fun openTagAdd() {
        _tagAdd.value = TagAddState(open = true)
    }

    // Partly AI-generated (Claude)
    fun onTagAddLabelChange(v: String) {
        if (_tagAdd.value.busy) return
        _tagAdd.update { it.copy(label = v.take(TAG_LABEL_MAX), error = null) }
    }

    // Partly AI-generated (Claude)
    fun dismissTagAdd() {
        if (_tagAdd.value.busy) return
        _tagAdd.value = TagAddState()
    }

    // Partly AI-generated (Claude)
    /** 종류를 서버에 만들고 목록에 붙여 고른다. 실패하면 대화상자와 입력을 그대로 두고 이유를 보인다. */
    fun submitTagAdd() {
        val current = _tagAdd.value
        if (current.busy || current.label.isBlank()) return
        _tagAdd.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.createTag(current.label)) {
                is EventRepository.Result.Success -> {
                    _eventTags.update { tagsWith(it, result.data) }
                    onAddTagChange(result.data.key)
                    _tagAdd.value = TagAddState()
                }
                is EventRepository.Result.Failure ->
                    _tagAdd.update { it.copy(busy = false, error = tagAddErrorOf(result)) }
            }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 내 종류 이름 바꾸기·지우기 대화상자(event-tags-api.md §5-5). 일정 추가·수정의 종류 드롭다운에서
     * 내가 만든 종류 줄의 [편집] 으로 연다. 전역 종류는 서버가 고치지 못하게 하므로(404) 열지 않는다.
     *
     * @property key 고치는 종류. null 이면 닫혀 있다
     * @property original 열 때의 이름. 바뀌지 않았으면 보내지 않고 닫는다
     * @property confirmDelete [삭제] 를 한 번 눌러 "지울까요?" 를 묻는 중
     * @property busy 보내는 중. 닫기·중복 전송을 막는다
     * @property error 대화상자 안의 빨간 줄
     */
    data class TagEditState(
        val key: String? = null,
        val label: String = "",
        val original: String = "",
        val confirmDelete: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val open: Boolean get() = key != null
    }

    private val _tagEdit = MutableStateFlow(TagEditState())
    val tagEdit: StateFlow<TagEditState> = _tagEdit.asStateFlow()

    // Partly AI-generated (Claude)
    fun openTagEdit(key: String) {
        val tag = _eventTags.value.firstOrNull { it.key == key && it.custom == true } ?: return
        _tagEdit.value = TagEditState(key = tag.key, label = tag.label, original = tag.label)
    }

    // Partly AI-generated (Claude)
    fun onTagEditLabelChange(v: String) {
        if (_tagEdit.value.busy) return
        _tagEdit.update { it.copy(label = v.take(TAG_LABEL_MAX), error = null) }
    }

    // Partly AI-generated (Claude)
    fun dismissTagEdit() {
        if (_tagEdit.value.busy) return
        _tagEdit.value = TagEditState()
    }

    // Partly AI-generated (Claude)
    /** [삭제] 를 누르면 바로 지우지 않고 대화상자 안에서 한 번 더 묻는다. [ask] 가 false 면 이름 입력으로 돌아간다 */
    fun askTagDelete(ask: Boolean = true) {
        if (_tagEdit.value.busy) return
        _tagEdit.update { it.copy(confirmDelete = ask, error = null) }
    }

    // Partly AI-generated (Claude)
    /**
     * 이름을 바꿔 목록의 같은 자리에 끼운다. 다듬은 이름이 그대로면 보내지 않고 닫는다.
     * 일정·알람은 그대로다(이름만 바뀌면 서버가 알람을 다시 섞지 않는다, event-tags-api.md §4).
     */
    fun submitTagRename() {
        val current = _tagEdit.value
        val key = current.key ?: return
        if (current.busy || current.label.isBlank()) return
        if (current.label.trim() == current.original) {
            _tagEdit.value = TagEditState()
            return
        }
        _tagEdit.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.renameTag(key, current.label)) {
                is EventRepository.Result.Success -> {
                    _eventTags.update { tagsRenamed(it, result.data) }
                    _tagEdit.value = TagEditState()
                    // 홈의 일정 줄에 붙은 종류 칩도 새 이름으로.
                    refresh(silent = true)
                }
                is EventRepository.Result.Failure ->
                    _tagEdit.update { it.copy(busy = false, error = tagEditErrorOf(result)) }
            }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 종류를 지운다. 그 종류를 단 일정은 서버에서 종류 없음이 되고 알람이 프로필 기본 여유로 다시
     * 맞춰지므로(event-tags-api.md §4) 홈을 다시 받는다. 지금 고른 종류였으면 "선택 안 함" 으로 돌린다.
     */
    fun confirmTagDelete() {
        val current = _tagEdit.value
        val key = current.key ?: return
        if (current.busy) return
        _tagEdit.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.deleteTag(key)) {
                is EventRepository.Result.Success -> {
                    _eventTags.update { tagsWithout(it, key) }
                    if (_add.value.tagKey == key) onAddTagChange(null)
                    _tagEdit.value = TagEditState()
                    refresh(silent = true)
                }
                is EventRepository.Result.Failure ->
                    _tagEdit.update { it.copy(busy = false, error = tagEditErrorOf(result)) }
            }
        }
    }

    /** 설정 화면. 가입할 때 정한 집 주소·준비 시간·캘린더 연동을 여기서 고친다 */
    fun openSettings() {
        _nav.update { it.copy(stack = it.stack + AppRoute.Settings, forward = true) }
        // "로그인 연결" 에 쓸 로그인 방법. 설정을 열 때마다 새로 받는다(다른 기기에서 바꿨을 수 있다).
        loadLoginMethods()
        loadCalendarSources()
        syncSavedHomes()
    }

    fun openHomeSetup() {
        primeCurrentLocation()
        _nav.update { it.copy(stack = it.stack + AppRoute.HomeSetup, forward = true) }
    }

    // --- 아침 기록 --------------------------------------------------------
    //
    // 저장소와 상태 선언은 이 위 `init` 블록보다 앞에 있다. `init` 이
    // reloadMorning() 을 부르므로 여기 두면 앱을 켤 때마다 죽는다.

    // --- 놓친 알람 ---------------------------------------------------------
    // Partly AI-generated (Claude)

    /**
     * 울리지 않은 알람을 디스크에서 다시 읽는다.
     *
     * 이미 그 일정을 추적하고 있으면 띄우지 않는다 — 사용자가 다른 길로 기록을
     * 시작했다는 뜻이다.
     */
    fun reloadMissedAlarm() {
        val app = getApplication<Application>()
        val trackingId = TripLiveState.snapshot.value?.eventId
        val missed = runCatching { AlarmScheduler(app).missed() }
            .onFailure { Log.w(TAG, "놓친 알람을 읽지 못했다", it) }
            .getOrDefault(emptyList())
            .firstOrNull { it.eventId != trackingId }
        _state.update {
            it.copy(
                missedAlarm = missed?.let { schedule ->
                    MissedAlarmNotice(
                        schedule = schedule,
                        canTrack = schedule.canTrack && LocationPermissions.granted(app),
                    )
                }
            )
        }
    }

    /**
     * 놓친 알람의 이동 기록을 지금 시작한다.
     *
     * 알람 해제([com.swpp.wakeup.alarm.AlarmActivity])와 같은 서비스를 같은 계획으로
     * 띄운다. **아침 기록은 시작하지 않는다.** 알람 없이 이미 준비를 시작한
     * 뒤라 블록마다 걸린 시간이 맞지 않고, 틀린 기록은 준비 시간 학습을 흐린다.
     */
    fun startMissedAlarmTracking() {
        val notice = _state.value.missedAlarm ?: return
        val app = getApplication<Application>()
        if (notice.schedule.canTrack && LocationPermissions.granted(app)) {
            runCatching { TripTrackingService.start(app, notice.schedule) }
                .onFailure { Log.e(TAG, "놓친 알람 추적 시작 실패", it) }
        }
        dismissMissedAlarm()
    }

    /** 안내를 닫는다. 같은 알람으로 다시 띄우지 않는다. */
    fun dismissMissedAlarm() {
        val notice = _state.value.missedAlarm ?: return
        runCatching { AlarmScheduler(getApplication()).acknowledgeMissed(notice.schedule) }
            .onFailure { Log.w(TAG, "놓친 알람 처리 표시 실패", it) }
        // 같은 아침에 두 일정을 놓쳤으면 다음 것을 띄운다.
        reloadMissedAlarm()
    }

    /**
     * 화면에 들어올 때마다 디스크에서 다시 읽는다. 알람 액티비티가 만들었을 수 있다.
     *
     * 기록이 닫혔는데(출발·도착·"안 감") 아침 기록 화면이 열려 있으면 그 화면을 닫는다.
     * `goBack()` 은 쓰지 않는다 — `init` 아래에 선언된 위험도 화면 상태를 읽는다.
     */
    fun reloadMorning() {
        val session = morningStore.current()
        _morning.value = session
        _state.update { it.copy(morningBlocksLeft = session?.remaining?.size) }
        if (session == null) {
            _nav.update {
                if (it.current == AppRoute.MorningProgress) {
                    it.copy(stack = it.stack.dropLast(1), forward = false)
                } else {
                    it
                }
            }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 서비스가 출발·도착으로 아침 기록을 닫았다. 아침 기록 화면을 보고 있었으면 이유를 알린다.
     * 나중에 만들어진 ViewModel 은 홈에 있으므로 조용히 신호만 지운다.
     */
    private fun onMorningClosedByTrip(closed: TripLiveState.MorningClosed) {
        val wasOnMorning = _nav.value.current == AppRoute.MorningProgress
        reloadMorning()
        if (wasOnMorning && _morning.value == null) {
            notify(
                when (closed.reason) {
                    MorningClose.Reason.DEPARTED -> "출발이 확인돼 아침 기록을 끝냈습니다"
                    MorningClose.Reason.ARRIVED -> "도착이 확인돼 아침 기록을 끝냈습니다"
                }
            )
        }
        TripLiveState.consumeMorningClosed(closed)
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
        // 서비스가 방금 기록을 닫았을 수 있다. 메모리 사본으로 저장하면 닫힌 기록이 되살아난다.
        if (morningStore.current()?.eventId != session.eventId) {
            Log.i(TAG, "아침 기록이 이미 닫혔다. 누른 것을 무시한다")
            reloadMorning()
            return
        }
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
        if (morningStore.current()?.eventId != session.eventId) {
            reloadMorning()
            return
        }
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
    // Partly AI-generated (Claude)
    data class ReportState(
        val loading: Boolean = true,
        val error: String? = null,
        val weekly: WeeklyReportView? = null,
        val longTerm: CalibrationView? = null,
        /** 보고 있는 주. null 이면 지난 주 */
        val week: LocalDate? = null,
        /** 홈에서 리포트를 연 횟수. 바뀌면 ⑧ 이 맨 위로 스크롤한다(⑨ 에서 돌아올 때는 그대로) */
        val openCount: Int = 0,
    ) {
        val hasData: Boolean get() = weekly != null
    }

    // Partly AI-generated (Claude)
    /**
     * ⑧ 의 상태와 주 이동 규칙(실패 시 주 되돌림, "›" 가드, [ReportState.openCount]).
     * 규칙은 [ReportWeekNavigator] 에 있다 — `HomeViewModel` 은 JVM 테스트로 만들 수 없어서다.
     */
    private val reportNavigator = ReportWeekNavigator(
        scope = viewModelScope,
        weekly = { week -> reports.weekly(week) },
        calibration = { reports.calibration() },
        onCalibrationFailure = { Log.w(TAG, "전체 캘리브레이션 조회 실패: $it") },
    )
    val report: StateFlow<ReportState> = reportNavigator.state

    // Partly AI-generated (Claude)
    /** ⑧ 을 연다. 마지막으로 본 주를 다시 연다(처음은 지난 주). */
    fun openReport() {
        _nav.update { it.copy(stack = it.stack + AppRoute.WeeklyReport, forward = true) }
        reportNavigator.open()
    }

    // AI-generated (Claude)
    /** ⑨ 상세 리포트. ⑧ 이 이미 받은 주간 응답을 보므로 새로 부르지 않는다. */
    fun openReportDay(date: LocalDate, eventId: Long?) {
        _nav.update {
            it.copy(stack = it.stack + AppRoute.ReportDay(date, eventId), forward = true)
        }
    }

    // Partly AI-generated (Claude)
    /** [week] 주를 불러온다. 실패하면 보이던 주로 되돌린다([ReportWeekNavigator]). */
    fun loadReport(week: LocalDate? = null) = reportNavigator.load(week)

    // Partly AI-generated (Claude)
    /**
     * 한 주 앞뒤로 이동한다. 기준은 보고 있는 주, 없으면 지난 주다.
     *
     * 다음 주로는 서버가 허락할 때만 간다(`has_next_week`). 이번 주까지 가고 미래
     * 주는 못 간다 — 화면이 "›" 를 끄지만 여기서도 막는다.
     */
    fun shiftReportWeek(weeks: Long) = reportNavigator.shift(weeks)

    // --- 캘린더 가져오기 --------------------------------------------------

    private val _calendarImport = MutableStateFlow(CalendarImportState())
    val calendarImport: StateFlow<CalendarImportState> = _calendarImport.asStateFlow()

    fun openCalendarImport() {
        _nav.update { it.copy(stack = it.stack + AppRoute.CalendarImport, forward = true) }
        _calendarImport.value = CalendarImportState(loading = true)
        loadCalendarCandidates()
    }

    /** 스위치를 빠르게 여러 번 누르면 읽기가 겹친다. 마지막 것만 화면에 반영한다 */
    private var importLoadGeneration = 0

    /**
     * 캘린더를 읽어 후보를 만든다.
     *
     * 권한이 없으면 **읽지 않고** 그 사실을 상태에 담는다. 권한 없음과 "앞으로
     * 일정이 없음" 은 사용자가 할 일이 완전히 다르다.
     *
     * **연동한 캘린더만 읽는다**(⑱ · ⑯). 연동한 것이 없으면 일정을 읽지 않고 화면 위에
     * 캘린더 스위치를 띄운다 — "일정이 없음" 과는 할 일이 다르다. (Partly AI-generated (Claude))
     */
    fun loadCalendarCandidates() {
        val app = getApplication<Application>()
        if (!DeviceCalendar.hasPermission(app)) {
            _calendarImport.value = CalendarImportState(permissionDenied = true)
            return
        }

        val generation = ++importLoadGeneration
        _calendarImport.update {
            it.copy(loading = true, permissionDenied = false, error = null)
        }
        viewModelScope.launch {
            // ContentResolver 질의는 디스크를 탄다. 메인 스레드에서 하면 목록이
            // 큰 캘린더에서 프레임이 끊긴다.
            val calendars = withContext(Dispatchers.IO) { DeviceCalendar.listCalendars(app) }
            val stored = withContext(Dispatchers.IO) { calendarLinkStore.linkedIds() }
            if (generation != importLoadGeneration) return@launch
            _calendarLink.update {
                it.copy(
                    permission = CalendarPermission.GRANTED,
                    loading = false,
                    calendars = calendars,
                    storedIds = stored,
                )
            }
            val linked = _calendarLink.value.linkedIds
            if (linked.isEmpty()) {
                _calendarImport.update {
                    it.copy(loading = false, candidates = emptyList(), linkedCount = 0, linkPicker = true)
                }
                return@launch
            }

            val events = withContext(Dispatchers.IO) {
                DeviceCalendar.read(app, calendarIds = linked)
            }
            val result = repository.buildImportCandidates(events)
            if (generation != importLoadGeneration) return@launch
            when (result) {
                is EventRepository.Result.Success -> {
                    _calendarImport.update {
                        it.copy(
                            loading = false,
                            error = null,
                            candidates = result.data,
                            linkedCount = linked.size,
                        )
                    }
                    resolvePlaces(result.data)
                }

                is EventRepository.Result.Failure -> _calendarImport.update {
                    it.copy(loading = false, error = result.message, linkedCount = linked.size)
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

    /**
     * 권한 요청 결과를 받는다. 허용됐으면 바로 읽는다.
     *
     * ⑱ · ⑯ · 가져오기가 같은 요청을 쓴다. 가져오기 화면이 열려 있을 때만 일정까지
     * 읽는다 — 설정에서 허용했는데 일정 목록과 서버 조회까지 돌 이유가 없다.
     */
    fun onCalendarPermissionResult(granted: Boolean) {
        _calendarLink.update {
            it.copy(permission = if (granted) CalendarPermission.GRANTED else CalendarPermission.DENIED)
        }
        val importOpen = AppRoute.CalendarImport in _nav.value.stack
        if (granted) {
            loadCalendarSources()
            if (importOpen) loadCalendarCandidates()
        } else if (importOpen) {
            _calendarImport.update { it.copy(permissionDenied = true, loading = false) }
        }
    }

    // --- 캘린더 연동 (⑱ · ⑯) ---------------------------------------------
    // Partly AI-generated (Claude)

    /**
     * 기기 캘린더 목록과 저장된 연동을 읽는다. ⑱ · ⑯ 이 열릴 때와 권한이 바뀐 뒤 부른다.
     *
     * 이미 거절된 상태(DENIED)는 그대로 둔다. 다시 읽어도 권한이 없다는 사실만 같고,
     * MISSING 으로 돌리면 "기기 설정에서 켜야 함" 안내가 사라진다.
     */
    fun loadCalendarSources() {
        val app = getApplication<Application>()
        if (!DeviceCalendar.hasPermission(app)) {
            _calendarLink.update {
                it.copy(
                    permission = if (it.permission == CalendarPermission.DENIED) {
                        CalendarPermission.DENIED
                    } else {
                        CalendarPermission.MISSING
                    },
                    loading = false,
                    calendars = emptyList(),
                    storedIds = calendarLinkStore.linkedIds(),
                )
            }
            return
        }
        _calendarLink.update { it.copy(permission = CalendarPermission.GRANTED, loading = true) }
        viewModelScope.launch {
            val calendars = withContext(Dispatchers.IO) { DeviceCalendar.listCalendars(app) }
            val stored = withContext(Dispatchers.IO) { calendarLinkStore.linkedIds() }
            _calendarLink.update { it.copy(loading = false, calendars = calendars, storedIds = stored) }
        }
    }

    /**
     * 화면으로 돌아왔을 때. 사용자가 기기 설정에서 권한을 바꾸고 왔을 수 있다.
     *
     * 권한이 바뀌었으면 목록과(가져오기 화면이면) 일정까지 다시 읽는다.
     *
     * 권한이 그대로여도 **캘린더 목록은 다시 읽는다.** "캘린더가 안 보이면" 안내를 따라
     * 휴대폰 설정에서 동기화를 켜거나 구글 캘린더 앱에서 공유를 켜고 왔을 수 있다. 이미
     * 받은 목록은 그대로 그리므로 깜빡이지 않는다. 가져오기의 일정 목록은 서버 조회와 장소
     * 검색이 따라오므로 이 경우에는 다시 읽지 않는다 — 새 캘린더를 켜면 그때 읽는다.
     * (Partly AI-generated (Claude))
     */
    fun onCalendarScreenResumed() {
        val current = _nav.value.current
        if (current != AppRoute.Settings && current != AppRoute.CalendarLink &&
            current != AppRoute.CalendarImport
        ) return
        val granted = DeviceCalendar.hasPermission(getApplication())
        val known = _calendarLink.value.permission == CalendarPermission.GRANTED
        if (granted != known) {
            if (granted) _calendarLink.update { it.copy(permission = CalendarPermission.GRANTED) }
            loadCalendarSources()
            if (current == AppRoute.CalendarImport) loadCalendarCandidates()
            return
        }
        if (granted) loadCalendarSources()
    }

    /** 거절된 뒤라 시스템이 대화상자를 다시 띄우지 않을 수 있는가. 화면이 기기 설정으로 보낸다 */
    val calendarPermissionDenied: Boolean
        get() = _calendarLink.value.permission == CalendarPermission.DENIED

    /**
     * ⑯ 설정·가져오기의 스위치. **누르는 즉시 저장한다.**
     *
     * 기기에서 사라진 캘린더의 id 는 이참에 버린다. 남겨 두면 같은 번호를 받은 새
     * 캘린더가 저절로 켜질 수 있다.
     */
    fun setCalendarLinked(id: Long, linked: Boolean) {
        val link = _calendarLink.value
        val present = link.calendars.mapTo(HashSet()) { it.id }
        if (id !in present) return
        val next = (if (linked) link.storedIds + id else link.storedIds - id) intersect present
        calendarLinkStore.setLinked(next)
        _calendarLink.update { it.copy(storedIds = next) }
        if (_nav.value.current == AppRoute.CalendarImport) loadCalendarCandidates()
    }

    /** ⑱ 의 체크. 저장은 [submitCalendarOnboarding] 이 한다 */
    fun toggleCalendarOnboarding(id: Long) = _calendarOnboarding.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    /** ⑱ "N개 연동하고 시작하기" */
    fun submitCalendarOnboarding() {
        val chosen = _calendarOnboarding.value.chosen(_calendarLink.value)
        if (chosen.isEmpty()) return
        calendarLinkStore.setLinked(chosen)
        calendarLinkStore.markAsked()
        _calendarLink.update { it.copy(storedIds = chosen) }
        _calendarOnboarding.update { it.copy(done = true, savedCount = chosen.size) }
    }

    /**
     * ⑱ "나중에" · 뒤로. 아무것도 저장하지 않고, 이 기기·계정에서는 다시 묻지 않는다.
     *
     * 다시 묻지 않는 이유: 없어도 알람이 계산되는 값이다. 앱을 열 때마다 캘린더를
     * 물으면 건너뛴 사용자를 귀찮게만 한다. ⑯ 설정에서 켤 수 있다.
     */
    fun skipCalendarOnboarding() {
        calendarLinkStore.markAsked()
        _calendarOnboarding.update { it.copy(done = true, savedCount = null) }
    }

    fun resetCalendarOnboarding() {
        _calendarOnboarding.value = CalendarOnboardingState()
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
     * 서버가 블록 수정 뒤 앞으로의 알람을 카카오 호출 없이 다시 계산한다. 그래서 저장되면
     * 홈을 다시 받아 기기 알람까지 맞춘다([refresh]).
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
                is RoutineRepository.RoutineResult.Success -> {
                    _routine.update { state ->
                        state.copy(
                            blocks = state.blocks.map { if (it.id == id) r.data else it },
                            original = state.original + (id to r.data.checked),
                            notice = DEFINITION_NOTICE,
                        )
                    }
                    // 서버가 앞으로의 알람을 다시 계산했다. 홈과 기기 알람을 맞춘다.
                    refresh()
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
                    // 서버가 앞으로의 알람을 다시 계산했다. 안 하면 6시간 정기 동기화까지
                    // 홈에 예전 시각이 보인다(todo §2 "다시 계산 버튼 정리" ②).
                    refresh()
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
                    refresh()
                }

                is RoutineRepository.RoutineResult.Failure -> _routine.update {
                    it.copy(saving = false, error = r.message)
                }
            }
        }
    }

    // --- 경로 선택 (Figma ⑬) ---------------------------------------------

    /**
     * 경로 선택 화면 상태.
     *
     * 출발지는 일정 추가 화면(⑫)에서 고른다. 여기서는 그 값으로 후보를 받기만
     * 한다. [origin] 이 null 이면 서버가 프로필 집을 쓴 상태다.
     */
    data class RouteState(
        val loading: Boolean = true,
        val error: String? = null,
        val choice: RouteChoice? = null,

        /** 일정 추가에서 고른 출발지. null 이면 프로필 집 */
        val origin: PlaceSearchItem? = null,

        /**
         * 지도 미리보기로 연 후보 key. null 이면 목록이다.
         *
         * 새 화면 경로를 쌓지 않고 같은 경로 안에서 바꿔 그린다. "이 경로로 계산" 이
         * 일정 추가로 한 번에 돌아가야 해서다. 뒤로는 `MainActivity` 가 먼저 이걸 닫는다.
         */
        val previewKey: String? = null,

        /**
         * [error] 가 다시 시도로 풀리는가. 가까운 출발·도착(400)은 장소를 바꿔야
         * 풀리므로 false 다([candidatesRetryable]).
         */
        val retryable: Boolean = true,
    )

    private val _routeChoice = MutableStateFlow<RouteState?>(null)
    val routeChoice: StateFlow<RouteState?> = _routeChoice.asStateFlow()

    // Partly AI-generated (Claude)
    /**
     * 경로 후보를 받아 ⑬ 으로 이동한다.
     *
     * 서버가 외부 API 를 최대 4번 부르므로 이미 받아 둔 후보가 있으면 다시
     * 부르지 않는다. 장소를 바꾸면 [onAddPlaceSelected] 가 캐시를 버린다.
     *
     * 출발지는 일정 추가 화면에서 고른 값([AddState.origin], null 이면 집)을 그대로
     * 쓴다. 출발지를 바꾸면 [onAddOriginSelected] 가 캐시를 버린다.
     */
    fun openRouteChoice() {
        val add = _add.value
        if (!add.canPickRoute(_state.value.hasHome)) return
        val place = add.selectedPlace ?: return
        _nav.update { it.copy(stack = it.stack + AppRoute.RouteChoice, forward = true) }

        val cached = _routeChoice.value?.choice
        if (cached != null) {
            // 다시 들어오면 목록부터 보인다. 미리보기가 열린 채 남아 있으면 안 된다.
            _routeChoice.update { it?.copy(previewKey = null) }
            return
        }

        _routeChoice.value = RouteState(loading = true, origin = add.origin)
        viewModelScope.launch { loadCandidates(place, add.origin, keepSelection = true) }
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

    // Partly AI-generated (Claude)
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
            MapTarget.ORIGIN -> _add.value.origin
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

        // 보이는 사각형은 지도 좌표(KakaoMapProjection)의 사각형이다. 네 모서리를
        // 위·경도로 되돌려 그것을 모두 담는 범위를 보낸다.
        val halfWidthM = viewWidthPx / 2.0 * metersPerPixel
        val halfHeightM = viewHeightPx / 2.0 * metersPerPixel
        val centerTm = KakaoMapProjection.forward(state.center)
        val corners = listOf(-1.0, 1.0).flatMap { sx ->
            listOf(-1.0, 1.0).map { sy ->
                KakaoMapProjection.inverse(
                    KakaoMapProjection.Tm(
                        centerTm.x + sx * halfWidthM,
                        centerTm.y + sy * halfHeightM,
                    )
                )
            }
        }

        val rect = listOf(
            corners.minOf { it.lng },
            corners.minOf { it.lat },
            corners.maxOf { it.lng },
            corners.maxOf { it.lat },
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

    // Partly AI-generated (Claude)
    /** 지도에서 고른 것을 원래 검색으로 되돌리고 닫는다. */
    fun confirmMapPick() {
        val state = _mapPick.value ?: return
        val picked = state.selected ?: return
        when (state.target) {
            MapTarget.DESTINATION -> onAddPlaceSelected(picked)
            MapTarget.HOME -> onHomePlaceSelected(picked)
            MapTarget.ORIGIN -> onAddOriginSelected(picked)
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

    // Partly AI-generated (Claude)
    private fun placeSearchOf(target: MapTarget): PlaceSearch = when (target) {
        MapTarget.DESTINATION -> _add.value.place
        MapTarget.HOME -> _homeSetup.value.place
        MapTarget.ORIGIN -> _add.value.originPlace
    }

    // Partly AI-generated (Claude)
    private fun setPlaceSearch(target: MapTarget, next: PlaceSearch) = when (target) {
        MapTarget.DESTINATION -> _add.update { it.copy(place = next) }
        MapTarget.HOME -> _homeSetup.update { it.copy(place = next) }
        MapTarget.ORIGIN -> _add.update { it.copy(originPlace = next) }
    }

    /** 후보 조회 한 곳. 최초 진입·재시도·출발지 변경이 모두 이걸 쓴다. */
    private suspend fun loadCandidates(
        place: PlaceSearchItem,
        origin: PlaceSearchItem?,
        keepSelection: Boolean,
    ) {
        _routeChoice.update {
            (it ?: RouteState()).copy(loading = true, error = null, retryable = true)
        }
        when (val result = repository.routeCandidates(place, origin)) {
            is EventRepository.Result.Success -> _routeChoice.update { current ->
                val base = current ?: RouteState()
                val choice = if (keepSelection) {
                    result.data.copy(
                        selectedKey = _add.value.routeKey?.takeIf { key ->
                            result.data.options.any { it.key == key }
                        } ?: result.data.selectedKey
                    )
                } else {
                    result.data
                }
                base.copy(
                    loading = false,
                    error = null,
                    choice = choice,
                    previewKey = previewKeyAfterReload(base.previewKey, choice.options),
                )
            }

            is EventRepository.Result.Failure -> {
                _routeChoice.update { current ->
                    (current ?: RouteState()).copy(
                        loading = false,
                        error = result.message,
                        retryable = candidatesRetryable(result),
                    )
                }
                // 가까운 출발·도착(400)은 일정 추가 화면의 도착지 줄에도 남긴다.
                // 뒤로 돌아가면 무엇을 고쳐야 하는지 그 줄이 말한다.
                result.fields["destination"]?.let { message ->
                    _add.update { it.copy(fieldErrors = it.fieldErrors + (AddField.DESTINATION to message)) }
                }
            }
        }
    }

    // AI-generated (Claude)
    /**
     * 경로 후보의 지도 미리보기를 연다(수단 무관, 좌표가 있을 때). 그 후보를 고른 상태로
     * 만든다 — 닫고 목록으로 돌아오면 그 카드가 골라져 있다.
     *
     * 일정 지도(`_routeMap`)·추적 상태는 건드리지 않는다. 지도 상태는 미리보기 화면
     * 안에서만 산다(`CandidateRouteMapCard`).
     */
    fun openRoutePreview(key: String) {
        onRouteSelected(key)
        _routeChoice.update { it?.copy(previewKey = key) }
    }

    // AI-generated (Claude)
    /** 미리보기를 닫고 목록으로. 고른 후보는 그대로 둔다. */
    fun closeRoutePreview() {
        _routeChoice.update { it?.copy(previewKey = null) }
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

    /** 후보 지도는 화면 내부 상태를 쓰며 추적 중인 일정 지도와 분리한다. */
    suspend fun loadCandidateMap(
        center: GeoPoint,
        level: Int,
        widthDp: Int,
        heightDp: Int,
    ): EventRepository.Result<Bitmap> = repository.staticMap(
        center, level, widthDp, heightDp, markers = emptyList(),
    )

    /** 고른 경로를 일정 추가 폼에 반영하고 돌아간다. */
    fun confirmRoute() {
        // 후보 캐시는 다시 들어올 때 쓰려고 남긴다. 미리보기가 열린 채 남으면 안 된다.
        _routeChoice.update { it?.copy(previewKey = null) }
        val current = _routeChoice.value ?: return
        val choice = current.choice ?: return
        val option = choice.options.firstOrNull { it.key == choice.selectedKey }
        // 집 버튼은 프로필 집을 사용한다는 뜻이다. 좌표를 별도 출발지로 보내면
        // 서버가 집 밖의 일정으로 판단해 준비 루틴을 제외한다.
        val eventOrigin = current.origin?.takeUnless { it == _state.value.homePlace }
        _add.update {
            it.copy(
                routeKey = option?.key,
                routeLabel = option?.let { o -> "${o.minutesLabel} · ${o.mode}" },
                // 집 외의 출발지는 경로 선택 때의 좌표를 그대로 보존한다.
                origin = eventOrigin,
                originLabel = current.origin?.name ?: choice.originLabel,
                error = null,
                fieldErrors = it.fieldErrors - AddField.ROUTE - AddField.ORIGIN,
            )
        }
        goBack()
    }

    fun goBack() {
        if (_nav.value.current is AppRoute.RiskChoice) {
            ++riskGeneration
            riskJob?.cancel()
            _risk.value = RiskState()
        }
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
                    // 다른 기기에서 도착했으면 이 화면도 "도착" 이어야 한다.
                    result.data.startAtMillis?.let { start ->
                        reconcileArrivals(
                            listOf(ArrivalCandidate(result.data.eventId, start, null, result.data.skipped))
                        )
                    }
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
            TripLiveState.arrivalFor(plan.eventId) != null -> false
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
        val moving = if (TripLiveState.arrivalFor(map.eventId) != null) false else inTransit
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
     * 도착 결과가 있으면 유지하고, 추적 중이면 판정기의 단계를 쓴다.
     * 둘 다 없으면 알람·일정 시각으로 가른다.
     * 판정기는 "알람 전" 과 "준비 중" 을 구분하지 않는다 — 판정에는 같지만
     * (둘 다 집에 있다) 사용자에게는 전혀 다른 상태다.
     *
     * 판정기의 "출발 전" 은 판정에 쓴 위치가 2분 안일 때만 쓴다. 흐린 위치만 오는
     * 동안에도 판정기는 집에 머물러, 집을 나선 사람에게 "준비 중" 을 계속 보였다.
     *
     * 시각 판정은 [AlarmPlanView.alarmPassed]·[AlarmPlanView.eventPassed] 가 이미
     * 담고 있다. 여기서 시계를 다시 읽으면 화면에 보이는 문구("지난 알람")와
     * 단계가 서로 다른 시점을 근거로 삼게 된다.
     */
    fun stageOf(
        plan: AlarmPlanView,
        arrival: TripLiveState.Arrival? = TripLiveState.arrivalFor(plan.eventId),
        /** 추적 판정의 나이를 잴 시각. "출발 전" 은 싱싱할 때만 믿는다([TripStage.tracked]) */
        nowMillis: Long = System.currentTimeMillis(),
    ): TripStage = if (arrival?.eventId == plan.eventId) TripStage.ARRIVED else TripStage.of(
        tracked = TripLiveState.pointFor(plan.eventId)?.let { live ->
            TripStage.tracked(live.phase, live.phaseCheckedAtMillis, nowMillis)
        } ?: TripLiveState.liveRouteFor(plan.eventId)
            ?.takeIf { it.usableLiveRoute() != null }
            ?.let { TripStage.IN_TRANSIT },
        alarmPassed = plan.alarmPassed,
        eventPassed = plan.eventPassed,
        prepApplies = plan.prepApplies,
        alarmOn = plan.alarmOn,
        departPassed = plan.departPassed,
    )

    // --- 일정 추가 --------------------------------------------------------

    // Partly AI-generated (Claude)
    data class AddState(
        val title: String = "",
        val date: LocalDate = LocalDate.now().plusDays(1),
        val hour: Int = 9,
        val minute: Int = 0,
        val repeatWeekdays: Set<Int> = emptySet(),
        /** null 이면 "기타" — 서버에서 태그를 비우고 프로필 기본 τ 를 쓴다 */
        val tagKey: String? = "class",
        /** 목적지 검색. 집 주소·출발지와 같은 상태를 쓴다 */
        val place: PlaceSearch = PlaceSearch(),
        val selectedPlace: PlaceSearchItem? = null,
        /** 집 버튼으로 선택한 목적지. 좌표가 같아도 검색한 장소는 일반 목적지다. */
        val destinationIsHome: Boolean = false,

        /** ⑬ 경로 선택에서 고른 값. 비어 있으면 서버가 최단 경로를 쓴다 */
        val routeKey: String? = null,
        /** 고른 경로 요약. "23분 · 지하철+도보+버스" */
        val routeLabel: String? = null,

        /**
         * 출발지. **null 이면 집**이다 — 서버가 `origin_*` 이 비어 있으면 프로필 집을 쓴다.
         *
         * 기본값은 집이다. 일정은 대개 나중 일이라, 일정을 추가하는 지금 위치가 그때
         * 출발지라는 보장이 없다. 현재 위치는 사용자가 고를 때만 쓴다.
         *
         * [routeKey] 와 **짝으로 움직여야 한다.** 경로는 이 출발지 기준으로 고른
         * 것이므로 출발지가 바뀌면 고른 경로를 버린다.
         */
        val origin: PlaceSearchItem? = null,
        /** 출발지 표시명. null 이면 집 */
        val originLabel: String? = null,
        /** 출발지 검색. 목적지 검색과 독립이지만 같은 구현을 쓴다 */
        val originPlace: PlaceSearch = PlaceSearch(),
        /** "현재 위치 사용" 을 눌러 위치를 재는 중 */
        val originLocating: Boolean = false,
        /** 현재 위치를 못 구했을 때의 안내. 값은 바꾸지 않고 이유만 보여준다 */
        val originNotice: String? = null,

        /**
         * 각 줄 바로 아래에 붙일 서버 오류(제목·시각·종류·경로·출발지·도착지). 예: 가까운 출발·도착(400)
         * "출발지와 도착지는 50m 이상 떨어져 있어야 한다." 그 줄의 값을 고치면 지운다(A2).
         */
        val fieldErrors: Map<AddField, String> = emptyMap(),

        val submitting: Boolean = false,
        /** 버튼 위의 빨간 줄. 줄에 붙지 않은 오류와 통신·서버 오류 */
        val error: String? = null,
        val done: Boolean = false,
        /** 저장 실패를 한 번 눈에 띄게 알릴 snackbar. 화면이 띄운 뒤 [consumeAddFailureNotice] 로 지운다(A1) */
        val failureNotice: AddFailureNotice? = null,
        /**
         * 같은 요청을 다시 보내도 일정이 하나만 생기게 하는 값(A5). 새 [AddState] 마다 새 UUID 이고
         * `copy` 는 유지한다. 다시 시도·입력 고침은 같은 값, 새로 열기·버리기·저장 성공 뒤는 새 값.
         */
        val clientRequestId: String = java.util.UUID.randomUUID().toString(),
        /** 방금 저장했지만 알람 계산이 `route_failed` 인 일정(A6). 화면이 "다시 계산" 안내를 띄운다 */
        val routeFailedEventId: Long? = null,

        /**
         * 집이 아닌 출발지에서 준비 시간을 넣는가(include-prep-api.md, "여기서 자고 출발해요").
         * 출발지가 집이면 스위치가 없고 이 값은 쓰지 않는다.
         */
        val includePrep: Boolean = false,
        /** 출발지가 저장한 집이면 서버의 그 집 id(B2). 출발지를 바꾸면 지운다 */
        val originPlaceId: Long? = null,

        /** 고치는 일정. null 이면 새 일정 추가다(Figma ⑫ 변형 · 일정 수정) */
        val editingEventId: Long? = null,
        /** 고치기 전 값. 바뀐 필드만 보내려고 들고 있다([EventEditPatch]) */
        val editBase: EventEditValues? = null,
        /** 반복 일정의 한 회차를 고치는 중. 요일은 고칠 수 없고 이 회차만 바뀐다 */
        val recurring: Boolean = false,
        /** 저장 뒤 화면이 띄울 한 줄. null 이면 "일정을 추가했습니다" */
        val doneMessage: String? = null,
    ) {
        val canSubmit: Boolean get() = !submitting && title.isNotBlank()
        val isEditing: Boolean get() = editingEventId != null

        /**
         * 경로를 고를 수 있는 조건. 출발지와 도착지가 **둘 다** 있어야 한다.
         * 출발지는 고른 곳이 있거나([origin]), 비어 있으면 저장된 집이다.
         */
        fun canPickRoute(hasHome: Boolean): Boolean =
            !submitting && selectedPlace != null && (origin != null || hasHome)

    }

    /** 저장 실패 안내 한 건. [id] 가 바뀌면 같은 문구도 다시 띄운다 */
    data class AddFailureNotice(val id: Long, val message: String, val retry: Boolean)

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()

    fun consumeAddFailureNotice(id: Long) {
        _add.update { if (it.failureNotice?.id == id) it.copy(failureNotice = null) else it }
    }

    fun resetAdd() {
        _add.value = AddState()
        _routeChoice.value = null
    }

    // --- 쓰다 만 일정 (A4) — Partly AI-generated (Claude) ------------------------------

    private val _addResumePrompt = MutableStateFlow(false)

    /** "작성 중인 일정이 있어요. 이어서 쓸까요?" 를 띄울 때 true */
    val addResumePrompt: StateFlow<Boolean> = _addResumePrompt.asStateFlow()

    private fun addDraftCandidate(): AddState? =
        _add.value.takeIf { it.isUnsavedDraft } ?: pendingAddDraft

    /**
     * 홈의 "일정 추가". 쓰다 만 일정이 있으면 이어 쓸지 묻고(대화상자), 없으면 새 폼을 연다.
     * 입력은 저장에 성공하거나 사용자가 버릴 때만 지운다.
     */
    fun onAddEventClick() {
        if (addDraftCandidate() == null) {
            resetAdd()
            openAddEvent()
        } else {
            _addResumePrompt.value = true
        }
    }

    fun resumeAddDraft() {
        val draft = addDraftCandidate() ?: run {
            _addResumePrompt.value = false
            return
        }
        _add.value = draft.copy(submitting = false, failureNotice = null)
        _routeChoice.value = null
        pendingAddDraft = null
        AddDraftHolder.clear()
        _addResumePrompt.value = false
        openAddEvent()
    }

    /** "새로 쓰기". 이어 쓰기 대화상자를 닫고 새 폼(새 `client_request_id`)을 연다 */
    fun discardAddDraft() {
        resetAdd()
        pendingAddDraft = null
        AddDraftHolder.clear()
        _addResumePrompt.value = false
        openAddEvent()
    }

    /** 대화상자 바깥을 누르거나 뒤로 가면 아무것도 바꾸지 않고 닫는다 */
    fun dismissAddResumePrompt() {
        _addResumePrompt.value = false
    }

    /** 화면이 사라질 때 쓰다 만 일정을 프로세스 안의 보관함에 맡긴다(로그인 만료 → 다시 로그인 대비) */
    override fun onCleared() {
        // super.onCleared() 는 빈 메서드라 부르지 않는다(lint EmptySuperCall).
        addDraftCandidate()?.let { AddDraftHolder.put(draftOwner, it.copy(submitting = false, failureNotice = null)) }
    }

    // Partly AI-generated (Claude)
    fun onAddTitleChange(v: String) =
        _add.update { it.copy(title = v, error = null, fieldErrors = it.fieldErrors - AddField.TITLE) }
    fun onAddDateChange(v: LocalDate) =
        _add.update { it.copy(date = v, fieldErrors = it.fieldErrors - AddField.TIME) }
    fun onAddTimeChange(h: Int, m: Int) =
        _add.update { it.copy(hour = h, minute = m, fieldErrors = it.fieldErrors - AddField.TIME) }
    fun onAddRepeatDayToggle(day: Int) = _add.update {
        val days = it.repeatWeekdays
        it.copy(
            repeatWeekdays = if (day in days) days - day else days + day,
            fieldErrors = it.fieldErrors - AddField.TIME,
        )
    }
    fun onAddTagChange(key: String?) =
        _add.update { it.copy(tagKey = key, fieldErrors = it.fieldErrors - AddField.TAG) }
    fun onAddQueryChange(v: String) =
        _add.update { it.copy(place = it.place.withQuery(v)) }

    /**
     * 장소가 바뀌면 고른 경로를 버린다.
     *
     * 목적지가 달라졌는데 이전 경로 key 를 들고 있으면 서버가 그 노선을 찾지
     * 못해 `route_failed` 가 되거나, 운 나쁘면 엉뚱한 노선에 맞아 버린다.
     */
    fun onAddPlaceSelected(item: PlaceSearchItem?) = selectAddDestination(item, isHome = false)

    fun onAddDestinationHome() {
        val home = _state.value.homePlace ?: return
        selectAddDestination(home, isHome = true)
    }

    private fun selectAddDestination(item: PlaceSearchItem?, isHome: Boolean) {
        _add.update {
            it.copy(
                selectedPlace = item,
                destinationIsHome = isHome,
                place = it.place.copy(results = emptyList(), query = item?.name ?: ""),
                routeKey = null,
                routeLabel = null,
                fieldErrors = it.fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
            )
        }
        _routeChoice.value = null
    }

    // --- 일정 추가: 출발지 -------------------------------------------------

    // AI-generated (Claude)
    fun onAddOriginQueryChange(v: String) =
        _add.update { it.copy(originPlace = it.originPlace.withQuery(v), originNotice = null) }

    // AI-generated (Claude)
    fun searchAddOriginPlaces() = searchAddOriginPlaces(page = 1)

    // AI-generated (Claude)
    fun loadMoreAddOriginPlaces() {
        if (_add.value.originPlace.canLoadMore) {
            searchAddOriginPlaces(page = _add.value.originPlace.page + 1)
        }
    }

    // AI-generated (Claude)
    fun onAddOriginSortChange(sort: String) = searchAddOriginPlaces(page = 1, sort = sort)

    // AI-generated (Claude)
    private fun searchAddOriginPlaces(page: Int, sort: String? = null) = runPlaceSearch(
        get = { _add.value.originPlace },
        set = { next -> _add.update { it.copy(originPlace = next) } },
        page = page,
        sort = sort,
    )

    // Partly AI-generated (Claude)
    /**
     * 출발지를 바꾼다. null 이면 집이다.
     *
     * 출발지가 달라지면 소요시간과 노선이 전부 달라지므로 고른 경로를 버린다.
     * 남겨 두면 없는 노선 key 를 들고 일정을 만들어 `route_failed` 가 된다.
     */
    fun onAddOriginSelected(item: PlaceSearchItem?) {
        _add.update {
            it.copy(
                origin = item,
                originLabel = item?.name,
                originPlaceId = null,
                originPlace = PlaceSearch(),
                originNotice = null,
                routeKey = null,
                routeLabel = null,
                fieldErrors = it.fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
                // 집으로 되돌리면 스위치가 사라진다. 값도 지운다(include-prep-api.md §5 ①).
                includePrep = if (item == null) false else it.includePrep,
            )
        }
        _routeChoice.value = null
    }

    /** "여기서 자고 출발해요 (준비 시간 포함)" */
    fun onAddIncludePrepChange(on: Boolean) = _add.update { it.copy(includePrep = on && it.origin != null) }

    // Partly AI-generated (Claude)
    /**
     * 출발지로 다른 집을 고른다(Figma ⑯ 변형 `498:9` ②). 그 집에서 자고 나서는 날이므로 준비 시간을
     * 넣은 채로 둔다(`include_prep=true`). 사용자가 끌 수 있다.
     */
    fun onAddOriginSavedHome(home: SavedHome) {
        onAddOriginSelected(home.toPlace())
        // 서버에 저장한 집이면 id 를 함께 보낸다. 서버는 저장한 집 출발을 집으로 쳐서 준비 시간을 넣는다.
        _add.update { it.copy(includePrep = true, originPlaceId = home.serverId) }
    }

    // --- 다른 집 (todo §2 "집 여러 개", 기기에만 저장) — Partly AI-generated (Claude) -------

    private fun reloadSavedHomes() {
        val homes = runCatching { savedHomesStore.all() }
            .onFailure { Log.w(TAG, "다른 집 목록을 읽지 못했다", it) }
            .getOrDefault(emptyList())
        _state.update { it.copy(savedHomes = homes, savedHomesOnServer = useSavedPlacesServer()) }
    }

    // Partly AI-generated (Claude)
    /**
     * 다른 집을 서버와 맞춘다(B2). 기기 사본이 아직 서버에 올라가지 않았으면 한 번에 올리고(`bulk`), 아니면
     * 서버 목록을 받아 사본을 갱신한다. **서버가 확인하기 전에는 기기 목록을 바꾸지 않는다** — 통신 실패,
     * 5xx, 이전 서버(404·405)면 기기 목록이 그대로 남는다.
     */
    private fun syncSavedHomes() {
        if (savedHomesSyncJob?.isActive == true) return
        savedHomesSyncJob = viewModelScope.launch {
            val local = runCatching { savedHomesStore.all() }.getOrDefault(emptyList())
            val upload = local.isNotEmpty() && !savedHomesStore.synced()
            val result = if (upload) savedPlaces.bulk(local) else savedPlaces.list()
            when (result) {
                is SavedPlacesResult.Ok -> {
                    // 로그아웃 중이라 쓰지 못하면 아무것도 바꾸지 않는다(다음 로그인이 새로 맞춘다).
                    if (!savedHomesStore.replaceSynced(result.homes)) return@launch
                    savedPlacesMode = SavedPlacesMode.SERVER
                    _state.update { it.copy(savedHomes = result.homes, savedHomesOnServer = true) }
                    if (upload && result.skippedOverLimit > 0) {
                        notify("저장한 집이 5곳을 넘어 ${result.skippedOverLimit}곳은 서버에 올리지 못했어요")
                    }
                }

                SavedPlacesResult.Unsupported -> {
                    savedPlacesMode = SavedPlacesMode.DEVICE
                    _state.update { it.copy(savedHomesOnServer = false) }
                }

                is SavedPlacesResult.Failure -> {
                    Log.w(TAG, "다른 집 동기화 실패: ${result.status} ${result.code}")
                    // 서버가 요청 자체를 거절(400)하면 다시 보내도 같다. 이번 실행에서는 기기 목록으로 두고
                    // 다음 실행에 다시 시도한다. 통신 실패·5xx 는 상태를 그대로 두고 다음 기회에 다시 한다.
                    if (upload && result.status == 400) {
                        savedPlacesMode = SavedPlacesMode.DEVICE
                        _state.update { it.copy(savedHomesOnServer = false) }
                    }
                }
            }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 처음 서버에 올리는 중이면 안내만 띄우고 true 를 돌려준다(code-review m1). 이때 더하거나 지운 집은
     * 업로드 응답이 덮어쓰므로 집을 바꾸는 동작은 모두 이 확인을 먼저 한다.
     */
    private fun blockedByFirstUpload(): Boolean {
        val running = savedHomesFirstUploadRunning(
            syncJobActive = savedHomesSyncJob?.isActive == true,
            synced = runCatching { savedHomesStore.synced() }.getOrDefault(false),
        )
        if (running) notify(SAVED_HOMES_UPLOADING_NOTICE)
        return running
    }

    /** "+ 다른 집 추가". 집 주소 화면(⑭)을 이름 칸과 함께 연다 */
    fun openExtraHomeSetup() {
        if (blockedByFirstUpload()) return
        _homeSetup.value = HomeSetupState(target = HomeTarget.EXTRA)
        openHomeSetup()
    }

    fun onHomeLabelChange(value: String) =
        _homeSetup.update { it.copy(label = value.take(SavedHomes.LABEL_MAX), error = null, labelError = null) }

    fun removeSavedHome(id: String) {
        if (blockedByFirstUpload()) return
        val target = _state.value.savedHomes.firstOrNull { it.id == id }
        val serverId = target?.serverId
        if (serverId != null && useSavedPlacesServer()) {
            removeSavedHomeOnServer(id, serverId)
            return
        }
        val next = SavedHomes.remove(_state.value.savedHomes, id)
        if (!savedHomesStore.replace(next)) {
            notify("다른 집을 지우지 못했습니다")
            return
        }
        _state.update { it.copy(savedHomes = next) }
        notify("다른 집을 지웠습니다")
    }

    // Partly AI-generated (Claude)
    /** 서버에 저장한 집을 지운다. 서버가 지운 뒤에만 목록에서 뺀다 */
    private fun removeSavedHomeOnServer(id: String, serverId: Long) {
        if (_state.value.savedHomeBusyId != null) return
        _state.update { it.copy(savedHomeBusyId = id) }
        viewModelScope.launch {
            when (val result = savedPlaces.delete(serverId)) {
                is SavedPlacesResult.Ok -> {
                    val next = SavedHomes.remove(_state.value.savedHomes, id)
                    savedHomesStore.replaceSynced(next)
                    _state.update { it.copy(savedHomes = next, savedHomeBusyId = null) }
                    notify("다른 집을 지웠습니다")
                }

                is SavedPlacesResult.Failure -> {
                    _state.update { it.copy(savedHomeBusyId = null) }
                    notify("다른 집을 지우지 못했어요. ${result.message}")
                }

                SavedPlacesResult.Unsupported -> _state.update { it.copy(savedHomeBusyId = null) }
            }
        }
    }

    /**
     * "기본으로". 서버의 집을 이 집으로 바꾸고(알람이 다시 계산된다) 예전 집을 목록에 남긴다. 서버가
     * 받지 못하면 아무것도 바꾸지 않는다. 서버에 저장한 집이면 서버가 기본 집과 자리를 바꾼다.
     */
    fun makeSavedHomeDefault(id: String) {
        val current = _state.value
        if (current.savedHomeBusyId != null) return
        if (blockedByFirstUpload()) return
        val home = current.savedHomes.firstOrNull { it.id == id } ?: return
        val serverId = home.serverId
        if (serverId != null && useSavedPlacesServer()) {
            makeSavedHomeDefaultOnServer(home, serverId)
            return
        }
        _state.update { it.copy(savedHomeBusyId = id) }
        viewModelScope.launch {
            when (val result = repository.setHome(label = home.name, lat = home.lat, lng = home.lng)) {
                is EventRepository.Result.Success -> {
                    val previous = current.homePlace?.let { place ->
                        SavedHome(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "이전 집",
                            name = place.name,
                            address = place.address,
                            lat = place.lat,
                            lng = place.lng,
                        )
                    }
                    val next = SavedHomes.promote(_state.value.savedHomes, id, previous)
                    savedHomesStore.replace(next)
                    _state.update { it.copy(savedHomes = next, savedHomeBusyId = null) }
                    notify(
                        "기본 집을 ${home.label}${com.swpp.wakeup.domain.model.RerouteAlert.roParticle(home.label)} " +
                            "바꿨습니다. 알람을 다시 계산합니다"
                    )
                    refresh()
                }

                is EventRepository.Result.Failure -> {
                    _state.update { it.copy(savedHomeBusyId = null) }
                    notify("기본 집을 바꾸지 못했습니다. ${result.message}")
                }
            }
        }
    }

    // Partly AI-generated (Claude)
    private fun makeSavedHomeDefaultOnServer(home: SavedHome, serverId: Long) {
        _state.update { it.copy(savedHomeBusyId = home.id) }
        viewModelScope.launch {
            when (val result = savedPlaces.makeDefault(serverId)) {
                is SavedPlacesResult.Ok -> {
                    savedHomesStore.replaceSynced(result.homes)
                    _state.update { it.copy(savedHomes = result.homes, savedHomeBusyId = null) }
                    notify(
                        "기본 집을 ${home.label}${com.swpp.wakeup.domain.model.RerouteAlert.roParticle(home.label)} " +
                            "바꿨습니다. 알람을 다시 계산합니다"
                    )
                    refresh()
                }

                is SavedPlacesResult.Failure -> {
                    _state.update { it.copy(savedHomeBusyId = null) }
                    if (result.code == SavedPlacesRepository.CODE_NOT_FOUND) {
                        // 다른 기기에서 지운 집이다. 목록을 다시 받는다.
                        syncSavedHomes()
                        notify("이 집을 찾지 못했어요. 목록을 다시 불러왔어요")
                    } else {
                        notify("기본 집을 바꾸지 못했습니다. ${result.message}")
                    }
                }

                SavedPlacesResult.Unsupported -> _state.update { it.copy(savedHomeBusyId = null) }
            }
        }
    }

    private fun SavedHome.toPlace(): PlaceSearchItem = PlaceSearchItem(
        kakaoPlaceId = null,
        name = label.ifBlank { name },
        address = address ?: name,
        lat = lat,
        lng = lng,
        category = null,
    )

    // Partly AI-generated (Claude)
    /**
     * 일정 수정 화면을 연다(todo §2 "알람 결정 후 수정 UI", event-edit-api.md). ⑫ 를 수정 모드로
     * 다시 쓴다. 서버의 지금 값을 채워 두고 저장할 때 바뀐 필드만 보낸다.
     */
    fun openEditEvent(eventId: Long) {
        if (_add.value.submitting) return
        viewModelScope.launch {
            when (val loaded = repository.loadEvent(eventId)) {
                is EventRepository.Result.Failure -> notify("일정을 불러오지 못했습니다. ${loaded.message}")
                is EventRepository.Result.Success -> {
                    val dto = loaded.data
                    val base = EventEditValues.from(dto, _state.value.homePlace)
                    if (base == null) {
                        notify("일정 시각을 읽지 못해 고칠 수 없습니다")
                        return@launch
                    }
                    val start = base.startAt.atZoneSameInstant(ZoneId.systemDefault())
                    val plan = dto.alarmPlan
                    // 쓰다 만 새 일정이 있으면 덮어쓰기 전에 맡겨 둔다. "일정 추가" 에서 이어 쓸 수 있다(A4).
                    if (_add.value.isUnsavedDraft) {
                        pendingAddDraft = _add.value.copy(submitting = false, failureNotice = null)
                    }
                    _add.value = AddState(
                        title = base.title,
                        date = start.toLocalDate(),
                        hour = start.hour,
                        minute = start.minute,
                        tagKey = base.tagKey,
                        place = PlaceSearch(query = base.place?.name.orEmpty()),
                        selectedPlace = base.place,
                        destinationIsHome = base.destinationIsHome,
                        routeKey = base.routeKey,
                        routeLabel = plan?.travelMinutes?.let { minutes ->
                            listOfNotNull("${minutes}분", plan.travelMode?.takeIf(String::isNotBlank))
                                .joinToString(" · ")
                        },
                        origin = base.origin,
                        originLabel = base.origin?.name,
                        includePrep = base.includePrep == true,
                        originPlaceId = base.originPlaceId,
                        editingEventId = eventId,
                        editBase = base,
                        recurring = dto.recurrenceId != null,
                    )
                    _routeChoice.value = null
                    loadEventTags()
                    _nav.update { it.copy(stack = it.stack + AppRoute.EditEvent(eventId), forward = true) }
                }
            }
        }
    }

    // Partly AI-generated (Claude)
    /** 수정 저장. 바뀐 필드만 보내고, 저장되면 이 화면의 계획과 홈·기기 알람을 다시 맞춘다. */
    private fun submitEdit(current: AddState, startAt: OffsetDateTime) {
        val eventId = current.editingEventId ?: return
        val base = current.editBase ?: return
        val edited = EventEditValues(
            title = current.title,
            startAt = startAt,
            tagKey = current.tagKey,
            place = current.selectedPlace,
            destinationIsHome = current.destinationIsHome,
            origin = current.origin,
            routeKey = current.routeKey,
            // 저장한 집에서는 끈 스위치도 false 로 보낸다(서버가 저장한 집을 집으로 쳐서 준비 시간을 넣는다).
            includePrep = createIncludePrep(current.includePrep, current.origin != null, current.originPlaceId),
            originPlaceId = current.originPlaceId.takeIf { current.origin != null },
        )
        val patch = EventEditPatch.build(base, edited)
        if (patch.size() == 0) {
            _add.update { it.copy(done = true, doneMessage = "바뀐 항목이 없음") }
            return
        }
        _add.update { it.copy(submitting = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            when (val result = repository.updateEvent(eventId, patch)) {
                is EventRepository.Result.Success -> {
                    if (result.data.plan != null) {
                        ++planLoadGeneration
                        _plan.value = result.data.plan
                    }
                    // 계산하지 못한 계획이면 결정 화면(저장 뒤 돌아가는 곳)의 "다시 계산" 으로 이어진다(A6·A7).
                    val failed = result.data.plan?.status == AlarmPlanDto.STATUS_ROUTE_FAILED
                    _add.update {
                        it.copy(
                            submitting = false,
                            done = true,
                            doneMessage = if (failed) EDIT_ROUTE_FAILED_MESSAGE else "일정을 수정했습니다",
                        )
                    }
                    // 다른 날로 옮기면 그날 다른 일정의 켬/끔도 바뀐다(event-edit-api.md §4).
                    refresh()
                }

                is EventRepository.Result.Failure -> onSaveFailed(result, editing = true)
            }
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 저장 실패를 화면에 푼다: 줄별 문구·버튼 위 빨간 줄·snackbar. 입력은 그대로 둔다(A1-A4).
     * 로그인이 끝난 경우(세션 만료 표시가 올라 있음)는 화면이 곧 로그인으로 나가므로 snackbar 를 건너뛴다.
     */
    private fun onSaveFailed(failure: EventRepository.Result.Failure, editing: Boolean) {
        val e = saveErrorOf(failure, editing)
        val skipNotice = e.kind == SaveErrorKind.AUTH && SessionState.expired.value
        _add.update {
            it.copy(
                submitting = false,
                error = e.line,
                fieldErrors = e.fields,
                failureNotice = if (skipNotice) null else AddFailureNotice(++addNoticeSeq, e.snackbar, e.retry),
            )
        }
    }

    // AI-generated (Claude)
    /**
     * 출발지를 현재 위치로 잡는다. 사용자가 출발지 검색의 "현재 위치 사용" 을
     * 눌렀을 때만 부른다. 위치를 못 구하면 값을 바꾸지 않고 이유를 적는다.
     */
    fun useCurrentLocationForAddOrigin() {
        if (_add.value.originLocating) return
        _add.update { it.copy(originLocating = true, originNotice = null) }
        viewModelScope.launch {
            val here = detectCurrentPlace()
            if (here == null) {
                _add.update {
                    it.copy(
                        originLocating = false,
                        originNotice = "현재 위치를 확인할 수 없음. 위치 권한과 GPS 를 확인하거나 직접 검색할 것",
                    )
                }
            } else {
                _add.update { it.copy(originLocating = false) }
                onAddOriginSelected(here)
            }
        }
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

        if (current.isEditing) {
            submitEdit(current, startAt)
            return
        }

        _add.update { it.copy(submitting = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val result = repository.createEvent(
                title = current.title,
                startAt = startAt,
                place = current.selectedPlace,
                tagKey = current.tagKey,
                routeKey = current.routeKey,
                origin = current.origin,
                destinationIsHome = current.destinationIsHome,
                repeatWeekdays = current.repeatWeekdays,
                includePrep = current.includePrep,
                originPlaceId = current.originPlaceId,
                // 다시 시도는 같은 값이라 서버가 중복 일정을 만들지 않는다(A5). 200 도 성공이다.
                clientRequestId = current.clientRequestId,
            )
            when (result) {
                is EventRepository.Result.Success -> {
                    // 일정은 저장됐지만 알람을 계산하지 못했으면 그렇게 알린다(A6).
                    val failed = result.data.alarmPlan?.status == AlarmPlanDto.STATUS_ROUTE_FAILED
                    _add.update {
                        it.copy(
                            submitting = false,
                            done = true,
                            doneMessage = if (failed) ADD_ROUTE_FAILED_MESSAGE else null,
                            routeFailedEventId = result.data.id.takeIf { failed },
                        )
                    }
                    refresh()
                }

                // 줄별 문구·빨간 줄·snackbar. 입력은 그대로 둔다.
                is EventRepository.Result.Failure -> onSaveFailed(result, editing = false)
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
     * 정확히 그것이다. 시계 다이얼은 값이 없으면 0:00 에서 열린다 — 제안값이 아니고,
     * 범위 밖이라 그대로 확인할 수 없다. 값은 반드시 사용자가 고른 것이어야 한다.
     *
     * (Partly AI-generated (Claude): [fromSettings], [range])
     */
    data class PrepOnboardingState(
        val minutes: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
        /**
         * 설정(⑯)에서 고치러 들어왔는가. 버튼이 "저장" 이 되고 "나중에 입력" 이 숨는다.
         * 뒤로 = 저장하지 않고 닫기다.
         */
        val fromSettings: Boolean = false,
    ) {
        /** 다이얼이 받아들이는 범위(분). R-6 으로 서버와 맞출 때까지 앱 상수다. */
        val range: IntRange get() = PREP_MIN..PREP_MAX

        /** 범위를 벗어난 값은 없는 것으로 본다. 5분 미만·4시간 초과는 입력 실수다. */
        val parsed: Int? get() = minutes.toIntOrNull()?.takeIf { it in range }
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
        _prepOnboarding.value = PrepOnboardingState(
            minutes = _state.value.onboardingPrepMin?.toString().orEmpty(),
            fromSettings = true,
        )
        _nav.update { it.copy(stack = it.stack + AppRoute.PrepOnboarding, forward = true) }
    }

    fun resetPrepOnboarding() {
        _prepOnboarding.value = PrepOnboardingState()
    }

    /** 다이얼에서 고른 합계(분)를 받는다. 범위 판정은 [PrepOnboardingState.parsed] 가 한다. */
    fun onPrepOnboardingChange(v: String) =
        _prepOnboarding.update { it.copy(minutes = v.filter(Char::isDigit).take(3), error = null) }

    fun submitPrepOnboarding() {
        val minutes = _prepOnboarding.value.parsed ?: return
        _prepOnboarding.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.setOnboardingPrep(minutes)) {
                is EventRepository.Result.Success -> {
                    _state.update { it.copy(onboardingPrepMin = result.data.onboardingPrepMin) }
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
        _prepOnboarding.value = PrepOnboardingState(done = true)
    }

    /**
     * 아직 받지 못한 온보딩 화면을 밀어 넣는다. 집 주소 → 준비 시간 → 캘린더 연동 순이다.
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
     *
     * 캘린더 연동(⑱)은 답하지 않아도 다시 묻지 않는다. 없어도 알람이 계산되는 값이라
     * 이 기기·계정에서 한 번만 보여 준다(Partly AI-generated (Claude)).
     */
    private fun maybeAskOnboarding(data: EventRepository.HomeData) {
        // 오프라인 사본으로는 판단하지 않는다. 캐시에 값이 없을 뿐인데
        // 물어보면, 이미 답한 사용자에게 같은 질문을 다시 하게 된다.
        if (data.fromCache) return

        // 순서 규칙은 [OnboardingQueue] 에 있다(⑭ → ⑮ → ⑱ → 홈). 캘린더 연동은 다른
        // 온보딩 화면이 뜰 때 그 끝에 한 번만 붙는다 — 가입을 마친 사용자에게는 묻지 않는다.
        val pending = OnboardingQueue.pushOrder(
            askHome = !data.hasHome && !homeOnboardingAsked,
            askPrep = data.onboardingPrepMin == null && !prepOnboardingAsked,
            askCalendar = !calendarOnboardingAsked && !calendarLinkStore.wasAsked(),
        )
        if (pending.isEmpty()) return
        if (AppRoute.PrepOnboarding in pending) prepOnboardingAsked = true
        if (AppRoute.HomeSetup in pending) homeOnboardingAsked = true
        if (AppRoute.CalendarLink in pending) {
            calendarOnboardingAsked = true
            // 가져오기 화면에서 이미 켠 것이 있으면 체크해 둔 채 보여 준다.
            _calendarOnboarding.value = CalendarOnboardingState(selected = calendarLinkStore.linkedIds())
            loadCalendarSources()
        }

        // 온보딩으로 여는 것이라 화면 문구가 달라진다. 밀어 넣기 전에 정한다.
        //
        // `_homeSetup` 선언이 이 함수보다 아래에 있어도 괜찮다. 이 함수는
        // `refresh()` 가 띄운 코루틴 안에서만 불리고, 코루틴 본문은 생성자가
        // 끝난 뒤에 돈다. **동기 호출로 바꾸면 그때 깨진다** — 근거는
        // `HomeViewModelInitOrderTest` 에 있다.
        if (pending.contains(AppRoute.PrepOnboarding)) {
            // 설정에서 열었다가 시스템 뒤로 나간 상태(fromSettings = true)가 남아 있으면
            // 온보딩에서 "나중에 입력" 이 사라진다. 온보딩으로 열 때는 새로 시작한다.
            _prepOnboarding.value = PrepOnboardingState()
        }
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
        // Partly AI-generated (Claude)
        /** 서버가 기본 집과 같은 곳이라고 거절했을 때 이름 칸 아래에 보일 문구(Figma F-50 `528:105`) */
        val labelError: String? = null,
        val done: Boolean = false,
        /**
         * 닫힌 이유가 저장인가.
         *
         * 건너뛰기도 [done] 을 세우므로 이것 없이는 구분할 수 없다. 구분하지
         * 않으면 건너뛴 사용자에게 "저장했습니다" 를 띄운다.
         */
        val saved: Boolean = false,
        /** 기본 집(서버)인가, 기기에만 둘 다른 집인가(todo §2 "집 여러 개") */
        val target: HomeTarget = HomeTarget.PRIMARY,
        /** 다른 집의 이름("본가"). 비우면 장소 이름을 쓴다 */
        val label: String = "",
    ) {
        val canSubmit: Boolean get() = !submitting && selected != null
    }

    /** 집 주소 화면이 무엇을 저장하는가 */
    enum class HomeTarget { PRIMARY, EXTRA }

    private val _homeSetup = MutableStateFlow(HomeSetupState())
    val homeSetup: StateFlow<HomeSetupState> = _homeSetup.asStateFlow()

    fun resetHomeSetup() {
        _homeSetup.value = HomeSetupState()
    }

    fun onHomeQueryChange(v: String) =
        _homeSetup.update { it.copy(place = it.place.withQuery(v)) }

    // Partly AI-generated (Claude)
    /**
     * 집 후보를 고른다. Figma ⑭ 처럼 **검색 결과 목록을 남겨 두고** 고른 줄에 ✓ 를
     * 표시한다 — 다른 후보로 바로 바꿀 수 있어야 한다. 검색어도 그대로 둔다.
     */
    fun onHomePlaceSelected(item: PlaceSearchItem?) = _homeSetup.update {
        it.copy(selected = item, labelError = null)
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

    private fun saveExtraHomeOnDevice(home: SavedHome) {
        val next = SavedHomes.add(_state.value.savedHomes, home)
        if (!savedHomesStore.replace(next)) {
            _homeSetup.update { it.copy(submitting = false, error = "이 기기에 저장하지 못했습니다. 다시 로그인해 주세요") }
            return
        }
        _state.update { it.copy(savedHomes = next) }
        _homeSetup.update { it.copy(submitting = false, done = true, saved = true) }
    }

    // Partly AI-generated (Claude)
    /**
     * 다른 집을 서버에 저장한다. 서버가 받은 뒤에만 목록에 넣는다. 서버가 이 API 를 모르면(이전 서버)
     * 기기에 저장한다. 같은 요청을 다시 보내도 늘지 않게 기기 id 를 `client_uuid` 로 보낸다.
     */
    private fun saveExtraHomeOnServer(home: SavedHome) {
        _homeSetup.update { it.copy(submitting = true, error = null, labelError = null) }
        viewModelScope.launch {
            val input = SavedPlaceInput(
                label = home.label,
                lat = home.lat,
                lng = home.lng,
                name = home.name,
                address = home.address,
                clientUuid = home.id,
            )
            when (val result = savedPlaces.create(input)) {
                is SavedPlacesResult.Ok -> {
                    val next = SavedHomes.merge(_state.value.savedHomes, result.homes.first())
                    if (!savedHomesStore.replaceSynced(next)) {
                        _homeSetup.update { it.copy(submitting = false, error = "이 기기에 저장하지 못했습니다. 다시 로그인해 주세요") }
                        return@launch
                    }
                    _state.update { it.copy(savedHomes = next) }
                    _homeSetup.update { it.copy(submitting = false, done = true, saved = true) }
                }

                SavedPlacesResult.Unsupported -> {
                    savedPlacesMode = SavedPlacesMode.DEVICE
                    _state.update { it.copy(savedHomesOnServer = false) }
                    saveExtraHomeOnDevice(home)
                }

                is SavedPlacesResult.Failure ->
                    _homeSetup.update { it.withAddFailure(result) }
            }
        }
    }

    fun submitHomeSetup() {
        val current = _homeSetup.value
        val place = current.selected ?: return

        // 다른 집은 기기에만 둔다. 서버의 집(알람 계산의 기본 출발지)은 그대로다.
        if (current.target == HomeTarget.EXTRA) {
            if (blockedByFirstUpload()) return
            val home = SavedHome(
                id = java.util.UUID.randomUUID().toString(),
                label = SavedHomes.labelOf(current.label, place.name),
                name = place.name,
                address = place.address,
                lat = place.lat,
                lng = place.lng,
            )
            if (useSavedPlacesServer()) {
                saveExtraHomeOnServer(home)
            } else {
                saveExtraHomeOnDevice(home)
            }
            return
        }

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

        const val ADD_ROUTE_FAILED_MESSAGE = "일정은 저장했어요. 알람을 계산하지 못해 다시 계산이 필요해요"
        const val EDIT_ROUTE_FAILED_MESSAGE = "일정을 고쳤어요. 알람을 계산하지 못해 다시 계산이 필요해요"

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

        /**
         * 블록 **정의**를 고쳤을 때의 안내.
         *
         * 서버가 블록 추가·수정·삭제 뒤 앞으로의 일정을 카카오 호출 없이 다시 계산한다
         * (todo §1 "다시 계산 버튼 없애도 되게", `planning/refresh.py`). 저장 뒤 홈을 다시
         * 받아 기기 알람도 맞춘다. (Partly AI-generated (Claude))
         */
        const val DEFINITION_NOTICE = "저장함 · 앞으로의 알람에 반영했음"

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
