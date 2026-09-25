package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 경로를 얼마나 왔는지.
 *
 * ## 시간이 아니라 거리다
 *
 * 진행률을 경과 시간으로 채우면 **가만히 있어도 바가 늘어난다.** 지하철을
 * 기다리는 8분 동안 화면은 "가고 있다" 고 말하고, 사용자는 그것을 근거로
 * 안심한다. 그래서 분자는 **경로를 따라 실제로 이동한 거리**다.
 *
 * ## 직선거리도 아니다
 *
 * 출발지에서 현재 위치까지의 직선거리(displacement)를 쓰면 경로가 돌아가는
 * 구간에서 진행률이 거꾸로 줄어든다. 2호선이 남쪽으로 내려갔다 동쪽으로 도는
 * 구간에서 실제로 그렇게 된다. 그래서 **경로 위에 투영한 뒤 그 지점까지의
 * 누적 거리**를 쓴다.
 */
data class RouteProgress(
    /** 0~1. 경로 누적거리 기준 */
    val ratio: Float,
    /** 경로를 따라 이동한 거리(m) */
    val traveledM: Int,
    /** 경로 전체 길이(m) */
    val totalM: Int,
    /** 현재 위치가 경로에서 떨어진 거리(m) */
    val offRouteM: Int,
) {
    /**
     * 경로를 따라가고 있는가.
     *
     * 벗어났으면 진행률을 **보여 주지 않는 쪽**이 맞다. 경로에서 2km 떨어진
     * 사람에게 "46% 왔음" 은 틀린 정보이고, 그 숫자를 근거로 아직 여유가
     * 있다고 판단하면 지각한다.
     */
    val onRoute: Boolean get() = offRouteM <= OFF_ROUTE_LIMIT_M

    /** "10.0km 중 4.6km 이동" */
    val label: String
        get() = "경로 ${formatKm(totalM)} 중 ${formatKm(traveledM)} 이동"

    /** "4.6km 이동". 진행 바 안에 들어가는 짧은 형태다 */
    val movedLabel: String get() = "${formatKm(traveledM)} 이동"

    /** 남은 거리(m). 음수가 되지 않게 0 에서 자른다 */
    val remainingM: Int get() = (totalM - traveledM).coerceAtLeast(0)

    /**
     * "5.4km 남음".
     *
     * 이동한 거리 대신 **남은 거리**를 오른쪽에 둔다. 이동 중인 사람이 알고 싶은
     * 것은 얼마나 왔는지가 아니라 얼마나 더 가야 하는지다.
     */
    val remainingLabel: String get() = "${formatKm(remainingM)} 남음"

    /** 경로에서 벗어난 거리. "2.1km" */
    val offRouteLabel: String get() = formatKm(offRouteM)

    val percent: Int get() = (ratio * 100).roundToInt().coerceIn(0, 100)

    companion object {
        /**
         * 이 거리를 넘게 떨어지면 경로를 벗어난 것으로 본다.
         *
         * 300m 는 GPS 오차(도심에서 50~100m)보다 넉넉하고, 지하철 한 역
         * 간격보다는 짧다. 더 크게 잡으면 다른 노선을 타고 있어도 경로 위라고
         * 말하게 된다.
         */
        const val OFF_ROUTE_LIMIT_M = 300

        private fun formatKm(meters: Int): String =
            if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0)

        /**
         * 두 점 사이 거리(m).
         *
         * 위도는 그대로 환산하고 경도는 위도에 따라 짧아지므로 cos 로 보정한다.
         * 서버의 `clients.path_length_m` 과 **같은 식이어야 한다** — 다르면
         * 화면의 분모와 서버가 보관한 길이가 어긋난다.
         */
        fun distanceM(a: GeoPoint, b: GeoPoint): Double {
            val cos = cos(Math.toRadians((a.lat + b.lat) / 2))
            val dy = (b.lat - a.lat) * StaticMapScale.METERS_PER_DEGREE
            val dx = (b.lng - a.lng) * StaticMapScale.METERS_PER_DEGREE * cos
            return hypot(dx, dy)
        }

        /** 경로 전체 길이(m). 분모다. */
        fun lengthM(path: List<GeoPoint>): Double {
            var total = 0.0
            for (i in 1 until path.size) total += distanceM(path[i - 1], path[i])
            return total
        }

        /**
         * 현재 위치를 경로에 투영해 진행률을 낸다. 경로가 비었으면 null.
         *
         * 모든 구간에 대해 위치를 선분에 내린 발을 구하고, 가장 가까운 발을
         * 고른다. **꼭짓점만 비교하면 안 된다** — 점 간격이 30m 넘는 구간에서
         * 사용자가 두 점 사이 한가운데 있으면 진행률이 그 간격만큼 계단처럼
         * 튄다.
         */
        fun of(path: List<GeoPoint>, here: GeoPoint): RouteProgress? {
            if (path.size < 2) return null

            var bestOff = Double.MAX_VALUE
            var bestTraveled = 0.0
            var walked = 0.0

            for (i in 1 until path.size) {
                val a = path[i - 1]
                val b = path[i]
                val segment = distanceM(a, b)
                if (segment > 0.0) {
                    // 선분 위에서의 위치를 0~1 로 구한다. 미터 평면으로 바꿔
                    // 계산해야 경도 왜곡이 섞이지 않는다.
                    val t = projectionRatio(a, b, here, segment)
                    val foot = GeoPoint(
                        lat = a.lat + (b.lat - a.lat) * t,
                        lng = a.lng + (b.lng - a.lng) * t,
                    )
                    val off = distanceM(here, foot)
                    if (off < bestOff) {
                        bestOff = off
                        bestTraveled = walked + segment * t
                    }
                }
                walked += segment
            }

            if (bestOff == Double.MAX_VALUE) return null
            val total = walked
            if (total <= 0.0) return null

            return RouteProgress(
                ratio = (bestTraveled / total).toFloat().coerceIn(0f, 1f),
                traveledM = bestTraveled.roundToInt(),
                totalM = total.roundToInt(),
                offRouteM = bestOff.roundToInt(),
            )
        }

        /**
         * 점을 선분 `a→b` 에 내린 발의 위치(0~1). 선분 밖이면 끝으로 잘린다.
         *
         * 자르지 않으면 경로 시작 전이나 도착 후에 발이 선분 바깥으로 나가
         * 누적 거리가 음수가 되거나 전체 길이를 넘는다.
         */
        private fun projectionRatio(
            a: GeoPoint,
            b: GeoPoint,
            p: GeoPoint,
            segmentM: Double,
        ): Double {
            val cos = cos(Math.toRadians(a.lat))
            val ax = 0.0
            val ay = 0.0
            val bx = (b.lng - a.lng) * StaticMapScale.METERS_PER_DEGREE * cos
            val by = (b.lat - a.lat) * StaticMapScale.METERS_PER_DEGREE
            val px = (p.lng - a.lng) * StaticMapScale.METERS_PER_DEGREE * cos
            val py = (p.lat - a.lat) * StaticMapScale.METERS_PER_DEGREE

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq <= 0.0 || abs(segmentM) < 1e-9) return 0.0
            return (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        }
    }
}

/**
 * 사용자가 지금 여정의 어디에 있는가.
 *
 * 서버가 아니라 앱이 정한다. 판정 근거(위치·시각)가 기기에 있고, 서버는
 * 관측이 올라간 뒤에야 안다.
 *
 * [TripGeofence.Phase] 는 출발·도착 **판정**을 위한 세 단계이고, 이것은 화면에
 * 보여 줄 네 단계다. 알람이 울리기 전과 준비 중은 판정 관점에서 같지만
 * (둘 다 집에 있다) 사용자에게는 전혀 다른 상태다.
 */
enum class TripStage(val label: String) {
    /** 알람이 아직 울리지 않았다 */
    BEFORE_ALARM("알람 전"),

    /** 알람은 울렸고 아직 집이다 */
    PREPARING("준비 중"),

    /** 집을 나섰다 */
    IN_TRANSIT("이동 중"),

    /** 목적지에 도착했다 */
    ARRIVED("도착"),

    /**
     * 일정 시각이 지났고 이동 기록이 없다.
     *
     * **[ordered] 에 넣지 않는다.** 진행 막대의 한 칸이 아니라 "막대로 말할
     * 것이 없다" 는 상태다. 이것이 없으면 알람이 지난 일정은 영원히 "준비 중"
     * 으로 남는다 — 이틀 전 일정을 열어도 지금 준비하고 있다고 말하게 된다.
     */
    PAST("지난 일정");

    companion object {
        /** 화면에 그릴 순서. 진행 막대의 단계 표시가 이 순서를 쓴다 */
        val ordered: List<TripStage> = listOf(BEFORE_ALARM, PREPARING, IN_TRANSIT, ARRIVED)

        /**
         * 단계를 정한다. 추적 중이면 [tracked] 가 이기고, 아니면 시각으로 가른다.
         *
         * **시각을 여기서 읽지 않는다.** 두 불린은 화면 문구("지난 알람")를 만든
         * 것과 같은 시점에서 나온 값이다. 여기서 시계를 다시 보면 문구와 단계가
         * 서로 다른 시점을 근거로 삼는다.
         *
         * @param tracked 추적기가 판정한 단계. 없으면 null
         * @param alarmPassed 알람 시각이 지났는가
         * @param eventPassed 일정 시각까지 지났는가
         * @param prepApplies 준비 단계가 있는 일정인가. 집에서 출발하지 않으면 없다
         */
        fun of(
            tracked: TripStage?,
            alarmPassed: Boolean,
            eventPassed: Boolean,
            prepApplies: Boolean = true,
        ): TripStage = when {
            // 준비 단계가 없는 일정에서 추적기가 "준비 중" 을 주면 이동 중으로
            // 읽는다. 집에 있지도 않은 사람에게 준비 중이라고 할 근거가 없다.
            tracked == PREPARING && !prepApplies -> IN_TRANSIT
            tracked != null -> tracked
            !alarmPassed -> BEFORE_ALARM
            // 일정이 끝났는데 이동 기록이 없다. 알람만 보면 계속 "준비 중" 인데,
            // 이틀 전 일정을 열어 놓고 지금 준비하고 있다고 말하는 셈이 된다.
            eventPassed -> PAST
            !prepApplies -> IN_TRANSIT
            else -> PREPARING
        }
    }
}
