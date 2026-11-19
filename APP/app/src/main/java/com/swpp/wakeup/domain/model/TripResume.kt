// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

/**
 * 알람이 울렸고 아직 끝나지 않은 아침(11-17 사용자: 울린 알람이 도착 전까지 홈 맨 위에 커야 한다).
 * 순수 함수만 둔다(JVM 테스트 대상).
 */
object TripResume {

    /**
     * 진행 중인 아침인가: 알람이 켜져 있고 안 감이 아니며, **울릴 시각이 지났고**(미룬 알람은 미룬 시각),
     * 도착하지 않았고, 추적 마감(시작 + [HomeList.OPEN_TRIP_MILLIS]) 전이다.
     */
    fun isOngoing(
        e: UpcomingEvent,
        nowMillis: Long,
        arrived: Set<Long>,
        device: Map<Long, AlarmSchedule>,
    ): Boolean {
        if (!e.alarmOn || e.skipped || e.id in arrived) return false
        val ring = HomeList.effectiveAlarmMillis(e, nowMillis, device) ?: return false
        return ring <= nowMillis && nowMillis < e.startAtMillis + HomeList.OPEN_TRIP_MILLIS
    }

    /** 진행 중인 아침 중 맨 위에 둘 것. 둘 이상이면 가장 최근에 울린 것 */
    fun ongoing(
        events: List<UpcomingEvent>,
        nowMillis: Long,
        arrived: Set<Long>,
        device: Map<Long, AlarmSchedule>,
    ): UpcomingEvent? = events
        .filter { isOngoing(it, nowMillis, arrived, device) }
        .maxByOrNull { HomeList.effectiveAlarmMillis(it, nowMillis, device) ?: Long.MIN_VALUE }

    /** 진행 중 카드의 단계 */
    enum class Stage(val label: String) {
        PREPARING("준비 중"),
        IN_TRANSIT("이동 중"),

        /** 추적이 돌고 있지 않다 — 앱이 강제로 꺼졌거나 권한이 없어 출발 · 도착을 기록하지 못한다 */
        NOT_TRACKING("이동을 기록하고 있지 않아요"),
    }

    /**
     * 지금 단계. 이 일정을 추적 중이면 그 단계([inTransit]), 아니면 [Stage.NOT_TRACKING].
     */
    fun stage(eventId: Long, trackingEventId: Long?, inTransit: Boolean): Stage = when {
        trackingEventId != eventId -> Stage.NOT_TRACKING
        inTransit -> Stage.IN_TRANSIT
        else -> Stage.PREPARING
    }

    /**
     * 카드의 "다음 할 일" 한 줄. 출발 전이면 출발 시각, 이동 중이면 도착 예정, 기록이 멈췄으면 다시 시작하라는 말.
     * [departClock] · [arriveClock] 은 "오전 9:18" 같은 화면 시각이다(모르면 null).
     */
    fun nextLine(stage: Stage, departClock: String?, arriveClock: String?, startClock: String): String = when (stage) {
        Stage.PREPARING -> departClock?.let { "${it}까지 출발해요" } ?: "${startClock} 일정에 늦지 않게 출발해요"
        Stage.IN_TRANSIT -> arriveClock?.let { "$it 도착 예정이에요" } ?: "${startClock} 일정으로 가고 있어요"
        Stage.NOT_TRACKING -> "출발과 도착을 기록하려면 다시 시작해 주세요"
    }

    /**
     * 앱을 열었을 때 이어 갈 추적. 남은 사본([active])이 있고, 지금 그 일정을 추적하지 않으며, 도착하지 않았고,
     * 추적 마감 전일 때만. 정상으로 끝난 추적은 사본이 지워져 있어 다시 시작하지 않는다.
     */
    fun toResume(
        active: AlarmSchedule?,
        trackingEventId: Long?,
        arrived: Set<Long>,
        nowMillis: Long,
    ): AlarmSchedule? {
        val s = active ?: return null
        if (trackingEventId == s.eventId) return null
        if (s.eventId in arrived) return null
        if (nowMillis >= s.trackingDeadlineMillis) return null
        if (nowMillis < s.alarmAtMillis) return null
        return s
    }
}
