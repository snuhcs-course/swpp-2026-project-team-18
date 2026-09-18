package com.swpp.wakeup.domain.model

/**
 * 알람 하나를 등록하고 그 아침의 이동을 따라가는 데 필요한 값 전부.
 *
 * [AlarmPlanView] 와 나눈 이유가 있다. 그쪽은 **화면용**이라 시각이 이미
 * "7:40" 같은 문자열이고 좌표가 없다. 알람 등록에는 epoch 밀리초가 필요하고
 * 출발·도착 판별에는 좌표가 필요하다. 표시 문자열에서 되돌릴 수 없다.
 *
 * **디스크에 저장된다.** 재부팅하면 등록한 알람이 전부 사라지는데
 * ([com.swpp.wakeup.alarm.BootReceiver] 참고) 그때 서버가 닿지 않을 수 있다.
 * 그래서 Gson 으로 직렬화할 수 있는 평평한 모양을 유지한다 — 여기에
 * `java.time` 타입이나 sealed 타입을 넣으면 복원이 깨진다.
 */
data class AlarmSchedule(
    val eventId: Long,

    /** 알람이 울릴 시각. */
    val alarmAtMillis: Long,
    /** 계획된 출발 시각. 출발 판별의 비교 기준이다. */
    val departByMillis: Long?,
    /** 계획된 도착 시각. 도착 판별의 비교 기준이다. */
    val arriveAtMillis: Long?,
    /** 일정 시작 시각. 추적을 언제까지 할지 정하는 데 쓴다. */
    val startAtMillis: Long,

    /** 알람 화면에 크게 띄우는 "7:40". */
    val alarmLabel: String,
    /** "AM" / "PM" */
    val meridiem: String,
    /** "09:00 자료구조 및 알고리즘" */
    val eventLine: String,
    /** "302동 105호". 없으면 null */
    val placeName: String?,
    /** "8:50 도착 예정". 계산 못 했으면 null */
    val arrivalLine: String?,

    /** 출발 판별의 기준점 = 프로필 집 위치. */
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    /** 도착 판별의 기준점 = 일정 장소. */
    val destLat: Double? = null,
    val destLng: Double? = null,
) {
    /** 집이 있으면 "언제 나갔는지" 를 판별할 수 있다. */
    val canDetectDeparture: Boolean get() = homeLat != null && homeLng != null

    /** 목적지가 있으면 "언제 닿았는지" 를 판별할 수 있다. */
    val canDetectArrival: Boolean get() = destLat != null && destLng != null

    val canTrack: Boolean get() = canDetectDeparture || canDetectArrival

    /**
     * 추적을 포기하는 시각.
     *
     * 일정 시작 후 한 시간까지 본다. 그때까지 도착 판정이 안 나면 더 기다려도
     * 의미가 없다 — 이미 지각이고, 서비스만 배터리를 먹는다.
     */
    val trackingDeadlineMillis: Long
        get() = startAtMillis + TRACKING_GRACE_MILLIS

    companion object {
        private const val TRACKING_GRACE_MILLIS = 60 * 60 * 1000L
    }
}
