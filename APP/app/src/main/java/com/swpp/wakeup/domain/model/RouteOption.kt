package com.swpp.wakeup.domain.model

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
) {
    /** 자동차는 요금이 크게 다르므로 화면에서 구분해 표시한다. */
    val isCar: Boolean get() = key == "car"
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
}
