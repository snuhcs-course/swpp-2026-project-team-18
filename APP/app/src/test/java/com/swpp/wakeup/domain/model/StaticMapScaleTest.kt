package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정적 지도 축척.
 *
 * **이 값이 틀리면 조용히 틀린다.** "이 지역 재검색" 이 화면과 다른 범위를
 * 검색하고, 지도를 끌면 손가락보다 많거나 적게 움직인다. 둘 다 에러가 나지
 * 않아서 "왜 안 보이던 게 나오지" 로만 드러난다.
 *
 * 그래서 실측값을 못 박는다. 측정은 `jit-tools/calibrate_staticmap.py` 가
 * 같은 이미지에 마커 두 개를 알려진 위도 차로 찍고 픽셀 간격을 재는 방식으로
 * 했다.
 */
class StaticMapScaleTest {

    @Test
    fun `기준 레벨에서 한 단위가 1미터다`() {
        assertEquals(1.0, StaticMapScale.metersPerUnit(StaticMapScale.BASE_LEVEL), 0.001)
    }

    @Test
    fun `레벨 하나가 정확히 두 배다`() {
        // 실측에서 lv4→lv7 이 8.012배, lv7→lv10 이 7.989배였다. 두 배가 맞다.
        for (level in StaticMapScale.BASE_LEVEL until 14) {
            val here = StaticMapScale.metersPerUnit(level)
            val next = StaticMapScale.metersPerUnit(level + 1)
            assertEquals("lv$level → lv${level + 1}", here * 2, next, 0.0001)
        }
    }

    @Test
    fun `기준 레벨 아래로는 더 확대되지 않는다`() {
        // lv1~4 를 모두 1.00 으로 측정했다. 카카오가 그 아래를 잘라 낸다.
        for (level in 1..StaticMapScale.BASE_LEVEL) {
            assertEquals("lv$level", 1.0, StaticMapScale.metersPerUnit(level), 0.001)
        }
    }

    @Test
    fun `실제로 측정한 지점과 맞는다`() {
        // calibrate_staticmap.py 결과: lv7 = 8.03, lv10 = 64.15
        assertEquals(8.0, StaticMapScale.metersPerUnit(7), 0.1)
        assertEquals(64.0, StaticMapScale.metersPerUnit(10), 0.5)
    }

    @Test
    fun `쓸 수 있는 줌 범위가 뒤집히지 않았다`() {
        assertTrue(StaticMapScale.MIN_LEVEL < StaticMapScale.MAX_LEVEL)
        assertTrue(StaticMapScale.DEFAULT_LEVEL in StaticMapScale.MIN_LEVEL..StaticMapScale.MAX_LEVEL)
    }

    @Test
    fun `밀도가 1이면 픽셀당 거리가 단위당 거리와 같다`() {
        val level = 6
        assertEquals(
            StaticMapScale.metersPerUnit(level),
            StaticMapScale.metersPerPixel(level, requestUnits = 360, viewPixels = 360),
            0.0001,
        )
    }

    @Test
    fun `밀도가 3이면 픽셀당 거리가 삼분의 일이다`() {
        // 서버에는 dp 로 크기를 보내고 끌기는 화면 픽셀로 일어난다. 환산하지
        // 않으면 손가락을 1cm 끌었을 때 지도가 3cm 움직인다.
        val level = 6
        val perUnit = StaticMapScale.metersPerUnit(level)
        val perPixel = StaticMapScale.metersPerPixel(level, requestUnits = 360, viewPixels = 1080)
        assertEquals(perUnit / 3.0, perPixel, 0.0001)
    }

    @Test
    fun `크기가 0이면 단위당 거리로 떨어진다`() {
        // 화면 크기를 아직 모를 때 0으로 나누면 무한이 되고, 그 값으로 중심을
        // 옮기면 좌표가 NaN 이 된다.
        val level = 5
        assertEquals(
            StaticMapScale.metersPerUnit(level),
            StaticMapScale.metersPerPixel(level, requestUnits = 0, viewPixels = 0),
            0.0001,
        )
    }

    @Test
    fun `마커 상한이 카카오 한계와 같다`() {
        // 정적 지도는 한 번에 다섯 개까지만 그린다. 더 보내면 나머지가 조용히
        // 빠진다 — 에러가 아니라서 알아채기 어렵다.
        assertEquals(5, StaticMapScale.MARKER_LIMIT)
    }

    @Test
    fun `위도 1도가 약 111km 다`() {
        assertEquals(111_320.0, StaticMapScale.METERS_PER_DEGREE, 1.0)
    }
}
