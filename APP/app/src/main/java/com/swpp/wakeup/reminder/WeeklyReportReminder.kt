// Partly AI-generated (Claude)
package com.swpp.wakeup.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.swpp.wakeup.MainActivity
import com.swpp.wakeup.R
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 일요일 낮 12시 주간 리포트 알림(iter4 H2-2). "이번 주 리포트가 나왔어요" → 누르면 리포트 탭의 이번 주.
 *
 * **앱이 스스로 예약한다.** 서버 푸시(FCM)는 토큰 등록과 서버 스케줄러가 없어 서버 일이 크다.
 *
 * 흐름: [scheduleNext] 가 다음 일요일 12:00 을 `AlarmManager.setWindow` 로 건다(정확한 알람이 아니라
 * 15분 안에서 시스템이 고른다) → [WeeklyReportReminderReceiver] → [WeeklyReportReminderWorker] 가 이번 주
 * 리포트를 받아 문구를 고르고 알림을 띄운 뒤 다음 주를 건다.
 *
 * 다시 거는 때: 앱이 뜰 때(같은 시각을 덮어쓰므로 여러 번 불러도 하나다) · 재부팅 · 시각 · 시간대 변경
 * ([com.swpp.wakeup.alarm.BootReceiver]) · 알림을 처리한 뒤 · 설정에서 켤 때. 로그아웃해도 예약은
 * 둔다 — 워커가 로그인 여부를 보고 건너뛴다(정기 동기화와 같다).
 */
object WeeklyReportReminder {

    /** 알림 채널. 사용자가 기기 설정에서 이 채널만 끌 수 있다 */
    const val CHANNEL = "jit.weekly_report"

    /** 알림 id. 한 주에 하나라 고정값으로 덮어쓴다 */
    const val NOTIFICATION_ID = 1005

    const val ACTION_FIRE = "com.swpp.wakeup.action.WEEKLY_REPORT_REMINDER"

    private const val REQUEST_ALARM = 4001
    private const val REQUEST_OPEN = 4002
    private const val TAG = "WeeklyReportReminder"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "주간 리포트", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "일요일 낮 12시에 이번 주 결과를 알려 준다"
                // 배너로는 뜨되 소리는 내지 않는다. 일요일 낮에 소리까지 내면 채널을 끈다.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200)
            }
        )
    }

    /**
     * 다음 알림을 건다. 꺼져 있으면 걸어 둔 것을 지운다. 실패해도 앱은 계속 돈다 — 알림 하나 때문에 앱이
     * 죽으면 안 된다.
     */
    fun scheduleNext(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val store = WeeklyReportReminderStore(context)
        if (!store.enabled) {
            cancel(context)
            return
        }
        val at = WeeklyReportReminderLogic.nextTrigger(now, store.lastNotifiedWeek)
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setWindow(
                AlarmManager.RTC_WAKEUP,
                at.toInstant().toEpochMilli(),
                WeeklyReportReminderLogic.WINDOW_MINUTES * 60_000L,
                alarmIntent(context),
            )
        }.onSuccess { Log.i(TAG, "다음 주간 리포트 알림: $at") }
            .onFailure { Log.w(TAG, "주간 리포트 알림 예약 실패", it) }
    }

    /** 설정에서 켜고 끈다. 끄면 걸어 둔 것을 지운다 */
    fun setEnabled(context: Context, enabled: Boolean) {
        WeeklyReportReminderStore(context).enabled = enabled
        if (enabled) scheduleNext(context) else cancel(context)
    }

    fun isEnabled(context: Context): Boolean = WeeklyReportReminderStore(context).enabled

    private fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(alarmIntent(context)) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, WeeklyReportReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 알림 본문. 누르면 리포트 탭의 [week] 를 연다 */
    internal fun notification(context: Context, message: WeeklyReportReminderLogic.Message, week: LocalDate): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            MainActivity.reportIntent(context, week),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}

/** 일요일 12:00 에 깨어나는 곳. 네트워크가 필요하므로 워커로 넘긴다(리시버는 몇 초 안에 끝나야 한다) */
class WeeklyReportReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WeeklyReportReminder.ACTION_FIRE) return
        WeeklyReportReminderWorker.enqueue(context)
    }
}

/** 켜짐 여부와 마지막으로 처리한 주. 기기에만 둔다 */
internal class WeeklyReportReminderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 기본은 켬 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 마지막으로 처리한 주의 월요일. 같은 주에 두 번 띄우지 않는다 */
    var lastNotifiedWeek: LocalDate?
        get() = prefs.getString(KEY_LAST_WEEK, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) = prefs.edit { putString(KEY_LAST_WEEK, value?.toString()) }

    private companion object {
        const val PREFS = "weekly_report_reminder"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_WEEK = "last_notified_week"
    }
}
