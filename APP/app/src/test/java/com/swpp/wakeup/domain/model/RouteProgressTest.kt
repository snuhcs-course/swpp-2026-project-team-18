package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로 거리 기준 진행률.
 *
 * 세 가지를 고정한다.
 *
 * 1. 분자는 **경로를 따라간 거리**다. 직선거리를 쓰면 돌아가는 구간에서 거꾸로 준다
 * 2. 위치는 꼭짓점이 아니라 **선분에 투영**된다. 꼭짓점만 비교하면 점 간격만큼 튄다
 * 3. 경로를 벗어나면 그 사실을 알린다. 벗어난 사람에게 진행률은 틀린 정보다
 */
class RouteProgressTest {

    /** 위도만 다른 직선 경로. 0.01도 ≈ 1113m 라 계산이 눈으로 검산된다. */
    private fun straight(steps: Int = 10) = List(steps + 1) { i ->
        GeoPoint(lat = 37.500 + i * 0.001, lng = 127.0)
    }

    // --- 기본 ---------------------------------------------------------------

    @Test
    fun `점이 둘 미만이면 계산하지 않는다`() {
        assertNull(RouteProgress.of(emptyList(), GeoPoint(37.5, 127.0)))
        assertNull(RouteProgress.of(listOf(GeoPoint(37.5, 127.0)), GeoPoint(37.5, 127.0)))
    }

    @Test
    fun `출발점에 있으면 0퍼센트다`() {
        val p = RouteProgress.of(straight(), GeoPoint(37.500, 127.0))
        assertNotNull(p)
        assertEquals(0, p!!.percent)
        assertEquals(0, p.traveledM)
    }

    @Test
    fun `끝점에 있으면 100퍼센트다`() {
        val p = RouteProgress.of(straight(), GeoPoint(37.510, 127.0))!!
        assertEquals(100, p.percent)
        // 목적지에 닿았으면 분자와 분모가 같아야 한다. 어긋나면 "도착했는데
        // 97%" 가 되고 사용자는 무엇이 남았는지 알 수 없다.
        assertEquals(p.totalM, p.traveledM)
    }

    @Test
    fun `가운데에 있으면 절반이다`() {
        val p = RouteProgress.of(straight(), GeoPoint(37.505, 127.0))!!
        assertEquals(50, p.percent)
    }

    @Test
    fun `전체 길이는 위도차로 검산된다`() {
        // 0.01도 × 111320 = 1113.2m
        val p = RouteProgress.of(straight(), GeoPoint(37.500, 127.0))!!
        assertTrue("전체 길이 ${p.totalM}", kotlin.math.abs(p.totalM - 1113) <= 2)
    }

    // --- 선분 투영 ----------------------------------------------------------

    @Test
    fun `꼭짓점 사이에 있어도 그 비율만큼 반영된다`() {
        // 점이 둘뿐인 경로의 한가운데. 꼭짓점만 비교하는 구현이면 0% 나 100%
        // 중 하나로 튄다.
        val twoPoints = listOf(GeoPoint(37.500, 127.0), GeoPoint(37.510, 127.0))
        val p = RouteProgress.of(twoPoints, GeoPoint(37.505, 127.0))!!
        assertEquals(50, p.percent)
    }

    @Test
    fun `경로 옆으로 조금 비켜도 진행률은 유지된다`() {
        // GPS 는 늘 몇십 미터 흔들린다. 그때마다 진행률이 흔들리면 안 된다.
        val onLine = RouteProgress.of(straight(), GeoPoint(37.505, 127.0))!!
        val beside = RouteProgress.of(straight(), GeoPoint(37.505, 127.0005))!!

        assertEquals(onLine.percent, beside.percent)
        assertTrue("옆으로 벗어난 거리 ${beside.offRouteM}", beside.offRouteM in 1..80)
        assertTrue(beside.onRoute)
    }

    // --- 돌아가는 경로 ------------------------------------------------------

    @Test
    fun `돌아가는 경로에서 직선거리보다 진행이 크다`() {
        // ㄷ 모양으로 돌아가는 경로. 꺾인 곳 끝에 서 있으면 출발지에서의
        // 직선거리는 짧지만 경로로는 많이 온 것이다.
        val detour = listOf(
            GeoPoint(37.500, 127.000),
            GeoPoint(37.510, 127.000),
            GeoPoint(37.510, 127.010),
            GeoPoint(37.500, 127.010),
        )
        val p = RouteProgress.of(detour, GeoPoint(37.510, 127.010))!!

        // 세 구간 중 둘을 지났다. 직선거리로 재면 출발지에서 대각선 하나다.
        assertTrue("진행 ${p.percent}%", p.percent in 60..75)
    }

    @Test
    fun `되돌아오는 경로의 시작 근처에서 진행률이 100이 되지 않는다`() {
        // 갔다가 돌아오는 경로. 끝점이 출발점 가까이 온다. 출발 직후의 위치가
        // "끝점에 가깝다" 는 이유로 100% 가 되면 안 된다 — 가장 가까운 발을
        // 고르므로 시작 구간이 이긴다.
        val loop = listOf(
            GeoPoint(37.500, 127.000),
            GeoPoint(37.520, 127.000),
            GeoPoint(37.5001, 127.0001),
        )
        val p = RouteProgress.of(loop, GeoPoint(37.502, 127.000))!!
        assertTrue("진행 ${p.percent}%", p.percent < 20)
    }

    // --- 경로 이탈 ----------------------------------------------------------

    @Test
    fun `경로에서 멀리 떨어지면 이탈로 본다`() {
        // 경도로 0.05도 ≈ 4.4km 떨어진 지점.
        val p = RouteProgress.of(straight(), GeoPoint(37.505, 127.05))!!

        assertFalse("이탈인데 onRoute 가 참이다 (${p.offRouteM}m)", p.onRoute)
        assertTrue(p.offRouteM > RouteProgress.OFF_ROUTE_LIMIT_M)
    }

    @Test
    fun `한계 안이면 경로를 따르는 것으로 본다`() {
        // 0.002도 ≈ 222m. GPS 오차와 역 구내 이동을 견뎌야 한다.
        val p = RouteProgress.of(straight(), GeoPoint(37.505, 127.002))!!
        assertTrue("${p.offRouteM}m 인데 이탈로 본다", p.onRoute)
    }

    // --- 표시 --------------------------------------------------------------

    @Test
    fun `1km 미만은 미터로 적는다`() {
        val short = listOf(GeoPoint(37.5000, 127.0), GeoPoint(37.5045, 127.0))
        val p = RouteProgress.of(short, GeoPoint(37.5045, 127.0))!!
        assertTrue("라벨 ${p.label}", p.label.contains("m 이동"))
        assertFalse(p.label.contains("km 중"))
    }

    @Test
    fun `1km 이상은 킬로미터로 적는다`() {
        // 두 값을 각각 환산한다. 전체는 1.1km 지만 이동분은 612m 라 단위가
        // 섞인다 — 지도 앱들이 쓰는 방식이고, 612m 를 "0.6km" 로 적으면
        // 있는 정보를 버리는 것이다.
        val p = RouteProgress.of(straight(), GeoPoint(37.5055, 127.0))!!
        assertEquals("경로 1.1km 중 612m 이동", p.label)
    }

    @Test
    fun `퍼센트는 0에서 100 사이로 잘린다`() {
        // 경로 시작 전(뒤쪽)에 있어도 음수가 나오지 않는다.
        val before = RouteProgress.of(straight(), GeoPoint(37.490, 127.0))!!
        assertEquals(0, before.percent)

        val after = RouteProgress.of(straight(), GeoPoint(37.520, 127.0))!!
        assertEquals(100, after.percent)
    }

    // --- 길이 계산이 서버와 같은가 -------------------------------------------

    @Test
    fun `경로 길이는 서버와 같은 식이다`() {
        // 서버 clients.path_length_m 과 같은 상수·같은 cos 보정을 쓴다.
        // 다르면 화면의 분모와 서버가 보관한 route_distance_m 이 어긋난다.
        val path = listOf(GeoPoint(37.5, 127.0), GeoPoint(37.5, 128.0))
        val length = RouteProgress.lengthM(path)

        val expected = StaticMapScale.METERS_PER_DEGREE *
            kotlin.math.cos(Math.toRadians(37.5))
        assertTrue("$length vs $expected", kotlin.math.abs(length - expected) / expected < 0.001)
    }

    // --- 단계 --------------------------------------------------------------

    @Test
    fun `단계는 네 개이고 순서가 정해져 있다`() {
        assertEquals(4, TripStage.ordered.size)
        assertEquals(
            listOf("알람 전", "준비 중", "이동 중", "도착"),
            TripStage.ordered.map { it.label },
        )
    }

    @Test
    fun `지난 일정은 진행 순서에 들어가지 않는다`() {
        // 여정의 한 칸이 아니라 "말할 것이 없다" 는 상태다. 순서에 넣으면
        // 진행 바가 도착 다음 칸을 하나 더 그린다.
        assertFalse(TripStage.PAST in TripStage.ordered)
        assertEquals("지난 일정", TripStage.PAST.label)
    }

    // --- 단계 판정 ----------------------------------------------------------

    @Test
    fun `알람 전에는 알람 전이다`() {
        assertEquals(
            TripStage.BEFORE_ALARM,
            TripStage.of(tracked = null, alarmPassed = false, eventPassed = false),
        )
    }

    @Test
    fun `알람이 지났고 일정은 아직이면 준비 중이다`() {
        assertEquals(
            TripStage.PREPARING,
            TripStage.of(tracked = null, alarmPassed = true, eventPassed = false),
        )
    }

    @Test
    fun `일정까지 지났고 기록이 없으면 지난 일정이다`() {
        // 이 판정이 없으면 이틀 전 일정을 열어도 "준비 중" 으로 남는다.
        assertEquals(
            TripStage.PAST,
            TripStage.of(tracked = null, alarmPassed = true, eventPassed = true),
        )
    }

    @Test
    fun `추적 중이면 시각 판정을 이긴다`() {
        // 일정 시각이 지났어도 실제로 이동 중이면 이동 중이다. 늦었을 뿐이다.
        assertEquals(
            TripStage.IN_TRANSIT,
            TripStage.of(tracked = TripStage.IN_TRANSIT, alarmPassed = true, eventPassed = true),
        )
        assertEquals(
            TripStage.ARRIVED,
            TripStage.of(tracked = TripStage.ARRIVED, alarmPassed = true, eventPassed = true),
        )
    }

    @Test
    fun `알람 전에 위치가 들어와도 추적이 이긴다`() {
        // 알람보다 먼저 일어나 나간 경우다. "알람 전" 이라고 말하면 이미
        // 이동하고 있는 사람에게 틀린 상태를 보여 준다.
        assertEquals(
            TripStage.IN_TRANSIT,
            TripStage.of(tracked = TripStage.IN_TRANSIT, alarmPassed = false, eventPassed = false),
        )
    }

    // --- 남은 거리 ----------------------------------------------------------

    @Test
    fun `남은 거리는 전체에서 이동분을 뺀 값이다`() {
        val p = RouteProgress.of(straight(), GeoPoint(37.505, 127.0))!!
        assertEquals(p.totalM - p.traveledM, p.remainingM)
        assertTrue(p.remainingLabel.endsWith("남음"))
    }

    @Test
    fun `도착하면 남은 거리가 0이다`() {
        val p = RouteProgress.of(straight(), GeoPoint(37.510, 127.0))!!
        assertEquals(0, p.remainingM)
        assertEquals("0m 남음", p.remainingLabel)
    }

    @Test
    fun `남은 거리는 음수가 되지 않는다`() {
        // 투영이 끝점으로 잘리므로 이동분이 전체를 넘을 수 없지만, 반올림
        // 때문에 1m 넘칠 수 있다. 그때 "-1m 남음" 이 나오면 안 된다.
        val p = RouteProgress(ratio = 1f, traveledM = 1001, totalM = 1000, offRouteM = 0)
        assertEquals(0, p.remainingM)
    }

    @Test
    fun `이동 표시는 짧은 형태다`() {
        val p = RouteProgress(ratio = 0.46f, traveledM = 4600, totalM = 10000, offRouteM = 12)
        assertEquals("4.6km 이동", p.movedLabel)
        assertEquals("5.4km 남음", p.remainingLabel)
        assertEquals("12m", p.offRouteLabel)
    }
}
