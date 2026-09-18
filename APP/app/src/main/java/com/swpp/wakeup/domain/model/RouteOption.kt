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
 * [origin] 은 서버가 알려 준 출발지 이름이다. 앱이 프로필을 다시 읽지 않아도
 * "신림역 → 서울대학교 관악캠퍼스" 를 그릴 수 있다.
 */
data class RouteChoice(
    val origin: String,
    val destination: String,
    val options: List<RouteOption>,
    val selectedKey: String?,
)
