package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 경로 후보 하나. Figma "⑬ 경로 선택".
 *
 * 서버가 카카오에서 받은 후보를 축별로 추려 내려 준 것이다(back-spec 5.3
 * `/api/routes/candidates`). 앱은 순서를 바꾸지 않는다 — 이미 빠른 순이다.
 *
 * **[fareLabel] 이 "요금 미정" 인 경우가 정상이다.** 카카오가 환승 요금을
 * 계산하지 못한 후보가 섞여 온다. 0원으로 바꾸면 "무료" 로 잘못 읽힌다.
 */
data class RouteOption(
    /** `walk` / `bicycle` / `car` / `transit:<노선 체인>` */
    val key: String,
    /** "지하철+도보+버스" */
    val mode: String,
    val minutes: Int,
    /** "23분" */
    val minutesLabel: String,
    /** "2호선 → 5513 · 5.1km · 환승 1회 · 1,550원" */
    val detailLine: String,
    /** "가장 빠름" / "환승 없음" / "가장 저렴". 없으면 null */
    val badge: String?,
    /**
     * 구간 막대에 그릴 내용. 비어 있으면 막대를 그리지 않는다.
     *
     * 구버전 서버는 이 값을 내리지 않는다. 그때 빈 막대를 그리면 "0분 구간"
     * 처럼 보이므로 아예 숨긴다.
     */
    val segments: RouteSegments = RouteSegments(emptyList()),
    /** 카드 오른쪽 위 배지들. 예 ["최단 시간", "최소 비용"]. 서버 문구 그대로 */
    val badges: List<String> = listOfNotNull(badge),
    /** 전체 거리(m). 서버가 못 주면 null */
    val distanceM: Int? = null,
    /** 자전거만: 자전거도로 비율 %. 서버가 추정해 줄 때만(B-4) */
    val bikeRoadPercent: Int? = null,
) {
    /** 자동차는 요금이 크게 다르므로 화면에서 구분해 표시한다. */
    val isCar: Boolean get() = key == "car"

    /**
     * 막대를 그릴 값어치가 있는가.
     *
     * 구간이 하나뿐이면 막대가 통짜 한 칸이고, 그건 제목의 "14분" 이 이미
     * 말한 것이라 화면만 길어진다. 둘 이상일 때만 그린다.
     */
    val hasSegmentBar: Boolean get() = segments.items.size >= 2

    /** 출발·승차·하차·도착을 펼쳐 보여 줄 수 있는가. */
    val checkpoints: List<RouteCheckpoint> get() = segments.checkpoints()
    val hasCheckpoints: Boolean get() = checkpoints.isNotEmpty()

    /** 경로 선택 화면의 어느 탭에 들어가는지. [key] 로 정한다. */
    val travelMode: RouteMode
        get() = when (key) {
            "car" -> RouteMode.CAR
            "walk" -> RouteMode.WALK
            "bicycle" -> RouteMode.BICYCLE
            else -> RouteMode.TRANSIT
        }
}

// AI-generated (Claude)
/** 경로 선택 화면의 수단 탭. Figma 순서대로 둔다. */
enum class RouteMode(val label: String) {
    CAR("자동차"),
    TRANSIT("대중교통"),
    WALK("도보"),
    BICYCLE("자전거"),
}

/**
 * 경로 선택 화면 상태.
 *
 * [originLabel] 은 화면에 그릴 출발지 이름이고, [originLat]/[originLng] 는 그
 * 좌표다. **좌표를 함께 들고 있어야 한다** — 사용자가 출발지를 바꾸면 같은
 * 좌표를 일정 생성 요청에도 보내야 하고, 보내지 않으면 서버가 집 기준으로
 * 알람을 계산해 화면에 보인 소요시간과 달라진다.
 *
 * 좌표가 null 이면 서버가 프로필 집을 쓴 것이다(사용자가 바꾸지 않은 상태).
 */
data class RouteChoice(
    val originLabel: String,
    val destination: String,
    val options: List<RouteOption>,
    val selectedKey: String?,
    val originLat: Double? = null,
    val originLng: Double? = null,
) {
    /** 사용자가 집 대신 다른 출발지를 고른 상태인지. */
    val hasCustomOrigin: Boolean get() = originLat != null && originLng != null

    // AI-generated (Claude)
    /**
     * 한 수단의 후보. 서버가 준 순서(빠른 순)를 그대로 둔다.
     *
     * 도보·자전거는 **1개만** 보여준다. 카카오 도보·자전거 길찾기가 경로를 1개만
     * 주므로(task.md 13-0) 여러 개가 오면 앞의 것(가장 빠른 것)만 쓴다.
     */
    fun optionsOf(mode: RouteMode): List<RouteOption> {
        val all = options.filter { it.travelMode == mode }
        return if (mode == RouteMode.WALK || mode == RouteMode.BICYCLE) all.take(1) else all
    }

    // AI-generated (Claude)
    /**
     * 처음 열 탭. 이미 고른 후보가 있으면 그 수단, 없으면 가장 빠른 후보의 수단.
     * 후보가 하나도 없으면 대중교통.
     */
    fun defaultMode(): RouteMode =
        options.firstOrNull { it.key == selectedKey }?.travelMode
            ?: options.minByOrNull { it.minutes }?.travelMode
            ?: RouteMode.TRANSIT
}
