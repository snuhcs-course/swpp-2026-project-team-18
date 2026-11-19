package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 알람 전에 더 빠른 경로가 있다는 제안(todo "출발 전 재계획").
 *
 * @property alarmAtMillis 이 제안이 유효한 마감. 알람이 울린 뒤에는 일정에 저장한 경로를 바꾸지 않는다
 *   (알람이 울린 시점의 계획이 학습 기준이 된다)
 */
data class FasterRouteOffer(
    val routeKey: String,
    /** "9호선 → 2호선". 없으면 null */
    val label: String?,
    val fasterMinutes: Int,
    val alarmAtMillis: Long,
    /** 이 경로로 갈 때의 이동 시간(분). 지금 경로의 이동 시간을 모르면 null */
    val travelMinutes: Int? = null,
) {
    /** 아직 알람 전인가. 화면을 그릴 때와 버튼을 누를 때 모두 이것으로 다시 확인한다 */
    fun isOpen(nowMillis: Long): Boolean = nowMillis < alarmAtMillis
}

/** 알람 전 "더 빠른 경로로 바꾸기" 판정과 문구. 순수 함수다(JVM 테스트 대상). */
object FasterRoute {
    /**
     * 이보다 적게 빠르면 제안하지 않는다. 서버가 대안을 붙이는 기준(1분 이상 빠름)과 같다 — 지도에는 대안 선이
     * 그려지는데 버튼은 없던 어긋남을 없앴다(11-17 사용자: "더 빠른 경로가 있을 때 바꿀 버튼이 없음").
     */
    const val MIN_FASTER_MINUTES = 1

    /**
     * 제안할 수 있으면 제안, 아니면 null.
     *
     * 계산된 일정이고, 안 감이 아니고, 알람이 켜져 있고, **알람이 아직 울리기 전**이고, 대안 key 가
     * 지금 경로와 다르고, 대안이 [MIN_FASTER_MINUTES] 분 이상 빠를 때만이다.
     */
    fun offer(
        isComputed: Boolean,
        skipped: Boolean,
        alarmOn: Boolean,
        alarmAtMillis: Long?,
        nowMillis: Long,
        routeKey: String?,
        altRouteKey: String?,
        altLabel: String?,
        altFasterMinutes: Int?,
        /** 지금 경로의 이동 시간(분). 주면 제안에 대안의 이동 시간을 붙인다 */
        currentTravelMinutes: Int? = null,
    ): FasterRouteOffer? {
        if (!isComputed || skipped || !alarmOn) return null
        if (alarmAtMillis == null || nowMillis >= alarmAtMillis) return null
        val key = altRouteKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (key == routeKey?.trim()) return null
        val faster = altFasterMinutes?.takeIf { it >= MIN_FASTER_MINUTES } ?: return null
        return FasterRouteOffer(
            routeKey = key,
            label = altLabel?.trim()?.takeIf { it.isNotEmpty() },
            fasterMinutes = faster,
            alarmAtMillis = alarmAtMillis,
            travelMinutes = currentTravelMinutes?.minus(faster)?.takeIf { it > 0 },
        )
    }

    /** 카드 머리(11-16 사용자: 지도에 보이는 데서 그치지 말고 고를 수 있게) */
    const val TITLE = "더 빠른 경로가 있어요"

    /** 카드 둘째 줄. "9호선 → 2호선 · 35분 (7분 빠름)" — 이동 시간을 모르면 빼고, 이름이 없으면 "다른 경로" */
    fun detail(offer: FasterRouteOffer): String =
        listOfNotNull(offer.label ?: "다른 경로", offer.travelMinutes?.let { "${it}분" }).joinToString(" · ") +
            " (${offer.fasterMinutes}분 빠름)"

    /**
     * 바꾼 뒤 스낵바. 알람 시각이 바뀌었으면 그 시각을 말한다 — "경로를 바꿨어요. 알람이 오전 7:47로 바뀌었어요".
     * [undo] 면 "경로를 되돌렸어요" 로 시작한다. 새 알람 시각을 모르면 다시 맞췄다고만 한다.
     */
    fun switchedMessage(
        oldAlarmMillis: Long?,
        newAlarmMillis: Long?,
        undo: Boolean = false,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String {
        val head = if (undo) "경로를 되돌렸어요." else "경로를 바꿨어요."
        if (newAlarmMillis == null) return "$head 알람을 다시 맞췄어요"
        val minute = 60_000L
        if (oldAlarmMillis != null && oldAlarmMillis / minute == newAlarmMillis / minute) return "$head 알람 시각은 그대로예요"
        val at = java.time.Instant.ofEpochMilli(newAlarmMillis).atZone(zone)
        val clock = AlarmTimeChoice.clockLabel(at.hour * 60 + at.minute)
        return "$head 알람이 $clock${RerouteAlert.roParticle(clock)} 바뀌었어요"
    }

    /** "9호선 → 2호선으로 가면 6분 빨라요" / "다른 경로로 가면 6분 빨라요" */
    fun line(offer: FasterRouteOffer): String {
        val name = offer.label ?: "다른 경로"
        return "$name${RerouteAlert.roParticle(name)} 가면 ${offer.fasterMinutes}분 빨라요"
    }
}

/**
 * [이 경로로 바꾸기] 스낵바의 [되돌리기]. 바꾸기 전 경로 key 로 다시 바꾼다.
 *
 * @property alarmAtMillis 바꾼 뒤의 알람 시각. 되돌릴 때도 "알람 전에만" 규칙을 이 시각으로 본다
 */
data class RouteSwitchUndo(val eventId: Long, val routeKey: String, val alarmAtMillis: Long?)
