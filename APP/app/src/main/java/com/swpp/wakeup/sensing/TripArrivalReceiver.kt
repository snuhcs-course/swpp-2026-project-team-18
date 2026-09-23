package com.swpp.wakeup.sensing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.swpp.wakeup.alarm.AlarmNotifications

/**
 * 도착 결과 알림의 "확인" 을 받는다.
 *
 * ## 왜 액티비티가 아니라 리시버인가
 *
 * 확인은 "읽었다" 는 뜻뿐이다. 화면을 띄울 이유가 없고, 강의실 앞에서 앱이
 * 갑자기 열리면 그게 더 방해다. 리시버는 알림만 치우고 끝난다.
 *
 * ## 밀어서 지운 것도 확인으로 본다
 *
 * Android 14+ 는 `setOngoing(true)` 로도 스와이프를 막지 못한다. 앱이 되돌릴
 * 수 없는 정책이다. 그래서 밀어서 지운 경우도 같은 곳으로 보내
 * ([ACTION_DISMISS]) 같게 처리한다.
 *
 * 되살리는 쪽은 택하지 않았다. 지울 수 없는 알림을 만들면 사용자가 채널을
 * 끄고, 그러면 **다음 아침부터 도착 결과를 영영 못 본다.** 한 번 놓치는 것보다
 * 그게 크다.
 */
class TripArrivalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val how = when (intent.action) {
            ACTION_CONFIRM -> "확인"
            ACTION_DISMISS -> "밀어서 지움"
            else -> {
                Log.w(TAG, "모르는 동작: ${intent.action}")
                return
            }
        }

        // 취소는 권한을 요구하지 않는다. 띄우는 것만 POST_NOTIFICATIONS 가 필요하다.
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(AlarmNotifications.NOTIFICATION_ARRIVAL)
        }.onFailure { Log.w(TAG, "도착 알림을 치우지 못했다", it) }

        Log.i(TAG, "도착 결과를 닫았다 ($how)")
    }

    companion object {
        private const val TAG = "TripArrivalReceiver"

        const val ACTION_CONFIRM = "com.swpp.wakeup.action.ARRIVAL_CONFIRM"
        const val ACTION_DISMISS = "com.swpp.wakeup.action.ARRIVAL_DISMISS"

        /**
         * 요청 코드를 동작마다 다르게 준다.
         *
         * 같은 코드로 만들면 두 PendingIntent 가 같은 것으로 취급되어
         * `FLAG_UPDATE_CURRENT` 가 앞의 것을 덮어쓴다. 확인 버튼과 스와이프가
         * 한쪽으로 몰린다.
         */
        private const val REQUEST_CONFIRM = 2001
        private const val REQUEST_DISMISS = 2002

        fun confirmIntent(context: Context): PendingIntent = pending(
            context,
            ACTION_CONFIRM,
            REQUEST_CONFIRM,
        )

        fun dismissIntent(context: Context): PendingIntent = pending(
            context,
            ACTION_DISMISS,
            REQUEST_DISMISS,
        )

        private fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, TripArrivalReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
