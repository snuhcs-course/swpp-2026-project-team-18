package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Partly AI-generated (Claude)
/**
 * 카카오 지도의 좌표계. EPSG:5181(Korea 2000 / Central Belt) 횡메르카토르다.
 *
 * GRS80 타원체, 원점 북위 38°·동경 127°, 축척 1, 가산값 동 200,000m·북 500,000m.
 * 카카오 좌표 변환 API 의 `WTM` 과 같은 값이다 — 서울·부산·대구·제주 등 일곱 지점에서
 * 1mm 안쪽으로 일치했다(2026-10-18).
 *
 * ## 왜 필요한가
 *
 * 정적 지도는 이 좌표계로 그린 그림이고, 한 레벨의 축척도 이 좌표의 미터로 정해진다
 * ([StaticMapScale.metersPerUnit]). 위·경도를 "위도 1도 = 111,320m, 경도는 cos(위도)배"
 * 로 펼쳐 그리면 두 가지가 틀린다.
 *
 * 1. **지도 위쪽은 진북이 아니라 127° 자오선 방향이다.** 서울(126.9°)에서는 거의
 *    같지만 부산(129.1°)에서는 1.2° 돌아가 있다. 중심에서 5km 떨어진 점이 옆으로
 *    100m 가까이 밀린다. 부산·대구에서 같은 위도의 동서 두 마커를 찍어 확인했다.
 * 2. 위도 1도는 서울에서 약 110,990m 다. 111,320 을 쓰면 남북 거리가 0.3% 길어진다.
 *
 * 경로 길이·진행률은 이 좌표계가 아니라 [RouteProgress] 의 지면 거리로 잰다. 서버의
 * `path_length_m` 과 같은 식이어야 하기 때문이다. 여기는 **지도 위에 그리는 것**만 맡는다.
 */
object KakaoMapProjection {

    /** 지도 좌표(m). x 는 동쪽, y 는 북쪽으로 커진다 */
    data class Tm(val x: Double, val y: Double)

    private const val A = 6_378_137.0
    private const val FLATTENING = 1 / 298.257222101
    private const val E2 = FLATTENING * (2 - FLATTENING)
    private const val EP2 = E2 / (1 - E2)
    private const val K0 = 1.0
    private const val FALSE_EASTING = 200_000.0
    private const val FALSE_NORTHING = 500_000.0
    private val ORIGIN_LNG = Math.toRadians(127.0)
    private val ORIGIN_ARC = meridianArc(Math.toRadians(38.0))

    /** 위·경도 → 지도 좌표 */
    fun forward(point: GeoPoint): Tm {
        val phi = Math.toRadians(point.lat)
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val n = A / sqrt(1 - E2 * sinPhi * sinPhi)
        val t = tan(phi).pow(2)
        val c = EP2 * cosPhi * cosPhi
        val a = (Math.toRadians(point.lng) - ORIGIN_LNG) * cosPhi
        val x = FALSE_EASTING + K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120
            )
        val y = FALSE_NORTHING + K0 * (
            meridianArc(phi) - ORIGIN_ARC + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720
                )
            )
        return Tm(x, y)
    }

    /** 지도 좌표 → 위·경도. [forward] 의 역이다(왕복 오차 1e-9° 안쪽) */
    fun inverse(tm: Tm): GeoPoint {
        val arc = ORIGIN_ARC + (tm.y - FALSE_NORTHING) / K0
        val mu = arc / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))
        val root = sqrt(1 - E2)
        val e1 = (1 - root) / (1 + root)
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val sin1 = sin(phi1)
        val cos1 = cos(phi1)
        val c1 = EP2 * cos1 * cos1
        val t1 = tan(phi1).pow(2)
        val n1 = A / sqrt(1 - E2 * sin1 * sin1)
        val r1 = A * (1 - E2) / (1 - E2 * sin1 * sin1).pow(1.5)
        val d = (tm.x - FALSE_EASTING) / (n1 * K0)
        val phi = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lambda = ORIGIN_LNG + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos1
        return GeoPoint(Math.toDegrees(phi), Math.toDegrees(lambda))
    }

    /** 적도부터 위도 [phi](라디안)까지의 자오선 호 길이(m) */
    private fun meridianArc(phi: Double): Double {
        val e4 = E2 * E2
        val e6 = e4 * E2
        return A * (
            (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }
}
