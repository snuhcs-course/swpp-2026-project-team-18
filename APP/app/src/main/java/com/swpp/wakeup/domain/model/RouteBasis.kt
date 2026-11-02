package com.swpp.wakeup.domain.model

import java.util.Locale

/**
 * 알람 결정 화면 "계산 방법" 의 경로 기준 한 줄. 예 "9호선 → 2호선 · 6.0km 기준".
 *
 * todo §2 "적용 알람 문구 수정": 서버가 만든 문장(`route_detail`·`route_summary`)을 그대로
 * 쓰지 않고 앱이 구조 데이터(경로 key)로 만든다. key 를 읽지 못하면 서버 문장으로 돌아간다.
 * 서버 문자열 정리(코드를 주고 문장은 앱이)는 백엔드 P2 라 아직 없다.
 */
object RouteBasis {

    // Partly AI-generated (Claude)
    /** 기준 한 줄. 경로도 거리도 모르면 null */
    fun label(routeKey: String?, routeDetail: String?, routeSummary: String?, distanceM: Int?): String? {
        val route = fromKey(routeKey)
            ?: routeDetail?.trim()?.takeIf { it.isNotEmpty() }
            ?: routeSummary?.trim()?.takeIf { it.isNotEmpty() }
        val km = distanceM?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1fkm", it / 1000.0) }
        val parts = listOfNotNull(route, km)
        return if (parts.isEmpty()) null else parts.joinToString(" · ") + " 기준"
    }

    // Partly AI-generated (Claude)
    /**
     * 경로 key 를 사람 말로. `transit:9호선>2호선` → "9호선 → 2호선", `car`·`car:…` → "자동차",
     * `walk` → "도보", `bicycle` → "자전거". 모르는 모양이면 null.
     */
    fun fromKey(routeKey: String?): String? {
        val key = routeKey?.trim().orEmpty()
        return when {
            key.isEmpty() -> null
            key == "walk" -> "도보"
            key == "bicycle" -> "자전거"
            key == "car" || key.startsWith("car:") -> "자동차"
            key.startsWith("transit:") -> key.removePrefix("transit:")
                .split('>')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" → ")
            else -> null
        }
    }
}
