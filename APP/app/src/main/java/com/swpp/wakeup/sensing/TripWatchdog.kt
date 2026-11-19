// Partly AI-generated (Claude)
package com.swpp.wakeup.sensing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swpp.wakeup.data.local.ActiveTripStore
import com.swpp.wakeup.data.local.ArrivalStore
import com.swpp.wakeup.domain.model.TripResume

/**
 * 추적이 죽어도 **앱을 열지 않고** 다시 살린다(11-17 사용자: "꺼져도 다시 이어야지").
 *
 * 추적이 도는 동안 [INTERVAL_MILLIS] 마다 정확한 알람으로 깨어나, 추적 중이어야 할 일정([ActiveTripStore])이 있는데
 * 이 프로세스에서 추적이 돌고 있지 않으면 다시 띄운다. 이어 갈 판단은 [TripResume.toResume] 하나다 — 이미 돌고
 * 있거나 · 도착했거나 · 마감이 지났으면 아무것도 하지 않는다(중복 실행 · 중복 기록 없음).
 *
 * 왜 WorkManager 가 아니라 정확한 알람인가: Android 12+ 는 백그라운드에서 포그라운드 서비스 시작을 막는데, 이 앱의
 * 정확한 알람(USE_EXACT_ALARM)이 울린 순간은 예외로 허용된다. WorkManager 작업은 예외가 아니다.
 *
 * **못 하는 것.** 기기 설정의 "강제 중지"(또는 PC 의 실행 · adb force-stop)는 이 앱의 알람과 서비스를 모두 지우고,
 * 사용자가 앱을 다시 열 때까지 어떤 수신기도 깨우지 않는다. 그때는 앱을 여는 순간 이어진다(HomeViewModel).
 * 위치 권한이 "앱 사용 중에만" 이면 여기서 다시 띄운 추적은 위치를 받지 못한다 — "항상 허용" 이 필요하다.
 */
object TripWatchdog {

    private const val TAG = "TripWatchdog"

    /** 점검 간격. `setExactAndAllowWhileIdle` 은 Doze 에서 9분에 한 번까지라 그보다 길게 둔다 */
    const val INTERVAL_MILLIS = 10 * 60_000L

    private const val REQUEST_CODE = 7_201

    /** 다음 점검을 건다. 추적 마감([deadlineMillis]) 뒤로는 걸지 않는다 */
    fun schedule(context: Context, deadlineMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        val at = nowMillis + INTERVAL_MILLIS
        if (at >= deadlineMillis) {
            cancel(context)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }.onFailure { Log.w(TAG, "추적 점검을 걸지 못했다", it) }
    }

    /** 추적이 정상으로 끝났다 */
    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    /**
     * 끊긴 추적을 지금 잇는다. 점검 알람 · 재부팅 · 앱 업데이트에서 부른다. 이었거나 아직 이어 갈 것이 남았으면
     * 다음 점검을 건다. 이은 일정 id 를 돌려준다(없으면 null).
     */
    fun resumeIfCut(context: Context, reason: String, foreground: Boolean = false): Long? {
        val active = runCatching { ActiveTripStore(context).current() }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val arrived = runCatching { ArrivalStore(context).all(now).keys }.getOrDefault(emptySet()) +
            TripLiveState.arrivals.value.keys
        val toResume = TripResume.toResume(
            active = active,
            trackingEventId = TripLiveState.trackingEventId.value,
            arrived = arrived,
            nowMillis = now,
        )
        // 마감이 지났거나 도착했으면 사본을 치운다 — 더 볼 것이 없다.
        if (now >= active.trackingDeadlineMillis || active.eventId in arrived) {
            ActiveTripStore(context).clear(active.eventId)
            cancel(context)
            return null
        }
        schedule(context, active.trackingDeadlineMillis, now)
        if (toResume == null) return null
        if (!LocationPermissions.granted(context)) {
            Log.w(TAG, "$reason: 위치 권한이 없어 추적을 잇지 못한다 (일정 ${toResume.eventId})")
            return null
        }
        // 백그라운드(점검 알람 · 재부팅)에서는 위치 "항상 허용" 이 있어야 시스템이 위치 추적 서비스를 허락한다.
        // 없으면 띄우지 않는다 — 띄워도 거절된다. 사본은 남겨 앱을 열 때 잇는다.
        if (!foreground && !LocationPermissions.backgroundGranted(context)) {
            Log.i(TAG, "$reason: 위치가 '앱 사용 중에만' 이라 앱을 열 때 잇는다 (일정 ${toResume.eventId})")
            return null
        }
        Log.i(TAG, "$reason: 끊긴 추적을 잇는다 (일정 ${toResume.eventId})")
        return runCatching {
            TripTrackingService.resume(context, toResume)
            toResume.eventId
        }.onFailure { Log.w(TAG, "$reason: 추적을 다시 띄우지 못했다", it) }.getOrNull()
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, TripWatchdogReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** [TripWatchdog] 의 점검 알람이 깨우는 곳 */
class TripWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TripWatchdog.resumeIfCut(context.applicationContext, "점검 알람")
    }
}
