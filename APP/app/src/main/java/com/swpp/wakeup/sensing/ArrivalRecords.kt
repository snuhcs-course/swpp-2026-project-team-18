package com.swpp.wakeup.sensing

import android.content.Context
import android.util.Log
import com.swpp.wakeup.data.local.ArrivalStore

// Partly AI-generated (Claude)
/**
 * 도착을 메모리([TripLiveState])와 디스크([ArrivalStore])에 함께 적는 한 곳. todo §2 "앱 재시작 후
 * 도착 판정 복원".
 *
 * 추적 서비스(GPS 도착)·"도착했어요"·서버 확인이 모두 여기를 지난다. 메모리에만 적으면 앱
 * 프로세스가 다시 시작될 때 도착이 사라지고, 홈에 이미 도착한 일정이 다시 나타난다.
 */
object ArrivalRecords {

    private const val TAG = "ArrivalRecords"

    /** 도착을 적는다. 디스크 쓰기가 실패해도 화면에는 알린다 */
    fun record(context: Context, eventId: Long, atMillis: Long) {
        TripLiveState.recordArrival(eventId, atMillis)
        runCatching { ArrivalStore(context).record(eventId, atMillis) }
            .onFailure { Log.w(TAG, "도착을 디스크에 남기지 못했다: 일정 $eventId", it) }
    }

    /** 서버에서 확인한 도착. 이 기기가 이미 아는 도착은 덮지 않는다 */
    fun restoreFromServer(context: Context, eventId: Long, atMillis: Long) {
        if (TripLiveState.arrivalFor(eventId) != null) return
        TripLiveState.restoreArrivals(mapOf(eventId to atMillis))
        runCatching { ArrivalStore(context).record(eventId, atMillis) }
            .onFailure { Log.w(TAG, "서버 도착을 디스크에 남기지 못했다: 일정 $eventId", it) }
    }

    /** 디스크 사본을 메모리로 되살린다. 화면·서비스가 시작할 때 부른다 */
    fun restore(context: Context) {
        runCatching { TripLiveState.restoreArrivals(ArrivalStore(context).all()) }
            .onFailure { Log.w(TAG, "도착 사본을 읽지 못했다", it) }
    }

    /** 한 일정의 도착을 잊는다. 같은 일정을 새로 추적하기 시작할 때 */
    fun forget(context: Context, eventId: Long) {
        TripLiveState.forgetArrival(eventId)
        runCatching { ArrivalStore(context).remove(eventId) }
            .onFailure { Log.w(TAG, "도착 사본을 지우지 못했다: 일정 $eventId", it) }
    }
}
