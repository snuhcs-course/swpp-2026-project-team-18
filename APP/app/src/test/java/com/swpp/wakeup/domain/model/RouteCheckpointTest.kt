package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경로 구간을 사용자 행동 순서로 펼치는 규칙. */
class RouteCheckpointTest {

    private fun subway(arrivals: List<RouteArrival> = emptyList()) = RouteSegment(
        kind = RouteSegment.Kind.SUBWAY,
        seconds = 420,
        label = "2호선",
        lineName = "2호선",
        region = "metro_seoul",
        stops = listOf("신림", "봉천", "서울대입구(관악구청)"),
        arrivals = arrivals,
    )

    private fun bus(arrivals: List<RouteArrival> = emptyList()) = RouteSegment(
        kind = RouteSegment.Kind.BUS,
        seconds = 480,
        label = "5513",
        lineName = "5513",
        busType = "지선",
        region = "metro_seoul",
        stops = listOf("봉천", "관악구청"),
        arrivals = arrivals,
    )

    @Test
    fun `출발부터 승하차를 거쳐 도착까지 순서대로 만든다`() {
        val checkpoints = RouteSegments(
            listOf(
                RouteSegment(RouteSegment.Kind.WALK, 240, "도보"),
                subway(),
                RouteSegment(RouteSegment.Kind.WALK, 120, "도보"),
                bus(),
                RouteSegment(RouteSegment.Kind.WALK, 120, "도보"),
            )
        ).checkpoints()

        val labels = checkpoints.filterIsInstance<RouteCheckpoint.Stop>().map { it.label }
        assertEquals(
            listOf(
                "출발",
                "신림 승차",
                "서울대입구(관악구청) 하차",
                "봉천 승차",
                "관악구청 하차",
                "도착",
            ),
            labels,
        )
    }

    @Test
    fun `다음 차량과 그다음 차량은 승차 바로 뒤에 온다`() {
        val segment = subway(
            listOf(
                RouteArrival(200, "3분 20초 뒤 도착"),
                RouteArrival(580, "9분 40초 뒤 도착"),
            )
        )

        val checkpoints = RouteSegments(listOf(segment)).checkpoints()

        assertTrue(checkpoints[0] is RouteCheckpoint.Stop)
        assertEquals("신림 승차", (checkpoints[1] as RouteCheckpoint.Stop).label)
        val next = checkpoints[2] as RouteCheckpoint.VehicleArrival
        val later = checkpoints[3] as RouteCheckpoint.VehicleArrival
        assertEquals("다음 열차", next.vehicleLabel)
        assertTrue(next.primary)
        assertEquals("3분 20초 뒤 도착", next.arrival.displayText)
        assertEquals("그다음 열차", later.vehicleLabel)
        assertFalse(later.primary)
        assertEquals("9분 40초 뒤 도착", later.arrival.displayText)
        assertEquals("서울대입구(관악구청) 하차", (checkpoints[4] as RouteCheckpoint.Stop).label)
    }

    @Test
    fun `버스 도착정보는 다음 버스와 그다음 버스로 부른다`() {
        val checkpoints = RouteSegments(
            listOf(
                bus(
                    listOf(
                        RouteArrival(70, "1분 10초 뒤 도착", crowding = "여유"),
                        RouteArrival(810, "13분 30초 뒤 도착"),
                    )
                )
            )
        ).checkpoints()
        val arrivals = checkpoints.filterIsInstance<RouteCheckpoint.VehicleArrival>()

        assertEquals(listOf("다음 버스", "그다음 버스"), arrivals.map { it.vehicleLabel })
        assertEquals("1분 10초 뒤 도착 · 여유", arrivals[0].arrival.displayText)
        assertEquals("13분 30초 뒤 도착", arrivals[1].arrival.displayText)
    }

    @Test
    fun `도착정보가 없어도 체크포인트는 남는다`() {
        val checkpoints = RouteSegments(listOf(subway())).checkpoints()

        assertTrue(checkpoints.none { it is RouteCheckpoint.VehicleArrival })
        assertEquals(
            listOf("출발", "신림 승차", "서울대입구(관악구청) 하차", "도착"),
            checkpoints.filterIsInstance<RouteCheckpoint.Stop>().map { it.label },
        )
    }

    @Test
    fun `세 번째 이후 차량은 화면에 넣지 않는다`() {
        val segment = subway(
            listOf(
                RouteArrival(60, "1분 뒤 도착"),
                RouteArrival(120, "2분 뒤 도착"),
                RouteArrival(180, "3분 뒤 도착"),
            )
        )

        val arrivals = RouteSegments(listOf(segment)).checkpoints()
            .filterIsInstance<RouteCheckpoint.VehicleArrival>()

        assertEquals(2, arrivals.size)
        assertEquals(listOf(60, 120), arrivals.map { it.arrival.seconds })
    }

    @Test
    fun `도보 자동차 자전거만 있으면 상세 체크포인트를 만들지 않는다`() {
        listOf(
            RouteSegment.Kind.WALK,
            RouteSegment.Kind.CAR,
            RouteSegment.Kind.BICYCLE,
        ).forEach { kind ->
            assertTrue(
                RouteSegments(listOf(RouteSegment(kind, 600, "이동"))).checkpoints().isEmpty()
            )
        }
    }

    @Test
    fun `정류장이 하나면 승차만 만들고 거짓 하차를 만들지 않는다`() {
        val oneStop = bus().copy(stops = listOf("제2공학관"))
        val stops = RouteSegments(listOf(oneStop)).checkpoints()
            .filterIsInstance<RouteCheckpoint.Stop>()

        assertEquals(listOf("출발", "제2공학관 승차", "도착"), stops.map { it.label })
    }

    @Test
    fun `이미 승차가 붙은 이름에는 중복으로 붙이지 않는다`() {
        val named = bus().copy(stops = listOf("제2공학관 승차", "관악구청 하차"))
        val stops = RouteSegments(listOf(named)).checkpoints()
            .filterIsInstance<RouteCheckpoint.Stop>()

        assertEquals("제2공학관 승차", stops[1].label)
        assertEquals("관악구청 하차", stops[2].label)
    }

    @Test
    fun `노선표는 승차 지점에만 나온다`() {
        val stops = RouteSegments(listOf(subway())).checkpoints()
            .filterIsInstance<RouteCheckpoint.Stop>()

        assertEquals("2호선", stops.first { it.role == RouteCheckpoint.Stop.Role.BOARD }.lineLabel)
        assertEquals("", stops.first { it.role == RouteCheckpoint.Stop.Role.ALIGHT }.lineLabel)
        assertEquals("", stops.first { it.role == RouteCheckpoint.Stop.Role.START }.lineLabel)
    }

    @Test
    fun `초를 사람이 읽는 도착 문구로 바꾼다`() {
        assertEquals("곧 도착", RouteArrival.formatSeconds(0))
        assertEquals("40초 뒤 도착", RouteArrival.formatSeconds(40))
        assertEquals("1분 뒤 도착", RouteArrival.formatSeconds(60))
        assertEquals("3분 20초 뒤 도착", RouteArrival.formatSeconds(200))
        assertEquals("곧 도착", RouteArrival.formatSeconds(-1))
    }

    @Test
    fun `서버 문구가 비면 초로 문구를 만든다`() {
        assertEquals("3분 20초 뒤 도착", RouteArrival(200, "").displayText)
    }
}
