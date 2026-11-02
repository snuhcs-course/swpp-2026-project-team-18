package com.swpp.wakeup.sensing

import com.swpp.wakeup.domain.model.AlarmSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Partly AI-generated (Claude)
/**
 * HE(human evaluation) 시연용 가짜 이동. **main 에 넣지 않는다.**
 *
 * 실제로 걷지 않아도 출발 판정 → 이동 → 도착 판정이 **진짜 판별기**([TripGeofence])와
 * 추적 서비스를 그대로 지나가게 한다. 가짜인 것은 위치 출처뿐이다.
 *
 * **장소를 고정하지 않는다.** 일정의 출발지(집)·목적지·경로선(`routePath`)을 이어 그
 * 선 위를 같은 간격으로 걷는다. 경로선이 없으면 직선이다.
 *
 * **시각은 두 개다.**
 * - 화면·판별기에는 실제 시각을 준다. 지도·"N초 전 갱신" 은 미래 시각의 위치를
 *   버린다(`now - atMillis in 0..MAX`).
 * - 도착 결과에는 [DemoClock] 으로 늘린 시각을 쓴다. 30초 만에 도착해도 결과가 "계획한
 *   이동 시간만큼 걸려 도착" 으로 나와야 "버퍼만큼 일찍" 같은 실제와 같은 문구가 된다.
 *
 * 서버에는 아무것도 올리지 않는다([DemoSession]).
 */
object DemoTrip {

    /** 위치 한 점 사이의 실제 간격. 30점이면 30초 남짓 걸린다 */
    const val STEP_MILLIS = 1_000L

    /** 경로 위 점 개수(출발지·목적지 포함) */
    const val SAMPLE_COUNT = 30

    /** 출발 전에 집에서 보내는 점 개수. 판별기가 "집" 을 먼저 확인하게 한다 */
    const val HOME_FIXES = 2

    /** 가짜 위치의 오차(m). 판정에 쓰이도록 [TripGeofence.MAX_ACCURACY_M] 보다 작다 */
    const val ACCURACY_M = 8f

    /** 계획이 이동 시간을 말하지 않을 때 쓰는 시연 이동 시간 */
    const val FALLBACK_TRAVEL_MILLIS = 20 * 60_000L

    /** 시간 빨리감기를 누른 뒤 알람이 울리기까지 */
    const val FAST_FORWARD_LEAD_MILLIS = 10_000L

    private val ALARM_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val EVENT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 알람이 지금부터 [leadMillis] 뒤에 울리게 하는 차이. 음수면 앞당김이다 */
    fun fastForwardDelta(
        alarmAtMillis: Long,
        nowMillis: Long,
        leadMillis: Long = FAST_FORWARD_LEAD_MILLIS,
    ): Long = nowMillis + leadMillis - alarmAtMillis

    /**
     * 일정의 시각을 모두 [deltaMillis] 만큼 옮긴다. 준비·이동·버퍼 간격은 그대로다.
     *
     * 알람 화면이 라벨을 그대로 그리므로 라벨도 새 시각으로 고친다. 형식은
     * `EventRepository.toSchedule` 과 같다.
     */
    fun shift(
        schedule: AlarmSchedule,
        deltaMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AlarmSchedule {
        if (deltaMillis == 0L) return schedule
        fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)

        val alarm = local(schedule.alarmAtMillis + deltaMillis)
        val arrive = schedule.arriveAtMillis?.plus(deltaMillis)
        val start = schedule.startAtMillis + deltaMillis
        // eventLine 은 "09:00 제목" 이다.
        val title = schedule.eventLine.substringAfter(' ', schedule.eventLine)
        return schedule.copy(
            alarmAtMillis = alarm.toInstant().toEpochMilli(),
            departByMillis = schedule.departByMillis?.plus(deltaMillis),
            arriveAtMillis = arrive,
            startAtMillis = start,
            alarmLabel = alarm.format(ALARM_LABEL_FORMAT),
            meridiem = if (alarm.hour < 12) "AM" else "PM",
            eventLine = "${local(start).format(EVENT_TIME_FORMAT)} $title",
            arrivalLine = arrive?.let { "${local(it).format(ALARM_LABEL_FORMAT)} 도착 예정" },
        )
    }

    /** 출발지 → 경로선 → 목적지. 연속 중복 점은 뺀다 */
    fun polyline(home: GeoPoint?, destination: GeoPoint?, path: List<GeoPoint>): List<GeoPoint> {
        val points = buildList {
            home?.let(::add)
            addAll(path)
            destination?.let(::add)
        }
        return points.fold(mutableListOf()) { acc, p ->
            if (acc.lastOrNull() != p) acc.add(p)
            acc
        }
    }

    /** 선 위를 거리로 같은 간격 [count] 점. 첫 점과 끝 점은 선의 양 끝이다 */
    fun sample(line: List<GeoPoint>, count: Int = SAMPLE_COUNT): List<GeoPoint> {
        if (line.size < 2 || count < 2) return line
        val legs = line.zipWithNext { a, b -> TripGeofence.distanceMeters(a, b) }
        val total = legs.sum()
        if (total <= 0.0) return listOf(line.first(), line.last())

        return (0 until count).map { i ->
            val target = total * i / (count - 1)
            var walked = 0.0
            var index = 0
            while (index < legs.lastIndex && walked + legs[index] < target) {
                walked += legs[index]
                index++
            }
            val leg = legs[index]
            val t = if (leg <= 0.0) 0.0 else ((target - walked) / leg).coerceIn(0.0, 1.0)
            val a = line[index]
            val b = line[index + 1]
            GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        }
    }

    /** 보낼 위치 순서. 집에서 [HOME_FIXES] 번 머문 뒤 경로를 따라간다 */
    fun points(home: GeoPoint?, destination: GeoPoint?, path: List<GeoPoint>): List<GeoPoint> {
        val walk = sample(polyline(home, destination, path))
        val stay = home?.let { h -> List(HOME_FIXES) { h } }.orEmpty()
        return stay + walk.drop(if (home != null) 1 else 0)
    }

    /**
     * 시연 시계.
     *
     * 실제 [realStartMillis] 를 [virtualStartMillis] 로 두고, 그 뒤의 실제 경과를
     * [scale] 배로 늘린다.
     */
    data class DemoClock(
        val realStartMillis: Long,
        val virtualStartMillis: Long,
        val scale: Double,
    ) {
        fun map(realMillis: Long): Long =
            virtualStartMillis + ((realMillis - realStartMillis).coerceAtLeast(0) * scale).toLong()

        companion object {
            /**
             * 계획대로 걸린 것처럼 보이게 맞춘다.
             *
             * 출발은 계획 출발 시각(아직 안 됐으면) 또는 지금이다. 늦게 누르면 늦게 나간
             * 것으로 기록된다 — 실제와 같다. 집에 머무는 점이 끝난 뒤부터 경로를 다 걷는
             * 데 걸리는 실제 시간이 계획 이동 시간으로 늘어난다.
             */
            fun planned(
                nowMillis: Long,
                departByMillis: Long?,
                arriveAtMillis: Long?,
                walkSteps: Int,
                homeFixes: Int = HOME_FIXES,
                stepMillis: Long = STEP_MILLIS,
            ): DemoClock {
                val travel = if (departByMillis != null && arriveAtMillis != null &&
                    arriveAtMillis > departByMillis
                ) arriveAtMillis - departByMillis else FALLBACK_TRAVEL_MILLIS
                val departReal = nowMillis + homeFixes * stepMillis
                val departVirtual = maxOf(departReal, departByMillis ?: departReal)
                val walkReal = (walkSteps.coerceAtLeast(1) * stepMillis).toDouble()
                val scale = travel / walkReal
                return DemoClock(
                    realStartMillis = departReal,
                    virtualStartMillis = departVirtual,
                    scale = scale,
                )
            }
        }
    }
}
