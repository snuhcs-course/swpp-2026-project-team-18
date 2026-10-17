// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.ReportRepository.ReportResult
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * ⑧ 주간 리포트의 상태와 주 이동 규칙. [HomeViewModel] 이 들고, 화면 스택은 그쪽이 민다.
 *
 * `AndroidViewModel` 은 `Application` 이 있어야 만들 수 있어 JVM 단위 테스트가 닿지
 * 않는다(`HomeViewModelInitOrderTest` 머리말). 그래서 규칙을 여기 두고 저장소 대신 함수를
 * 받는다 — 테스트는 가짜 응답을 넣는다(`ReportWeekNavigatorTest`).
 *
 * - 불러오기에 실패하면 **보이던 주로 되돌린다.** 요청한 주에 머물면 화면의 주와 "›"
 *   판정이 서로 다른 주를 가리켜, 다시 누르면 미래 주까지 갈 수 있다.
 * - 다음 주로는 서버가 허락할 때만 간다(`has_next_week`). 화면이 "›" 를 끄지만 여기서도 막는다.
 * - 홈에서 열 때마다 [HomeViewModel.ReportState.openCount] 를 올린다. ⑧ 이 그 값으로 맨 위로
 *   스크롤한다(⑨ 에서 돌아올 때는 그대로).
 */
internal class ReportWeekNavigator(
    private val scope: CoroutineScope,
    private val weekly: suspend (LocalDate?) -> ReportResult<WeeklyReportView>,
    private val calibration: suspend () -> ReportResult<CalibrationView>,
    /** 긴 창 캘리브레이션 실패. 화면을 막지 않으므로 로그만 남긴다 */
    private val onCalibrationFailure: (String) -> Unit = {},
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    private val _state = MutableStateFlow(HomeViewModel.ReportState())
    val state: StateFlow<HomeViewModel.ReportState> = _state.asStateFlow()

    /** 홈에서 연다. 마지막으로 본 주를 다시 연다(처음은 null — 서버 기본값인 지난 주). */
    fun open() {
        _state.update { it.copy(openCount = it.openCount + 1) }
        load(_state.value.week)
    }

    fun load(week: LocalDate?) {
        _state.update { it.copy(loading = true, error = null, week = week) }
        scope.launch {
            when (val result = weekly(week)) {
                is ReportResult.Success -> _state.update {
                    it.copy(loading = false, error = null, weekly = result.data)
                }

                is ReportResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        error = result.message,
                        week = it.weekly?.weekStart ?: week,
                    )
                }
            }

            // 긴 창 캘리브레이션은 실패해도 화면을 막지 않는다. 주간 리포트만
            // 있어도 볼 것이 있다.
            when (val long = calibration()) {
                is ReportResult.Success -> _state.update { it.copy(longTerm = long.data) }
                is ReportResult.Failure -> onCalibrationFailure(long.message)
            }
        }
    }

    /** 한 주 앞뒤로 이동한다. 기준은 보고 있는 주, 없으면 지난 주다. */
    fun shift(weeks: Long) {
        val current = _state.value
        if (weeks > 0 && current.weekly?.canGoNext == false) return
        val base = current.week ?: current.weekly?.weekStart ?: today().minusWeeks(1)
        load(base.plusWeeks(weeks))
    }
}
