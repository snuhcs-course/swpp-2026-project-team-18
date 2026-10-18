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
 * 그래서 실측값을 못 박는다. 2026-10-18 에 **마커 없이** 다시 쟀다 — 같은 크기의
 * 지도를 중심만 알려진 거리만큼 옮겨 두 장 받고 위상 상관으로 그림이 밀린 픽셀
 * 수를 쟀다. 예전 측정(마커 두 개의 간격)은 두 배 틀렸다. 카카오는 마커를 주면
 * 마커가 다 보이도록 줌을 바꾸기 때문이다.
 */
class StaticMapScaleTest {

    @Test
    fun `기준 레벨에서 한 단위가 1미터다`() {
        assertEquals(3, StaticMapScale.REFERENCE_LEVEL)
        assertEquals(1.0, StaticMapScale.metersPerUnit(StaticMapScale.REFERENCE_LEVEL), 0.001)
    }

    @Test
    fun `레벨 하나가 정확히 두 배다`() {
        // 재측정에서 lv1~15 모두 두 배였다.
        for (level in 1 until StaticMapScale.ROUTE_MAX_LEVEL) {
            val here = StaticMapScale.metersPerUnit(level)
            val next = StaticMapScale.metersPerUnit(level + 1)
            assertEquals("lv$level → lv${level + 1}", here * 2, next, 0.0001)
        }
    }

    @Test
    fun `실제로 측정한 지점과 맞는다`() {
        // 마커 없는 두 장의 위상 상관(scale 1·2 같음).
        val measured = mapOf(1 to 0.25, 2 to 0.5, 3 to 1.0, 4 to 2.0, 6 to 8.0, 8 to 32.0, 10 to 128.0, 15 to 4096.0)
        for ((level, metersPerUnit) in measured) {
            assertEquals("lv$level", metersPerUnit, StaticMapScale.metersPerUnit(level), 1e-9)
        }
    }

    @Test
    fun `장소 고르기 기본 줌은 360단위 폭이 약 3km 다`() {
        val widthM = 360 * StaticMapScale.metersPerUnit(StaticMapScale.DEFAULT_LEVEL)
        assertEquals(2_880.0, widthM, 1.0)
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
