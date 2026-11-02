package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ObservationsApi
import com.swpp.wakeup.data.remote.TripObservationDto
import com.swpp.wakeup.data.remote.TripObservationInput
import com.swpp.wakeup.domain.model.ArrivalLedger
import java.io.IOException
import java.time.OffsetDateTime

// Partly AI-generated (Claude)
/**
 * 서버가 아는 도착(`GET /api/observations?event=&kind=arrive`). todo §2 "앱 재시작 후 도착 판정 복원".
 *
 * 이 기기의 사본([com.swpp.wakeup.data.local.ArrivalStore])이 없을 때만 묻는다 — 재설치했거나
 * 다른 기기에서 도착했을 때다. 확인하지 못하면 조용히 null 이다. 도착을 모르는 것은 지금과 같고,
 * 화면을 막을 이유가 없다.
 */
class ArrivalRepository(
    private val api: ObservationsApi = ApiClient.observations,
) {

    /** [eventId] 의 도착 시각(epoch ms). 없거나 확인하지 못하면 null */
    suspend fun serverArrival(eventId: Long): Long? = try {
        val response = api.list(eventId = eventId, kind = TripObservationInput.KIND_ARRIVE, limit = LIMIT)
        if (!response.isSuccessful) {
            Log.w(TAG, "도착 확인 실패: HTTP ${response.code()}")
            null
        } else {
            ArrivalLedger.representative(response.body()?.results.orEmpty().mapNotNull { it.toObserved(eventId) })
        }
    } catch (e: IOException) {
        Log.w(TAG, "도착 확인 실패: 네트워크", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "도착 확인 실패", e)
        null
    }

    private companion object {
        const val TAG = "ArrivalRepository"

        /** 한 일정의 도착 관측은 많아야 몇 건이다(GPS 하나, "도착했어요" 하나) */
        const val LIMIT = 20
    }
}

/** 이 일정의 도착 관측만 옮긴다. 다른 일정·출발·시각이 깨진 것은 버린다 */
internal fun TripObservationDto.toObserved(eventId: Long): ArrivalLedger.Observed? {
    if (event != eventId || kind != TripObservationInput.KIND_ARRIVE) return null
    val at = runCatching { OffsetDateTime.parse(observedAt).toInstant().toEpochMilli() }.getOrNull()
        ?: return null
    return ArrivalLedger.Observed(atMillis = at, manual = detector == TripObservationInput.DETECTOR_MANUAL)
}
