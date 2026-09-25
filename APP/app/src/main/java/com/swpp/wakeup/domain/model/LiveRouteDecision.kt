package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint

/**
 * 이동 중에 "여기서부터 더 빠른 길" 을 언제 다시 받을지.
 *
 * ## 왜 판단을 따로 떼어 두는가
 *
 * 사용자가 이동 중인 동안에는 보행·정차 여부와 관계없이 1분마다 현재 위치를
 * 기준으로 경로를 다시 확인한다. 빠른 위치 콜백마다 선을 바꾸면 지도가 어지럽기
 * 때문에 [PERIOD_MILLIS]가 하한을 고정한다.
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

    /** 화면 복귀 때 사용할 수 있는 마지막 성공 경로의 최대 보관 시간. */
    const val MAX_USABLE_AGE_MILLIS = 5 * 60_000L

    /**
     * 지금 받아야 하는가.
     *
     * @param lastAtMillis 마지막으로 요청을 시작한 단조 시계 시각. 한 번도 없으면 null
     */
    fun shouldFetch(
        lastAtMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        // 처음은 무조건 받는다. 화면을 열자마자 보여 줄 것이 있어야 한다.
        if (lastAtMillis == null) return true

        // elapsedRealtime 은 보통 뒤로 가지 않지만, 재부팅 등으로 기준이 바뀌면
        // 간격을 신뢰할 수 없으므로 즉시 받고 기준을 다시 잡는다.
        if (nowMillis < lastAtMillis) return true

        return nowMillis - lastAtMillis >= PERIOD_MILLIS
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
