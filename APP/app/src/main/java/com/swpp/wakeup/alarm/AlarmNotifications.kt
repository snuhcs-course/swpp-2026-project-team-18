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
import com.swpp.wakeup.domain.model.ArrivalVerdict

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

    /**
     * 도착 결과 채널.
     *
     * [CHANNEL_TRIP] 과 나눈다. 추적 알림은 30분 동안 조용히 상태바에 있어야
     * 해서 IMPORTANCE_LOW 인데, 도착 결과는 **그 아침의 답**이라 한 번은 눈에
     * 띄어야 한다. 같은 채널에 두면 둘 중 하나가 반드시 잘못된 중요도로 뜬다.
     */
    const val CHANNEL_ARRIVAL = "jit.arrival"

    /** 알람 알림 id. 한 번에 하나만 울리므로 고정값을 쓴다. */
    const val NOTIFICATION_ALARM = 1001

    /** 이동 추적 포그라운드 서비스 알림 id. */
    const val NOTIFICATION_TRIP = 1002

    /**
     * 도착 결과 알림 id.
     *
     * **[NOTIFICATION_TRIP] 과 달라야 한다.** 같은 id 를 쓰면 그것이 곧
     * 포그라운드 서비스 알림이고, 서비스가 멈추는 순간 시스템이 함께 치운다 —
     * 도착 결과가 사용자 눈앞에서 사라진다. 실제로 그렇게 동작하고 있었다.
     */
    const val NOTIFICATION_ARRIVAL = 1003

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

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ARRIVAL,
                "도착 결과",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "목적지에 닿았을 때 약속 시각과 비교해 알려 준다"
                // 소리는 내지 않는다. 도착 시점은 대개 강의실 앞이고, 거기서
                // 울리면 사용자가 채널을 끈다 — 그러면 결과를 영영 못 본다.
                // 진동만으로도 상단에 뜨는 것은 보장된다.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200)
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
        /**
         * 지금 어느 단계인지. 주면 펼친 화면에 진행 표시를 함께 그린다.
         *
         * null 이면 아직 단계를 모르는 시점(서비스가 막 시작해 첫 fix 를 받기
         * 전)이다. 그때 단계를 단정하면 "준비 중" 이 잠깐 떴다가 "이동 중" 으로
         * 바뀌는데, 이미 집을 나선 사람에게는 틀린 표시다.
         */
        phase: TripPhaseLabel? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.swpp.wakeup.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val expanded = if (phase == null) text else "$text\n${phase.breadcrumb()}"

        return NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
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

    /**
     * 도착 결과 알림.
     *
     * ## 왜 따로 만드는가
     *
     * 도착하면 추적 서비스는 할 일이 끝나 멈춘다. 그런데 결과를 포그라운드
     * 서비스 알림([NOTIFICATION_TRIP]) 에 써 넣고 멈추면 시스템이 그 알림을
     * 함께 치운다. 사용자가 보기 전에 사라진다. 그래서 **서비스 수명과 무관한
     * 별도 알림**으로 띄운다.
     *
     * ## 사라지는 조건
     *
     * `setAutoCancel(false)` 라서 눌러도 사라지지 않고, 시간이 지나 사라지는
     * 설정도 없다. 사라지는 유일한 정상 경로는 "확인" 이다.
     *
     * **다만 Android 14+ 에서는 사용자가 밀어서 지울 수 있다.**
     * `setOngoing(true)` 가 더 이상 스와이프를 막지 못하도록 OS 가 바뀌었다.
     * 이건 앱이 되돌릴 수 없으므로, 밀어서 지운 것도 확인으로 취급한다
     * ([com.swpp.wakeup.sensing.TripArrivalReceiver] 의 `deleteIntent`).
     * 억지로 되살리면 지울 수 없는 알림이 되어 사용자가 채널을 끈다.
     */
    fun arrivalNotification(
        context: Context,
        verdict: ArrivalVerdict,
        /** "09:30 해석개론 · 302동 105호" */
        eventLine: String,
        confirmIntent: PendingIntent,
        dismissIntent: PendingIntent? = null,
    ): Notification {
        val body = "$eventLine\n${verdict.detail()}"

        return NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(verdict.headline)
            .setContentText(eventLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 눌러도 지워지지 않는다. "확인" 만이 정상 종료다.
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "확인", confirmIntent)
            .apply {
                if (dismissIntent != null) setDeleteIntent(dismissIntent)
            }
            .build()
    }

    /**
     * 진행 알림에 그릴 단계.
     *
     * [com.swpp.wakeup.sensing.TripGeofence.Phase] 를 그대로 쓰지 않는다.
     * 그쪽은 판정기의 내부 상태이고 이쪽은 사용자에게 보여 줄 말이다. 섞으면
     * 판정 규칙을 고치려다 문구가 바뀌거나 그 반대가 된다.
     */
    enum class TripPhaseLabel(val label: String) {
        /** 아직 출발 전. */
        PREPARING("준비 중"),

        /** 출발했고 목적지로 가는 중. */
        IN_TRANSIT("이동 중"),

        /** 목적지 반경에 닿았다. */
        ARRIVED("도착"),
        ;

        /**
         * "● 준비 중 → ○ 이동 중 → ○ 도착"
         *
         * 제목만으로도 단계를 알 수 있지만, 세 단계가 있다는 것과 지금 몇 번째인지는
         * 나란히 놓고 봐야 분명해진다. 펼친 화면에만 넣어 접힌 줄은 그대로 둔다.
         */
        fun breadcrumb(): String = entries.joinToString(" → ") {
            if (it == this) "● ${it.label}" else "○ ${it.label}"
        }
    }
}
