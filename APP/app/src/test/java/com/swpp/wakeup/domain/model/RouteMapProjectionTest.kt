package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 실제로 그렇게 틀렸었다. 축척을 두 배로 잘못 알고 있어서 경로선이 지도 중심에서
 * 두 배 멀리 그려졌다(신림역→서울대 301동의 끝이 관악산 능선). 2026-10-18 에 마커
 * 없는 지도 두 장의 위상 상관으로 축척을 다시 쟀다([StaticMapScale]).
 */
class RouteMapProjectionTest {

    private val center = GeoPoint(37.4867, 126.9789)

    /** lv9, 360 요청 단위, 화면도 360px(밀도 1 가정) */
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
    fun `lv9 에서 한 픽셀은 64미터다`() {
        // 실측값. lv3 이 1.0 이고 레벨당 두 배이므로 2^6 = 64.
        assertEquals(64.0, viewport().metersPerPixel, 0.001)
    }

    @Test
    fun `축척은 마커 없는 실측과 같다`() {
        // 중심만 북쪽으로 50단위만큼 옮긴 두 장이 lv4·6·8·10 모두 정확히 50단위 밀렸다.
        assertEquals(1.0, StaticMapScale.metersPerUnit(3), 1e-9)
        assertEquals(2.0, StaticMapScale.metersPerUnit(4), 1e-9)
        assertEquals(8.0, StaticMapScale.metersPerUnit(6), 1e-9)
        assertEquals(32.0, StaticMapScale.metersPerUnit(8), 1e-9)
        assertEquals(128.0, StaticMapScale.metersPerUnit(10), 1e-9)
        assertEquals(0.25, StaticMapScale.metersPerUnit(1), 1e-9)
    }

    @Test
    fun `위도 0_01도는 lv9 에서 약 17픽셀이다`() {
        // 지도 좌표로 1,109.9m(위도 1도 ≈ 110,990m), ÷ 64 = 17.34px.
        val north = GeoPoint(center.lat + 0.01, center.lng)
        val px = RouteMapProjection.toPx(north, viewport())
        assertEquals(130f - 17.34f, px.y, 0.02f)
        assertEquals(180f, px.x, 0.02f)
    }

    @Test
    fun `카카오 마커와 같은 자리에 그린다`() {
        // 실측: lv8, 500단위 지도 중심(37.46, 126.952)에서 동쪽 4.8km 마커의 끝이
        // 중심에서 149.6단위 오른쪽, 높이 차이 없음(핀 끝 검출 오차 ±0.5단위).
        val vp = RouteMapProjection.Viewport(GeoPoint(37.46, 126.952), 8, 500, 500, 500)
        val px = RouteMapProjection.toPx(GeoPoint(37.46, 127.00625), vp)
        assertEquals(250f + 149.65f, px.x, 0.6f)
        assertEquals(250f, px.y, 0.6f)
    }

    @Test
    fun `부산에서는 지도 좌표의 북쪽이 화면 위다`() {
        // 진북으로 그리면 같은 위도의 동쪽 점이 중심과 같은 높이에 찍힌다. 실제 지도에서는
        // 3단위 위에 있다(KakaoMapProjectionTest).
        val vp = RouteMapProjection.Viewport(GeoPoint(35.1796, 129.0756), 8, 500, 500, 500)
        val px = RouteMapProjection.toPx(GeoPoint(35.1796, 129.1283), vp)
        assertEquals(250f + 150.05f, px.x, 0.05f)
        assertEquals(250f - 3.17f, px.y, 0.05f)
    }

    @Test
    fun `밀도가 높으면 픽셀당 거리가 줄어든다`() {
        // 요청은 360 단위인데 화면이 1080px 이면 한 픽셀이 담는 거리가 1/3 이다.
        // 이걸 무시하면 경로가 화면의 1/3 크기로 그려진다.
        val dense = viewport(viewPx = 1080)
        assertEquals(64.0 / 3, dense.metersPerPixel, 0.001)
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
    fun `중심은 경로의 지도 좌표 범위 가운데다`() {
        val path = listOf(GeoPoint(37.48, 126.93), GeoPoint(37.50, 127.03))
        val (c, _) = RouteMapProjection.fit(path, 360, 260)!!
        val a = KakaoMapProjection.forward(path[0])
        val b = KakaoMapProjection.forward(path[1])
        val mid = KakaoMapProjection.forward(c)
        assertEquals((a.x + b.x) / 2, mid.x, 0.01)
        assertEquals((a.y + b.y) / 2, mid.y, 0.01)
        // 서울에서는 위·경도 가운데와 몇 m 차이다.
        assertEquals(37.49, c.lat, 1e-4)
        assertEquals(126.98, c.lng, 1e-4)
    }

    @Test
    fun `신림에서 강남까지는 lv8 로 맞는다`() {
        // 동서 8.6km. 360 단위 화면의 반(여백 18 제외 162단위)에 4.3km 가 들어가려면
        // 한 단위 26.7m 이상 — lv8(32m).
        val path = listOf(
            GeoPoint(37.484267, 126.929745),
            GeoPoint(37.497942, 127.027621),
        )
        val (_, level) = RouteMapProjection.fit(path, 360, 260)!!
        assertEquals(8, level)
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

    // --- 줌 범위가 조작과 맞는가 -------------------------------------------

    @Test
    fun `전체 보기는 줌 조작이 닿는 범위만 고른다`() {
        // 여기서 더 큰 값이 나오면 확대 버튼이 그 레벨을 끌어내려 한 번에 두
        // 단계가 튀거나 축소 버튼이 꺼진 채로 남는다.
        for (span in listOf(0.01, 0.05, 0.2, 0.5, 1.0, 3.0)) {
            val path = listOf(GeoPoint(37.0, 126.9), GeoPoint(37.0 + span, 126.9 + span))
            val (_, level) = RouteMapProjection.fit(path, 360, 260)!!
            assertTrue(
                "span=$span 에서 레벨 $level 이 범위를 넘었다",
                level in StaticMapScale.MIN_LEVEL..StaticMapScale.ROUTE_MAX_LEVEL,
            )
        }
    }

    @Test
    fun `수원에서 서울대 정도 거리도 한 화면에 들어온다`() {
        // 남북 21.8km. 260 단위 높이에 들어가려면 lv10(128m) 이다. 통학 경로로 실제로 있을 거리다.
        val path = listOf(GeoPoint(37.2636, 127.0286), GeoPoint(37.4599, 126.9519))
        val (c, level) = RouteMapProjection.fit(path, 360, 260)!!
        assertEquals(10, level)
        assertInside(path, c, level)
    }

    @Test
    fun `천안에서 서울대 같은 장거리는 장소 고르기 상한보다 축소한다`() {
        // 장소 고르기 상한(레벨 10)으로 자르면 남북 72km 가 화면에 안 들어간다.
        val path = listOf(GeoPoint(36.8099, 127.1462), GeoPoint(37.4599, 126.9519))
        val (c, level) = RouteMapProjection.fit(path, 360, 260)!!
        assertTrue("레벨 10 이하로는 이 경로가 안 들어간다 ($level)", level > StaticMapScale.MAX_LEVEL)
        assertInside(path, c, level)
    }

    private fun assertInside(path: List<GeoPoint>, c: GeoPoint, level: Int) {
        val vp = RouteMapProjection.Viewport(c, level, 360, 360, 260)
        for (p in path) {
            val px = RouteMapProjection.toPx(p, vp)
            assertTrue("x=${px.x} 가 화면 밖이다", px.x in 0f..360f)
            assertTrue("y=${px.y} 가 화면 밖이다", px.y in 0f..260f)
        }
    }

    @Test
    fun `경로 지도 상한은 서버 상한과 같다`() {
        // 서버(clients.STATIC_MAP_MAX_LEVEL)가 15 까지 받는다. 앱이 그보다 큰
        // 값을 보내면 서버가 400 을 준다.
        assertEquals(15, StaticMapScale.ROUTE_MAX_LEVEL)
    }
}

/**
 * 전체 보기가 **두 경로를 함께** 담는지.
 *
 * 고른 경로 위에 "지금 더 빠른 대안" 을 보라 실선으로 겹쳐 그린다. 대안은 다른
 * 길로 돌아가므로 좌표 범위가 고른 경로보다 넓을 수 있다. 고른 경로만 기준으로
 * 맞추면 초록 선이 화면 밖으로 나가고, 사용자는 **선이 잘렸다**고 읽는다.
 *
 * `fit` 의 서명을 바꾸지 않고 두 목록을 이어 붙여 넘긴다. `fit` 은 좌표의
 * 최소·최대만 쓰므로 이어 붙이기가 정확히 맞는 방법이다. 그 사실을 여기서
 * 고정한다 — 나중에 `fit` 이 순서나 인접성을 쓰게 바뀌면 이 단언이 깨진다.
 */
class RouteMapFitWithAltTest {

    private val width = 360
    private val height = 260

    private fun fit(path: List<GeoPoint>) =
        RouteMapProjection.fit(path, requestUnits = width, requestHeightUnits = height)

    /** 좌표 전부가 여백 안에 들어오는가. */
    private fun allInside(path: List<GeoPoint>, center: GeoPoint, level: Int): Boolean {
        val viewport = RouteMapProjection.Viewport(
            center = center,
            level = level,
            requestUnits = width,
            viewPx = width,
            viewHeightPx = height,
        )
        return path.all { point ->
            val px = RouteMapProjection.toPx(point, viewport)
            px.x >= 0f && px.x <= width && px.y >= 0f && px.y <= height
        }
    }

    // 고른 경로는 동서로 짧게 간다.
    private val chosen = listOf(
        GeoPoint(37.4800, 126.9300),
        GeoPoint(37.4820, 126.9600),
        GeoPoint(37.4840, 126.9900),
    )

    // 대안은 북쪽으로 크게 돌아간다. 고른 경로의 범위를 한참 넘는다.
    private val alt = listOf(
        GeoPoint(37.4800, 126.9300),
        GeoPoint(37.5600, 126.9500),
        GeoPoint(37.5700, 127.0400),
        GeoPoint(37.4840, 126.9900),
    )

    @Test
    fun `고른 경로만 맞추면 대안이 화면을 벗어난다`() {
        val (center, level) = fit(chosen)!!

        assertTrue("고른 경로는 당연히 들어온다", allInside(chosen, center, level))
        assertFalse(
            "이 상태에서 보라 경로선을 그리면 잘린다 — 그래서 합쳐서 맞춘다",
            allInside(alt, center, level),
        )
    }

    @Test
    fun `두 경로를 합쳐 맞추면 둘 다 들어온다`() {
        val (center, level) = fit(chosen + alt)!!

        assertTrue(allInside(chosen, center, level))
        assertTrue(allInside(alt, center, level))
    }

    @Test
    fun `합쳐 맞추면 더 축소된다`() {
        val onlyChosen = fit(chosen)!!.second
        val both = fit(chosen + alt)!!.second

        // 레벨이 클수록 축소다(StaticMapScale).
        assertTrue("$onlyChosen -> $both", both > onlyChosen)
    }

    @Test
    fun `대안이 없으면 결과가 달라지지 않는다`() {
        // 대안이 없는 일정에서 지도가 갑자기 축소되면 안 된다.
        assertEquals(fit(chosen), fit(chosen + emptyList()))
    }

    @Test
    fun `대안이 고른 경로 안쪽이면 축소하지 않는다`() {
        val inside = listOf(GeoPoint(37.4810, 126.9400), GeoPoint(37.4830, 126.9700))
        assertEquals(fit(chosen)!!.second, fit(chosen + inside)!!.second)
    }
}
