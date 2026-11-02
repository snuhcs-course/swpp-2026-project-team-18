package com.swpp.wakeup.domain.model

/**
 * 이동 중 "지금 경로로는 늦어요. 이 경로로 바꿀까요?"(todo §2 "이동 중 늦으면 경로 바꾸기").
 *
 * 서버가 판정한다(`switch_suggestion`, 지금 경로로 늦고 대안이 더 일찍 닿을 때만). 이 파일은
 * **언제 알릴지**와 **무슨 말로 알릴지**만 정한다. 순수 함수다(JVM 테스트 대상).
 */
data class SwitchSuggestion(
    val routeKey: String,
    /** "9호선 → 2호선". 없으면 null */
    val label: String?,
    /** 대안으로 닿는 시각 "8:57". 모르면 null */
    val arriveAtLabel: String?,
    /** 대안으로도 늦는 분. 0 이면 대안으로는 안 늦는다 */
    val lateMinutes: Int,
    /** 대안이 몇 분 일찍 닿는가 */
    val savesMinutes: Int,
)

/** 이동 화면의 경로 바꾸기 카드. [routeKey] 가 없으면 "늦음" 만 알린다(바꿀 경로가 없다). */
data class RerouteBanner(
    val headline: String,
    val body: String,
    val routeKey: String?,
)

object RerouteAlert {

    /** 이보다 빠르면 버스·지하철에 타고 있다고 본다(약 22km/h). 걷기·뛰기는 아래다 */
    const val RIDING_SPEED_MPS = 6f

    /** 한 번 빠르게 움직이면 이만큼은 탑승 중으로 본다. 정류장·역에 서 있는 동안 알리지 않게 */
    const val RIDING_HOLD_MILLIS = 2 * 60_000L

    /** 남은 이동이 이만큼 이하면 알리지 않는다(도착 직전) */
    const val NEAR_ARRIVAL_MINUTES = 5
    const val NEAR_ARRIVAL_METERS = 400.0

    /** 서버 `reason` 값. 알림을 보고 바꿀 때 함께 보낸다 */
    const val REASON_LATE_NOW = "late_now"

    // Partly AI-generated (Claude)
    /** 다음 "탑승 중" 마감. 빠르게 움직였으면 지금부터 [RIDING_HOLD_MILLIS], 아니면 그대로 둔다. */
    fun ridingUntil(previousUntilMillis: Long?, speedMps: Float?, atMillis: Long): Long? =
        if (speedMps != null && speedMps.isFinite() && speedMps >= RIDING_SPEED_MPS) {
            maxOf(previousUntilMillis ?: 0L, atMillis + RIDING_HOLD_MILLIS)
        } else {
            previousUntilMillis
        }

    // Partly AI-generated (Claude)
    /**
     * 알림(헤드업)을 띄울지. 같은 경로는 한 번만 알린다. 탑승 중·도착 직전에는 미룬다 — 미룬
     * 경로는 [notifiedKeys] 에 넣지 않으므로 내린 뒤 다음 갱신에서 알린다.
     *
     * @param remainingMinutes 지금 위치부터 남은 이동(분). 모르면 null
     * @param remainingMeters 목적지까지 남은 직선 거리(m). 모르면 null
     */
    fun shouldNotify(
        suggestion: SwitchSuggestion?,
        notifiedKeys: Set<String>,
        nowMillis: Long,
        ridingUntilMillis: Long?,
        remainingMinutes: Int?,
        remainingMeters: Double?,
    ): Boolean {
        if (suggestion == null) return false
        if (suggestion.routeKey in notifiedKeys) return false
        if (ridingUntilMillis != null && nowMillis < ridingUntilMillis) return false
        if (remainingMinutes != null && remainingMinutes <= NEAR_ARRIVAL_MINUTES) return false
        if (remainingMeters != null && remainingMeters <= NEAR_ARRIVAL_METERS) return false
        return true
    }

    // Partly AI-generated (Claude)
    /** "지금 경로로는 6분 늦어요" */
    fun headline(lateMinutes: Int?): String =
        lateMinutes?.takeIf { it > 0 }?.let { "지금 경로로는 ${it}분 늦어요" } ?: "지금 경로로는 늦어요"

    // Partly AI-generated (Claude)
    /** "9호선 → 2호선으로 가면 안 늦어요" / "… 3분 덜 늦어요" */
    fun body(suggestion: SwitchSuggestion): String {
        val name = suggestion.label?.takeIf { it.isNotBlank() } ?: "다른 경로"
        val outcome = if (suggestion.lateMinutes <= 0) "안 늦어요" else "${suggestion.savesMinutes}분 덜 늦어요"
        return "$name${roParticle(name)} 가면 $outcome"
    }

    // Partly AI-generated (Claude)
    /**
     * 이동 화면 카드. 바꿀 경로가 있으면 그 경로와 도착 예상, 없고 늦기만 하면 "늦음" 한 줄.
     * 안 늦으면 null.
     */
    fun banner(route: LiveRoute?): RerouteBanner? {
        route ?: return null
        val suggestion = route.suggestion
        if (suggestion != null) {
            val arrive = suggestion.arriveAtLabel?.let { " · $it 도착 예상" }.orEmpty()
            return RerouteBanner(
                headline = headline(route.lateMinutes),
                body = body(suggestion) + arrive,
                routeKey = suggestion.routeKey,
            )
        }
        val late = route.lateMinutes?.takeIf { it > 0 } ?: return null
        return RerouteBanner(
            headline = "지금 경로로 가면 ${late}분 늦어요",
            body = "더 일찍 닿는 경로가 없음",
            routeKey = null,
        )
    }

    /**
     * "로" / "으로". 받침이 없거나 ㄹ 받침이면 "로". 숫자는 읽는 소리로 본다(2호선 → "선" 받침
     * 있음, 5516 → "육" 받침 있음, 9 → "구" 받침 없음).
     */
    internal fun roParticle(word: String): String {
        val last = word.trim().lastOrNull { !it.isWhitespace() && it != ')' } ?: return "로"
        return when {
            last in '가'..'힣' -> {
                val jong = (last - '가') % 28
                if (jong == 0 || jong == 8) "로" else "으로"
            }
            // 영(ㅇ)·삼(ㅁ)·육(ㄱ) 만 받침이 있다. 일·칠·팔은 ㄹ 받침이라 "로".
            last.isDigit() -> if (last in "036") "으로" else "로"
            else -> "로"
        }
    }
}
