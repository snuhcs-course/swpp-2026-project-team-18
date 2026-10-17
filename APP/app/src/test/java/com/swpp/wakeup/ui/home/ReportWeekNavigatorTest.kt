// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.ReportRepository.ReportResult
import com.swpp.wakeup.data.repository.toView
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * ⑧ 주 이동 규칙. `HomeViewModel` 은 JVM 에서 만들 수 없어, 그 규칙을 맡은
 * [ReportWeekNavigator] 를 가짜 저장소 응답으로 돌린다.
 *
 * 지키는 것
 * - 불러오기에 실패하면 보이던 주로 되돌린다 → 다음 "›" 가 미래 주로 건너뛰지 않는다
 * - 서버가 다음 주를 허락하지 않으면(`has_next_week: false`) "›" 를 무시한다
 * - 홈에서 열 때마다 `openCount` 가 오른다(⑧ 스크롤을 맨 위로) — 주 이동은 올리지 않는다
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportWeekNavigatorTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private val oct5 = LocalDate.of(2026, 10, 5)
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val sep28 = LocalDate.of(2026, 9, 28)

    /** 서버 응답 모양의 한 주. 실제 매핑을 거친다. */
    private fun week(start: LocalDate, hasNext: Boolean = true): WeeklyReportView =
        WeeklyReportDto(
            weekStart = start.toString(),
            weekEnd = start.plusDays(6).toString(),
            hasNextWeek = hasNext,
        ).toView(seoul)

    /** 가짜 저장소. 요청한 주를 적어 두고 정해 둔 답을 준다. */
    private class FakeReports {
        val requested = mutableListOf<LocalDate?>()
        var answer: (LocalDate?) -> ReportResult<WeeklyReportView> =
            { ReportResult.Failure("답을 정하지 않았음") }

        fun weekly(week: LocalDate?): ReportResult<WeeklyReportView> {
            requested += week
            return answer(week)
        }

        fun calibration(): ReportResult<CalibrationView> = ReportResult.Failure("캘리브레이션 없음")
    }

    private fun TestScope.navigator(fake: FakeReports) = ReportWeekNavigator(
        scope = this,
        weekly = { fake.weekly(it) },
        calibration = { fake.calibration() },
        today = { LocalDate.of(2026, 10, 14) },
    )

    @Test
    fun `불러오기에 실패하면 보이던 주로 되돌린다`() = runTest {
        val fake = FakeReports()
        val nav = navigator(fake)
        fake.answer = { ReportResult.Success(week(oct5)) }
        nav.open()
        advanceUntilIdle()
        assertEquals(oct5, nav.state.value.weekly?.weekStart)

        fake.answer = { ReportResult.Failure("서버에 연결할 수 없다.") }
        nav.shift(-1)
        assertTrue("누르는 동안은 요청한 주를 불러오는 중", nav.state.value.loading)
        assertEquals(sep28, nav.state.value.week)
        advanceUntilIdle()

        val failed = nav.state.value
        assertFalse(failed.loading)
        assertEquals("서버에 연결할 수 없다.", failed.error)
        assertEquals("보이던 주로 되돌린다", oct5, failed.week)
        assertEquals("화면은 보이던 주를 그대로 보여 준다", oct5, failed.weekly?.weekStart)

        // 되돌렸으니 다음 "›" 는 보이던 주(10월 5일)의 다음 주다. 되돌리지 않으면 요청했던
        // 9월 28일에서 한 주 뒤(10월 5일)를 다시 부르고, 화면과 "›" 판정이 어긋난다.
        fake.answer = { ReportResult.Success(week(oct12, hasNext = false)) }
        nav.shift(1)
        advanceUntilIdle()
        assertEquals(listOf(null, sep28, oct12), fake.requested)
        assertNull(nav.state.value.error)
    }

    @Test
    fun `처음 실패하면 요청한 주에 머문다`() = runTest {
        // 보이던 주가 없으면 되돌릴 곳이 없다. 다시 시도는 같은 주를 부른다.
        val fake = FakeReports()
        val nav = navigator(fake)
        nav.load(oct5)
        advanceUntilIdle()

        assertNull(nav.state.value.weekly)
        assertEquals(oct5, nav.state.value.week)
        assertEquals("답을 정하지 않았음", nav.state.value.error)
    }

    @Test
    fun `서버가 다음 주를 허락하지 않으면 다음 주로 가지 않는다`() = runTest {
        val fake = FakeReports()
        val nav = navigator(fake)
        fake.answer = { ReportResult.Success(week(oct12, hasNext = false)) }
        nav.load(oct12)
        advanceUntilIdle()
        val before = nav.state.value

        nav.shift(1)
        advanceUntilIdle()
        assertEquals("요청하지 않는다", listOf<LocalDate?>(oct12), fake.requested)
        assertEquals("상태도 그대로다", before, nav.state.value)

        // 지난 주로는 간다.
        fake.answer = { ReportResult.Success(week(oct5)) }
        nav.shift(-1)
        advanceUntilIdle()
        assertEquals(oct5, fake.requested.last())
        assertEquals(oct5, nav.state.value.weekly?.weekStart)
    }

    @Test
    fun `홈에서 열 때마다 openCount 가 오르고 마지막으로 본 주를 다시 연다`() = runTest {
        val fake = FakeReports()
        val nav = navigator(fake)
        fake.answer = { requested -> ReportResult.Success(week(requested ?: oct5)) }

        assertEquals(0, nav.state.value.openCount)
        nav.open()
        advanceUntilIdle()
        assertEquals(1, nav.state.value.openCount)
        assertEquals("처음은 서버 기본값(지난 주)", listOf<LocalDate?>(null), fake.requested)

        nav.shift(-1)
        advanceUntilIdle()
        assertEquals("주 이동은 openCount 를 바꾸지 않는다", 1, nav.state.value.openCount)

        nav.open()
        advanceUntilIdle()
        assertEquals(2, nav.state.value.openCount)
        assertEquals("마지막으로 본 주를 다시 연다", sep28, fake.requested.last())
        assertEquals(sep28, nav.state.value.weekly?.weekStart)
    }
}
