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

    /**
     * 목적지 반경 안에 들어와 충분히 머물렀다.
     *
     * [dwellMillis] 는 반경에 처음 들어온 fix 부터 판정 fix 까지의 시간이다.
     * **판정 근거의 세기를 이 값이 말한다.** 기준(2분)을 넘겨 판정한 것과
     * 추적 마감에 밀려 도중에 확정한 것이 같은 행으로 저장되면 나중에
     * 구분할 수 없다.
     */
    data class Arrived(
        override val fix: LocationFix,
        val distanceM: Double,
        val dwellMillis: Long,
    ) : TripEvent
}

/**
 * 출발·도착 판별기.
 *
 * **규칙**
 * - 출발 — 집에서 [departureRadiusM] 이상 멀어지면 나간 것으로 본다.
 * - 도착 — 목적지 [arrivalRadiusM] 안에 들어와 [dwellMillis] 이상 머무르면
 *   닿은 것으로 본다.
 *
 * 그런데 이 두 줄만으로는 동작하지 않는다. 실제 GPS 에는 네 가지 함정이 있다.
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
 * **4. 목적지를 지나가는 것과 도착하는 것.** 반경에 들어왔다는 사실만으로는
 * 둘을 구분할 수 없다. 목적지 앞을 지나는 버스는 50m 안을 통과하고, 10초
 * 주기로 받으면 그 사이에 fix 가 두세 개 들어온다 — [requiredStreak] 만으로는
 * 막히지 않는다. 그래서 반경 안에서 [dwellMillis] 이상 **머물러야** 도착으로
 * 본다. 지나가는 차는 머무르지 않는다.
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
    private val dwellMillis: Long = DEFAULT_DWELL_MILLIS,
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

    /**
     * 목적지 반경 안에서 머문 시간(ms). 반경 밖이면 null.
     *
     * 알림에 "도착 확인 중" 진행 상황을 쓸 때 읽는다. 사용자가 목적지에 서
     * 있는 동안 아무 변화도 보이지 않으면 앱이 멈춘 것처럼 보인다.
     */
    val dwellElapsedMillis: Long?
        get() {
            val entered = arrivalEnteredAtMillis ?: return null
            val fix = lastAcceptedFix ?: return null
            return (fix.atMillis - entered).coerceAtLeast(0)
        }

    /** 반경 안에 있고 체류 시간만 더 채우면 도착인 상태. */
    val awaitingDwell: Boolean
        get() = phase == Phase.IN_TRANSIT &&
            arrivalEnteredAtMillis != null &&
            arrivalStreak >= requiredStreak

    private var sawFirstFix = false
    private var departureStreak = 0
    private var arrivalStreak = 0

    /** 목적지 반경에 들어온 첫 fix 의 시각. 반경을 벗어나면 버린다. */
    private var arrivalEnteredAtMillis: Long? = null

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
            // 반경을 벗어나면 연속 기록과 체류 시작 시각을 **함께** 버린다.
            // 하나만 지우면 잠깐 나갔다 들어온 사람이 즉시 도착이 된다.
            arrivalStreak = 0
            arrivalEnteredAtMillis = null
            return null
        }

        val enteredAt = markDwellStart(fix)
        arrivalStreak++

        // 게이트 둘을 모두 넘어야 도착이다. 연속 기록은 튀는 좌표 한 점을,
        // 체류 시간은 목적지를 지나쳐 가는 경우를 막는다. 둘은 다른 것을
        // 막으므로 체류를 넣었다고 연속 기록을 뺄 수 없다.
        if (arrivalStreak < requiredStreak) return null

        val dwelled = fix.atMillis - enteredAt
        if (dwelled < dwellMillis) return null

        phase = Phase.ARRIVED
        return TripEvent.Arrived(fix, distance, dwellMillis = dwelled)
    }

    /**
     * 체류 시작 시각을 정하고 돌려준다.
     *
     * fix 의 시각이 거꾸로 가는 일이 있다 — 시계 보정이나 캐시된 좌표다.
     * 그때는 창을 다시 시작한다. 음수 체류를 0 으로 깎으면 기준 시각이
     * 미래에 남아 창이 영영 닫히지 않는다.
     */
    private fun markDwellStart(fix: LocationFix): Long {
        val entered = arrivalEnteredAtMillis
        if (entered == null || fix.atMillis < entered) {
            arrivalEnteredAtMillis = fix.atMillis
            return fix.atMillis
        }
        return entered
    }

    /**
     * 추적을 끝내야 하는데 체류 시간이 덜 찼을 때의 마지막 판정.
     *
     * **왜 필요한가.** 추적에는 마감 시각이 있다(일정 시작 후 한 시간).
     * 마감 직전에 목적지에 들어오면 2분을 채울 시간이 없다. 그때 아무것도
     * 기록하지 않으면 **실제로 관측한 도착이 사라진다** — 체류 조건을 넣기
     * 전에는 기록됐던 도착이다. 반경 진입은 이미 [requiredStreak] 회
     * 확인했으므로 근거가 없는 것이 아니다.
     *
     * 짧은 체류로 확정한 것은 [TripEvent.Arrived.dwellMillis] 가 말해 준다.
     * 나중에 "2분을 채운 관측만" 골라 쓸 수 있다.
     *
     * 사용자가 알림에서 직접 중지한 경우에는 부르지 않는다. 그건 명시적인
     * 중단이고, 기록을 남기지 말라는 뜻으로 읽는 것이 맞다.
     */
    fun finalizeArrival(): TripEvent.Arrived? {
        if (!awaitingDwell) return null
        val dest = destination ?: return null
        val fix = lastAcceptedFix ?: return null
        val entered = arrivalEnteredAtMillis ?: return null

        phase = Phase.ARRIVED
        return TripEvent.Arrived(
            fix = fix,
            distanceM = distanceMeters(fix.point, dest),
            dwellMillis = (fix.atMillis - entered).coerceAtLeast(0),
        )
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

        /**
         * 도착으로 인정할 최소 체류 시간.
         *
         * 2분으로 잡은 이유 — 목적지 앞을 지나는 버스나 차는 50m 반경을
         * 10~20초에 통과한다. 반대로 정말 도착한 사람은 건물로 들어가 계속
         * 그 안에 있다. 2분은 이 둘이 확실히 갈리는 가장 짧은 시간이다.
         *
         * 더 길게 잡을 수 없는 이유는 추적 마감(일정 시작 후 한 시간)이다.
         * 체류 기준이 길수록 마감에 걸려 [finalizeArrival] 로 떨어지는 관측이
         * 늘어난다.
         *
         * 위치 갱신 주기가 이동 중 10초이므로 2분이면 fix 12개가 들어온다.
         * 그중 흐린 것이 섞여도 판정에는 여유가 있다.
         */
        const val DEFAULT_DWELL_MILLIS = 120_000L

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
