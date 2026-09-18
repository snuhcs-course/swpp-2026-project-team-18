package com.swpp.wakeup.sensing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 위치 한 점. */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * 위치 fix 하나.
 *
 * `android.location.Location` 을 쓰지 않는다. 판별 로직을 단위 테스트로
 * 검증해야 하고, `Location` 은 실기기나 Robolectric 없이 만들 수 없다.
 * 이 프로젝트에서 알람과 위치는 손으로 확인하기 가장 어려운 부분이라
 * 로직만이라도 테스트 가능해야 한다.
 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    /** 오차 반경(m). 이 값이 크면 판정 근거가 되지 못한다. */
    val accuracyM: Float,
    val atMillis: Long,
) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
}

/** 판별 결과. */
sealed interface TripEvent {
    val fix: LocationFix

    /** 기준점(집)에서 충분히 멀어졌다. */
    data class Departed(override val fix: LocationFix, val distanceM: Double) : TripEvent

    /** 목적지 반경에 들어왔다. */
    data class Arrived(override val fix: LocationFix, val distanceM: Double) : TripEvent
}

/**
 * 출발·도착 판별기.
 *
 * **규칙**
 * - 출발 — 집에서 [departureRadiusM] 이상 멀어지면 나간 것으로 본다.
 * - 도착 — 목적지 [arrivalRadiusM] 안에 들어오면 닿은 것으로 본다.
 *
 * 그런데 이 두 줄만으로는 동작하지 않는다. 실제 GPS 에는 세 가지 함정이 있다.
 *
 * **1. 흐린 fix.** 실내에서는 오차가 수백 m 까지 벌어진다. 오차 200m 인
 * 좌표로 "150m 벗어남" 을 말할 수 없다. [maxAccuracyM] 보다 흐린 fix 는
 * 판정에 쓰지 않는다. 버리는 것이지 반대로 세는 것이 아니다 — 흐린 fix 하나가
 * 쌓아 둔 연속 기록을 지워서는 안 된다.
 *
 * **2. 튀는 좌표.** 정확도가 좋다고 보고하면서도 한 점만 엉뚱하게 튀는 일이
 * 있다. 그 한 점으로 출발을 선언하면 집에 있는 사람이 나간 것으로 기록된다.
 * 그래서 [requiredStreak] 회 **연속**으로 조건을 만족해야 판정한다.
 *
 * **3. 이미 집이 아닌 곳에서 시작.** 외박했거나 알람을 늦게 해제한 경우다.
 * 첫 fix 부터 반경 밖이면 **언제 나갔는지 알 수 없다.** 이때 출발 시각을
 * 지어내지 않는다([departureMissed] 로 알린다). 없는 관측을 만들면 그게
 * 그대로 학습 데이터가 된다.
 *
 * 상태를 들고 있으므로 이동 한 건당 인스턴스 하나를 쓴다.
 */
class TripGeofence(
    private val home: GeoPoint?,
    private val destination: GeoPoint?,
    private val departureRadiusM: Double = DEFAULT_DEPARTURE_RADIUS_M,
    private val arrivalRadiusM: Double = DEFAULT_ARRIVAL_RADIUS_M,
    private val maxAccuracyM: Float = MAX_ACCURACY_M,
    private val requiredStreak: Int = DEFAULT_REQUIRED_STREAK,
) {

    enum class Phase {
        /** 아직 집에 있다. */
        BEFORE_DEPARTURE,

        /** 나갔다. 목적지 도착을 기다린다. */
        IN_TRANSIT,

        /** 도착했다. 더 볼 것이 없다. */
        ARRIVED,
    }

    var phase: Phase = if (home == null) Phase.IN_TRANSIT else Phase.BEFORE_DEPARTURE
        private set

    /**
     * 출발 시각을 놓쳤는지.
     *
     * 첫 fix 부터 집 반경 밖이었다는 뜻이다. 이 이동에서는 출발 관측을
     * 만들지 않는다.
     */
    var departureMissed: Boolean = false
        private set

    /** 판정에 쓴 마지막 fix. 알림에 남은 거리를 표시할 때 쓴다. */
    var lastAcceptedFix: LocationFix? = null
        private set

    /** 목적지까지 남은 거리(m). 아직 쓸만한 fix 가 없으면 null. */
    val distanceToDestinationM: Double?
        get() {
            val fix = lastAcceptedFix ?: return null
            val dest = destination ?: return null
            return distanceMeters(fix.point, dest)
        }

    private var sawFirstFix = false
    private var departureStreak = 0
    private var arrivalStreak = 0

    /**
     * fix 하나를 넣고 판정을 받는다.
     *
     * 판정이 났으면 그 사건을, 아니면 null 을 돌려준다. 한 fix 가 출발과
     * 도착을 동시에 만들 수는 없다 — 출발을 먼저 처리하고 도착은 다음 fix 에서
     * 본다. 집과 목적지가 겹칠 만큼 가까운 경우는 애초에 판별 대상이 아니다.
     */
    fun offer(fix: LocationFix): TripEvent? {
        // 1. 흐린 fix 는 판정에 쓰지 않는다. 연속 기록은 건드리지 않는다.
        if (fix.accuracyM > maxAccuracyM) return null

        lastAcceptedFix = fix

        return when (phase) {
            Phase.BEFORE_DEPARTURE -> checkDeparture(fix)
            Phase.IN_TRANSIT -> checkArrival(fix)
            Phase.ARRIVED -> null
        }
    }

    private fun checkDeparture(fix: LocationFix): TripEvent? {
        val origin = home ?: run {
            phase = Phase.IN_TRANSIT
            return null
        }

        val distance = distanceMeters(fix.point, origin)

        // 3. 첫 fix 부터 반경 밖이면 출발 시각을 알 수 없다.
        if (!sawFirstFix) {
            sawFirstFix = true
            if (distance > departureRadiusM) {
                departureMissed = true
                phase = Phase.IN_TRANSIT
                // 이 fix 로 바로 도착 판정까지 해 본다. 이미 목적지에 있을 수도 있다.
                return checkArrival(fix)
            }
        }

        if (distance <= departureRadiusM) {
            departureStreak = 0
            return null
        }

        // 2. 연속으로 만족해야 인정한다.
        departureStreak++
        if (departureStreak < requiredStreak) return null

        phase = Phase.IN_TRANSIT
        departureStreak = 0
        return TripEvent.Departed(fix, distance)
    }

    private fun checkArrival(fix: LocationFix): TripEvent? {
        val dest = destination ?: return null

        val distance = distanceMeters(fix.point, dest)
        if (distance > arrivalRadiusM) {
            arrivalStreak = 0
            return null
        }

        arrivalStreak++
        if (arrivalStreak < requiredStreak) return null

        phase = Phase.ARRIVED
        return TripEvent.Arrived(fix, distance)
    }

    companion object {
        /**
         * 출발 판정 반경.
         *
         * 150m 로 잡은 이유 — 건물 안팎의 GPS 산포가 수십 m 이고, 아파트
         * 단지나 대학 캠퍼스는 그 자체로 100m 를 넘는다. 이보다 좁게 잡으면
         * 집에 앉아 있는 사람이 나간 것으로 기록된다.
         */
        const val DEFAULT_DEPARTURE_RADIUS_M = 150.0

        /**
         * 도착 판정 반경.
         *
         * **10m 로 잡을 수 없다.** 도심 GPS 의 실제 오차가 10~30m 라서 10m
         * 게이트는 대부분의 아침에 한 번도 열리지 않는다 — 도착이 영영
         * 기록되지 않는다는 뜻이다. 50m 는 판정이 실제로 일어나는 가장 좁은
         * 반경이다. 대신 판정에 쓴 fix 의 오차를 관측에 함께 저장하므로
         * 나중에 "오차 10m 이하인 관측만" 골라 쓸 수 있다.
         */
        const val DEFAULT_ARRIVAL_RADIUS_M = 50.0

        /**
         * 판정에 쓸 수 있는 최대 오차.
         *
         * 서버 `apps/observations/serializers.MAX_ACCURACY_M` 과 같은 값이어야
         * 한다. 앱이 걸러 보내지만 서버도 막는다.
         */
        const val MAX_ACCURACY_M = 50f

        /** 몇 번 연속으로 만족해야 판정할지. */
        const val DEFAULT_REQUIRED_STREAK = 2

        private const val EARTH_RADIUS_M = 6_371_008.8

        /**
         * 두 점 사이 거리(m). 하버사인.
         *
         * `Location.distanceBetween` 을 쓰지 않는 이유는 이 함수가 단위
         * 테스트에서도 돌아야 하기 때문이다. 수 km 범위에서 오차는 0.5%
         * 미만이고 반경 판정에는 충분하다.
         */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLat = lat2 - lat1
            val dLng = Math.toRadians(b.lng - a.lng)

            val h = sin(dLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h).coerceAtMost(1.0))
        }
    }
}
