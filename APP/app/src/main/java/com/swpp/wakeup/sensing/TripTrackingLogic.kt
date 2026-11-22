package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import com.google.gson.Gson
import com.swpp.wakeup.alarm.AlarmNotifications
import com.swpp.wakeup.data.local.withDiskDefaults
import com.swpp.wakeup.domain.model.AlarmSchedule
import kotlin.math.roundToInt

/**
 * [TripTrackingService] 에서 Android 없이 정하는 부분.
 *
 * 서비스는 위치 콜백 · 포그라운드 알림 · 핸들러에 묶여 있어 JVM 에서 띄울 수 없다. 그 안에
 * 섞여 있던 **값만 보는 판단**(알림 문구, 기준점, 위치 주기, 다시 전달 여부)을 여기로 옮겨
 * `TripTrackingLogicTest` 로 고정한다. 서비스는 이 함수들을 부르기만 한다. 동작은 그대로다.
 */
internal object TripTrackingLogic {

    const val TITLE_TRACKING = "이동 기록 중"
    const val TITLE_ARRIVED = "도착을 기록했어요"

    /** 출발을 기다리는 동안. 150m 를 구분하면 되므로 느슨하게 본다. */
    const val IDLE_INTERVAL_MS = 30_000L

    /** 이동 중. 100m 반경 진입을 빠르게 확인한다. */
    const val TRANSIT_INTERVAL_MS = 10_000L

    /** 위치 갱신 주기. 고정확도(이동 중)면 짧게, 아니면 길게 */
    fun updateIntervalMillis(highAccuracy: Boolean): Long =
        if (highAccuracy) TRANSIT_INTERVAL_MS else IDLE_INTERVAL_MS

    /**
     * 시스템이 다시 전달한 시작인가. 프로세스가 죽었다 살아난 재전달(`START_FLAG_REDELIVERY`)과
     * 앱이 끊긴 추적을 잇는 [TripTrackingService.ACTION_RESUME] 은 둘 다 이어 가기다 — 처음부터
     * 다시 시작하면 이미 집을 나선 사람의 출발을 "준비 중" 으로 되돌린다.
     */
    fun isRedelivery(flags: Int, redeliveryFlag: Int, action: String?): Boolean =
        flags and redeliveryFlag != 0 || action == TripTrackingService.ACTION_RESUME

    /** 시작 인텐트에 실린 일정. 없거나 읽지 못하면 null — 서비스가 멈춘다 */
    fun parseSchedule(gson: Gson, raw: String?): AlarmSchedule? = raw?.let {
        runCatching { gson.fromJson(it, AlarmSchedule::class.java)?.withDiskDefaults() }.getOrNull()
    }

    /** 출발 판별 기준점(집). 좌표가 하나라도 없으면 null */
    fun homeOf(schedule: AlarmSchedule): GeoPoint? =
        schedule.homeLat?.let { lat -> schedule.homeLng?.let { lng -> GeoPoint(lat, lng) } }

    /** 도착 판별 기준점(목적지). 좌표가 하나라도 없으면 null */
    fun destinationOf(schedule: AlarmSchedule): GeoPoint? =
        schedule.destLat?.let { lat -> schedule.destLng?.let { lng -> GeoPoint(lat, lng) } }

    /**
     * 위치 하나 → 판정기 입력. 오차가 없으면 판정 근거가 되지 못하게 최대값으로, 시각이 없으면
     * 받은 시각으로 둔다.
     */
    fun fixOf(
        lat: Double,
        lng: Double,
        hasAccuracy: Boolean,
        accuracyM: Float,
        timeMillis: Long,
        nowMillis: Long,
    ): LocationFix = LocationFix(
        lat = lat,
        lng = lng,
        accuracyM = if (hasAccuracy) accuracyM else Float.MAX_VALUE,
        atMillis = if (timeMillis > 0) timeMillis else nowMillis,
    )

    /** 집 단계를 막 벗어났는가. 이때 고정확도로 올리고 출발 전 확인을 멈춘다 */
    fun leftHome(before: TripGeofence.Phase, after: TripGeofence.Phase): Boolean =
        before == TripGeofence.Phase.BEFORE_DEPARTURE && after != TripGeofence.Phase.BEFORE_DEPARTURE

    /** 시작 알림의 한 줄. 무엇을 기록하는지 기준점에 맞춰 적는다 */
    fun initialText(schedule: AlarmSchedule): String = buildString {
        append(schedule.eventLine)
        append(" · ")
        append(
            when {
                schedule.canDetectDeparture && schedule.canDetectArrival ->
                    "집을 나서는 시각과 도착 시각을 기록해요"
                schedule.canDetectArrival -> "도착 시각을 기록해요"
                else -> "집을 나서는 시각을 기록해요"
            }
        )
    }

    /**
     * 판정기 단계를 알림 문구로 옮긴다.
     *
     * 출발 판별 기준점이 없으면 판정기가 처음부터 [TripGeofence.Phase.IN_TRANSIT]
     * 로 시작한다. 그건 "출발을 알 수 없어서 건너뛴 것" 이지만 사용자에게는
     * 그대로 "이동 중" 으로 보여 주는 것이 맞다 — 준비 중이라고 하면 출발을
     * 기다리는 것처럼 보이는데 실제로는 아무것도 기다리지 않는다.
     */
    fun phaseLabel(phase: TripGeofence.Phase): AlarmNotifications.TripPhaseLabel = when (phase) {
        TripGeofence.Phase.BEFORE_DEPARTURE -> AlarmNotifications.TripPhaseLabel.PREPARING
        TripGeofence.Phase.IN_TRANSIT -> AlarmNotifications.TripPhaseLabel.IN_TRANSIT
        TripGeofence.Phase.ARRIVED -> AlarmNotifications.TripPhaseLabel.ARRIVED
    }

    /** "1.2km" · "350m" */
    fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000)
        else "${meters.roundToInt()}m"

    /**
     * 진행 알림에 남은 거리를 적을지. 마지막으로 받아들인 위치가 오래됐으면 적지 않는다 —
     * 지하철 안에서 10분 전 거리를 "지금" 처럼 보여 주면 틀린 말이다.
     */
    fun freshRemainingMeters(distanceM: Double?, lastFixAtMillis: Long?, nowMillis: Long): Double? =
        distanceM?.takeIf {
            lastFixAtMillis != null && nowMillis - lastFixAtMillis <= TripGeofence.EVIDENCE_MAX_AGE_MS
        }

    /** 진행 알림 한 장의 제목 · 본문 · 단계. 단계가 null 이면 아직 집에 있는지도 모르는 때다 */
    data class Progress(
        val title: String,
        val text: String,
        val phase: AlarmNotifications.TripPhaseLabel?,
    )

    /**
     * 진행 알림 내용.
     *
     * @param atHomeConfirmed 출발 전 단계에서 집에 있다는 근거가 있는가. 없으면 "위치 확인 중" 이다 —
     *   이미 집을 나선 사람에게 "준비 중" 을 단정하지 않는다.
     */
    fun progress(
        eventLine: String,
        phase: TripGeofence.Phase,
        atHomeConfirmed: Boolean,
        remainingM: Double?,
    ): Progress {
        val unknown = phase == TripGeofence.Phase.BEFORE_DEPARTURE && !atHomeConfirmed
        val label = if (unknown) null else phaseLabel(phase)
        val text = buildString {
            append(eventLine)
            append(" · ")
            when {
                unknown -> append("위치 확인 중")
                phase == TripGeofence.Phase.BEFORE_DEPARTURE -> append("아직 출발 전")
                phase == TripGeofence.Phase.IN_TRANSIT -> {
                    if (remainingM != null) {
                        append("목적지까지 ")
                        append(formatDistance(remainingM))
                    } else {
                        append("목적지로 가는 중")
                    }
                }
                else -> append("도착을 기록했어요")
            }
        }
        return Progress(label?.label ?: TITLE_TRACKING, text, label)
    }

    /**
     * 진행 알림에 "도착했어요" 버튼을 달지. 이동 중이고, 아직 수동 도착을 누르지 않았고,
     * 도착이 기록되지 않았을 때만 — 이미 끝난 아침에 버튼을 남기면 두 번 기록된다.
     */
    fun offersManualArrive(
        phase: AlarmNotifications.TripPhaseLabel?,
        manualArrivedAtMillis: Long?,
        alreadyArrived: Boolean,
    ): Boolean =
        phase == AlarmNotifications.TripPhaseLabel.IN_TRANSIT && manualArrivedAtMillis == null && !alreadyArrived

    /** 도착 결과 알림의 일정 줄. "09:30 해석개론 · 302동 105호" */
    fun arrivalLine(schedule: AlarmSchedule): String =
        listOfNotNull(schedule.eventLine, schedule.placeName).joinToString(" · ")

    /** 수동 도착 뒤 알림 본문 */
    fun manualArrivedText(schedule: AlarmSchedule): String = "${schedule.eventLine} · 도착을 기록했어요"
}
