// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.data.remote.PlaceSearchItem
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 알람 추가(H2-8 `545:192`)의 도착지 칩에 넣을 최근 도착지. 서버를 늘리지 않고 이미 받은 일정 목록에서 뽑는다.
 *
 * @property id 일정 id. 클수록 나중에 만든 일정이다(최신순의 기준)
 */
data class DestinationCandidate(
    val id: Long,
    val place: PlaceSearchItem,
    val destinationIsHome: Boolean,
)

object RecentDestinations {

    /** 칩 수(최근 2곳 + [집]) */
    const val LIMIT = 2

    /** 이보다 가까우면 같은 장소로 본다. 같은 건물을 검색과 지도로 따로 고른 경우 */
    private const val SAME_PLACE_METERS = 50.0

    /**
     * 최근에 만든 일정의 도착지부터 [limit] 곳. 같은 장소는 한 번만, 집(집 버튼으로 고른 도착지 ·
     * 집 좌표 근처)은 뺀다 — 집은 따로 [집] 칩이 있다.
     */
    fun pick(
        candidates: List<DestinationCandidate>,
        home: PlaceSearchItem?,
        limit: Int = LIMIT,
    ): List<PlaceSearchItem> {
        val picked = mutableListOf<PlaceSearchItem>()
        for (c in candidates.sortedByDescending { it.id }) {
            if (picked.size >= limit) break
            if (c.destinationIsHome) continue
            if (home != null && samePlace(c.place, home)) continue
            if (picked.any { samePlace(it, c.place) }) continue
            picked += c.place
        }
        return picked
    }

    /** 카카오 장소 id 가 같거나, 좌표가 [SAME_PLACE_METERS] 안이면 같은 장소 */
    fun samePlace(a: PlaceSearchItem, b: PlaceSearchItem): Boolean {
        if (!a.kakaoPlaceId.isNullOrBlank() && a.kakaoPlaceId == b.kakaoPlaceId) return true
        return meters(a.lat, a.lng, b.lat, b.lng) < SAME_PLACE_METERS
    }

    /** 짧은 거리라 평면 근사로 충분하다 */
    private fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }
}
