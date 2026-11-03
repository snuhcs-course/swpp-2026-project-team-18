package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 알람을 끈 뒤 집을 나서기 전의 "지금 나가도 늦나" 확인(todo "출발 전 재계획").
 *
 * 추적 서비스가 알람을 끄는 순간과 출발 10분 전, 최대 두 번 지금 있는 곳(출발지) 기준 경로를 묻는다.
 * 이 파일은 **언제 묻는지**와 **무슨 말로 보여 줄지**만 정한다. 순수 함수다(JVM 테스트 대상).
 */
object PreDepartureCheck {
    /** 한 번의 추적에서 묻는 최대 횟수. 알람을 끌 때 한 번, 출발 전에 한 번 */
    const val MAX_CALLS = 2

    /** 두 번째 확인은 계획한 출발 시각보다 이만큼 먼저 한다 */
    const val LEAD_MILLIS = 10 * 60_000L

    /** 두 번째 확인은 첫 확인에서 이만큼은 지난 뒤에 한다(연달아 두 번 부르지 않는다) */
    const val MIN_GAP_MILLIS = 5 * 60_000L

    /** 이보다 오래된 결과는 카드로 보여 주지 않는다 */
    const val MAX_AGE_MILLIS = 20 * 60_000L

    /**
     * 두 번째 확인 시각(epoch ms). `max(출발 시각 − 10분, 첫 확인 + 5분)`.
     *
     * 계획한 출발 시각을 모르거나, 계산한 시각이 일정 시작 이후면 null — 그때는 확인하지 않는다.
     */
    fun secondCallAtMillis(departByMillis: Long?, firstCallAtMillis: Long, startAtMillis: Long): Long? {
        departByMillis ?: return null
        val at = maxOf(departByMillis - LEAD_MILLIS, firstCallAtMillis + MIN_GAP_MILLIS)
        return at.takeIf { it < startAtMillis }
    }

    /** "지금 나가도 6분 늦어요" / 분을 모르면 "지금 나가도 늦어요" */
    fun headline(lateMinutes: Int?): String =
        lateMinutes?.takeIf { it > 0 }?.let { "지금 나가도 ${it}분 늦어요" } ?: "지금 나가도 늦어요"

    /** 결과가 카드로 보여 줄 만큼 새것인가 */
    fun isFresh(fetchedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - fetchedAtMillis in 0..MAX_AGE_MILLIS

    /**
     * 준비 중 화면의 카드. 바꿀 경로가 있으면 그 경로와 도착 예상, 없고 늦기만 하면 "늦음" 한 줄.
     * 안 늦으면 null.
     */
    fun banner(route: LiveRoute?): RerouteBanner? {
        route ?: return null
        val suggestion = route.suggestion
        if (suggestion != null) {
            val arrive = suggestion.arriveAtLabel?.let { " · $it 도착 예상" }.orEmpty()
            return RerouteBanner(
                headline = headline(route.lateMinutes),
                body = RerouteAlert.body(suggestion) + arrive,
                routeKey = suggestion.routeKey,
            )
        }
        val late = route.lateMinutes?.takeIf { it > 0 } ?: return null
        return RerouteBanner(
            headline = headline(late),
            body = "더 일찍 닿는 경로가 없음",
            routeKey = null,
        )
    }
}
