package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCompletionTest {
    private val start = 1_800_000_000_000L
    private val minute = 60_000L
    private val plan = AlarmPlanView(
        eventId = 1, whenLabel = "오늘", dateLabel = "11/28", eventTitle = "일정", eventPlace = "목적지",
        sensitivityTag = null, alarmAt = "7:00", meridiem = "AM", remaining = null,
        onTimeProbability = null, tauUsed = .9, confidence = ConfidenceView(null, "기록 없음"),
        breakdown = emptyList(), totalMinutes = 60, arrivalLine = null, status = "ok", statusLabel = null,
        startAtMillis = start, alarmAtMillis = start - 60 * minute, departByMillis = start - 20 * minute,
        alarmOn = true,
    )

    @Test fun `scheduled start alone never records or infers arrival`() {
        assertEquals(EventCompletion.State.OPEN, EventCompletion.of(start, start, false, false))
        assertEquals(EventCompletion.State.OPEN, EventCompletion.of(start, start + 59 * minute, false, false))
    }

    @Test fun `tracking deadline expires without claiming arrival`() {
        val deadline = start + AlarmSchedule.TRACKING_GRACE_MILLIS
        assertEquals(EventCompletion.State.OPEN, EventCompletion.of(start, deadline - 1, false, false))
        assertEquals(EventCompletion.State.EXPIRED_UNCONFIRMED, EventCompletion.of(start, deadline, false, false))
    }

    @Test fun `recorded arrival survives the observation deadline`() {
        assertEquals(EventCompletion.State.ARRIVED, EventCompletion.of(start, start + 2 * 60 * minute, true, false))
        assertEquals(TripStage.ARRIVED, EventCompletion.stage(plan, null, true, start + 2 * 60 * minute))
    }

    @Test fun `explicit skipped event is separate from an arrival`() {
        assertEquals(EventCompletion.State.SKIPPED, EventCompletion.of(start, start, false, true))
        assertEquals(TripStage.PAST, EventCompletion.stage(plan.copy(skipped = true), TripStage.IN_TRANSIT, false, start))
    }

    @Test fun `late open journey with missing GPS still offers manual arrival`() {
        val stage = EventCompletion.stage(plan.copy(eventPassed = true), null, false, start + 20 * minute)
        assertEquals(TripStage.DEPARTURE_UNKNOWN, stage)
        assertTrue(stage.canMarkArrived)
    }

    @Test fun `a loaded plan crosses its start boundary without needing a reload`() {
        assertEquals(TripStage.PREPARING, EventCompletion.stage(plan, null, false, start - 30 * minute))
        assertEquals(TripStage.DEPARTURE_UNKNOWN, EventCompletion.stage(plan, null, false, start))
    }

    @Test fun `stale transit snapshot cannot keep an expired journey open forever`() {
        val expired = EventCompletion.stage(plan, TripStage.IN_TRANSIT, false, start + 60 * minute)
        assertEquals(TripStage.PAST, expired)
        assertFalse(expired.canMarkArrived)
    }

    @Test fun `tracking before the deadline still wins over missing planned departure`() {
        assertEquals(TripStage.IN_TRANSIT, EventCompletion.stage(plan, TripStage.IN_TRANSIT, false, start + 10 * minute))
    }

    @Test fun `alarm off and future events do not gain a manual arrival action`() {
        assertEquals(TripStage.BEFORE_ALARM, EventCompletion.stage(plan, null, false, start - 90 * minute))
        assertEquals(TripStage.ALARM_OFF, EventCompletion.stage(plan.copy(alarmOn = false), null, false, start))
    }

    @Test fun `legacy plan without timestamp retains its explicit past signal`() {
        assertEquals(TripStage.PAST, EventCompletion.stage(plan.copy(startAtMillis = null, eventPassed = true), null, false, start))
        assertEquals(EventCompletion.State.OPEN, EventCompletion.of(null, start, false, false))
    }
}
