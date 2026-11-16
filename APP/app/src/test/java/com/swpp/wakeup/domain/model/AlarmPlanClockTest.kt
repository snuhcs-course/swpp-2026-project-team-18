// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알람 결정 화면의 시각으로 정해지는 값([AlarmPlanView.at]).
 *
 * 화면은 그릴 때마다 지금 시각으로 남은 시간 · 알람 · 출발 · 일정이 지났는가를 다시 정한다. 예전에는 계획을 받을 때
 * 한 번만 정해서, 밤에 열어 둔 화면이 아침에도 "9시간 0분 남음" · "알람 전" 이었다. 남은 분은 올림한다 — 내림하면
 * 59초 남은 알람을 "지난 알람" 이라고 하고 단계도 일찍 "준비 중" 이 된다.
 */
class AlarmPlanClockTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** 알람 07:00. 출발은 알람 30분 뒤, 일정은 알람 2시간 뒤다 */
    private val alarm = 1_800_000_000_000L

    private val plan = AlarmPlanView(
        eventId = 1,
        whenLabel = "내일 아침",
        dateLabel = "11월 9일 월",
        eventTitle = "09:00 1교시 수업",
        eventPlace = "서울대",
        sensitivityTag = "수업",
        alarmAt = "7:00",
        meridiem = "AM",
        remaining = null,
        onTimeProbability = null,
        tauUsed = 0.9,
        confidence = ConfidenceView(null, "정시 도착 확률 · 아직 계산할 수 없음", null, null),
        breakdown = emptyList(),
        totalMinutes = 80,
        arrivalLine = null,
        startAtMillis = alarm + 2 * hour,
        alarmAtMillis = alarm,
        departByMillis = alarm + 30 * minute,
        status = "ok",
        statusLabel = "계산 완료",
    )

    @Test
    fun `남은 분은 올림해서 알람 직전에도 지난 알람이라고 하지 않는다`() {
        // Given 알람 07:00 인 계획
        // When 30초 전 · 61초 전 · 1초 전에 그린다
        val before30s = plan.at(alarm - 30_000L)
        val before61s = plan.at(alarm - 61_000L)
        val before1s = plan.at(alarm - 1_000L)

        // Then 아직 울리지 않았으니 남은 시간을 말하고 알람 전으로 본다
        assertEquals("0시간 1분 남음", before30s.remaining)
        assertFalse(before30s.alarmPassed)
        assertEquals("0시간 2분 남음", before61s.remaining)
        assertEquals("0시간 1분 남음", before1s.remaining)
        assertFalse(before1s.alarmPassed)
    }

    @Test
    fun `알람 시각이 되면 지난 알람이고 알람이 지난 것으로 본다`() {
        // When 알람 시각 정각과 1초 뒤에 그린다
        val atAlarm = plan.at(alarm)
        val after1s = plan.at(alarm + 1_000L)

        // Then 둘 다 지난 알람이다
        assertEquals("지난 알람", atAlarm.remaining)
        assertTrue(atAlarm.alarmPassed)
        assertEquals("지난 알람", after1s.remaining)
        assertTrue(after1s.alarmPassed)
    }

    @Test
    fun `밤에 받은 계획도 아침에 그리면 남은 시간과 단계가 지금을 따른다`() {
        // Given 22:00(알람 9시간 전)에 받은 계획
        val night = plan.at(alarm - 9 * hour)

        // When 그 계획을 그대로 들고 08:00(알람 1시간 뒤)에 다시 그린다
        val morning = night.at(alarm + hour)

        // Then 밤에는 알람 전이고, 아침에는 알람 · 출발이 지났다(추적이 없으면 출발 확인 안 됨)
        assertEquals("9시간 0분 남음", night.remaining)
        assertEquals(TripStage.BEFORE_ALARM, stageOf(night))
        assertEquals("지난 알람", morning.remaining)
        assertTrue(morning.alarmPassed)
        assertTrue(morning.departPassed)
        assertFalse(morning.eventPassed)
        assertEquals(TripStage.DEPARTURE_UNKNOWN, stageOf(morning))
    }

    @Test
    fun `일정 시각이 지나면 지난 일정이다`() {
        // When 일정 시각 1분 뒤에 그린다
        val after = plan.at(alarm + 2 * hour + minute)

        // Then 일정까지 지났다
        assertTrue(after.eventPassed)
        assertEquals(TripStage.PAST, stageOf(after))
    }

    @Test
    fun `알람 시각을 모르면 남은 시간과 알람 판정을 그대로 둔다`() {
        // Given 알람을 계산하지 못한 계획(시각 없음)
        val notComputed = plan.copy(alarmAt = null, alarmAtMillis = null, departByMillis = null, status = "no_home")

        // When 아무 때나 그린다
        val drawn = notComputed.at(alarm + 5 * hour)

        // Then 남은 시간을 지어내지 않고 알람 · 출발이 지났다고 하지 않는다
        assertNull(drawn.remaining)
        assertFalse(drawn.alarmPassed)
        assertFalse(drawn.departPassed)
    }

    /** 추적 없이 시각으로만 정한 단계. 화면이 [TripStage.of] 에 넘기는 값과 같다 */
    private fun stageOf(p: AlarmPlanView) = TripStage.of(
        tracked = null,
        alarmPassed = p.alarmPassed,
        eventPassed = p.eventPassed,
        prepApplies = p.prepApplies,
        alarmOn = p.alarmOn,
        departPassed = p.departPassed,
    )
}
