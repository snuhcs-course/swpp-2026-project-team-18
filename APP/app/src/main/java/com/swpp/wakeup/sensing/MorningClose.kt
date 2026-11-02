package com.swpp.wakeup.sensing

import android.content.Context
import android.util.Log
import com.swpp.wakeup.data.local.MorningSessionStore
import com.swpp.wakeup.sensing.TripGeofence.Phase

/**
 * 집을 나서거나 도착하면 그 일정의 아침 기록을 닫는다(todo P0 "기록 끝내기 화면 조건").
 *
 * 예전에는 세션이 6시간 남아 있어서, 나간 뒤에 "마침" 을 누르면 이동 시간까지 준비
 * 소요로 올라갔다. 판정은 [TripTrackingService] 만 할 수 있고 화면 없이 돌 수 있으므로
 * 서비스가 여기를 부른다. 수동 도착도 같은 입구를 쓴다.
 */
object MorningClose {

    private const val TAG = "MorningClose"

    enum class Reason { DEPARTED, ARRIVED }

    // Partly AI-generated (Claude)
    /**
     * 위치 하나를 판정한 앞뒤 단계로 닫을 이유를 정한다. 순수 함수다.
     *
     * - 도착: 이번 위치로 도착이 되었으면 닫는다.
     * - 출발: 출발 전 → 이동 중이 되었고, 이 추적이 출발지에서 사용자를 본 적이 있을 때만.
     *   첫 위치부터 출발지 밖이었던 아침(다른 곳에서 잔 날)은 그때 누른 기록이 진짜
     *   준비 시간이라 닫지 않는다.
     */
    fun reasonFor(phaseBefore: Phase, phaseAfter: Phase, sawOriginFix: Boolean): Reason? = when {
        phaseAfter == Phase.ARRIVED && phaseBefore != Phase.ARRIVED -> Reason.ARRIVED
        phaseBefore == Phase.BEFORE_DEPARTURE && phaseAfter == Phase.IN_TRANSIT && sawOriginFix ->
            Reason.DEPARTED
        else -> null
    }

    // Partly AI-generated (Claude)
    /**
     * [eventId] 의 아침 기록이 저장돼 있으면 닫고 화면에 한 번 알린다.
     *
     * 안 누른 블록은 기록하지 않는다. 이미 누른 블록은 큐에 들어가 있다. 예외를 던지지
     * 않는다 — 저장소가 깨져도 추적은 계속돼야 한다.
     *
     * @return 닫았으면 true
     */
    fun close(context: Context, eventId: Long, reason: Reason, nowMillis: Long): Boolean =
        runCatching {
            MorningSessionStore(context).closeFor(eventId) ?: return@runCatching false
            TripLiveState.recordMorningClosed(eventId, reason, nowMillis)
            Log.i(TAG, "아침 기록을 닫았다($reason): 일정 $eventId")
            true
        }.getOrElse {
            Log.w(TAG, "아침 기록을 닫지 못했다: 일정 $eventId", it)
            false
        }
}
