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

    /** 첫 유효 위치에서 도착한다. 기존 관측 형식의 체류 시간은 0이다. */
    data class Arrived(
        override val fix: LocationFix,
        val distanceM: Double,
        val dwellMillis: Long,
        /** 반경 진입 시각(epoch ms). 즉시 판정하므로 [fix]의 시각과 같다. */
        val enteredAtMillis: Long,
    ) : TripEvent
}

/**
 * 출발·도착 판별기.
 *
 * **규칙**
 * - 출발 — 집에서 [departureRadiusM] 이상 멀어지거나, **지속적으로 이동하는
 *   것이 감지되면** 나간 것으로 본다. 둘 중 먼저 오는 것을 쓴다.
 * - 도착 — 이동 중 목적지 [arrivalRadiusM] 안에 들어온 첫 유효 위치에서 확정한다.
 *
 * **반경만으로는 늦다.** 아파트 단지나 캠퍼스는 그 자체가 150m 를 넘는다.
 * 집을 나서서 몇 분을 걸어도 반경 안이라 화면은 계속 "준비 중" 이었다. 그래서
 * 이동 감지를 함께 본다 — [movingWindowMillis] 안에 [movingDistanceM] 이상
 * 나아가면 걷고 있는 것이다.
 *
 * 실제 GPS에서는 다음을 함께 고려한다.
 *
 * **1. 흐린 fix.** 실내에서는 오차가 수백 m 까지 벌어진다. 오차 200m 인
 * 좌표로 "150m 벗어남" 을 말할 수 없다. [maxAccuracyM] 보다 흐린 fix 는
 * 판정에 쓰지 않는다. 버리는 것이지 반대로 세는 것이 아니다 — 흐린 fix 하나가
 * 쌓아 둔 연속 기록을 지워서는 안 된다.
 *
 * **2. 튀는 좌표.** 정확도가 좋다고 보고하면서도 한 점만 엉뚱하게 튀는 일이
 * 있다. 그 한 점으로 출발을 선언하면 집에 있는 사람이 나간 것으로 기록된다.
 * 그래서 출발은 [requiredStreak] 회 **연속**으로 조건을 만족해야 판정한다.
 *
 * **3. 이미 집이 아닌 곳에서 시작.** 외박했거나 알람을 늦게 해제한 경우다.
 * 첫 fix 부터 반경 밖이면 **언제 나갔는지 알 수 없다.** 이때 출발 시각을
 * 지어내지 않는다([departureMissed] 로 알린다). 없는 관측을 만들면 그게
 * 그대로 학습 데이터가 된다.
 *
 * **4. 나간 뒤 흐린 fix 만 온다.** 출발 전에는 저전력(Wi-Fi·기지국) 위치를 받는데,
 * 집을 나서 Wi-Fi 가 없는 곳·지하철로 가면 오차가 수백 m 로 벌어져 1 에서 전부
 * 버려진다. 그러면 판정이 영영 나지 않아 집을 나선 지 한참 뒤에도 "준비 중" 이었다.
 * 흐린 fix 라도 **오차를 다 빼고도** 집에서 [coarseDepartureMarginM] 넘게 떨어져
 * 있으면 나간 근거로 센다([requiredStreak] 회 연속). 근거가 채워진 시각은 나간
 * 시각이 아니므로 출발 관측을 만들지 않는다([departureMissed]).
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
    private val movingDistanceM: Double = MOVING_DISTANCE_M,
    private val movingWindowMillis: Long = MOVING_WINDOW_MILLIS,
    private val movingResetM: Double = MOVING_RESET_M,
    private val coarseDepartureMarginM: Double = COARSE_DEPARTURE_MARGIN_M,
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
     * 첫 fix 부터 집 반경 밖이었거나(규칙 3), 흐린 fix 로만 나간 것을 알았다는
     * 뜻이다(규칙 4). 이 이동에서는 출발 관측을 만들지 않는다.
     */
    var departureMissed: Boolean = false
        private set

    /**
     * 판정에 쓴 마지막 fix. 알림에 남은 거리를 표시할 때 쓴다.
     *
     * 흐린 fix 로 나간 것을 알았으면(규칙 4) 비운다. 그 전의 정확한 fix 는 집이라
     * 지금 위치를 말하지 않는다.
     */
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
     * 출발 전이라는 판정을 지금도 믿을 수 있는가.
     *
     * 판정에 쓴 마지막 fix 가 [EVIDENCE_MAX_AGE_MS] 안이어야 한다. 흐린 fix 만 오거나
     * 아무것도 오지 않으면 [phase] 는 마지막으로 확인한 "집" 에 머문다. 그 상태로
     * "준비 중" 을 말하면 이미 나간 사람에게 틀린 말을 계속하게 된다 — 판정과 표시를
     * 나누는 이유다. 단계가 [Phase.BEFORE_DEPARTURE] 가 아니면 false 다.
     *
     * @param nowMillis 지금 시각(epoch ms). fix 시각과 같은 시계여야 한다
     * (Partly AI-generated (Claude))
     */
    fun atHomeConfirmed(nowMillis: Long): Boolean {
        if (phase != Phase.BEFORE_DEPARTURE) return false
        val last = lastAcceptedFix ?: return false
        return nowMillis - last.atMillis <= EVIDENCE_MAX_AGE_MS
    }

    private var sawFirstFix = false
    private var departureStreak = 0

    /** 흐린 fix 가 "집에서 확실히 멀다" 를 연속으로 말한 횟수. 규칙 4 */
    private var coarseDepartureStreak = 0

    /**
     * 이동을 재는 기준 fix. 여기서부터 얼마나 나아갔는지를 본다.
     *
     * 제자리에 있으면 계속 앞으로 밀어 준다([movingResetM] 안에 있으면 재설정).
     * 밀지 않으면 GPS 산포가 몇십 분에 걸쳐 쌓여 앉아 있는 사람이 "이동 중" 이
     * 된다. 반대로 조금이라도 나아갔으면 기준을 그대로 두어야 한다 — 매번
     * 밀면 천천히 걷는 사람은 한 창에서 기준 거리를 못 넘겨 영영 감지되지 않는다.
     */
    private var movementAnchor: LocationFix? = null

    /**
     * fix 하나를 넣고 판정을 받는다.
     *
     * 판정이 났으면 그 사건을, 아니면 null 을 돌려준다. 한 fix 가 출발과
     * 도착을 동시에 만들 수는 없다 — 출발을 먼저 처리하고 도착은 다음 fix 에서
     * 본다. 집과 목적지가 겹칠 만큼 가까운 경우는 애초에 판별 대상이 아니다.
     */
    fun offer(fix: LocationFix): TripEvent? {
        // 1. 흐린 fix 는 판정에 쓰지 않는다. 연속 기록은 건드리지 않는다.
        //    단, 출발 전에는 "확실히 멀다" 는 근거로만 쓸 수 있다(규칙 4).
        if (!isUsable(fix, maxAccuracyM)) {
            if (phase == Phase.BEFORE_DEPARTURE) checkCoarseDeparture(fix)
            return null
        }

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

        if (distance > departureRadiusM) {
            // 2. 연속으로 만족해야 인정한다.
            departureStreak++
            if (departureStreak >= requiredStreak) return depart(fix, distance)
        } else {
            departureStreak = 0
            // 집 반경 안의 정확한 위치는 흐린 fix 가 쌓은 "멀다" 도 뒤집는다.
            coarseDepartureStreak = 0
        }

        // 5. 반경 안이라도 지속적으로 이동하고 있으면 나간 것이다.
        //
        // 아파트 단지·캠퍼스는 그 자체가 150m 를 넘어서, 반경만 보면 집을 나서
        // 몇 분을 걸어도 "준비 중" 으로 남는다. 사용자가 보는 단계가 실제와
        // 어긋나면 진행률과 도착 예정도 함께 어긋난다.
        if (detectMovement(fix)) return depart(fix, distance)
        return null
    }

    private fun depart(fix: LocationFix, distance: Double): TripEvent {
        phase = Phase.IN_TRANSIT
        departureStreak = 0
        coarseDepartureStreak = 0
        movementAnchor = null
        return TripEvent.Departed(fix, distance)
    }

    /**
     * 규칙 4. 판정에 못 쓰는 흐린 fix 로 "이미 나갔다" 만 판단한다.
     *
     * 오차 원 전체가 집에서 [coarseDepartureMarginM] 넘게 떨어져 있어야 근거다.
     * 150m 반경을 기준으로 삼지 않는 이유 — 기지국 위치는 한쪽으로 치우친 채 같은
     * 값을 되풀이하는 일이 있어서, 연속 기록만으로는 집에 있는 사람을 내보내는 것을
     * 막지 못한다. 1km 를 넘겨 치우치는 일은 드물고, 그만큼 갔으면 이미 이동 중이다.
     *
     * 근거가 못 되는 흐린 fix 는 연속 기록을 지우지 않는다(규칙 1 과 같다). 지우는
     * 것은 집 반경 안의 정확한 fix 뿐이다. 오차를 모르는 fix(음수·무한·NaN)는 거리를
     * 말할 수 없으므로 세지 않는다.
     *
     * 출발 관측은 만들지 않는다. 언제 나갔는지는 이 fix 가 말해 주지 않는다.
     * (Partly AI-generated (Claude))
     */
    private fun checkCoarseDeparture(fix: LocationFix) {
        val origin = home ?: return
        if (!fix.accuracyM.isFinite() || fix.accuracyM < 0f) return

        val worstCase = distanceMeters(fix.point, origin) - fix.accuracyM
        if (worstCase <= coarseDepartureMarginM) return

        coarseDepartureStreak++
        if (coarseDepartureStreak < requiredStreak) return

        departureMissed = true
        phase = Phase.IN_TRANSIT
        departureStreak = 0
        coarseDepartureStreak = 0
        movementAnchor = null
        // 마지막 정확한 위치는 집이다. 남겨 두면 알림이 집에서 잰 거리를 지금 남은
        // 거리처럼 보인다. 이동 중의 정확한 위치를 받을 때까지 비워 둔다.
        lastAcceptedFix = null
    }

    /**
     * 지속적으로 이동하고 있는가.
     *
     * 두 조건을 **함께** 요구한다.
     *
     * - 기준 fix 에서 [movingDistanceM] 이상 나아갔다
     * - 그 이동이 [movingWindowMillis] 이상에 걸쳐 있다
     *
     * **단발 변위로 판정하지 않는다.** 정확도 게이트가 [maxAccuracyM] 이라 두
     * 좌표의 변위가 오차만으로 그 두 배까지 튈 수 있다. 집에 앉아 있는 사람이
     * 한 번의 튐으로 "이동 중" 이 된다.
     *
     * 기준 fix 는 제자리에 있을 때만 앞으로 민다([movingResetM] 안). 매번 밀면
     * 천천히 걷는 사람이 한 창에서 기준 거리를 못 넘겨 영영 감지되지 않고,
     * 전혀 밀지 않으면 산포가 쌓여 앉아 있는 사람이 감지된다.
     */
    private fun detectMovement(fix: LocationFix): Boolean {
        val anchor = movementAnchor
        // 시각이 거꾸로 가면(시계 보정·캐시 좌표) 창을 다시 시작한다.
        if (anchor == null || fix.atMillis < anchor.atMillis) {
            movementAnchor = fix
            return false
        }

        val travelled = distanceMeters(fix.point, anchor.point)
        if (travelled <= movingResetM) {
            // 기준에서 벗어나지 못했다. 이 정도는 "움직였다" 고 말할 수 없는
            // 도심 GPS 오차 범위다. 기준을 여기로 옮겨 산포가 쌓이지 않게 한다.
            movementAnchor = fix
            return false
        }

        if (fix.atMillis - anchor.atMillis < movingWindowMillis) return false
        return travelled >= movingDistanceM
    }

    private fun checkArrival(fix: LocationFix): TripEvent? {
        val dest = destination ?: return null
        val distance = distanceMeters(fix.point, dest)
        if (distance !in 0.0..arrivalRadiusM) return null

        phase = Phase.ARRIVED
        return TripEvent.Arrived(fix, distance, dwellMillis = 0, enteredAtMillis = fix.atMillis)
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

        /** 첫 유효 위치가 이 반경 안이면 즉시 도착이다(BE-24). */
        const val DEFAULT_ARRIVAL_RADIUS_M = 100.0

        /**
         * 판정에 쓸 수 있는 최대 오차.
         *
         * 서버 `apps/observations/serializers.MAX_ACCURACY_M` 과 같은 값이어야
         * 한다. 앱이 걸러 보내지만 서버도 막는다.
         */
        const val MAX_ACCURACY_M = 50f

        /** 출발을 몇 번 연속으로 만족해야 판정할지. */
        const val DEFAULT_REQUIRED_STREAK = 2

        /**
         * 흐린 fix 로 출발을 말하려면 오차를 빼고도 집에서 이만큼 떨어져야 한다(규칙 4).
         *
         * 기지국 위치의 오차는 수백 m~1km 대이고 한쪽으로 치우친 채 반복되기도 한다.
         * 그 치우침이 오차 반경을 다시 1km 넘게 벗어나는 일은 드물다. 반대로 통학·
         * 통근은 대개 1km 를 넘으므로 지하에 들어가 정확한 위치를 못 받아도 가는 동안
         * 이 선을 넘는다.
         */
        const val COARSE_DEPARTURE_MARGIN_M = 1_000.0

        /**
         * 판정에 쓸 위치가 이만큼 끊기면 "출발 전" 을 더는 믿지 않는다.
         *
         * 출발 전 위치 주기가 30초라 2분이면 네 번을 못 받은 것이다. 서비스는 이때
         * 고정확도로 올리고, 화면과 알림은 "준비 중" 을 단정하지 않는다
         * ([atHomeConfirmed]). (Partly AI-generated (Claude))
         */
        const val EVIDENCE_MAX_AGE_MS = 120_000L

        /** 판정에 쓸 수 있는 fix 인가. 오차가 없거나(NaN·음수) [MAX_ACCURACY_M] 보다 흐리면 아니다 */
        fun isUsable(fix: LocationFix, maxAccuracyM: Float = MAX_ACCURACY_M): Boolean =
            fix.accuracyM in 0f..maxAccuracyM

        /**
         * 이동으로 인정할 최소 누적 거리.
         *
         * 출발 전 위치 주기가 30초(`TripTrackingService.IDLE_INTERVAL_MS`)이고
         * 도보 속도가 약 1.1m/s 다. 60초면 66m 를 걷는다. 80m 는 "실제로 걷기
         * 시작했다" 의 최소선이고, 실내 산포(수십 m)와 갈린다.
         */
        const val MOVING_DISTANCE_M = 80.0

        /**
         * 그 이동이 이 시간 이상에 걸쳐 있어야 한다.
         *
         * 30초 주기에서 60초는 좌표 2~3개다. 이보다 짧게 잡으면 좌표 한 번의
         * 튐이 그대로 판정이 된다 — 정확도 게이트가 50m 이므로 변위가 오차만으로
         * 100m 까지 벌어질 수 있다.
         */
        const val MOVING_WINDOW_MILLIS = 60_000L

        /**
         * 기준 fix 를 다시 잡는 거리.
         *
         * 이 안에 있으면 "움직였다" 고 말할 수 없다 — 도심 GPS 오차가 10~30m 다.
         * 기준을 앞으로 밀어 산포가 몇십 분에 걸쳐 쌓이는 것을 막는다.
         */
        const val MOVING_RESET_M = 30.0

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
