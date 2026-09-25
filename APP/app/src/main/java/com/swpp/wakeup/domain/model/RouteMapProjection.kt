package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

/**
 * 좌표를 정적 지도 이미지 위의 픽셀로 옮긴다.
 *
 * ## 왜 필요한가
 *
 * 카카오 정적 지도에는 **선을 그리는 파라미터가 없다.** 실측에서 `path`,
 * `polyline`, `line`, `paths`, `route` 를 모두 시험했고 다섯 응답의 MD5 가
 * 동일했다 — 조용히 무시된다. 마커는 다섯 개까지만 가능하다.
 *
 * 그래서 지도는 이미지로 받고 **경로선은 앱이 그 위에 직접 그린다.** 이 객체가
 * 좌표 → 픽셀 변환을 맡는다. 같은 식이 `jit-tools/make_route_map.py` 에 있고
 * 피그마의 경로 지도도 그것으로 그렸으므로, 디자인과 구현이 같은 계산을 쓴다.
 *
 * ## 단위
 *
 * [StaticMapScale.metersPerUnit] 은 **요청 단위**(서버에 보낸 `w`/`h`)당 미터다.
 * 화면 픽셀과 다를 수 있으므로 [Viewport] 가 둘을 함께 들고 환산한다.
 */
object RouteMapProjection {

    /**
     * 지도 한 장의 좌표계.
     *
     * @param requestUnits 서버에 보낸 `w`. 축척의 기준이다
     * @param viewPx 화면에서 실제로 차지하는 가로 픽셀
     */
    data class Viewport(
        val center: GeoPoint,
        val level: Int,
        val requestUnits: Int,
        val viewPx: Int,
        val viewHeightPx: Int,
    ) {
        /** 화면 픽셀 하나가 담는 거리(m). */
        val metersPerPixel: Double
            get() = StaticMapScale.metersPerPixel(level, requestUnits, viewPx)
    }

    /** 화면 위의 점. Compose 의 Offset 을 domain 에 끌어들이지 않는다 */
    data class Px(val x: Float, val y: Float)

    /**
     * 좌표 하나를 픽셀로.
     *
     * y 는 부호가 반대다 — 화면 y 는 아래로 증가하고 위도는 위로 증가한다.
     * 이걸 빠뜨리면 경로가 위아래로 뒤집힌 채 그려지는데, 지도와 겹쳐 보면
     * "대충 맞는 것 같은" 모양이 나와서 알아채기 어렵다.
     */
    fun toPx(point: GeoPoint, viewport: Viewport): Px {
        val mpp = viewport.metersPerPixel
        if (mpp <= 0.0) return Px(viewport.viewPx / 2f, viewport.viewHeightPx / 2f)
        val cosLat = cos(Math.toRadians(viewport.center.lat))
        val dx = (point.lng - viewport.center.lng) *
            StaticMapScale.METERS_PER_DEGREE * cosLat / mpp
        val dy = (point.lat - viewport.center.lat) *
            StaticMapScale.METERS_PER_DEGREE / mpp
        return Px(
            x = (viewport.viewPx / 2f + dx).toFloat(),
            y = (viewport.viewHeightPx / 2f - dy).toFloat(),
        )
    }

    /**
     * 경로 전체가 화면에 들어오는 중심과 줌.
     *
     * 경로의 좌표 범위 중앙을 중심으로 잡고, **여백을 남기고** 전부 들어오는
     * 가장 확대된 레벨을 고른다. 한 단계라도 크게 잡으면 경로 끝이 화면 밖으로
     * 나가고, 사용자는 선이 잘렸다고 읽는다.
     */
    fun fit(
        path: List<GeoPoint>,
        requestUnits: Int,
        requestHeightUnits: Int,
        marginUnits: Int = 18,
    ): Pair<GeoPoint, Int>? {
        if (path.isEmpty()) return null
        val minLat = path.minOf { it.lat }
        val maxLat = path.maxOf { it.lat }
        val minLng = path.minOf { it.lng }
        val maxLng = path.maxOf { it.lng }
        val center = GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2)
        val cosLat = cos(Math.toRadians(center.lat))

        val halfW = (requestUnits / 2.0 - marginUnits).coerceAtLeast(1.0)
        val halfH = (requestHeightUnits / 2.0 - marginUnits).coerceAtLeast(1.0)

        for (level in StaticMapScale.BASE_LEVEL..StaticMapScale.ROUTE_MAX_LEVEL) {
            val mpu = StaticMapScale.metersPerUnit(level)
            val dxUnits = abs(maxLng - minLng) / 2 *
                StaticMapScale.METERS_PER_DEGREE * cosLat / mpu
            val dyUnits = abs(maxLat - minLat) / 2 *
                StaticMapScale.METERS_PER_DEGREE / mpu
            if (dxUnits <= halfW && dyUnits <= halfH) return center to level
        }
        // 가장 축소해도 안 들어가는 경로다. 상한을 준다 — 여기서 더 큰 값을
        // 돌려주면 줌 버튼이 닿지 못하는 레벨이 되어 조작이 튄다.
        return center to StaticMapScale.ROUTE_MAX_LEVEL
    }
}
