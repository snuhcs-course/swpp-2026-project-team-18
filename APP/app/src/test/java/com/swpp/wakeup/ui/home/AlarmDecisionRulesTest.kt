// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.ui.nav.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알람 결정(④) · 지각 위험(⑤) 화면이 같이 쓰는 상태의 규칙.
 *
 * 두 화면은 일정마다 스택에 따로 쌓일 수 있지만(이동 중 알림이 결정 화면을 하나 더 연다) 계획 · 선택지 상태는
 * 하나다. 이 파일이 지키는 것은 이렇다.
 *
 * 1. **화면은 제 일정의 것만 보인다.** 다른 일정의 계획을 그리면 그 제목 · 알람을 보이면서 삭제 · 수정은 이 일정에
 *    걸린다
 * 2. **다시 맨 위가 되면 제 것을 다시 받는다.** 다른 일정의 계획이면 새로, 제 계획이면 1분이 지났을 때만 조용히
 * 3. **살아 있는 화면의 결과는 버리지 않는다.** 위에 다른 화면이 쌓였다고 저장 결과를 버리면 스피너가 끝나지 않는다
 * 4. **저장한 계획은 그 계획을 보일 화면에만 쓴다.** 다른 일정의 결정 화면이 위에 열렸으면 그 화면의 계획을 덮지
 *    않는다(Partly AI-generated (Claude): 4 · 5 · 6)
 * 5. **지운 일정의 화면에 남지 않는다.** 맨 위가 그 일정의 화면이면 홈으로 가고, 상관없는 화면이면 그대로 둔다
 * 6. **받지 못한 이유는 카드와 같은 말투로 쓴다.** 통신 실패는 연결을 확인하라고 한다
 */
class AlarmDecisionRulesTest {

    private fun plan(eventId: Long) = AlarmPlanView(
        eventId = eventId,
        whenLabel = "내일 아침",
        dateLabel = "11월 9일 월",
        eventTitle = "09:00 일정 $eventId",
        eventPlace = "서울대",
        sensitivityTag = null,
        alarmAt = "7:00",
        meridiem = "AM",
        remaining = "9시간 0분 남음",
        onTimeProbability = null,
        tauUsed = 0.9,
        confidence = ConfidenceView(null, "정시 도착 확률 · 아직 계산할 수 없음", null, null),
        breakdown = emptyList(),
        totalMinutes = 80,
        arrivalLine = null,
        status = "ok",
        statusLabel = null,
    )

    // --- 어느 일정의 것을 보이나 --------------------------------------------------

    @Test
    fun `결정 화면은 다른 일정의 계획을 그리지 않는다`() {
        // Given A(1) 화면 위에 B(2) 화면이 열려 같이 쓰는 계획이 B 의 것으로 바뀌었다
        val held: AlarmPlanView? = plan(eventId = 2)

        // When 뒤로 돌아온 A 화면과 B 화면이 각자 보일 계획을 고른다
        val onA = held.forEvent(1)
        val onB = held.forEvent(2)

        // Then A 는 받는 중으로 보이고(B 의 제목을 보이며 버튼이 A 에 걸리지 않는다) B 는 그대로다
        assertNull(onA)
        assertSame(held, onB)
        assertNull((null as AlarmPlanView?).forEvent(1))
    }

    @Test
    fun `받지 못한 이유는 그 일정의 화면에만 보인다`() {
        // Given B(2) 의 계획을 받지 못했다
        val error: HomeViewModel.PlanLoadError? = HomeViewModel.PlanLoadError(2, "이 일정의 알람이 아직 정해지지 않았어요")

        // When 두 화면이 보일 이유를 고른다
        // Then B 화면만 이유를 보이고, 실패가 없으면 아무도 보이지 않는다(받는 중)
        assertEquals("이 일정의 알람이 아직 정해지지 않았어요", error.messageFor(2))
        assertNull(error.messageFor(1))
        assertNull((null as HomeViewModel.PlanLoadError?).messageFor(2))
    }

    // AI-generated (Claude)
    @Test
    fun `통신이 안 돼 받지 못했으면 연결을 확인하라고 해요체로 말한다`() {
        // Given 통신 실패. 저장소 문구는 ~다 체이고 "서버" 가 보인다
        val offline = EventRepository.Result.Failure("서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다.", network = true)

        // When 오류 카드에 쓸 이유를 만든다
        val reason = planLoadReasonOf(offline)

        // Then 해요체 제목("알람 정보를 불러오지 못했어요") 아래에 같은 말투로 쓴다
        assertEquals("인터넷 연결을 확인하고 다시 시도해 주세요", reason)
        assertFalse(reason.contains("서버"))
    }

    // AI-generated (Claude)
    @Test
    fun `통신 실패가 아니면 받은 이유를 그대로 쓴다`() {
        // Given 알람이 아직 정해지지 않았거나 서버가 이유를 줬다
        val noPlan = EventRepository.Result.Failure("이 일정의 알람이 아직 정해지지 않았어요")
        val notFound = EventRepository.Result.Failure("대상을 찾을 수 없다.", status = 404)

        // When / Then 바꾸지 않는다
        assertEquals("이 일정의 알람이 아직 정해지지 않았어요", planLoadReasonOf(noPlan))
        assertEquals("대상을 찾을 수 없다.", planLoadReasonOf(notFound))
    }

    // --- 다시 맨 위가 되면 무엇을 다시 받나 -------------------------------------------

    @Test
    fun `돌아온 결정 화면이 다른 일정의 계획을 들고 있으면 새로 받는다`() {
        // Given A(1) 위에 열었던 B(2) 를 닫았고, 계획은 B 의 것이다
        // When A 가 다시 맨 위가 된다
        val reload = planReloadOf(
            eventId = 1,
            heldPlanEventId = 2,
            loadingEventId = null,
            nowElapsed = 10_000L,
            lastLoadElapsed = 9_000L,
        )

        // Then 1분이 안 됐어도 비우고 새로 받는다
        assertEquals(PlanReload.FULL, reload)
    }

    @Test
    fun `계획을 받지 못한 채 돌아오거나 B 를 받는 도중에 돌아오면 A 를 새로 받는다`() {
        // Given 계획이 없다(받지 못했거나, 닫힌 B 화면의 요청이 아직 돌고 있다)
        // When A(1) 가 다시 맨 위가 된다
        // Then A 를 새로 받는다 — B 의 요청은 세대가 바뀌어 버려진다
        assertEquals(PlanReload.FULL, planReloadOf(1, heldPlanEventId = null, loadingEventId = null, 10_000L, 9_000L))
        assertEquals(PlanReload.FULL, planReloadOf(1, heldPlanEventId = null, loadingEventId = 2, 10_000L, 9_000L))
    }

    @Test
    fun `그 일정을 이미 받는 중이면 다시 보내지 않는다`() {
        // Given A(1) 를 받는 중이다
        // When A 가 다시 맨 위가 된다(앱 복귀)
        val reload = planReloadOf(1, heldPlanEventId = null, loadingEventId = 1, 200_000L, 0L)

        // Then 같은 요청을 또 보내지 않는다
        assertEquals(PlanReload.NONE, reload)
    }

    @Test
    fun `제 계획이면 1분이 지났을 때만 조용히 다시 받는다`() {
        // Given A(1) 의 계획을 10초에 받기 시작했다
        val started = 10_000L

        // When 1분이 되기 1초 전 · 1분째 · 밤새 지나 다시 맨 위가 된다
        val tooSoon = planReloadOf(1, 1, null, nowElapsed = started + 59_000L, lastLoadElapsed = started)
        val minute = planReloadOf(1, 1, null, nowElapsed = started + 60_000L, lastLoadElapsed = started)
        val overnight = planReloadOf(1, 1, null, nowElapsed = started + 9 * 3_600_000L, lastLoadElapsed = started)

        // Then 1분이 지나야 보던 계획을 둔 채 다시 받는다(밤새 경로 갱신 워커가 알람을 옮겼을 수 있다)
        assertEquals(PlanReload.NONE, tooSoon)
        assertEquals(PlanReload.SILENT, minute)
        assertEquals(PlanReload.SILENT, overnight)
    }

    // --- 저장이 돌려준 계획을 쓸지 -------------------------------------------------------

    // AI-generated (Claude)
    @Test
    fun `저장한 계획은 돌아갈 결정 화면이 그 일정의 것이면 쓴다`() {
        // Given A(5) 의 결정 화면에서 일정 수정 · 이 아침 할 일을 열고 저장을 기다린다
        val editing = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))
        val blocks = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EventBlocks(5))

        // When 저장이 끝난다
        // Then 쓴다 — 돌아갈 A 화면이 저장한 계획을 보인다
        assertTrue(savedPlanApplies(editing, 5))
        assertTrue(savedPlanApplies(blocks, 5))
    }

    // AI-generated (Claude)
    @Test
    fun `저장하는 동안 다른 일정의 결정 화면이 위에 열렸으면 그 화면의 계획을 덮지 않는다`() {
        // Given A(5) 를 저장하는 동안 경로 바꾸기 알림이 B(9) 의 결정 화면을 위에 열고 B 를 받는 중이다
        val overEdit = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5), AppRoute.AlarmDecision(9))
        val overBlocks = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EventBlocks(5), AppRoute.AlarmDecision(9))
        // 수정 폼을 떠나 B 를 연 뒤 스낵바의 다시 시도를 눌렀다
        val retriedOnB = listOf(AppRoute.Home, AppRoute.AlarmDecision(9))

        // When A 의 저장이 끝난다
        // Then 쓰지 않는다(세대도 올리지 않는다) — 쓰면 B 가 받던 계획이 버려지거나 덮여 B 가 스피너로 남는다
        assertFalse(savedPlanApplies(overEdit, 5))
        assertFalse(savedPlanApplies(overBlocks, 5))
        assertFalse(savedPlanApplies(retriedOnB, 5))
        // 그리고 A 가 다시 맨 위가 되면 B 의 계획을 들고 있으니(B 를 받는 중이어도) 저장한 A 를 새로 받는다
        assertEquals(PlanReload.FULL, planReloadOf(5, heldPlanEventId = 9, loadingEventId = null, 10_000L, 9_000L))
        assertEquals(PlanReload.FULL, planReloadOf(5, heldPlanEventId = null, loadingEventId = 9, 10_000L, 9_000L))
    }

    // AI-generated (Claude)
    @Test
    fun `맨 위가 결정 화면이 아니어도 그 아래 가장 위의 결정 화면이 그 일정이면 쓴다`() {
        // Given 수정 저장이 실패해 뒤로 나간 뒤 지각 위험(또는 설정)을 열고 스낵바의 다시 시도를 눌렀다
        val overRisk = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.RiskChoice(5))
        val overSettings = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.Settings)
        // 다른 일정의 결정 화면 위에 이 일정의 결정 화면이 있다
        val nested = listOf(AppRoute.Home, AppRoute.AlarmDecision(9), AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))
        // 같은 일정의 알림이 같은 결정 화면을 하나 더 열었다
        val sameEvent =
            listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5), AppRoute.AlarmDecision(5))

        // When / Then 쓴다 — 안 쓰면 돌아왔을 때 1분 동안 저장 전 계획이 보인다
        assertTrue(savedPlanApplies(overRisk, 5))
        assertTrue(savedPlanApplies(overSettings, 5))
        assertTrue(savedPlanApplies(nested, 5))
        assertTrue(savedPlanApplies(sameEvent, 5))
    }

    // AI-generated (Claude)
    @Test
    fun `결정 화면이 하나도 없으면 저장한 계획을 쓰지 않는다`() {
        // Given 수정 폼을 떠나 홈 · 설정에서 스낵바의 다시 시도를 눌렀다
        // When / Then 쓰지 않는다 — 다음에 결정 화면을 열면 새로 받는다
        assertFalse(savedPlanApplies(listOf(AppRoute.Home), 5))
        assertFalse(savedPlanApplies(listOf(AppRoute.Home, AppRoute.Settings), 5))
    }

    // --- 지운 뒤 홈으로 갈지 ---------------------------------------------------------

    // AI-generated (Claude)
    @Test
    fun `지운 일정의 화면이 맨 위면 홈으로 간다`() {
        // Given 일정 삭제를 누르고 기다리는 동안 그 일정의 지각 위험 · 이 아침 할 일 · 일정 수정을 열었다
        val decision = listOf(AppRoute.Home, AppRoute.AlarmDecision(5))
        val opened = listOf(AppRoute.RiskChoice(5), AppRoute.EventBlocks(5), AppRoute.EditEvent(5))

        // When 삭제가 끝난다
        // Then 어느 화면이든 홈으로 간다 — 남으면 하는 것마다 실패하고, 뒤로 가면 지운 일정이 그대로 보인다
        assertTrue(showsDeletedEvent(decision, 5, mapTarget = null))
        opened.forEach { assertTrue("$it", showsDeletedEvent(decision + it, 5, mapTarget = null)) }
    }

    // AI-generated (Claude)
    @Test
    fun `지운 일정의 수정 폼이 연 경로 선택과 지도도 그 일정의 화면이다`() {
        // Given 지운 일정의 수정 폼에서 ⑬ · 지도를 열었다
        val edit = listOf(AppRoute.Home, AppRoute.AlarmDecision(5), AppRoute.EditEvent(5))

        // When 삭제가 끝난다 / Then 홈으로 간다
        assertTrue(showsDeletedEvent(edit + AppRoute.RouteChoice, 5, mapTarget = null))
        assertTrue(showsDeletedEvent(edit + AppRoute.MapPick, 5, HomeViewModel.MapTarget.DESTINATION))
        assertTrue(showsDeletedEvent(edit + AppRoute.MapPick, 5, HomeViewModel.MapTarget.ORIGIN))
    }

    // AI-generated (Claude)
    @Test
    fun `지우는 동안 상관없는 화면으로 갔으면 그 화면을 닫지 않는다`() {
        // Given 지우는 동안 설정 · 다른 일정의 화면 · 새 일정 폼 · 집 주소로 갔다
        val decision = listOf(AppRoute.Home, AppRoute.AlarmDecision(5))
        val edit = decision + AppRoute.EditEvent(5)

        // When 삭제가 끝난다
        // Then 보던 화면을 그대로 둔다
        assertFalse(showsDeletedEvent(decision + AppRoute.Settings, 5, mapTarget = null))
        assertFalse(showsDeletedEvent(decision + AppRoute.AlarmDecision(9), 5, mapTarget = null))
        assertFalse(showsDeletedEvent(decision + AppRoute.AlarmDecision(9) + AppRoute.RiskChoice(9), 5, null))
        assertFalse(showsDeletedEvent(listOf(AppRoute.Home, AppRoute.AddEvent, AppRoute.RouteChoice), 5, null))
        assertFalse("집 주소는 제 폼이다", showsDeletedEvent(edit + AppRoute.HomeSetup, 5, mapTarget = null))
        assertFalse(
            "집 주소의 지도",
            showsDeletedEvent(edit + AppRoute.HomeSetup + AppRoute.MapPick, 5, HomeViewModel.MapTarget.HOME),
        )
        assertFalse("이미 홈이다", showsDeletedEvent(listOf(AppRoute.Home), 5, mapTarget = null))
    }

    @Test
    fun `돌아온 지각 위험 화면의 선택지가 다른 일정 것이면 다시 받는다`() {
        // Given A(1) 의 지각 위험 위에서 B(2) 의 지각 위험을 열었다 닫아 상태가 비었다
        val emptied = HomeViewModel.RiskState()
        val ofA = HomeViewModel.RiskState(eventId = 1, loading = true)

        // When A 의 지각 위험이 다시 맨 위가 된다
        // Then 빈 상태면 다시 받고(아니면 "선택지 불러오는 중" 이 끝나지 않는다), 제 상태면 그대로 둔다
        assertTrue(riskReloadNeeded(AppRoute.RiskChoice(1), emptied))
        assertTrue(riskReloadNeeded(AppRoute.RiskChoice(1), HomeViewModel.RiskState(eventId = 2)))
        assertFalse(riskReloadNeeded(AppRoute.RiskChoice(1), ofA))
        assertFalse("결정 화면은 계획 규칙을 따른다", riskReloadNeeded(AppRoute.AlarmDecision(1), emptied))
    }

    // --- 지각 위험 저장 결과 --------------------------------------------------------

    @Test
    fun `위에 다른 화면이 쌓여도 살아 있는 지각 위험 화면에 저장 결과를 쓴다`() {
        // Given 저장하는 동안 알림이 다른 결정 화면을 위에 쌓았지만 지각 위험 화면의 세대는 그대로다
        // When 저장이 끝난다
        val saved = riskSaveResultOf(sameScreen = true, error = null)
        val failed = riskSaveResultOf(sameScreen = true, error = "서버에 연결할 수 없다.")

        // Then 버리지 않는다 — 성공은 그 화면을 닫고, 실패는 그 화면에 이유를 둔다(돌아오면 보인다)
        assertEquals(RiskSaveResult.CLOSE, saved)
        assertEquals(RiskSaveResult.SHOW_ERROR, failed)
    }

    @Test
    fun `뒤로 나가 닫힌 지각 위험 화면의 실패는 스낵바로 알린다`() {
        // Given 저장하는 동안 뒤로 나가 지각 위험 화면이 닫혔다(세대가 바뀌었다)
        // When 저장이 끝난다
        val failed = riskSaveResultOf(sameScreen = false, error = "선택은 저장됐지만 알람 목록을 갱신하지 못했어요.")
        val saved = riskSaveResultOf(sameScreen = false, error = null)

        // Then 실패는 지금 화면에 알리고, 성공은 알릴 것이 없다(결정 화면이 바뀐 계획을 받는다)
        assertEquals(RiskSaveResult.NOTIFY, failed)
        assertEquals(RiskSaveResult.DROP, saved)
    }
}
