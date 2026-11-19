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

    // Partly AI-generated (Claude)
    /**
     * 다녀온 경로 기록(`GET /api/routes/history?sort=recent`, 서버가 최근에 다녀온 순으로 준다)의 도착지로 칩을 채운다.
     * 순서는 서버 그대로, 집(`kind == home` · 집 좌표 근처)과 같은 장소는 빼고 [limit] 곳. 좌표 · 이름이 없는 줄은 건너뛴다.
     * 홈 일정 목록으로 뽑는 [pick] 과 달리 지난 일정의 도착지도 나온다.
     */
    fun fromHistory(
        results: List<com.swpp.wakeup.data.remote.RouteHistoryItemDto>,
        home: PlaceSearchItem?,
        limit: Int = LIMIT,
    ): List<PlaceSearchItem> {
        val picked = mutableListOf<PlaceSearchItem>()
        for (row in results) {
            if (picked.size >= limit) break
            val d = row.destination ?: continue
            if (d.kind != "place") continue
            val lat = d.lat ?: continue
            val lng = d.lng ?: continue
            val name = d.name?.takeIf { it.isNotBlank() } ?: continue
            val place = PlaceSearchItem(
                kakaoPlaceId = d.kakaoPlaceId, name = name, address = d.address, lat = lat, lng = lng, category = null,
            )
            if (home != null && samePlace(place, home)) continue
            if (picked.any { samePlace(it, place) }) continue
            picked += place
        }
        return picked
    }

    /**
     * 기록 응답 → 칩. 2xx 가 아니면(배포 전 404 · 오류 · 연결 안 됨 = [status] null) null 이고, 화면은 홈 목록에서 뽑은
     * 칩([pick])을 그대로 쓴다. 2xx 여도 쓸 도착지가 없으면 빈 목록이다(이때도 화면은 홈 목록 칩을 쓴다, [choose]).
     */
    fun fromHistoryResponse(
        status: Int?,
        body: com.swpp.wakeup.data.remote.RouteHistoryResponse?,
        home: PlaceSearchItem?,
    ): List<PlaceSearchItem>? =
        if (status == null || status !in 200..299) null else fromHistory(body?.results.orEmpty(), home)

    /** 칩에 쓸 목록. 기록에서 뽑은 것이 있으면 그것, 없으면 홈 일정 목록에서 뽑은 것 */
    fun choose(fromHistory: List<PlaceSearchItem>?, fromHome: List<PlaceSearchItem>): List<PlaceSearchItem> =
        fromHistory?.takeIf { it.isNotEmpty() } ?: fromHome

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
