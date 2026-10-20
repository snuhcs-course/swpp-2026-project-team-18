package com.swpp.wakeup.domain.model

import kotlin.math.pow

/**
 * 카카오 정적 지도의 축척.
 *
 * ## 왜 표가 필요한가
 *
 * 정적 지도 API 는 **응답에 축척을 주지 않는다.** 그런데 화면에 담긴 범위를
 * 알아야 하는 일이 셋 있다.
 *
 * 1. 경로선·핀·현재 위치를 이미지 위 제자리에 그린다
 * 2. "이 지역 재검색" — 지금 보이는 영역을 `rect` 로 만들어 보낸다
 * 3. 지도를 끌었을 때 — 끈 픽셀을 좌표 이동으로 바꾼다
 *
 * ## 어떻게 측정했는가 (2026-10-18 재측정)
 *
 * **마커 없이** 같은 크기의 지도를 중심만 북·동으로 알려진 거리만큼 옮겨 두 장 받고,
 * 위상 상관으로 그림이 몇 픽셀 밀렸는지 쟀다. 레벨 1~15, `scale` 1·2 모두 같은 식이
 * 나왔다.
 *
 * ```
 *   lv1 0.25   lv2 0.5   lv3 1   lv4 2   lv6 8   lv8 32   lv10 128   lv15 4096  (m/단위)
 * ```
 *
 * 한 레벨이 **정확히 두 배**이고 lv3 이 1m 다. 미터는 [KakaoMapProjection] 좌표의 미터다.
 *
 * **예전 표(lv4 = 1m)는 두 배 틀렸다.** 그때는 마커 두 개의 간격으로 쟀는데, 카카오는
 * 마커를 주면 **마커가 다 보이도록 중심과 줌을 스스로 바꾼다**(같은 lv 로 마커 간격만
 * 바꿔 확인). 그래서 마커 간격을 정한 대로 값이 나왔다. 그 표로 그린 경로선은 실제보다
 * 지도 중심에서 두 배 멀리 그려졌다 — 신림역→서울대 경로의 끝이 관악산 능선에 찍혔다.
 * 앱은 지도에 마커를 넣지 않는다(핀은 Compose 가 그린다).
 *
 * ## 단위 주의
 *
 * `scale` 파라미터는 해상도만 바꾸고 지리적 범위는 건드리지 않는다(재측정에서도 같다).
 * 그래서 이 값은 **요청한 `size` 한 단위당 미터**다. 화면 픽셀이 아니다 — 서버에
 * `w`/`h` 로 보낸 값이 기준이다.
 */
object StaticMapScale {

    /** 이 레벨에서 요청 한 단위가 1m 다(실측) */
    const val REFERENCE_LEVEL = 3

    /**
     * 쓸 수 있는 줌 범위.
     *
     * 숫자가 작을수록 확대다(카카오 규칙). 3 은 한 단위가 1m 라 360 단위 폭이 약 360m
     * 다. 10 은 같은 폭이 약 46km 라 장소를 고를 수 없다.
     */
    const val MIN_LEVEL = REFERENCE_LEVEL
    const val MAX_LEVEL = 10

    /**
     * 경로 지도의 줌 상한. 장소 고르기보다 넓다.
     *
     * [MAX_LEVEL] 은 **장소를 고르는** 지도의 상한이다. 경로 지도는 목적이
     * 달라서 그 값으로 자르면 안 된다 — 천안에서 서울대(약 75km) 같은 장거리 통학은
     * 레벨 12 가 되어야 화면에 들어온다.
     *
     * **`RouteMapProjection.fit` 이 고를 수 있는 값과 같아야 한다.** 다르면
     * 전체 보기가 12를 골랐는데 확대 버튼이 10으로 잘라 한 번 눌렀을 때 두
     * 단계(4배)가 튀거나, 축소 버튼이 아예 꺼진 채로 남는다.
     *
     * 서버 상한(`clients.STATIC_MAP_MAX_LEVEL`)과 같은 15 로 둔다.
     */
    const val ROUTE_MAX_LEVEL = 15

    /** 장소를 고르는 지도의 기본 줌. 360 단위 폭이 약 2.9km 다 */
    const val DEFAULT_LEVEL = 6

    /**
     * 위도 1도의 지면 거리. **경로 길이·진행률 계산용**이다(서버 `path_length_m` 과 같은 값).
     * 지도 위에 그리는 좌표는 [KakaoMapProjection] 으로 바꾼다.
     */
    const val METERS_PER_DEGREE = 111_320.0

    /** 카카오 정적 지도가 한 번에 그려 주는 마커 수 상한. */
    const val MARKER_LIMIT = 5

    /** 요청 size 한 단위가 담는 지도 좌표 거리(m). */
    fun metersPerUnit(level: Int): Double = 2.0.pow(level - REFERENCE_LEVEL)

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
