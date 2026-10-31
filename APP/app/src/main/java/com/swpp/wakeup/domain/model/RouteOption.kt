package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint

// Partly AI-generated (Claude)
/**
 * 경로 후보 하나. Figma "⑬ 경로 선택".
 *
 * 서버가 카카오에서 받은 후보를 축별로 추려 내려 준 것이다(back-spec 5.3
 * `/api/routes/candidates`). 앱은 각 수단 안의 서버 순서를 유지한다.
 *
 * **[fareLabel] 이 "요금 미정" 인 경우가 정상이다.** 카카오가 환승 요금을
 * 계산하지 못한 후보가 섞여 온다. 0원으로 바꾸면 "무료" 로 잘못 읽힌다.
 */
data class RouteOption(
    /** `walk` / `bicycle` / `car` / `car:<대안>` / `transit:<노선 체인>` */
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
    /** 선택 화면에 그릴 경로. 좌표가 없으면 미리보기를 숨긴다. */
    val routePath: List<GeoPoint> = emptyList(),
    /** 고른 카드 아래에 그대로 보여 줄 서버 안내. 없으면 숨긴다. */
    val guide: String? = null,
) {
    /** 자동차는 요금이 크게 다르므로 화면에서 구분해 표시한다. */
    val isCar: Boolean get() = key == "car" || key.startsWith("car:")

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
        get() = when {
            isCar -> RouteMode.CAR
            key == "walk" -> RouteMode.WALK
            key == "bicycle" -> RouteMode.BICYCLE
            else -> RouteMode.TRANSIT
        }

    // Partly AI-generated (Claude)
    /**
     * 지도 미리보기를 열 수 있는가. 수단과 상관없이 좌표가 있으면 연다.
     *
     * 서버는 모든 후보에 좌표(`path`)를 준다(도보·자전거는 BE-23, 자동차·대중교통은
     * 2026-10-18). 구버전 서버는 자동차·대중교통에 주지 않는다. 점이 둘 미만이면 선이
     * 되지 않는다.
     */
    val canPreviewMap: Boolean get() = routePath.size >= 2

    // AI-generated (Claude)
    /**
     * 카드를 누르면 바로 지도로 가는가.
     *
     * 자동차·도보·자전거는 탭에 경로가 하나뿐이라 고르는 것이 곧 그 경로를 보는 것이다
     * (도보·자전거는 카카오가 하나만 주고, 자동차는 B-4 전까지 서버가 하나만 준다).
     * 대중교통은 후보가 여럿이라 누르면 **고르기만** 하고(정류장 목록이 펼쳐진다), 지도는
     * 카드의 "지도로 보기" 로 연다. 누를 때마다 전체 화면으로 가면 후보를 비교할 수 없다.
     */
    val opensPreviewOnTap: Boolean get() = canPreviewMap && travelMode != RouteMode.TRANSIT
}

// AI-generated (Claude)
/**
 * 경로 선택 화면의 수단 탭. Figma 순서대로 둔다.
 *
 * [candidateLimit] 는 서버 `CANDIDATE_LIMITS` 와 같다. 대중교통은 7개다(사용자 요청 10-31).
 */
enum class RouteMode(val label: String, val candidateLimit: Int) {
    CAR("자동차", 4),
    TRANSIT("대중교통", 7),
    WALK("도보", 1),
    BICYCLE("자전거", 1),
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
     * 수단별 상한을 지킨다. 카카오 도보·자전거 길찾기는 경로를 1개만 준다.
     */
    fun optionsOf(mode: RouteMode): List<RouteOption> {
        val all = options.filter { it.travelMode == mode }
        return all.take(mode.candidateLimit)
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
