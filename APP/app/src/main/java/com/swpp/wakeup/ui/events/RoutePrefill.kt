// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption

/**
 * 경로 칸 자동 채우기(C8, 11-18 사용자: "출발지 · 도착지가 전에 다녀온 일정과 같으면 경로를 자동으로 골라 주자").
 *
 * 출발지 · 도착지가 정해지면 경로 후보를 뒤에서 한 번 받아 둔다. 서버가 **같은 도착지에 간 지난 선택**을 근거로
 * 기본 경로를 줬을 때만(`preferred_basis == same_destination`) 경로 칸을 그 경로로 채우고 "지난번에 고른 경로" 를
 * 단다. "자주 쓰는 수단" 근거는 채우지 않는다 — 경로 선택 화면에서 미리 골라 두기만 한다.
 *
 * 그 경로가 이번 후보에 없으면 같은 수단의 가장 빠른 후보를 고르는 것은 서버가 한다(`route_preference.py`).
 * 응답만으로는 둘을 구분할 수 없어 같은 표시를 쓴다.
 */
internal object RoutePrefill {

    const val CAPTION = "지난번에 고른 경로"

    /** 기다릴 만큼. 넘으면 지금처럼 빈 칸으로 둔다(사용자는 그동안 다른 칸을 계속 쓴다) */
    const val TIMEOUT_MS = 10_000L

    /** 자동으로 채울 후보. 같은 도착지 근거가 아니거나 후보에 없으면 null */
    fun pick(choice: RouteChoice): RouteOption? {
        if (choice.preferredBasis != RouteCandidateResponse.BASIS_SAME_DESTINATION) return null
        val key = choice.preferredKey ?: return null
        return choice.options.firstOrNull { it.key == key }
    }

    /** 경로 칸 문구. 경로 선택 화면에서 고를 때와 같다("23분 · 지하철+도보+버스") */
    fun label(option: RouteOption): String = "${option.minutesLabel} · ${option.mode}"

    /**
     * 받아 온 결과를 아직 써도 되는가. 기다리는 동안 사용자가 경로를 직접 골랐거나(경로가 이미 있다),
     * 도착지 · 출발지를 바꿨거나, 저장 중이면 버린다.
     */
    fun stillApplies(
        routeKey: String?,
        submitting: Boolean,
        place: PlaceSearchItem?,
        origin: PlaceSearchItem?,
        requestedPlace: PlaceSearchItem,
        requestedOrigin: PlaceSearchItem?,
    ): Boolean = routeKey == null && !submitting && place == requestedPlace && origin == requestedOrigin
}
