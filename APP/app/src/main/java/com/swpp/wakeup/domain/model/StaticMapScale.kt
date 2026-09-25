package com.swpp.wakeup.domain.model

import kotlin.math.pow

/**
 * 카카오 정적 지도의 축척.
 *
 * ## 왜 표가 필요한가
 *
 * 정적 지도 API 는 **응답에 축척을 주지 않는다.** 그런데 화면에 담긴 범위를
 * 알아야 하는 일이 둘 있다.
 *
 * 1. "이 지역 재검색" — 지금 보이는 영역을 `rect` 로 만들어 보내야 한다
 * 2. 지도를 끌었을 때 — 끈 픽셀을 좌표 이동으로 바꿔야 한다
 *
 * 추측해서 넣으면 재검색 범위가 화면과 어긋나고, 그건 "왜 안 보이던 게
 * 나오지" 로 드러난다. 그래서 **직접 측정했다.**
 *
 * ## 어떻게 측정했는가
 *
 * 같은 이미지에 마커 두 개를 알려진 위도 차로 찍고 두 핀의 픽셀 간격을 쟀다
 * (`jit-tools/calibrate_staticmap.py`). 결과는 이렇다.
 *
 * ```
 *   lv1~4   1.00 m/단위      (이 아래로는 더 확대되지 않는다)
 *   lv7     8.03 m/단위
 *   lv10   64.15 m/단위
 * ```
 *
 * lv4 부터 한 레벨이 **정확히 두 배**다(lv4→lv7 이 8.012배, lv7→lv10 이
 * 7.989배). 그래서 표 대신 식으로 둔다.
 *
 * ## 단위 주의
 *
 * `scale` 파라미터는 해상도만 바꾸고 지리적 범위는 건드리지 않는다(실측
 * 확인). 그래서 이 값은 **요청한 `size` 한 단위당 미터**다. 화면 픽셀이
 * 아니다 — 서버에 `w`/`h` 로 보낸 값이 기준이다.
 */
object StaticMapScale {

    /** 이 레벨에서 한 단위가 1m 다. 아래로는 더 확대되지 않는다 */
    const val BASE_LEVEL = 4
    private const val BASE_METERS_PER_UNIT = 1.0

    /**
     * 쓸 수 있는 줌 범위.
     *
     * 숫자가 작을수록 확대다(카카오 규칙). 1~4 는 전부 같은 축척이라 4 아래로
     * 내려갈 이유가 없고, 10 이면 이미 도 단위라 장소를 고를 수 없다.
     */
    const val MIN_LEVEL = BASE_LEVEL
    const val MAX_LEVEL = 10

    /**
     * 경로 지도의 줌 상한. 장소 고르기보다 넓다.
     *
     * [MAX_LEVEL] 은 **장소를 고르는** 지도의 상한이다. 경로 지도는 목적이
     * 달라서 그 값으로 자르면 안 된다 — 레벨 10 은 360 단위 폭이 23km 라
     * 수원에서 서울대까지(약 30km) 같은 통학 경로가 화면에 들어오지 않는다.
     *
     * **`RouteMapProjection.fit` 이 고를 수 있는 값과 같아야 한다.** 다르면
     * 전체 보기가 12를 골랐는데 확대 버튼이 10으로 잘라 한 번 눌렀을 때 두
     * 단계(4배)가 튀거나, 축소 버튼이 아예 꺼진 채로 남는다.
     *
     * 서버 상한(`clients.STATIC_MAP_MAX_LEVEL`)과 같은 15 로 둔다.
     */
    const val ROUTE_MAX_LEVEL = 15

    /** 장소를 고르기 좋은 기본 줌. 360 단위 폭이 약 1.4km 다 */
    const val DEFAULT_LEVEL = 6

    /** 위도 1도의 거리. 경도는 위도에 따라 줄어들므로 cos 보정이 필요하다 */
    const val METERS_PER_DEGREE = 111_320.0

    /** 카카오 정적 지도가 한 번에 그려 주는 마커 수 상한. */
    const val MARKER_LIMIT = 5

    /** 요청 size 한 단위가 담는 거리(m). */
    fun metersPerUnit(level: Int): Double {
        val step = level.coerceAtLeast(BASE_LEVEL) - BASE_LEVEL
        return BASE_METERS_PER_UNIT * 2.0.pow(step)
    }

    /**
     * 화면 픽셀 하나가 담는 거리(m).
     *
     * 서버에는 `size` 를 dp 가 아니라 **요청 단위**로 보낸다. 그 단위 수가
     * 화면 픽셀 수와 다르면(밀도 보정 때문에 보통 다르다) 픽셀당 거리도
     * 달라진다. 끌기와 재검색은 화면 픽셀로 일어나므로 여기서 환산한다.
     */
    fun metersPerPixel(level: Int, requestUnits: Int, viewPixels: Int): Double {
        if (viewPixels <= 0 || requestUnits <= 0) return metersPerUnit(level)
        return metersPerUnit(level) * requestUnits / viewPixels
    }
}
