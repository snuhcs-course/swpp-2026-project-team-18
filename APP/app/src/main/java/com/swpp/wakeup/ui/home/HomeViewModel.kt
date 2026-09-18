package com.swpp.wakeup.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.sensing.TripObservationQueue
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.nav.NavState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 홈 화면 상태와 `MainActivity` 안의 화면 이동.
 *
 * **표본 데이터를 쓰지 않는다.** 모든 일정은 `/api/events` 에서 온다. 새 계정은
 * 일정이 없으므로 빈 목록이고, 사용자가 추가한 만큼만 보인다.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application)
    private val repository = EventRepository()

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

    /** 알람 결정 화면이 보고 있는 계획. */
    private val _plan = MutableStateFlow<AlarmPlanView?>(null)
    val plan: StateFlow<AlarmPlanView?> = _plan.asStateFlow()

    init {
        refresh()
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
                            unplannedCount = result.data.unplannedCount,
                        )
                    }
                    syncAlarms(result.data.schedules)
                }

                is EventRepository.Result.Failure -> _state.update {
                    it.copy(loading = false, error = result.message)
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
    private fun syncAlarms(schedules: List<AlarmSchedule>) {
        val app = getApplication<Application>()
        val scheduler = AlarmScheduler(app)
        val queue = TripObservationQueue(app)

        runCatching { scheduler.sync(schedules) }
            .onFailure { Log.e(TAG, "알람 등록 실패", it) }

        val registered = runCatching { scheduler.registered() }.getOrDefault(emptyList())

        _state.update {
            it.copy(
                registeredAlarms = registered.size,
                nextRegisteredLabel = registered.firstOrNull()?.alarmLabel,
                pendingObservations = queue.pendingCount,
            )
        }

        // 지하철에서 판정된 관측이 큐에 남아 있을 수 있다. 연결됐으니 올린다.
        viewModelScope.launch {
            val sent = queue.flush()
            if (sent > 0) {
                _state.update { it.copy(pendingObservations = queue.pendingCount) }
            }
        }
    }

    fun logout() = tokenStore.clear()

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
        _nav.update { it.copy(stack = it.stack + AppRoute.AddEvent, forward = true) }
    }

    fun openHomeSetup() {
        _nav.update { it.copy(stack = it.stack + AppRoute.HomeSetup, forward = true) }
    }

    // --- 경로 선택 (Figma ⑬) ---------------------------------------------

    data class RouteState(
        val loading: Boolean = true,
        val error: String? = null,
        val choice: RouteChoice? = null,
    )

    private val _routeChoice = MutableStateFlow<RouteState?>(null)
    val routeChoice: StateFlow<RouteState?> = _routeChoice.asStateFlow()

    /**
     * 경로 후보를 받아 ⑬ 으로 이동한다.
     *
     * 서버가 외부 API 를 최대 4번 부르므로 이미 받아 둔 후보가 있으면 다시
     * 부르지 않는다. 장소를 바꾸면 [onAddPlaceSelected] 가 캐시를 버린다.
     */
    fun openRouteChoice() {
        val place = _add.value.selectedPlace ?: return
        _nav.update { it.copy(stack = it.stack + AppRoute.RouteChoice, forward = true) }

        val cached = _routeChoice.value?.choice
        if (cached != null) return

        _routeChoice.value = RouteState(loading = true)
        viewModelScope.launch {
            when (val result = repository.routeCandidates(place)) {
                is EventRepository.Result.Success -> {
                    val choice = result.data.copy(
                        selectedKey = _add.value.routeKey ?: result.data.selectedKey
                    )
                    _routeChoice.value = RouteState(loading = false, choice = choice)
                }

                is EventRepository.Result.Failure ->
                    _routeChoice.value = RouteState(loading = false, error = result.message)
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
        _routeChoice.value = null
        val place = _add.value.selectedPlace ?: return
        _routeChoice.value = RouteState(loading = true)
        viewModelScope.launch {
            when (val result = repository.routeCandidates(place)) {
                is EventRepository.Result.Success ->
                    _routeChoice.value = RouteState(loading = false, choice = result.data)

                is EventRepository.Result.Failure ->
                    _routeChoice.value = RouteState(loading = false, error = result.message)
            }
        }
    }

    /** 고른 경로를 일정 추가 폼에 반영하고 돌아간다. */
    fun confirmRoute() {
        val choice = _routeChoice.value?.choice ?: return
        val option = choice.options.firstOrNull { it.key == choice.selectedKey }
        _add.update {
            it.copy(
                routeKey = option?.key,
                routeLabel = option?.let { o -> "${o.minutesLabel} · ${o.mode}" },
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
        _plan.value = null
        viewModelScope.launch {
            when (val result = repository.loadPlan(eventId)) {
                is EventRepository.Result.Success -> _plan.value = result.data
                is EventRepository.Result.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    // --- 일정 추가 --------------------------------------------------------

    data class AddState(
        val title: String = "",
        val date: LocalDate = LocalDate.now().plusDays(1),
        val hour: Int = 9,
        val minute: Int = 0,
        /** null 이면 "기타" — 서버에서 태그를 비우고 프로필 기본 τ 를 쓴다 */
        val tagKey: String? = "class",
        val query: String = "",
        val searching: Boolean = false,
        val results: List<PlaceSearchItem> = emptyList(),
        val selectedPlace: PlaceSearchItem? = null,

        /** ⑬ 경로 선택에서 고른 값. 비어 있으면 서버가 최단 경로를 쓴다 */
        val routeKey: String? = null,
        /** 고른 경로 요약. "23분 · 지하철+도보+버스" */
        val routeLabel: String? = null,

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
    fun onAddQueryChange(v: String) = _add.update { it.copy(query = v) }

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
                results = emptyList(),
                query = item?.name ?: "",
                routeKey = null,
                routeLabel = null,
            )
        }
        _routeChoice.value = null
    }

    fun searchPlaces() {
        val q = _add.value.query.trim()
        if (q.isBlank()) return
        _add.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.searchPlaces(q)) {
                is EventRepository.Result.Success ->
                    _add.update { it.copy(searching = false, results = result.data) }

                is EventRepository.Result.Failure ->
                    _add.update { it.copy(searching = false, error = result.message) }
            }
        }
    }

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

    // --- 집 위치 설정 -----------------------------------------------------

    data class HomeSetupState(
        val query: String = "",
        val searching: Boolean = false,
        val results: List<PlaceSearchItem> = emptyList(),
        val selected: PlaceSearchItem? = null,
        val prepMinutes: String = "30",
        val submitting: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    ) {
        val canSubmit: Boolean get() = !submitting && selected != null
    }

    private val _homeSetup = MutableStateFlow(HomeSetupState())
    val homeSetup: StateFlow<HomeSetupState> = _homeSetup.asStateFlow()

    fun resetHomeSetup() {
        _homeSetup.value = HomeSetupState()
    }

    fun onHomeQueryChange(v: String) = _homeSetup.update { it.copy(query = v) }
    fun onHomePlaceSelected(item: PlaceSearchItem?) =
        _homeSetup.update { it.copy(selected = item, results = emptyList(), query = item?.name ?: "") }

    fun onHomePrepChange(v: String) =
        _homeSetup.update { it.copy(prepMinutes = v.filter(Char::isDigit).take(3)) }

    fun searchHomePlaces() {
        val q = _homeSetup.value.query.trim()
        if (q.isBlank()) return
        _homeSetup.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.searchPlaces(q)) {
                is EventRepository.Result.Success ->
                    _homeSetup.update { it.copy(searching = false, results = result.data) }

                is EventRepository.Result.Failure ->
                    _homeSetup.update { it.copy(searching = false, error = result.message) }
            }
        }
    }

    fun submitHomeSetup() {
        val current = _homeSetup.value
        val place = current.selected ?: return

        _homeSetup.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = repository.setHome(
                label = place.name,
                lat = place.lat,
                lng = place.lng,
                prepMinutes = current.prepMinutes.toIntOrNull(),
            )
            when (result) {
                is EventRepository.Result.Success -> {
                    _homeSetup.update { it.copy(submitting = false, done = true) }
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
    }
}
