package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

// Partly AI-generated (Claude)
/**
 * 기본 집 말고 이 기기에 저장한 다른 집(본가·기숙사 등). todo §2 "집 여러 개".
 *
 * 서버는 아직 집을 하나만 안다(`Profile.home_*`, 백엔드 P2 `SavedPlace` 전). 그래서 다른 집은
 * 기기에만 두고, 일정 출발지로 고를 때 좌표와 "준비 시간 포함"(`include_prep=true`)을 함께 보낸다.
 * "기본으로" 는 서버의 집을 그 집으로 바꾸고 예전 집을 목록에 남긴다. Figma ⑯ 변형 `498:9`.
 *
 * Gson 으로 디스크에 쓰므로 읽을 때 칸이 비어 있을 수 있다고 본다([isUsable]).
 */
data class SavedHome(
    val id: String,
    /** "본가". 사용자가 붙인 이름 */
    val label: String,
    /** 장소 이름(검색 결과의 이름이나 주소) */
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
) {
    /** 깨진 사본을 거른다 */
    val isUsable: Boolean
        get() = !id.isNullOrBlank() && !name.isNullOrBlank() &&
            lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)

    /** 화면 한 줄. "본가 · 경기 수원시 팔달구 인계동" */
    val line: String get() = listOf(label.trim().ifEmpty { name }, address?.takeIf { it.isNotBlank() } ?: name)
        .distinct()
        .joinToString(" · ")

    val point: GeoPoint get() = GeoPoint(lat, lng)
}

object SavedHomes {

    /** 이만큼만 둔다. 다른 집이 이보다 많을 일은 드물고, 출발지 목록이 길어진다 */
    const val MAX = 5

    /** 이보다 가까우면 같은 집으로 본다(같은 건물을 두 번 고른 경우) */
    const val SAME_PLACE_METERS = 50.0

    /** 이름 칸 한도 */
    const val LABEL_MAX = 12

    /**
     * 집을 더한다. 같은 자리(50m 안)의 집이 있으면 그 집을 바꾼다. 가장 최근 것이 앞이고 [MAX] 를
     * 넘으면 오래된 것부터 버린다.
     */
    fun add(homes: List<SavedHome>, home: SavedHome): List<SavedHome> {
        val rest = homes.filter { it.id != home.id && !samePlace(it.point, home.point) }
        return (listOf(home) + rest).take(MAX)
    }

    fun remove(homes: List<SavedHome>, id: String): List<SavedHome> = homes.filter { it.id != id }

    /**
     * "기본으로": [id] 를 목록에서 빼고, 예전 기본 집([previous])이 있으면 그 자리에 넣는다. 서버에
     * 저장한 뒤에 부른다.
     */
    fun promote(homes: List<SavedHome>, id: String, previous: SavedHome?): List<SavedHome> {
        val rest = remove(homes, id)
        return if (previous == null) rest else add(rest, previous)
    }

    /** 이름이 비면 장소 이름을 쓴다. 너무 길면 자른다 */
    fun labelOf(input: String, placeName: String): String =
        input.trim().ifEmpty { placeName.trim() }.take(LABEL_MAX)

    /** 두 점이 [SAME_PLACE_METERS] 안인가(가까운 거리라 평면 근사로 충분하다) */
    fun samePlace(a: GeoPoint, b: GeoPoint): Boolean {
        val metersPerDegree = 111_320.0
        val dy = (a.lat - b.lat) * metersPerDegree
        val dx = (a.lng - b.lng) * metersPerDegree * cos(Math.toRadians((a.lat + b.lat) / 2))
        return abs(dx) <= SAME_PLACE_METERS && abs(dy) <= SAME_PLACE_METERS &&
            dx * dx + dy * dy <= SAME_PLACE_METERS * SAME_PLACE_METERS
    }
}
