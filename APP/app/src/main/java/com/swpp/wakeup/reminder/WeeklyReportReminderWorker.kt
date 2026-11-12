// Partly AI-generated (Claude)
package com.swpp.wakeup.reminder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.repository.toView
import com.swpp.wakeup.domain.model.WeeklyReportView
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 일요일 12:00 에 이번 주 리포트를 받아 알림을 띄운다([WeeklyReportReminder]).
 *
 * 네트워크 제약을 걸지 않는다 — 걸면 오프라인인 동안 기다리다 18시를 넘겨 그 주를 통째로 놓친다. 대신
 * 받지 못하면 숫자 없이 "이번 주 리포트를 확인해 보세요" 로 보낸다. 끝나면 언제나 다음 주를 건다.
 */
class WeeklyReportReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        try {
            notifyIfDue(context, ZonedDateTime.now())
        } finally {
            WeeklyReportReminder.scheduleNext(context)
        }
        return Result.success()
    }

    private suspend fun notifyIfDue(context: Context, now: ZonedDateTime) {
        val store = WeeklyReportReminderStore(context)
        if (!store.enabled) return
        // 18시를 넘겼거나, 이미 이번 주를 처리했거나, 알람 창보다 일찍 깼다. 다음 주(또는 오늘 12시)로 넘긴다.
        if (!WeeklyReportReminderLogic.shouldNotify(now, store.lastNotifiedWeek)) return
        val week = WeeklyReportReminderLogic.weekStart(now.toLocalDate())
        // **무엇보다 먼저** 이번 주를 처리한 것으로 적는다. 보내지 않고 끝나는 길(로그아웃 · 일정 없음 · 예외)
        // 에서 적지 않으면 다음 예약이 "지금" 이 되어 18시까지 계속 깨어난다.
        store.lastNotifiedWeek = week
        // 로그아웃 상태면 이번 주는 건너뛴다. 예약은 그대로 둔다.
        if (!TokenStore(context).isLoggedIn) return

        val message = when (val fetched = fetch(now.toLocalDate())) {
            Fetched.Unauthorized -> null
            Fetched.Failed -> WeeklyReportReminderLogic.message(null)
            is Fetched.Ok -> WeeklyReportReminderLogic.message(fetched.view)
        } ?: return

        WeeklyReportReminder.ensureChannel(context)
        // 권한 확인을 여기에 펼쳐 쓴다(AlarmReceiver 와 같은 이유 — lint MissingPermission 이 도우미 함수를 넘어 보지 못한다).
        val canPost = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) {
            Log.w(TAG, "알림 권한이 없어 주간 리포트 알림을 띄우지 못한다")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(
                WeeklyReportReminder.NOTIFICATION_ID,
                WeeklyReportReminder.notification(context, message, week),
            )
        }.onFailure { Log.e(TAG, "주간 리포트 알림 실패", it) }
    }

    private sealed interface Fetched {
        data class Ok(val view: WeeklyReportView) : Fetched
        /** 로그인이 풀렸다. 알림을 보내지 않는다 */
        data object Unauthorized : Fetched
        data object Failed : Fetched
    }

    /** [today] 가 든 주의 리포트. 저장소를 쓰지 않는 이유는 401 을 실패와 갈라야 해서다 */
    private suspend fun fetch(today: LocalDate): Fetched {
        if (!ApiClient.isReady) return Fetched.Failed
        return try {
            val response = ApiClient.reports.weekly(today.toString())
            val body = response.body()
            when {
                response.code() == 401 -> Fetched.Unauthorized
                response.isSuccessful && body != null -> Fetched.Ok(body.toView())
                else -> Fetched.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "주간 리포트를 받지 못했다", e)
            Fetched.Failed
        } catch (e: Exception) {
            Log.e(TAG, "주간 리포트 처리 실패", e)
            Fetched.Failed
        }
    }

    companion object {
        private const val TAG = "WeeklyReportReminder"
        private const val UNIQUE_NAME = "weekly_report_reminder"

        /** 알람이 깨운 직후 부른다. 이미 대기 중이면 하나로 둔다 */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeeklyReportReminderWorker>().build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.w(TAG, "주간 리포트 알림 작업 등록 실패", it) }
        }
    }
}
