package com.swpp.wakeup.ui.alarm

import androidx.lifecycle.ViewModel
import com.swpp.wakeup.data.repository.AlarmDecisionRepository
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.UserAlarmRule
import com.swpp.wakeup.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 알람 시각 시트의 조회·저장·구버전 보호·취소를 맡는다. Android나 전역 API를 직접 부르지 않는다. */
class AlarmDecisionViewModel(
    private val repository: AlarmDecisionRepository,
    parentScope: CoroutineScope,
    private val serverVersion: () -> String?,
    private val knownPlan: (Long) -> AlarmPlanView?,
    private val onSaved: suspend (AlarmPlanView) -> String?,
    private val notify: (String, HomeViewModel.AlarmUndo?) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** A read may discover an alarm changed by learning; synchronize Home and device alarms before enabling writes. */
    private val onRefreshed: suspend (AlarmPlanView) -> String? = { null },
    /** Wait for persisted observations/in-flight uploads before reading either server view. */
    private val onBeforeRead: suspend () -> String? = { null },
) : ViewModel() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _state = MutableStateFlow(HomeViewModel.AlarmTimeSheetState())
    val state = _state.asStateFlow()
    private var sheetGeneration = 0L
    private var optionGeneration = 0L
    private var planGeneration = 0L
    private var readGeneration = 0L
    private var beforeReadJob: Job? = null
    private var optionJob: Job? = null
    private var planJob: Job? = null
    private var planError: String? = null
    private var optionError: String? = null
    private var planLoading = false
    private var optionLoading = false
    private var planFresh = false
    private var optionsFresh = false
    private val savingEvents = mutableSetOf<Long>()
    private var closed = false

    fun open(eventId: Long) {
        if (closed || _state.value.eventId == eventId) return
        ++sheetGeneration
        optionJob?.cancel()
        planJob?.cancel()
        planError = null
        optionError = null
        // Set both-query intent before launching either one; an immediate options
        // response must not briefly advertise a ready cached plan.
        planLoading = true
        optionLoading = false
        planFresh = false
        optionsFresh = false
        _state.value = HomeViewModel.AlarmTimeSheetState(
            eventId = eventId, loading = true, saving = eventId in savingEvents,
            plan = knownPlan(eventId)?.takeIf { it.eventId == eventId },
        )
        // A pending write owns this event until its response and device sync finish.
        // Reopening may show its cached preview, but must not synchronize an older GET over it.
        if (eventId in savingEvents) return
        startRead(eventId)
    }

    private fun startRead(eventId: Long) {
        val ticket = ++readGeneration
        val sheet = sheetGeneration
        beforeReadJob?.cancel()
        ++planGeneration
        planJob?.cancel()
        ++optionGeneration
        optionJob?.cancel()
        planFresh = false
        optionsFresh = false
        planLoading = true
        optionLoading = true
        planError = null
        optionError = null
        publishReadState()
        beforeReadJob = scope.launch {
            val error = onBeforeRead()
            if (closed || ticket != readGeneration || sheet != sheetGeneration || _state.value.eventId != eventId) return@launch
            if (error != null) {
                planLoading = false
                optionLoading = false
                planError = error
                publishReadState()
                return@launch
            }
            requestOptions(eventId)
            loadPlan(eventId)
        }
    }

    private fun loadPlan(eventId: Long) {
        val ticket = ++planGeneration
        val sheet = sheetGeneration
        planJob?.cancel()
        planError = null
        planLoading = true
        planFresh = false
        publishReadState()
        planJob = scope.launch {
            val result = repository.plan(eventId)
            if (!isCurrentPlan(eventId, ticket, sheet)) return@launch
            when (result) {
                is Result.Success -> {
                    if (result.data.eventId != eventId) {
                        planError = "이 일정의 알람 시각을 받지 못했어요. 다시 시도해 주세요."
                    } else {
                        _state.update { it.copy(plan = result.data) }
                        val syncError = onRefreshed(result.data)
                        if (!isCurrentPlan(eventId, ticket, sheet)) return@launch
                        planError = syncError
                        planFresh = syncError == null
                    }
                }
                is Result.Failure -> planError = result.message
            }
            planLoading = false
            publishReadState()
        }
    }

    private fun isCurrentPlan(eventId: Long, ticket: Long, sheet: Long): Boolean =
        !closed && ticket == planGeneration && sheet == sheetGeneration && _state.value.eventId == eventId

    private fun publishReadState() {
        _state.update { it.copy(
            loading = planLoading || optionLoading,
            error = optionError ?: planError,
            canWrite = writeReady(it.saving),
        ) }
    }

    private fun writeReady(saving: Boolean): Boolean =
        planFresh && optionsFresh && !planLoading && !optionLoading && !saving

    fun dismiss() {
        ++sheetGeneration
        ++optionGeneration
        ++planGeneration
        ++readGeneration
        beforeReadJob?.cancel()
        optionJob?.cancel()
        planJob?.cancel()
        planError = null
        optionError = null
        planLoading = false
        optionLoading = false
        planFresh = false
        optionsFresh = false
        _state.value = HomeViewModel.AlarmTimeSheetState()
    }

    fun loadOptions(eventId: Long) {
        if (closed || _state.value.saving) return
        if (_state.value.eventId != eventId) {
            open(eventId)
            return
        }
        startRead(eventId)
    }

    private fun requestOptions(eventId: Long) {
        val generation = ++optionGeneration
        optionJob?.cancel()
        optionLoading = true
        optionsFresh = false
        optionError = null
        publishReadState()
        optionJob = scope.launch {
            val result = repository.options(eventId)
            if (closed || generation != optionGeneration || _state.value.eventId != eventId) return@launch
            when (result) {
                is Result.Success -> {
                    optionsFresh = true
                    _state.update { it.copy(options = result.data) }
                }
                is Result.Failure -> optionError = result.message
            }
            optionLoading = false
            publishReadState()
        }
    }

    fun choose(eventId: Long, key: String) {
        val current = _state.value
        if (closed || current.eventId != eventId || current.loading || current.saving || !planFresh || !optionsFresh) return
        val selected = current.options?.option(key) ?: return
        val label = AlarmTimeChoice.minutesOfDay(selected.alarmAt, selected.meridiem)
            ?.let(AlarmTimeChoice::clockLabel)
        save(eventId, label?.let { "$it 알람으로 바꿨어요" } ?: "알람 시각을 바꿨어요") {
            repository.choose(eventId, selected.tau)
        }
    }

    fun custom(eventId: Long, minutesOfDay: Int) {
        val current = _state.value
        if (closed || current.eventId != eventId || current.loading || current.saving || !planFresh || !optionsFresh || !UserAlarmRule.supported(serverVersion())) return
        val plan = current.plan?.takeIf { it.eventId == eventId } ?: return
        val start = plan.startAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return
        if (UserAlarmRule.isAfterStart(minutesOfDay, start.hour * 60 + start.minute)) {
            notify(UserAlarmRule.AFTER_START_NOTE, null)
            return
        }
        val at = UserAlarmRule.alarmDateTime(minutesOfDay, start)
        val label = AlarmTimeChoice.clockLabel(minutesOfDay)
        save(eventId, "$label 알람으로 바꿨어요", plan.alarmAtMillis?.let { HomeViewModel.AlarmUndo(eventId, it) }) {
            repository.custom(eventId, UserAlarmRule.wire(at))
        }
    }

    fun revert(eventId: Long) {
        val current = _state.value
        if (closed || current.eventId != eventId || current.loading || current.saving || !planFresh || !optionsFresh || !UserAlarmRule.supported(serverVersion())) return
        save(eventId, "추천 시각으로 되돌렸어요") { repository.revert(eventId) }
    }

    private fun save(
        eventId: Long,
        done: String,
        undo: HomeViewModel.AlarmUndo? = null,
        request: suspend () -> Result<AlarmPlanView>,
    ) {
        if (!savingEvents.add(eventId)) return
        val generation = sheetGeneration
        ++readGeneration
        beforeReadJob?.cancel()
        ++optionGeneration
        optionJob?.cancel()
        ++planGeneration
        planJob?.cancel()
        planLoading = false
        optionLoading = false
        _state.update { it.copy(saving = true, loading = false, error = null, canWrite = false) }
        scope.launch {
            val result = request()
            if (closed) return@launch
            // 시트를 닫은 뒤 저장이 성공해도 홈·기기 알람은 맞춘다. 로그아웃한 뒤에는 실행하지 않는다.
            val error = when (result) {
                is Result.Failure -> result.message
                is Result.Success -> onSaved(result.data)
            }
            savingEvents.remove(eventId)
            if (closed || _state.value.eventId != eventId) return@launch
            if (result is Result.Success && error != null) planFresh = false
            if (generation != sheetGeneration) {
                // A reopened sheet cannot start a second write until this request and alarm sync finish.
                ++planGeneration
                planJob?.cancel()
                ++optionGeneration
                optionJob?.cancel()
                planLoading = false
                optionLoading = false
                planFresh = false
                optionsFresh = false
                _state.update { it.copy(
                    saving = false,
                    loading = false,
                    plan = if (result is Result.Success) result.data else it.plan,
                    error = error,
                    canWrite = false,
                ) }
                if (error == null) {
                    startRead(eventId)
                }
                return@launch
            }
            if (error == null) {
                dismiss()
                notify(done, undo)
            } else {
                _state.update { it.copy(saving = false, error = error, canWrite = writeReady(saving = false)) }
            }
        }
    }

    fun close() {
        closed = true
        dismiss()
        scope.cancel()
        savingEvents.clear()
    }

    override fun onCleared() = close()
}
