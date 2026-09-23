package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment

/**
 * 구간 막대의 색.
 *
 * ## 왜 우리가 정한 색이 아니라 노선 색인가
 *
 * 사용자는 이미 2호선이 초록색이고 지선버스가 초록색, 간선버스가 파란색인
 * 세상에서 산다. 그 규칙을 무시하고 앱 브랜드색으로 칠하면 막대를 한 번 더
 * 해석해야 한다. 색이 곧 정보가 되게 하려면 남이 쓰는 색을 따라야 한다.
 *
 * ## ARGB Long 을 쓰는 이유
 *
 * `androidx.compose.ui.graphics.Color` 를 쓰지 않는다. 그러면 이 파일이
 * Compose 에 묶이고 색 결정 규칙을 단위 테스트로 고정할 수 없다. 색 하나를
 * 잘못 매핑해도 빌드는 통과하고 기기에서만 드러난다 —
 * `SegmentPaletteTest` 가 그걸 막는다. 컴포저블 쪽에서 `Color(argb)` 로
 * 감싼다.
 *
 * ## 색이 정확하지 않으면
 *
 * 노선 색은 운영 주체가 바꾸기도 하고 자료마다 미세하게 다르다. 여기 값이
 * 한두 톤 어긋나는 것은 기능 문제가 아니다. **모르는 노선을 억지로 칠하는
 * 것**이 문제다. 그때는 [FALLBACK_TRANSIT] 을 쓴다 — 틀린 색으로 확신을
 * 주는 것보다 중립이 낫다.
 *
 * ## 아는 한계: 수도권 밖
 *
 * 노선명만으로는 지역을 알 수 없다. 부산 1호선은 주황인데 이름에 "1호선" 이
 * 들어 있어서 서울 1호선 파랑으로 칠한다. 카카오 응답에 지역 필드가 없어서
 * 좌표로 판단해야 하는데, 이 앱은 서울대 통학을 전제로 만들고 있어 지금은
 * 고치지 않는다. `SegmentPaletteTest` 가 이 동작을 기록해 둔다.
 */
object SegmentPalette {

    /** 걷는 구간. 카카오맵도 도보를 회색으로 둔다. */
    const val WALK = 0xFF48526AL

    /** 차를 기다리는 시간. 도보보다 어둡게 해서 "움직이지 않는 시간" 으로 읽히게 한다. */
    const val WAIT = 0xFF39415AL

    /** 노선을 특정하지 못한 대중교통. */
    const val FALLBACK_TRANSIT = 0xFF6B7793L

    const val CAR = 0xFFE0823CL
    const val BICYCLE = 0xFF3FA37AL

    /** 막대 바탕. 구간이 다 채우지 못한 자리에 보인다. */
    const val TRACK = 0xFF2A3143L

    /** 색이 진한 칸 위의 글자. */
    const val ON_VEHICLE = 0xFFFFFFFFL

    /** 회색 칸 위의 글자. */
    const val ON_NEUTRAL = 0xFFC3CBD9L

    /**
     * 서울 시내버스 종류별 색.
     *
     * 2004년 버스 개편으로 정해진 체계다. 간선은 파랑, 지선은 초록, 광역은
     * 빨강, 순환은 노랑이고 마을버스도 초록 계열이다. 마을버스를 지선과 같은
     * 초록으로 두면 막대에서 구분이 안 되므로 한 톤 밝게 잡았다.
     */
    private val BUS_TYPES = mapOf(
        "간선" to 0xFF3D5BABL,
        "지선" to 0xFF53B332L,
        "마을" to 0xFF7BC855L,
        "광역" to 0xFFE60012L,
        "순환" to 0xFFF99D1CL,
        // 심야(N버스)는 간선보다 짙은 남색을 쓴다.
        "심야" to 0xFF2A3A7AL,
        // 공항버스는 리무진 계열의 베이지.
        "공항" to 0xFFAA9872L,
        // 경기·인천 직행좌석은 광역과 같은 성격이다.
        "직행좌석" to 0xFFE60012L,
        "좌석" to 0xFFE60012L,
        "급행" to 0xFFE60012L,
    )

    /**
     * 수도권 전철 노선색.
     *
     * 노선명은 서버가 카카오 응답의 `vehicles[0].name` 을 그대로 넘긴 값이다
     * ("2호선", "신분당선"). 표기가 조금씩 달라질 수 있어 정확히 같은지가
     * 아니라 **포함되는지**로 찾는다 — "수도권 2호선" 도 2호선으로 잡힌다.
     *
     * 순서가 중요하다. "9호선" 을 "1호선" 보다 먼저 찾지 않으면 "9호선" 이
     * 걸리지 않는다... 는 아니지만, "1호선" 이 "11호선" 류에 먼저 걸리는
     * 사고를 막으려고 긴 이름을 앞에 둔다.
     */
    private val SUBWAY_LINES = listOf(
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
        "10호선" to 0xFF6B7793L,
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

    /** 구간의 배경색. */
    fun fill(segment: RouteSegment): Long = when (segment.kind) {
        RouteSegment.Kind.WALK -> WALK
        RouteSegment.Kind.WAIT -> WAIT
        RouteSegment.Kind.CAR -> CAR
        RouteSegment.Kind.BICYCLE -> BICYCLE
        RouteSegment.Kind.BUS -> busColor(segment.busType)
        RouteSegment.Kind.SUBWAY -> subwayColor(segment.lineName)
        RouteSegment.Kind.UNKNOWN -> FALLBACK_TRANSIT
    }

    /** 그 배경 위에 읽히는 글자색. */
    fun onFill(segment: RouteSegment): Long = when (segment.kind) {
        RouteSegment.Kind.WALK, RouteSegment.Kind.WAIT -> ON_NEUTRAL
        else -> ON_VEHICLE
    }

    /**
     * 버스 종류 → 색.
     *
     * 카카오가 "지선", "간선" 처럼 짧게 주지만 다른 지역은 "일반", "농어촌"
     * 같은 값이 올 수 있다. 모르면 중립색이다.
     */
    fun busColor(busType: String?): Long {
        val type = busType?.trim().orEmpty()
        if (type.isEmpty()) return FALLBACK_TRANSIT
        BUS_TYPES[type]?.let { return it }
        // "간선버스" 처럼 뒤에 말이 붙어 오는 경우.
        return BUS_TYPES.entries.firstOrNull { type.contains(it.key) }?.value
            ?: FALLBACK_TRANSIT
    }

    /** 노선명 → 색. */
    fun subwayColor(lineName: String?): Long {
        val name = lineName?.trim().orEmpty()
        if (name.isEmpty()) return FALLBACK_TRANSIT
        return SUBWAY_LINES.firstOrNull { name.contains(it.first, ignoreCase = true) }?.second
            ?: FALLBACK_TRANSIT
    }
}
