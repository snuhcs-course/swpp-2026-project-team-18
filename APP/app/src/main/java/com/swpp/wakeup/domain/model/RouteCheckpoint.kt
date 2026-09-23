package com.swpp.wakeup.domain.model

/**
 * 선택한 경로 안에서 사용자가 행동하거나 확인할 지점.
 *
 * 출발·승차·하차·도착과 차량 도착정보를 한 목록으로 만든다. UI 가 서버의
 * `segments` 모양을 직접 해석하지 않게 한다 — 수단이 하나 더 생겨도 화면
 * 코드는 이 두 종류([Stop], [VehicleArrival])만 그리면 된다.
 */
sealed interface RouteCheckpoint {

    /** 출발/승차/하차/도착 지점. */
    data class Stop(
        val label: String,
        val role: Role,
        /** 승·하차가 속한 구간. 출발·도착이면 null. 점·노선표 색을 고른다. */
        val segment: RouteSegment? = null,
    ) : RouteCheckpoint {
        enum class Role { START, BOARD, ALIGHT, END }

        /** 승차 행에만 노선표를 붙인다. 하차 행에는 반복하지 않는다. */
        val lineLabel: String
            get() = if (role == Role.BOARD) segment?.label.orEmpty() else ""
    }

    /**
     * 승차 지점에 들어오는 차량.
     *
     * [primary] 가 true면 바로 다음 차량이고 노랑으로, false면 그다음 차량이고
     * 회색으로 그린다. 이 행은 **사용자가 지점에 닿는 시각이 아니다.**
     */
    data class VehicleArrival(
        val vehicleLabel: String,
        val arrival: RouteArrival,
        val primary: Boolean,
    ) : RouteCheckpoint
}

/**
 * 승차 가능한 구간을 출발부터 도착까지 체크포인트로 펼친다.
 *
 * 카카오 `stops` 의 첫 항목은 승차, 마지막은 하차다. 중간 정류장을 전부
 * 표시하면 20개 역 경로가 카드 하나를 화면 여러 장으로 밀어내므로, 사용자가
 * 행동하는 시작·끝만 표시한다. 지나치는 정류장 정보는 버리지 않고
 * [RouteSegment.stops] 에 남아 있다.
 */
fun RouteSegments.checkpoints(): List<RouteCheckpoint> {
    val transit = items.filter {
        (it.kind == RouteSegment.Kind.BUS || it.kind == RouteSegment.Kind.SUBWAY) &&
            it.stops.isNotEmpty()
    }
    if (transit.isEmpty()) return emptyList()

    val out = mutableListOf<RouteCheckpoint>()
    out += RouteCheckpoint.Stop("출발", RouteCheckpoint.Stop.Role.START)

    transit.forEach { segment ->
        val boarding = segment.stops.first().trim()
        if (boarding.isNotEmpty()) {
            out += RouteCheckpoint.Stop(
                label = actionLabel(boarding, "승차"),
                role = RouteCheckpoint.Stop.Role.BOARD,
                segment = segment,
            )

            val vehicleWord = if (segment.kind == RouteSegment.Kind.SUBWAY) "열차" else "버스"
            segment.arrivals.take(2).forEachIndexed { index, arrival ->
                out += RouteCheckpoint.VehicleArrival(
                    vehicleLabel = if (index == 0) "다음 $vehicleWord" else "그다음 $vehicleWord",
                    arrival = arrival,
                    primary = index == 0,
                )
            }
        }

        val alighting = segment.stops.last().trim()
        if (segment.stops.size >= 2 && alighting.isNotEmpty() && alighting != boarding) {
            out += RouteCheckpoint.Stop(
                label = actionLabel(alighting, "하차"),
                role = RouteCheckpoint.Stop.Role.ALIGHT,
                segment = segment,
            )
        }
    }

    out += RouteCheckpoint.Stop("도착", RouteCheckpoint.Stop.Role.END)
    return out
}

private fun actionLabel(stop: String, action: String): String =
    if (stop.endsWith(action)) stop else "$stop $action"
