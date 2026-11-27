package com.swpp.wakeup.ui.trip

import androidx.lifecycle.ViewModel
import com.swpp.wakeup.data.local.RouteSegmentsStore
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.TripPlanRepository
import com.swpp.wakeup.domain.model.CheckpointBasis
import com.swpp.wakeup.domain.model.RouteSegments
import com.swpp.wakeup.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 이동 탭의 조회·취소·오프라인 사본을 담당한다. HomeViewModel은 화면 이동만 연결한다. */
class TripViewModel(
    private val repository: TripPlanRepository,
    private val cache: CheckpointCache,
    parentScope: CoroutineScope,
) : ViewModel() {
    interface CheckpointCache {
        data class Saved(val segments: RouteSegments, val atMillis: Long)
        fun get(eventId: Long, routeKey: String): Saved?
        fun save(eventId: Long, routeKey: String, segments: RouteSegments)
        fun remove(eventId: Long)
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _plan = MutableStateFlow(HomeViewModel.TripTabState())
    val plan = _plan.asStateFlow()
    private val _checkpoints = MutableStateFlow<HomeViewModel.CheckpointState?>(null)
    val checkpoints = _checkpoints.asStateFlow()
    private var planJob: Job? = null
    private var checkpointJob: Job? = null
    private var planGeneration = 0L
    private var checkpointGeneration = 0L
    private var closed = false

    init {
        // 생성 직후의 변경도 놓치지 않게 구독을 즉시 시작한다.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.changes.collect { change ->
                if (closed) return@collect
                val current = _plan.value
                if (current.eventId == change.eventId) {
                    if (change.plan != null) {
                        ++planGeneration
                        planJob?.cancel()
                        _plan.value = current.copy(plan = change.plan, loading = false, failed = false)
                    } else {
                        loadPlan(change.eventId, current.version, force = true)
                    }
                }
                if (_checkpoints.value?.eventId == change.eventId) {
                    ++checkpointGeneration
                    checkpointJob?.cancel()
                    _checkpoints.value = null
                }
                runCatching { cache.remove(change.eventId) }
            }
        }
    }

    fun loadPlan(eventId: Long?, version: Long?, force: Boolean = false) {
        if (closed) return
        val current = _plan.value
        if (!force && eventId != null && current.eventId == eventId && current.version == version &&
            (current.loading || current.plan != null)
        ) return
        val generation = ++planGeneration
        planJob?.cancel()
        if (eventId == null) {
            _plan.value = HomeViewModel.TripTabState()
            return
        }
        _plan.value = HomeViewModel.TripTabState(
            eventId = eventId, version = version,
            plan = current.plan?.takeIf { it.eventId == eventId }, loading = true,
        )
        planJob = scope.launch {
            val result = repository.loadPlan(eventId)
            if (closed || generation != planGeneration) return@launch
            _plan.value = when (result) {
                is EventRepository.Result.Success -> _plan.value.copy(plan = result.data, loading = false, failed = false)
                is EventRepository.Result.Failure -> _plan.value.copy(loading = false, failed = _plan.value.plan == null)
            }
        }
    }

    fun loadCheckpoints(eventId: Long, routeKey: String?, force: Boolean = false) {
        if (closed || routeKey == null || !CheckpointBasis.hasStops(routeKey)) return
        val current = _checkpoints.value
        val same = current != null && current.eventId == eventId && current.routeKey == routeKey
        if (same && (!force || current?.loading == true)) return
        val generation = ++checkpointGeneration
        checkpointJob?.cancel()
        val saved = runCatching { cache.get(eventId, routeKey) }.getOrNull()
        _checkpoints.value = HomeViewModel.CheckpointState(
            eventId = eventId, routeKey = routeKey,
            segments = current?.takeIf { same }?.segments ?: saved?.segments,
            savedAtMillis = saved?.atMillis,
        )
        checkpointJob = scope.launch {
            val result = repository.baseRouteSegments(eventId, routeKey)
            if (closed || generation != checkpointGeneration) return@launch
            val state = _checkpoints.value ?: return@launch
            _checkpoints.value = when (result) {
                is EventRepository.Result.Success -> {
                    if (result.data != null) {
                        runCatching { cache.save(eventId, routeKey, result.data) }
                    } else {
                        // 새 응답이 경로가 없음을 확인했다면 다음 오프라인에도 옛 구간을 되살리지 않는다.
                        runCatching { cache.remove(eventId) }
                    }
                    state.copy(segments = result.data, loading = false, savedAtMillis = null, failed = false)
                }
                is EventRepository.Result.Failure -> state.copy(
                    segments = saved?.segments ?: state.segments, loading = false,
                    savedAtMillis = saved?.atMillis, failed = saved == null,
                )
            }
        }
    }

    fun retryCheckpoints() {
        val current = _checkpoints.value ?: return
        loadCheckpoints(current.eventId, current.routeKey, force = true)
    }

    /** 로그아웃 시 ViewModel 정리보다 먼저 호출해 진행 중인 옛 계정 응답을 차단한다. */
    fun close() {
        closed = true
        ++planGeneration
        ++checkpointGeneration
        scope.cancel()
        _plan.value = HomeViewModel.TripTabState()
        _checkpoints.value = null
    }

    override fun onCleared() = close()
}

class StoredCheckpointCache(private val store: RouteSegmentsStore) : TripViewModel.CheckpointCache {
    override fun get(eventId: Long, routeKey: String) = store.get(eventId, routeKey)?.let {
        TripViewModel.CheckpointCache.Saved(it.segments, it.savedAtMillis)
    }
    override fun save(eventId: Long, routeKey: String, segments: RouteSegments) {
        store.save(eventId, routeKey, segments)
    }
    override fun remove(eventId: Long) = store.remove(eventId)
}
