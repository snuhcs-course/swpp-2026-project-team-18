package com.swpp.wakeup.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 재부팅·앱 교체·시각 변경 후 알람을 다시 등록한다.
 *
 * **왜 필요한가.** 등록한 알람은 시스템에 남지만 재부팅하면 전부 사라진다.
 * 이 리시버가 없으면 밤에 기기를 다시 켠 사용자는 다음 아침에 알람을 못 받는다.
 * 그 한 번이 이 앱의 신뢰를 끝낸다.
 *
 * `TIME_SET`·`TIMEZONE_CHANGED` 도 받는다. 알람은 절대 시각(epoch)으로
 * 등록되므로 시간대가 바뀌면 사용자가 기대하는 벽시계 시각과 어긋난다.
 * 저장된 값으로 다시 등록해 맞춘다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) {
            Log.w(TAG, "예상하지 않은 액션: $action")
            return
        }

        AlarmNotifications.ensureChannels(context)
        val restored = AlarmScheduler(context).restoreAll()
        Log.i(TAG, "$action → 알람 ${restored}건 재등록")
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
