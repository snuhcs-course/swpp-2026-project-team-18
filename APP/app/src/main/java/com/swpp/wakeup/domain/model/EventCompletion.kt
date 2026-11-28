package com.swpp.wakeup.domain.model

/** Time passing can expire an observation window; it cannot prove arrival. */
object EventCompletion {
    enum class State { OPEN, ARRIVED, SKIPPED, EXPIRED_UNCONFIRMED }

    fun of(
        startAtMillis: Long?,
        nowMillis: Long,
        arrivalRecorded: Boolean,
        skipped: Boolean,
    ): State = when {
        arrivalRecorded -> State.ARRIVED
        skipped -> State.SKIPPED
        startAtMillis != null && nowMillis >= startAtMillis &&
            nowMillis - startAtMillis >= AlarmSchedule.TRACKING_GRACE_MILLIS -> State.EXPIRED_UNCONFIRMED
        else -> State.OPEN
    }

    /** Use the same observation window as the home list, even after start time. */
    fun stage(plan: AlarmPlanView, tracked: TripStage?, arrivalRecorded: Boolean, nowMillis: Long): TripStage {
        when (of(plan.startAtMillis, nowMillis, arrivalRecorded, plan.skipped)) {
            State.ARRIVED -> return TripStage.ARRIVED
            State.SKIPPED, State.EXPIRED_UNCONFIRMED -> return TripStage.PAST
            State.OPEN -> Unit
        }
        val startPassed = plan.startAtMillis?.let { nowMillis >= it } ?: plan.eventPassed
        return TripStage.of(
            tracked = tracked,
            alarmPassed = plan.alarmAtMillis?.let { nowMillis >= it } ?: (plan.alarmPassed || startPassed),
            eventPassed = plan.startAtMillis == null && plan.eventPassed,
            prepApplies = plan.prepApplies,
            alarmOn = plan.alarmOn,
            departPassed = plan.departByMillis?.let { nowMillis >= it } ?: (plan.departPassed || startPassed),
        )
    }
}
