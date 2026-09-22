package com.swpp.wakeup.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.RoutineRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.RoutineEditorState
import com.swpp.wakeup.sensing.CurrentLocation
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

    /**
     * 오프라인 캐시. **로그인한 계정으로 범위가 묶인다.**
     *
     * 이메일을 넘기지 않으면 캐시가 아무 일도 하지 않는다. 소유자 없는 행은
     * 다음 사용자가 읽을 수 있고, 캐시에는 집 위치와 다니는 장소가 들어 있다.
     */
    private val cache = OfflineCache(application, tokenStore.email)

    private val repository = EventRepository(cache = cache)
    private val routines = RoutineRepository(cache = cache)

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
                            unplannedCount = result.data.unplannedCount,
                            offline = result.data.fromCache,
                            offlineAgeLabel = result.data.ageLabel,
                        )
                    }
                    syncAlarms(result.data.schedules, fromCache = result.data.fromCache)
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
        tokenStore.clear()
        OfflineCache.wipeDetached(getApplication())
        // 배경 작업도 거둔다. 워커가 로그인 여부를 확인해 아무 일도 하지 않지만,
        // 로그아웃한 기기를 6시간마다 깨울 이유가 없다.
        JitWork.cancelAll(getApplication())
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
        _nav.update { it.copy(stack = it.stack + AppRoute.AddEvent, forward = true) }
    }

    fun openHomeSetup() {
        _nav.update { it.copy(stack = it.stack + AppRoute.HomeSetup, forward = true) }
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

        /** 출발지 검색 상태. 목적지 검색과 독립이다 */
        val originQuery: String = "",
        val originSearching: Boolean = false,
        val originResults: List<PlaceSearchItem> = emptyList(),
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
        return when (val r = repository.reversePlace(fix.latitude, fix.longitude)) {
            is EventRepository.Result.Success -> r.data
            is EventRepository.Result.Failure -> null
        }
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
                originQuery = if (editing) current?.originQuery.orEmpty() else "",
                originResults = if (editing) current?.originResults.orEmpty() else emptyList(),
            )
        }
    }

    fun onOriginQueryChange(v: String) {
        _routeChoice.update { (it ?: RouteState()).copy(originQuery = v) }
    }

    fun searchOriginPlaces() {
        val q = _routeChoice.value?.originQuery?.trim().orEmpty()
        if (q.isBlank()) return
        _routeChoice.update { (it ?: RouteState()).copy(originSearching = true) }
        viewModelScope.launch {
            when (val result = repository.searchPlaces(q)) {
                is EventRepository.Result.Success -> _routeChoice.update {
                    (it ?: RouteState()).copy(originSearching = false, originResults = result.data)
                }

                is EventRepository.Result.Failure -> _routeChoice.update {
                    (it ?: RouteState()).copy(originSearching = false, error = result.message)
                }
            }
        }
    }

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
                originQuery = "",
                originResults = emptyList(),
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
                // 출발지도 함께 버린다. 경로를 다시 고를 때 현재 위치를 새로
                // 잡아야 하고, 남겨 두면 어느 출발지로 고른 경로인지 흐려진다.
                origin = null,
                originLabel = null,
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

        /**
         * 블록 **정의**를 고쳤을 때의 안내.
         *
         * 서버가 정의 변경에 자동 재계산을 걸지 않는다(카카오 쿼터). 그 사실을
         * 알리지 않으면 사용자는 알람 시각이 그대로인 것을 고장으로 여긴다.
         */
        const val DEFINITION_NOTICE =
            "저장함 · 이미 계산된 알람은 그대로다. 알람 화면의 \"다시 계산\" 으로 반영한다"
    }
}
