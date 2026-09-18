package com.swpp.wakeup.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.swpp.wakeup.R
import com.swpp.wakeup.domain.model.AlarmSchedule

/**
 * 알림 채널과 알림 생성.
 *
 * 채널을 둘로 나눈다. 알람은 잠금화면을 뚫고 소리를 내야 하고, 이동 추적은
 * 조용히 상태바에만 있어야 한다. 한 채널로 묶으면 사용자가 알람 소리를
 * 끄려다 추적까지 끄거나 그 반대가 된다.
 */
object AlarmNotifications {

    const val CHANNEL_ALARM = "jit.alarm"
    const val CHANNEL_TRIP = "jit.trip"

    /** 알람 알림 id. 한 번에 하나만 울리므로 고정값을 쓴다. */
    const val NOTIFICATION_ALARM = 1001

    /** 이동 추적 포그라운드 서비스 알림 id. */
    const val NOTIFICATION_TRIP = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                "알람",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "계산된 기상 시각에 울린다"
                // 전체화면 인텐트가 막힌 기기에서는 이 채널 소리가 유일한
                // 알림 수단이다. 그래서 채널에도 알람 소리를 건다.
                // 화면이 뜨면 AlarmRinger 가 이어받아 계속 울린다.
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRIP,
                "이동 추적",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "출발·도착을 판별하는 동안 표시된다"
                setShowBadge(false)
            }
        )
    }

    /**
     * 알람 알림. `setFullScreenIntent` 로 잠금화면 위에 화면을 띄운다.
     *
     * 이것이 알람 화면을 띄우는 **정식 경로**다. 백그라운드에서
     * `startActivity` 를 직접 부르는 것은 Android 10+ 에서 막혀 있다.
     */
    fun alarmNotification(context: Context, schedule: AlarmSchedule): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            schedule.eventId.toInt(),
            AlarmActivity.intent(context, schedule.eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = listOfNotNull(
            schedule.eventLine,
            schedule.arrivalLine,
        ).joinToString(" · ")

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle("${schedule.alarmLabel} ${schedule.meridiem} · 지금 일어날 시각")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 사용자가 밀어서 지울 수 없게 한다. 알람은 해제해야 사라진다.
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
    }

    /**
     * 이동 추적 알림. 포그라운드 서비스가 요구한다.
     *
     * 위치를 계속 읽는 동안 사용자가 그 사실을 모르면 안 된다. 무엇을 왜 하고
     * 있는지 한 줄로 적는다.
     */
    fun tripNotification(
        context: Context,
        title: String,
        text: String,
        stopIntent: PendingIntent? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.swpp.wakeup.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply {
                if (stopIntent != null) addAction(0, "추적 중지", stopIntent)
            }
            .build()
    }
}
