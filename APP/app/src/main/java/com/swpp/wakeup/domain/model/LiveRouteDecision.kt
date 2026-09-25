package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

/**
 * 이동 중에 "여기서부터 더 빠른 길" 을 언제 다시 받을지.
 *
 * ## 왜 판단을 따로 떼어 두는가
 *
 * 이 결정이 **하루 카카오 쿼터를 정한다.** 1분마다 부르면 40분 통학에 40회이고,
 * 왕복 두 번에 사용자 셋이면 240회다. 무료 한도가 하루 1,000건이므로 조건이
 * 하나 틀리면 오후에 앱이 경로를 못 받는다. 그래서 뷰모델 안에 조건문으로
 * 흩어 두지 않고 순수 함수로 꺼내 시험한다.
 *
 * ## 왜 시간만으로 정하지 않는가
 *
 * 지하철을 기다리거나 신호에 걸려 서 있는 동안에는 경로가 바뀌지 않는다. 시간만
 * 보면 그 몇 분도 그대로 호출이 되어 **아무 값 없이** 쿼터를 태운다. 그래서
 * 시간과 **움직인 거리**를 모두 넘어야 부른다.
 *
 * ## 왜 거리만으로 정하지 않는가
 *
 * 차로 이동하면 몇 초에 수백 미터를 간다. 거리만 보면 초당 한 번씩 선이 다시
 * 그려져서 지도가 어지럽다. [PERIOD_MILLIS] 가 아래쪽을 막는다.
 *
 * ## 위치 스트림이 시계다
 *
 * 따로 타이머를 돌리지 않는다. 추적 서비스가 위치를 내보낼 때마다 이 함수에
 * 물어보면 된다. 서비스가 멈추면 호출도 멈추는 것이 맞다 — 사용자가 어디 있는지
 * 모르는 상태에서 "여기서부터" 를 말할 수 없다.
 */
object LiveRouteDecision {

    /** 다시 받기까지의 최소 간격. */
    const val PERIOD_MILLIS = 60_000L

    /** 정차 중이어도 이 시간이 지나면 교통 상황을 다시 확인한다. */
    const val MAX_USABLE_AGE_MILLIS = 5 * 60_000L

    /**
     * 다시 받기까지 움직여야 하는 최소 거리(m).
     *
     * 도보 속도가 분당 약 70~90m 다. 150m 는 걸어서 2분쯤이므로, 걷는 사람은
     * 2분에 한 번, 지하철·차는 1분에 한 번 받는다. 걷는 동안 경로가 바뀔 일이
     * 거의 없으므로 이 차이는 손실이 아니다.
     *
     * `TripGeofence.MOVING_DISTANCE_M`(80m, 이동 시작 판정)보다 크다. 그쪽은
     * "움직이기 시작했는가" 이고 이쪽은 "경로를 다시 볼 만큼 갔는가" 다.
     */
    const val MIN_MOVE_M = 150.0

    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * 지금 받아야 하는가.
     *
     * @param lastAtMillis 마지막으로 받은 시각. 한 번도 안 받았으면 null
     * @param lastPoint 마지막으로 받을 때의 위치. 한 번도 안 받았으면 null
     * @param hasUsableRoute 현재 위치 기준으로 성공한 경로를 이미 들고 있는가.
     * 첫 조회가 실패했다면 이동량과 무관하게 1분 뒤 재시도한다.
     */
    fun shouldFetch(
        lastAtMillis: Long?,
        lastPoint: GeoPoint?,
        nowMillis: Long,
        here: GeoPoint,
        hasUsableRoute: Boolean = true,
    ): Boolean {
        // 처음은 무조건 받는다. 화면을 열자마자 보여 줄 것이 있어야 한다.
        if (lastAtMillis == null || lastPoint == null) return true

        // 시계가 뒤로 갔다(사용자가 시간을 바꿨거나 기기가 보정했다). 간격을
        // 신뢰할 수 없으므로 한 번 받고 기준을 다시 잡는다.
        if (nowMillis < lastAtMillis) return true

        if (nowMillis - lastAtMillis < PERIOD_MILLIS) return false
        if (!hasUsableRoute) return true
        // 같은 자리에 있어도 배차·정체는 변한다. 이동 거리만 보면 지하철역에서
        // 오래 기다리는 동안 한 번 성공한 경로가 무기한 남는다.
        if (nowMillis - lastAtMillis >= MAX_USABLE_AGE_MILLIS) return true
        return movedMeters(lastPoint, here) >= MIN_MOVE_M
    }

    /** 두 점 사이 거리(m). 서울 규모에서는 평면 근사로 충분하다. */
    fun movedMeters(from: GeoPoint, to: GeoPoint): Double {
        val dLat = abs(to.lat - from.lat) * METERS_PER_DEGREE
        val dLng = abs(to.lng - from.lng) *
            METERS_PER_DEGREE * cos(Math.toRadians((from.lat + to.lat) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }
}

/**
 * 지금 있는 곳부터 가는 더 빠른 길.
 *
 * [AlarmPlanView.altRoutePath] 와 모양이 같지만 **기준점이 다르다.** 그쪽은
 * 출발지부터이고 이쪽은 현재 위치부터다. 이동 중에는 이쪽이 그쪽을 덮는다.
 *
 * 추적 서비스가 마지막 성공 결과 하나만 짧게 디스크에 저장한다. 화면이
 * 백그라운드에서 제거됐다 돌아와도 곧바로 그릴 수 있어야 하기 때문이다.
 * 저장 사본은 계정별로 격리하고 5분 뒤 폐기한다.
 */
data class LiveRoute(
    val routeKey: String,
    /** "9호선 → 2호선", "도보". 없으면 null */
    val label: String?,
    /** 현재 위치부터 목적지까지의 카카오 원 소요시간. */
    val minutes: Int,
    /** 선택 경로보다 빠른 후보를 표시하는가. */
    val isAlternative: Boolean,
    /** [isAlternative] 일 때 선택 경로보다 몇 분 빠른가. */
    val fasterMinutes: Int?,
    val path: List<GeoPoint>,
) {
    /**
     * 지도 아래에 적을 한 줄.
     *
     * **"여기서부터" 를 반드시 붙인다.** 출발지 기준 대안과 같은 자리에 같은
     * 색으로 나오므로, 문구가 기준점을 밝히지 않으면 사용자는 선이 자기 위치에서
     * 시작하는 것을 우연으로 읽는다.
     */
    val summary: String
        get() {
            val name = label?.takeIf { it.isNotBlank() }
            val faster = fasterMinutes?.takeIf { isAlternative && it > 0 }
            return when {
                faster != null && name != null ->
                    "여기서부터 $name · ${faster}분 빠름"
                faster != null -> "여기서부터 ${faster}분 빠른 길"
                name != null -> "여기서부터 $name · ${minutes}분"
                else -> "여기서부터 ${minutes}분"
            }
        }
}
