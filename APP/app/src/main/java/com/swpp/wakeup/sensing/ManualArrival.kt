package com.swpp.wakeup.sensing

import android.content.Context
import android.util.Log

/**
 * "도착했어요" 를 기록한다(todo §2 "도착 판정 버튼"). 화면과 추적 서비스가 같은 길을 탄다.
 *
 * 순서: 관측을 큐에 적고(연결되면 올라감) → 화면에 도착을 알리고 → 아침 기록을 닫는다.
 * 추적 중이면 서비스가 마지막 위치를 붙여 이것을 부르고, 잠시 더 GPS 도착을 기다린다.
 */
object ManualArrival {

    private const val TAG = "ManualArrival"

    // Partly AI-generated (Claude)
    /**
     * @param fix 추적 중 받은 최근 위치. 없으면 위치 없이 보낸다
     * @param destination 목적지. 거리를 계산할 때만 쓴다
     * @return 기록했으면 true. 이미 도착이 있으면 아무것도 하지 않고 false
     */
    fun record(
        context: Context,
        eventId: Long,
        fix: LocationFix?,
        destination: GeoPoint?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (TripLiveState.arrivalFor(eventId) != null) {
            Log.i(TAG, "이미 도착이 있다. 수동 도착을 무시한다: 일정 $eventId")
            return false
        }
        return runCatching {
            TripObservationQueue(context).enqueue(manualArrivalOf(eventId, nowMillis, fix, destination))
            ArrivalRecords.record(context, eventId, nowMillis)
            MorningClose.close(context, eventId, MorningClose.Reason.ARRIVED, nowMillis)
            Log.i(TAG, "수동 도착 기록: 일정 $eventId (위치 ${if (fix != null) "있음" else "없음"})")
            true
        }.getOrElse {
            Log.e(TAG, "수동 도착 기록 실패: 일정 $eventId", it)
            false
        }
    }
}
