package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment

/**
 * 구간 막대와 체크포인트의 색.
 *
 * 사용자는 이미 2호선이 초록색이고 지선버스가 초록색, 간선버스가 파란색인
 * 세상에서 산다. 그 규칙을 무시하고 브랜드색으로 칠하면 막대를 한 번 더
 * 해석해야 한다.
 *
 * 색은 ARGB Long 으로 둔다. Compose `Color` 에 묶지 않아야 색 결정 규칙을
 * 기기 없이 단위 테스트할 수 있다. UI 가 그릴 때만 `Color(argb)` 로 감싼다.
 */
object SegmentPalette {

    /** 걷는 구간. 카카오맵도 도보를 회색으로 둔다. */
    const val WALK = 0xFF48526AL

    /** 차를 기다리는 시간. 도보보다 어둡게 해 움직이지 않는 시간으로 읽힌다. */
    const val WAIT = 0xFF39415AL

    /** 노선이나 권역을 특정하지 못한 대중교통. */
    const val FALLBACK_TRANSIT = 0xFF6B7793L

    const val CAR = 0xFFE0823CL
    const val BICYCLE = 0xFF3FA37AL
    const val TRACK = 0xFF2A3143L
    const val ON_VEHICLE = 0xFFFFFFFFL
    const val ON_NEUTRAL = 0xFFC3CBD9L

    /**
     * 서울 시내버스 종류별 색.
     *
     * 간선 파랑, 지선 초록, 광역 빨강, 순환 노랑 체계를 따른다. 마을버스를
     * 지선과 같은 초록으로 두면 막대에서 구분되지 않아 한 톤 밝게 잡았다.
     */
    private val BUS_TYPES = mapOf(
        "간선" to 0xFF3D5BABL,
        "지선" to 0xFF53B332L,
        "마을" to 0xFF7BC855L,
        "광역" to 0xFFE60012L,
        "순환" to 0xFFF99D1CL,
        "심야" to 0xFF2A3A7AL,
        "공항" to 0xFFAA9872L,
        "직행좌석" to 0xFFE60012L,
        "좌석" to 0xFFE60012L,
        "급행" to 0xFFE60012L,
    )

    /** 수도권 전철 노선색. 긴 이름을 숫자 노선보다 앞에 둔다. */
    private val SEOUL_SUBWAY = listOf(
        "경의중앙선" to 0xFF77C4A3L,
        "수인분당선" to 0xFFF5A200L,
        "우이신설" to 0xFFB7C452L,
        "신분당선" to 0xFFD4003BL,
        "공항철도" to 0xFF0090D2L,
        "경춘선" to 0xFF0C8E72L,
        "경강선" to 0xFF0054A6L,
        "서해선" to 0xFF8FC31FL,
        "신림선" to 0xFF6789CAL,
        "김포골드" to 0xFFA17800L,
        "의정부" to 0xFFFDA600L,
        "용인에버라인" to 0xFF509F22L,
        "에버라인" to 0xFF509F22L,
        "인천1" to 0xFF7CA8D5L,
        "인천2" to 0xFFED8B00L,
        "GTX-A" to 0xFF9A6292L,
        "분당선" to 0xFFF5A200L,
        "중앙선" to 0xFF77C4A3L,
        "1호선" to 0xFF0052A4L,
        "2호선" to 0xFF00A84DL,
        "3호선" to 0xFFEF7C1CL,
        "4호선" to 0xFF00A5DEL,
        "5호선" to 0xFF996CACL,
        "6호선" to 0xFFCD7C2FL,
        "7호선" to 0xFF747F00L,
        "8호선" to 0xFFE6186CL,
        "9호선" to 0xFFBB8336L,
    )

    /**
     * 부산 도시철도 노선색.
     *
     * 카카오는 서울 1호선과 부산 1호선을 모두 `"1호선"` 으로만 준다. 서버가
     * 출발 좌표로 [RouteSegment.region] 을 붙이고, 여기서 권역별 표를 고른다.
     * 그래서 부산 1호선은 주황, 서울 1호선은 파랑으로 정확히 갈린다.
     */
    private val BUSAN_SUBWAY = listOf(
        "부산김해경전철" to 0xFF875CACL,
        "동해선" to 0xFF0054A6L,
        "1호선" to 0xFFF06A00L,
        "2호선" to 0xFF81BF48L,
        "3호선" to 0xFFBB8C00L,
        "4호선" to 0xFF217DCBL,
    )

    private val DAEGU_SUBWAY = listOf(
        "1호선" to 0xFFD93F5CL,
        "2호선" to 0xFF00AA80L,
        "3호선" to 0xFFFFB100L,
    )

    private val DAEJEON_SUBWAY = listOf("1호선" to 0xFF007448L)
    private val GWANGJU_SUBWAY = listOf("1호선" to 0xFF009088L)

    fun fill(segment: RouteSegment): Long = when (segment.kind) {
        RouteSegment.Kind.WALK -> WALK
        RouteSegment.Kind.WAIT -> WAIT
        RouteSegment.Kind.CAR -> CAR
        RouteSegment.Kind.BICYCLE -> BICYCLE
        RouteSegment.Kind.BUS -> busColor(segment.busType)
        RouteSegment.Kind.SUBWAY -> subwayColor(segment.region, segment.lineName)
        RouteSegment.Kind.UNKNOWN -> FALLBACK_TRANSIT
    }

    fun onFill(segment: RouteSegment): Long = when (segment.kind) {
        RouteSegment.Kind.WALK, RouteSegment.Kind.WAIT -> ON_NEUTRAL
        else -> ON_VEHICLE
    }

    fun busColor(busType: String?): Long {
        val type = busType?.trim().orEmpty()
        if (type.isEmpty()) return FALLBACK_TRANSIT
        BUS_TYPES[type]?.let { return it }
        return BUS_TYPES.entries.firstOrNull { type.contains(it.key) }?.value
            ?: FALLBACK_TRANSIT
    }

    /**
     * 권역·노선명 → 노선색.
     *
     * 권역이 없으면 서울로 추측하지 않는다. 구버전 서버 응답이라 지역을 모를
     * 수 있는데, 그때 부산 1호선을 파랑으로 칠하는 것보다 중립색이 정확하다.
     */
    fun subwayColor(region: String?, lineName: String?): Long {
        val name = lineName?.trim().orEmpty()
        if (name.isEmpty()) return FALLBACK_TRANSIT
        val palette = when (region) {
            "metro_seoul" -> SEOUL_SUBWAY
            "metro_busan" -> BUSAN_SUBWAY
            "metro_daegu" -> DAEGU_SUBWAY
            "metro_daejeon" -> DAEJEON_SUBWAY
            "metro_gwangju" -> GWANGJU_SUBWAY
            else -> return FALLBACK_TRANSIT
        }
        return palette.firstOrNull { name.contains(it.first, ignoreCase = true) }?.second
            ?: FALLBACK_TRANSIT
    }
}
