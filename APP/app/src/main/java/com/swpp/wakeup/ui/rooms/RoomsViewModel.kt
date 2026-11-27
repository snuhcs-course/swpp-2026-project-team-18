package com.swpp.wakeup.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RoomCreateInput
import com.swpp.wakeup.data.remote.RoomDestinationInput
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.RemoteRoomsRepository
import com.swpp.wakeup.data.repository.RoomsRepository
import com.swpp.wakeup.data.repository.RoomsResult
import com.swpp.wakeup.domain.model.AppointmentRoom
import com.swpp.wakeup.domain.model.RoomDetail
import com.swpp.wakeup.domain.model.takeCodePoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** No Android context or global navigation dependency: fake repository, search and clock in JVM tests. */
class RoomsViewModel internal constructor(
    private val repository: RoomsRepository,
    private val places: RoomPlaceSearcher,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    constructor() : this(RemoteRoomsRepository(ApiClient.rooms), EventRoomPlaceSearcher())

    enum class Page { LIST, CREATE, JOIN, DETAIL }

    data class CreateDraft(
        val title: String = "",
        val startAt: LocalDateTime,
        val targetPercent: String = "90",
        val placeQuery: String = "",
        val destination: PlaceSearchItem? = null,
        val places: List<PlaceSearchItem> = emptyList(),
        val searching: Boolean = false,
        val searched: Boolean = false,
        val searchError: String? = null,
        val fields: Map<String, String> = emptyMap(),
        val clientUuid: String = UUID.randomUUID().toString(),
    ) {
        val target: Double? get() = targetPercent.toIntOrNull()?.takeIf { it in 1..99 }?.div(100.0)
    }

    data class UiState(
        val page: Page = Page.LIST,
        val rooms: List<AppointmentRoom> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val submitting: Boolean = false,
        val unsupported: Boolean = false,
        val error: String? = null,
        val create: CreateDraft,
        val joinCode: String = "",
        val codeError: String? = null,
        val detailId: Long? = null,
        val detail: RoomDetail? = null,
        /** UI consumes this after refreshing personal events/registered alarms. */
        val membershipChange: Long = 0,
    )

    private val _state = MutableStateFlow(UiState(create = freshDraft()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val scope: CoroutineScope get() = testScope ?: viewModelScope
    private var request: Job? = null
    private var search: Job? = null
    private var generation = 0L
    private var searchGeneration = 0L

    fun enter() {
        // A tab change may detach this screen while a write is in flight. Keep its result/idempotency key.
        if (_state.value.submitting) return
        navigate(Page.LIST)
        refreshList()
    }

    fun refreshList() {
        if (_state.value.submitting) return
        request?.cancel()
        val ticket = ++generation
        _state.update { it.copy(loading = true, error = null, unsupported = false) }
        request = scope.launch {
            val result = repository.list()
            if (ticket != generation) return@launch
            _state.update { current ->
                when (result) {
                    is RoomsResult.Success -> current.copy(rooms = result.data, loaded = true, loading = false)
                    RoomsResult.Unsupported -> current.copy(loaded = true, loading = false, unsupported = true)
                    is RoomsResult.Failure -> current.copy(loading = false, error = result.message,
                        rooms = if (result.status in setOf(401, 403)) emptyList() else current.rooms)
                }
            }
        }
    }

    fun showCreate() {
        if (_state.value.submitting) return
        navigate(Page.CREATE)
        _state.update { it.copy(create = freshDraft()) }
    }

    fun showJoin() {
        if (_state.value.submitting) return
        navigate(Page.JOIN)
        _state.update { it.copy(joinCode = "", codeError = null) }
    }

    /** True means handled here; false lets the host return to the Me tab. */
    fun back(): Boolean {
        if (_state.value.submitting) return true
        if (_state.value.page == Page.LIST) return false
        navigate(Page.LIST)
        return true
    }

    private fun navigate(page: Page) {
        request?.cancel()
        search?.cancel()
        generation++
        searchGeneration++
        _state.update {
            it.copy(page = page, loading = false, error = null, unsupported = false,
                create = it.create.copy(searching = false))
        }
    }

    fun onTitleChange(value: String) = edit("title") { copy(title = value.takeCodePoints(40)) }
    fun onTargetChange(value: String) = edit("group_target_probability") {
        copy(targetPercent = value.filter { it in '0'..'9' }.take(3))
    }
    fun onDateChange(value: LocalDate) = edit("start_at") { copy(startAt = LocalDateTime.of(value, startAt.toLocalTime())) }
    fun onTimeChange(value: LocalTime) = edit("start_at") { copy(startAt = LocalDateTime.of(startAt.toLocalDate(), value.withSecond(0).withNano(0))) }

    fun onPlaceQueryChange(value: String) {
        search?.cancel()
        searchGeneration++
        edit("destination") {
            copy(placeQuery = value.takeCodePoints(100), destination = null, places = emptyList(),
                searching = false, searched = false, searchError = null)
        }
    }

    fun selectPlace(value: PlaceSearchItem) {
        if (value.name.isBlank() || !value.lat.isFinite() || !value.lng.isFinite() ||
            value.lat !in -90.0..90.0 || value.lng !in -180.0..180.0) return
        search?.cancel()
        searchGeneration++
        edit("destination") {
            copy(destination = value, placeQuery = value.name, places = emptyList(), searching = false,
                searchError = null, searched = false)
        }
    }

    fun searchPlaces() {
        val current = _state.value
        if (current.page != Page.CREATE || current.submitting) return
        val query = current.create.placeQuery.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(create = it.create.copy(searchError = "장소 이름을 입력해 주세요")) }
            return
        }
        search?.cancel()
        val ticket = ++searchGeneration
        _state.update { it.copy(create = it.create.copy(searching = true, searchError = null, searched = false)) }
        search = scope.launch {
            val result = places.search(query)
            if (ticket != searchGeneration || _state.value.page != Page.CREATE) return@launch
            _state.update { s ->
                val draft = when (result) {
                    is RoomsResult.Success -> s.create.copy(places = result.data, searching = false, searched = true)
                    is RoomsResult.Failure -> s.create.copy(searching = false, searchError = result.message)
                    RoomsResult.Unsupported -> s.create.copy(searching = false, searchError = "장소 검색을 아직 쓸 수 없어요. 잠시 뒤 다시 시도해 주세요")
                }
                s.copy(create = draft)
            }
        }
    }

    private fun edit(field: String, change: CreateDraft.() -> CreateDraft) {
        if (_state.value.submitting) return
        _state.update { s ->
            s.copy(error = null, unsupported = false, create = s.create.change().copy(
                fields = s.create.fields - field, clientUuid = UUID.randomUUID().toString(),
            ))
        }
    }

    fun onCodeChange(value: String) {
        if (_state.value.submitting) return
        _state.update {
            it.copy(joinCode = value.uppercase(Locale.ROOT).filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(6),
                error = null, unsupported = false, codeError = null)
        }
    }

    fun createRoom() {
        val current = _state.value
        if (current.page != Page.CREATE || current.submitting) return
        val d = current.create
        val fields = buildMap {
            if (d.title.isBlank()) put("title", "방 이름을 입력해 주세요")
            if (d.destination == null) put("destination", "검색 결과에서 약속 장소를 골라 주세요")
            if (!d.startAt.isAfter(now())) put("start_at", "앞으로의 약속 시각을 골라 주세요")
            if (d.target == null) put("group_target_probability", "1~99% 중에서 골라 주세요")
        }
        if (fields.isNotEmpty()) {
            _state.update { it.copy(create = d.copy(fields = fields)) }
            return
        }
        val destination = d.destination ?: return
        val input = RoomCreateInput(
            title = d.title.trim(), startAt = d.startAt.atZone(zone).toOffsetDateTime().toString(),
            destination = RoomDestinationInput(destination.name, destination.address, destination.lat,
                destination.lng, destination.kakaoPlaceId),
            groupTargetProbability = d.target ?: return, clientUuid = d.clientUuid,
        )
        submit { repository.create(input) }
    }

    fun joinRoom() {
        val current = _state.value
        if (current.page != Page.JOIN || current.submitting) return
        if (current.joinCode.length != 6) {
            _state.update { it.copy(codeError = "영문과 숫자로 된 6자리 초대 코드를 입력해 주세요") }
            return
        }
        submit { repository.join(current.joinCode) }
    }

    private fun submit(action: suspend () -> RoomsResult<RoomDetail>) {
        request?.cancel()
        val ticket = ++generation
        _state.update { it.copy(submitting = true, error = null, unsupported = false) }
        request = scope.launch {
            val result = action()
            if (ticket != generation) return@launch
            _state.update { s ->
                when (result) {
                    is RoomsResult.Success -> s.copy(
                        page = Page.DETAIL, submitting = false, detailId = result.data.room.id,
                        detail = result.data,
                        rooms = (s.rooms.filterNot { it.id == result.data.room.id } + result.data.room).sortedBy { it.startAt },
                        membershipChange = s.membershipChange + 1,
                    )
                    RoomsResult.Unsupported -> s.copy(submitting = false, unsupported = true)
                    is RoomsResult.Failure -> s.copy(submitting = false, error = result.message,
                        codeError = result.fields["code"], create = s.create.copy(fields = result.fields))
                }
            }
        }
    }

    fun openRoom(id: Long) {
        if (_state.value.submitting) return
        navigate(Page.DETAIL)
        _state.update { it.copy(detailId = id, detail = null) }
        refreshRoom()
    }

    fun refreshRoom() {
        val id = _state.value.detailId ?: return
        request?.cancel()
        val ticket = ++generation
        _state.update { it.copy(loading = true, error = null, unsupported = false) }
        request = scope.launch {
            val result = repository.detail(id)
            if (ticket != generation) return@launch
            _state.update { s ->
                when (result) {
                    is RoomsResult.Success -> s.copy(loading = false, detail = result.data)
                    RoomsResult.Unsupported -> s.copy(loading = false, unsupported = true)
                    is RoomsResult.Failure -> {
                        val inaccessible = result.status in setOf(401, 403, 404)
                        s.copy(loading = false, error = result.message,
                            detail = if (inaccessible) null else s.detail,
                            rooms = when (result.status) {
                                401, 403 -> emptyList()
                                404 -> s.rooms.filterNot { it.id == id }
                                else -> s.rooms
                            })
                    }
                }
            }
        }
    }

    private fun freshDraft() = CreateDraft(startAt = now().toLocalDate().plusDays(1).atTime(9, 0))
}

internal fun interface RoomPlaceSearcher {
    suspend fun search(query: String): RoomsResult<List<PlaceSearchItem>>
}

private class EventRoomPlaceSearcher(private val events: EventRepository = EventRepository()) : RoomPlaceSearcher {
    override suspend fun search(query: String): RoomsResult<List<PlaceSearchItem>> =
        // Omit near: neither measured GPS nor member home coordinates are sent by this search.
        when (val result = events.searchPlaces(query)) {
            is EventRepository.Result.Success -> RoomsResult.Success(result.data.results)
            is EventRepository.Result.Failure -> RoomsResult.Failure(result.message)
        }
}
