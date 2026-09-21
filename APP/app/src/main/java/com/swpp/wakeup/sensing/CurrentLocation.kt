package com.swpp.wakeup.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 현재 위치 1회 조회.
 *
 * [TripTrackingService] 는 이동을 따라가려고 위치를 **지속 갱신**한다. 여기서
 * 필요한 건 그게 아니다 — 경로 선택 화면의 출발지 기본값을 채우려고 좌표
 * 하나를 얻고 끝낸다. 지속 갱신을 켜면 화면을 보는 동안 배터리를 쓰고, 끄는
 * 책임까지 화면이 져야 한다.
 *
 * **`lastLocation` 만 믿지 않는다.** 캐시된 마지막 위치는 며칠 전 다른 도시의
 * 좌표일 수 있다. 출발지가 틀리면 알람 시각이 통째로 틀리므로, 캐시가
 * 낡았으면 새로 측정한다.
 */
object CurrentLocation {

    private const val TAG = "CurrentLocation"

    /** 캐시된 위치를 그대로 쓸 수 있는 한계. 이보다 오래되면 새로 측정한다. */
    private const val MAX_CACHE_AGE_MS = 2 * 60 * 1000L

    /** 새로 측정할 때 기다리는 한계. 실내에서 GPS 가 안 잡히는 경우를 끊는다. */
    private const val FRESH_TIMEOUT_MS = 10_000L

    /**
     * 현재 위치. 권한이 없거나 측정에 실패하면 null.
     *
     * 호출 전에 [LocationPermissions.granted] 로 권한을 확인할 것. 권한이
     * 없으면 예외 대신 null 을 돌려주므로, 화면은 "현재 위치를 쓸 수 없음" 만
     * 표시하고 사용자가 직접 검색하게 두면 된다.
     */
    @SuppressLint("MissingPermission")
    suspend fun get(context: Context): Location? {
        if (!LocationPermissions.granted(context)) {
            Log.i(TAG, "위치 권한이 없다. 출발지 기본값을 채우지 않는다.")
            return null
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)

        // 1) 충분히 최근이면 캐시를 쓴다. 측정 없이 즉시 끝난다.
        runCatching { await(fused.lastLocation) }
            .getOrNull()
            ?.let { cached ->
                val age = System.currentTimeMillis() - cached.time
                if (age in 0..MAX_CACHE_AGE_MS) {
                    Log.i(TAG, "캐시 위치 사용 age=${age}ms")
                    return cached
                }
                Log.i(TAG, "캐시 위치가 낡았다 age=${age}ms. 새로 측정한다.")
            }

        // 2) 새로 측정한다. 출발지는 건물 단위면 충분하므로 고정밀을 쓰지 않는다 —
        //    HIGH_ACCURACY 는 실내에서 오래 걸리고 배터리를 더 쓴다.
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(MAX_CACHE_AGE_MS)
            .setDurationMillis(FRESH_TIMEOUT_MS)
            .build()

        return runCatching { await(fused.getCurrentLocation(request, null)) }
            .onFailure { Log.w(TAG, "현재 위치 측정 실패", it) }
            .getOrNull()
    }

    /**
     * Play Services [com.google.android.gms.tasks.Task] 를 코루틴으로 잇는다.
     *
     * `kotlinx-coroutines-play-services` 의 `await()` 를 쓰지 않으려고 직접
     * 구현한다. 의존성 하나를 이것 때문에 추가할 이유가 없다.
     */
    private suspend fun <T> await(
        task: com.google.android.gms.tasks.Task<T>,
    ): T? = suspendCancellableCoroutine { cont ->
        task.addOnCompleteListener { done ->
            if (cont.isActive) {
                cont.resume(if (done.isSuccessful) done.result else null)
            }
        }
    }
}
