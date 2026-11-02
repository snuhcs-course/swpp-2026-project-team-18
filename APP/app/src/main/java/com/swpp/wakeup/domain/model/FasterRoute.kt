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
) {
    /** 아직 알람 전인가. 화면을 그릴 때와 버튼을 누를 때 모두 이것으로 다시 확인한다 */
    fun isOpen(nowMillis: Long): Boolean = nowMillis < alarmAtMillis
}

/** 알람 전 "더 빠른 경로로 바꾸기" 판정과 문구. 순수 함수다(JVM 테스트 대상). */
object FasterRoute {
    /** 이보다 적게 빠르면 제안하지 않는다(경로를 바꿀 만한 차이가 아니다) */
    const val MIN_FASTER_MINUTES = 5

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
        )
    }

    /** "9호선 → 2호선으로 가면 6분 빨라요" / "다른 경로로 가면 6분 빨라요" */
    fun line(offer: FasterRouteOffer): String {
        val name = offer.label ?: "다른 경로"
        return "$name${RerouteAlert.roParticle(name)} 가면 ${offer.fasterMinutes}분 빨라요"
    }
}
