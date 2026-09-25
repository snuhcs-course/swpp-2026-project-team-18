package com.swpp.wakeup.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 임박한 일정의 경로를 언제 다시 계산할지.
 *
 * **틀리면 돈이 나간다.** 재계산 한 번이 카카오 경로 API 를 1~2회 부르고 하루
 * 한도가 1,000건이다. 대상과 상한을 넓히는 실수는 워커를 돌려서는 잡히지 않고
 * 며칠 뒤 "경로가 안 나온다" 로 나타난다.
 *
 * 반대로 좁히는 실수는 알람이 어제 계산한 시각으로 울리는 것이다.
 */
class RouteRefreshDecisionTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun alarmIn(minutes: Long) = now + minutes * 60 * 1000L

    @Test
    fun `임박한 일정이 없으면 아무것도 갱신하지 않는다`() {
        // 이 경우가 하루의 대부분이다. 여기서 빈 목록이 나와야 15분 주기가
        // 네트워크를 쓰지 않는다.
        val alarms = listOf(1L to now + 10 * hour, 2L to now + 30 * hour)
        assertTrue(RouteRefreshDecision.dueEventIds(alarms, now).isEmpty())
    }

    @Test
    fun `등록된 알람이 없으면 빈 목록이다`() {
        assertTrue(RouteRefreshDecision.dueEventIds(emptyList(), now).isEmpty())
    }

    @Test
    fun `지평 안의 일정을 갱신한다`() {
        val alarms = listOf(1L to alarmIn(90))
        assertEquals(listOf(1L), RouteRefreshDecision.dueEventIds(alarms, now))
    }

    @Test
    fun `지평 경계를 넘으면 갱신하지 않는다`() {
        val horizon = RouteRefreshDecision.REFRESH_HORIZON_HOURS * hour
        assertEquals(listOf(1L), RouteRefreshDecision.dueEventIds(listOf(1L to now + horizon), now))
        assertTrue(
            RouteRefreshDecision.dueEventIds(listOf(1L to now + horizon + 1), now).isEmpty()
        )
    }

    @Test
    fun `이미 지난 알람은 갱신하지 않는다`() {
        // 그 아침은 끝났다. 갱신해도 쓸 데가 없고 쿼터만 쓴다.
        val alarms = listOf(1L to now - 1, 2L to now - 5 * hour)
        assertTrue(RouteRefreshDecision.dueEventIds(alarms, now).isEmpty())
    }

    @Test
    fun `가까운 알람을 먼저 갱신한다`() {
        val alarms = listOf(3L to alarmIn(150), 1L to alarmIn(20), 2L to alarmIn(80))
        assertEquals(listOf(1L, 2L), RouteRefreshDecision.dueEventIds(alarms, now))
    }

    @Test
    fun `한 번에 갱신하는 수에 상한이 있다`() {
        // 캘린더를 대량으로 가져온 계정이 한 번에 쿼터를 태우지 못하게 한다.
        val alarms = (1L..10L).map { it to alarmIn(it * 10) }
        val due = RouteRefreshDecision.dueEventIds(alarms, now)
        assertEquals(RouteRefreshDecision.REFRESH_MAX_EVENTS, due.size)
    }

    @Test
    fun `이동 중인 일정은 갱신하지 않는다`() {
        // 경로가 바뀌면 진행률의 분모가 바뀌어 바가 뒤로 물러난다.
        val alarms = listOf(1L to alarmIn(20), 2L to alarmIn(40))
        assertEquals(
            listOf(2L),
            RouteRefreshDecision.dueEventIds(alarms, now, trackingEventId = 1L),
        )
    }

    @Test
    fun `같은 일정이 두 번 들어와도 한 번만 갱신한다`() {
        val alarms = listOf(1L to alarmIn(20), 1L to alarmIn(25))
        assertEquals(listOf(1L), RouteRefreshDecision.dueEventIds(alarms, now))
    }

    @Test
    fun `쿼터 상한이 하루 한도 안에 들어온다`() {
        // 일정 1건이 지평 내내 갱신되는 최대 횟수 × 재계산당 카카오 호출 2회.
        val runsPerEvent =
            RouteRefreshDecision.REFRESH_HORIZON_HOURS * 60 / RouteRefreshDecision.REFRESH_PERIOD_MINUTES
        val worstCaseCalls = runsPerEvent * RouteRefreshDecision.REFRESH_MAX_EVENTS * 2
        assertTrue(
            "사용자 한 명이 하루 $worstCaseCalls 회를 부른다. 팀 규모에서 1,000건을 넘길 수 있다",
            worstCaseCalls <= 50,
        )
    }

    @Test
    fun `갱신 주기가 WorkManager 최소 주기 이상이다`() {
        // 15분보다 짧게 주면 WorkManager 가 조용히 15분으로 올린다.
        assertTrue(RouteRefreshDecision.REFRESH_PERIOD_MINUTES >= 15L)
    }
}
