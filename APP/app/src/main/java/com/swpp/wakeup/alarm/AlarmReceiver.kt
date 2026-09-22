package com.swpp.wakeup.alarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * AlarmManager 가 깨우는 지점.
 *
 * 여기서 하는 일은 최소한이다. `onReceive` 는 메인 스레드에서 돌고 몇 초 안에
 * 끝나야 한다. 실제 화면과 소리는 [AlarmActivity] 가 맡는다.
 *
 * **화면을 띄우는 방법이 두 가지다.**
 * 1. 전체화면 인텐트 알림 — Android 10+ 에서 백그라운드 액티비티를 띄우는
 *    정식 경로다. 잠금화면 위로도 뜬다.
 * 2. `startActivity` 직접 호출 — 기기가 잠기지 않았고 제약이 없을 때 즉시 뜬다.
 *
 * 둘 다 시도한다. 1번이 막힌 기기(사용자가 권한을 껐거나 제조사가 제한)에서는
 * 2번이, 2번이 막힌 상황에서는 1번이 동작한다. 알람이 안 뜨는 것보다 두 번
 * 시도하는 편이 낫다 — `singleInstance` 라서 화면이 두 개 생기지는 않는다.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (eventId <= 0) {
            Log.w(TAG, "eventId 없이 발화됐다. 무시한다")
            return
        }

        val schedule = ScheduledAlarmStore(context).find(eventId)
        if (schedule == null) {
            // 서버 동기화가 이 알람을 지운 뒤에 발화한 경우다. 취소가 한발
            // 늦었을 뿐이므로 조용히 넘긴다.
            Log.w(TAG, "일정 $eventId 의 저장된 알람이 없다. 무시한다")
            return
        }

        Log.i(TAG, "알람 발화: 일정 $eventId ${schedule.alarmLabel}")

        AlarmNotifications.ensureChannels(context)

        // 1) 전체화면 인텐트 알림
        //
        // 권한 확인을 **여기에 펼쳐 쓴다.** 도우미 함수로 빼면 lint 의
        // MissingPermission 검사가 그 경계를 넘어 보지 못해 오류로 잡는다.
        // 그 오류를 억제 주석으로 덮으면, 나중에 가드를 지워도 주석만 남아
        // 아무도 모른다. 조건을 눈에 보이는 자리에 둔다.
        val canPost = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (canPost) {
            runCatching {
                NotificationManagerCompat.from(context).notify(
                    AlarmNotifications.NOTIFICATION_ALARM,
                    AlarmNotifications.alarmNotification(context, schedule),
                )
            }.onFailure { Log.e(TAG, "알람 알림 게시 실패", it) }
        } else {
            Log.w(TAG, "알림 권한이 없다. 전체화면 인텐트를 쓸 수 없다")
        }

        // 2) 직접 시작
        runCatching {
            context.startActivity(
                AlarmActivity.intent(context, eventId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "액티비티 직접 시작 실패(알림 경로로 뜬다)", it) }
    }

    companion object {
        const val ACTION_FIRE = "com.swpp.wakeup.action.ALARM_FIRE"
        const val EXTRA_EVENT_ID = "event_id"
        private const val TAG = "AlarmReceiver"
    }
}
