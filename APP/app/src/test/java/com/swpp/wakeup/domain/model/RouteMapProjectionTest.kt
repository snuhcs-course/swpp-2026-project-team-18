package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 좌표 → 지도 픽셀 투영.
 *
 * 정적 지도가 선을 못 그리므로 경로선은 앱이 이미지 위에 직접 그린다. 그
 * 좌표 변환이 여기 있고, 틀려도 **에러가 나지 않는다** — 지도와 겹쳐 보면
 * 그럴싸한 모양이 나와서 눈으로는 알아채기 어렵다. 그래서 수치로 고정한다.
 *
 * 같은 식이 `jit-tools/make_route_map.py` 에 있고 피그마 경로 지도를 그것으로
 * 그렸다. 둘이 어긋나면 디자인과 구현이 다른 그림이 된다.
 */
class RouteMapProjectionTest {

    private val center = GeoPoint(37.4867, 126.9789)

    /** 실측 조건: lv9, 360 요청 단위, 화면도 360px(밀도 1 가정) */
    private fun viewport(level: Int = 9, viewPx: Int = 360, heightPx: Int = 260) =
        RouteMapProjection.Viewport(
            center = center,
            level = level,
            requestUnits = 360,
            viewPx = viewPx,
            viewHeightPx = heightPx,
        )

    // --- 기본 ---------------------------------------------------------------

    @Test
    fun `중심은 화면 가운데다`() {
        val px = RouteMapProjection.toPx(center, viewport())
        assertEquals(180f, px.x, 0.01f)
        assertEquals(130f, px.y, 0.01f)
    }

    @Test
    fun `북쪽은 화면 위쪽이다`() {
        // **y 부호를 빠뜨리기 쉬운 자리다.** 위도가 커지면 화면에서는 y 가
        // 작아져야 한다. 뒤집히면 경로가 상하 반전된 채 그려진다.
        val north = GeoPoint(center.lat + 0.01, center.lng)
        val px = RouteMapProjection.toPx(north, viewport())
        assertTrue("북쪽이 아래로 갔다 (y=${px.y})", px.y < 130f)
    }

    @Test
    fun `동쪽은 화면 오른쪽이다`() {
        val east = GeoPoint(center.lat, center.lng + 0.01)
        val px = RouteMapProjection.toPx(east, viewport())
        assertTrue("동쪽이 왼쪽으로 갔다 (x=${px.x})", px.x > 180f)
    }

    // --- 축척 --------------------------------------------------------------

    @Test
    fun `lv9 에서 한 픽셀은 32미터다`() {
        // 실측값. lv4 가 1.0 이고 레벨당 두 배이므로 2^5 = 32.
        assertEquals(32.0, viewport().metersPerPixel, 0.001)
    }

    @Test
    fun `위도 0_01도는 lv9 에서 약 35픽셀이다`() {
        // 0.01 × 111320 = 1113.2m, ÷ 32 = 34.8px
        val north = GeoPoint(center.lat + 0.01, center.lng)
        val px = RouteMapProjection.toPx(north, viewport())
        assertEquals(130f - 34.8f, px.y, 0.5f)
    }

    @Test
    fun `밀도가 높으면 픽셀당 거리가 줄어든다`() {
        // 요청은 360 단위인데 화면이 1080px 이면 한 픽셀이 담는 거리가 1/3 이다.
        // 이걸 무시하면 경로가 화면의 1/3 크기로 그려진다.
        val dense = viewport(viewPx = 1080)
        assertEquals(32.0 / 3, dense.metersPerPixel, 0.001)
    }

    @Test
    fun `줌을 한 단계 올리면 픽셀당 거리가 두 배다`() {
        assertEquals(
            viewport(level = 9).metersPerPixel * 2,
            viewport(level = 10).metersPerPixel,
            0.001,
        )
    }

    // --- 경로 맞춤 ----------------------------------------------------------

    @Test
    fun `빈 경로는 맞출 수 없다`() {
        assertNull(RouteMapProjection.fit(emptyList(), 360, 260))
    }

    @Test
    fun `중심은 경로 범위의 가운데다`() {
        val path = listOf(GeoPoint(37.48, 126.93), GeoPoint(37.50, 127.03))
        val (c, _) = RouteMapProjection.fit(path, 360, 260)!!
        assertEquals(37.49, c.lat, 1e-9)
        assertEquals(126.98, c.lng, 1e-9)
    }

    @Test
    fun `신림에서 강남까지는 lv9 로 맞는다`() {
        // 실측으로 확인한 값. make_route_map.py 가 같은 입력에 lv9 를 골랐다.
        val path = listOf(
            GeoPoint(37.484267, 126.929745),
            GeoPoint(37.497942, 127.027621),
        )
        val (_, level) = RouteMapProjection.fit(path, 360, 260)!!
        assertEquals(9, level)
    }

    @Test
    fun `맞춘 줌에서는 경로 전체가 화면 안에 들어온다`() {
        val path = listOf(
            GeoPoint(37.484267, 126.929745),
            GeoPoint(37.497942, 127.027621),
            GeoPoint(37.470000, 126.990000),
        )
        val (c, level) = RouteMapProjection.fit(path, 360, 260)!!
        val vp = RouteMapProjection.Viewport(c, level, 360, 360, 260)

        for (p in path) {
            val px = RouteMapProjection.toPx(p, vp)
            assertTrue("x=${px.x} 가 화면 밖이다", px.x in 0f..360f)
            assertTrue("y=${px.y} 가 화면 밖이다", px.y in 0f..260f)
        }
    }

    @Test
    fun `가까운 두 점은 더 확대된 줌을 고른다`() {
        val near = listOf(GeoPoint(37.4860, 126.9780), GeoPoint(37.4870, 126.9800))
        val far = listOf(GeoPoint(37.40, 126.80), GeoPoint(37.60, 127.20))

        val (_, nearLevel) = RouteMapProjection.fit(near, 360, 260)!!
        val (_, farLevel) = RouteMapProjection.fit(far, 360, 260)!!
        // 숫자가 작을수록 확대다(카카오 규칙).
        assertTrue("가까운 경로가 더 축소됐다 ($nearLevel vs $farLevel)", nearLevel < farLevel)
    }

    @Test
    fun `여백을 남긴다`() {
        // 여백이 0 이면 경로 끝이 테두리에 붙어 잘린 것처럼 보인다.
        val path = listOf(GeoPoint(37.48, 126.93), GeoPoint(37.50, 127.03))
        val (c, level) = RouteMapProjection.fit(path, 360, 260, marginUnits = 18)!!
        val vp = RouteMapProjection.Viewport(c, level, 360, 360, 260)

        val first = RouteMapProjection.toPx(path[0], vp)
        assertTrue("왼쪽 여백이 없다 (x=${first.x})", first.x >= 10f)
    }
}
