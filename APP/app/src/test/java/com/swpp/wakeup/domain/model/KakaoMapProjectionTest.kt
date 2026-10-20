// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 카카오 지도 좌표계(EPSG:5181).
 *
 * 기대값은 카카오 좌표 변환 API(`transcoord`, WGS84 → WTM)가 2026-10-18 에 준 값이다.
 * 이 식이 1mm 라도 틀리면 지도 위 선·핀이 어긋난다 — 에러 없이 그럴싸하게.
 */
class KakaoMapProjectionTest {

    private fun assertTm(lat: Double, lng: Double, x: Double, y: Double) {
        val tm = KakaoMapProjection.forward(GeoPoint(lat, lng))
        assertEquals("x ($lat, $lng)", x, tm.x, 0.005)
        assertEquals("y ($lat, $lng)", y, tm.y, 0.005)
    }

    @Test
    fun `원점은 가산값이다`() {
        assertTm(38.0, 127.0, 200_000.0, 500_000.0)
    }

    @Test
    fun `카카오 WTM 과 같다`() {
        assertTm(37.46, 126.952, 195_753.315, 440_065.739)          // 서울대
        assertTm(37.4502284, 126.9526062, 195_806.402, 438_981.199) // 301동
        assertTm(37.5665, 126.978, 198_056.367, 451_885.031)        // 시청
        assertTm(35.1796, 129.0756, 389_076.804, 188_993.756)       // 부산
        assertTm(35.8714, 128.6014, 344_628.140, 264_959.990)       // 대구
        assertTm(33.4996, 126.5312, 156_437.520, 758.481)           // 제주
    }

    @Test
    fun `역변환은 원래 좌표로 돌아온다`() {
        for (p in listOf(
            GeoPoint(37.46, 126.952),
            GeoPoint(35.1796, 129.0756),
            GeoPoint(33.4996, 126.5312),
            GeoPoint(38.3, 128.6),
        )) {
            val back = KakaoMapProjection.inverse(KakaoMapProjection.forward(p))
            assertEquals(p.lat, back.lat, 1e-9)
            assertEquals(p.lng, back.lng, 1e-9)
        }
    }

    @Test
    fun `127도에서 멀면 같은 위도의 동쪽 점이 지도에서 위로 올라간다`() {
        // 부산은 지도 위쪽(127° 자오선 방향)이 진북에서 1.2° 돌아가 있다. 실측: 같은 위도
        // 4.8km 동·서 두 마커가 화면에서 6단위(lv8) 높이가 달랐다. 진북 기준으로 그리면 0 이다.
        val center = KakaoMapProjection.forward(GeoPoint(35.1796, 129.0756))
        val east = KakaoMapProjection.forward(GeoPoint(35.1796, 129.1283))
        val west = KakaoMapProjection.forward(GeoPoint(35.1796, 129.0229))
        val riseUnits = (east.y - west.y) / StaticMapScale.metersPerUnit(8)
        assertEquals(6.27, riseUnits, 0.05)
        assertTrue(east.y > center.y && west.y < center.y)
    }

    @Test
    fun `서울에서는 진북과 거의 같다`() {
        // 126.95° 는 127° 에 가깝다. 같은 위도 4.8km 동쪽 점의 높이 차이는 0.04단위(lv8)다.
        val center = KakaoMapProjection.forward(GeoPoint(37.46, 126.952))
        val east = KakaoMapProjection.forward(GeoPoint(37.46, 127.00625))
        assertTrue(abs(east.y - center.y) / StaticMapScale.metersPerUnit(8) < 0.05)
    }
}
