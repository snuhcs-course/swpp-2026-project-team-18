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
) : ViewModel() {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _state = MutableStateFlow(HomeViewModel.AlarmTimeSheetState())
    val state = _state.asStateFlow()
    private var sheetGeneration = 0L
    private var optionGeneration = 0L
    private var planGeneration = 0L
    private var optionJob: Job? = null
    private var planJob: Job? = null
    private var planError: String? = null
    private val savingEvents = mutableSetOf<Long>()
    private var closed = false

    fun open(eventId: Long) {
        if (closed || _state.value.eventId == eventId) return
        ++sheetGeneration
        optionJob?.cancel()
        planJob?.cancel()
        planError = null
        _state.value = HomeViewModel.AlarmTimeSheetState(
            eventId = eventId, loading = true, saving = eventId in savingEvents,
        )
        requestOptions(eventId)
        loadPlan(eventId)
    }

    private fun loadPlan(eventId: Long) {
        val ticket = ++planGeneration
        planJob?.cancel()
        val known = knownPlan(eventId)?.takeIf { it.eventId == eventId }
        if (known != null) {
            val previousError = planError
            planError = null
            _state.update { it.copy(plan = known, error = it.error?.takeUnless { message -> message == previousError }) }
        } else {
            planJob = scope.launch {
                val result = repository.plan(eventId)
                if (closed || ticket != planGeneration || _state.value.eventId != eventId) return@launch
                when (result) {
                    is Result.Success -> {
                        val previousError = planError
                        planError = null
                        _state.update { it.copy(plan = result.data, error = it.error?.takeUnless { message -> message == previousError }) }
                    }
                    is Result.Failure -> {
                        planError = result.message
                        _state.update { it.copy(error = it.error ?: result.message) }
                    }
                }
            }
        }
    }

    fun dismiss() {
        ++sheetGeneration
        ++optionGeneration
        ++planGeneration
        optionJob?.cancel()
        planJob?.cancel()
        planError = null
        _state.value = HomeViewModel.AlarmTimeSheetState()
    }

    fun loadOptions(eventId: Long) {
        if (closed || _state.value.saving) return
        if (_state.value.eventId != eventId) {
            open(eventId)
            return
        }
        requestOptions(eventId)
        if (_state.value.plan == null && planJob?.isActive != true) loadPlan(eventId)
    }

    private fun requestOptions(eventId: Long) {
        val generation = ++optionGeneration
        optionJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        optionJob = scope.launch {
            val result = repository.options(eventId)
            if (closed || generation != optionGeneration || _state.value.eventId != eventId) return@launch
            _state.update {
                when (result) {
                    is Result.Success -> it.copy(options = result.data, loading = false, error = planError)
                    is Result.Failure -> it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    fun choose(eventId: Long, key: String) {
        val current = _state.value
        if (closed || current.eventId != eventId || current.loading || current.saving) return
        val selected = current.options?.option(key) ?: return
        val label = AlarmTimeChoice.minutesOfDay(selected.alarmAt, selected.meridiem)
            ?.let(AlarmTimeChoice::clockLabel)
        save(eventId, label?.let { "$it 알람으로 바꿨어요" } ?: "알람 시각을 바꿨어요") {
            repository.choose(eventId, selected.tau)
        }
    }

    fun custom(eventId: Long, minutesOfDay: Int) {
        val current = _state.value
        if (closed || current.eventId != eventId || current.saving || !UserAlarmRule.supported(serverVersion())) return
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
        if (closed || current.eventId != eventId || current.saving || !UserAlarmRule.supported(serverVersion())) return
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
        ++optionGeneration
        optionJob?.cancel()
        _state.update { it.copy(saving = true, loading = false, error = null) }
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
            if (generation != sheetGeneration) {
                // A reopened sheet cannot start a second write until this request and alarm sync finish.
                ++planGeneration
                planJob?.cancel()
                ++optionGeneration
                optionJob?.cancel()
                _state.update { it.copy(
                    saving = false,
                    loading = false,
                    plan = if (result is Result.Success) result.data else it.plan,
                    error = error,
                ) }
                if (error == null) requestOptions(eventId)
                return@launch
            }
            if (error == null) {
                dismiss()
                notify(done, undo)
            } else {
                _state.update { it.copy(saving = false, error = error) }
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
