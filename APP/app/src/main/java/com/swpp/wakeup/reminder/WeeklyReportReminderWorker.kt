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
import com.swpp.wakeup.data.remote.ReportsApi
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
        val tokens = TokenStore(context)
        runWeeklyReminder(
            now = ZonedDateTime.now(),
            store = WeeklyReportReminderStore(context),
            account = { tokens.email?.takeIf { tokens.isLoggedIn } },
            fetch = { today -> fetchWeeklyReport(today, ApiClient.isReady) { ApiClient.reports } },
            show = { message, week ->
                WeeklyReportReminder.ensureChannel(context)
                // lint가 권한 검사를 직접 볼 수 있게 실제 알림 호출과 같은 범위에 둔다.
                val canPost = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (canPost) {
                    runCatching {
                        NotificationManagerCompat.from(context).notify(
                            WeeklyReportReminder.NOTIFICATION_ID,
                            WeeklyReportReminder.notification(context, message, week),
                        )
                    }.onFailure { Log.e(TAG, "주간 리포트 알림 실패", it) }
                } else {
                    Log.w(TAG, "알림 권한이 없어 주간 리포트 알림을 띄우지 못한다")
                }
            },
            reschedule = { WeeklyReportReminder.scheduleNext(context) },
        )
        return Result.success()
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

/** 실기기 효과는 주입하고, 중복 방지·로그아웃·취소 이후 다음 예약은 JVM에서 검증한다. */
internal suspend fun runWeeklyReminder(
    now: ZonedDateTime,
    store: WeeklyReportReminderStore,
    account: () -> String?,
    fetch: suspend (LocalDate) -> WeeklyFetch,
    show: (WeeklyReportReminderLogic.Message, LocalDate) -> Unit,
    reschedule: () -> Unit,
) {
    try {
        if (!store.enabled || !WeeklyReportReminderLogic.shouldNotify(now, store.lastNotifiedWeek)) return
        val week = WeeklyReportReminderLogic.weekStart(now.toLocalDate())
        // 건너뛰거나 실패해도 같은 주를 계속 깨우지 않도록 요청 전에 처리 표시를 남긴다.
        store.lastNotifiedWeek = week
        val owner = account() ?: return
        val message = reminderMessageFor(fetch(now.toLocalDate())) ?: return
        // 서버 응답을 기다리는 동안 계정이 바뀌면 이전 사용자의 결과를 알리지 않는다.
        if (account() != owner) return
        show(message, week)
    } finally {
        reschedule()
    }
}

// Partly AI-generated (Claude) — 워커에서 Android 없이 판단하는 부분. JVM 테스트(WeeklyReportReminderFetchTest)로 고정한다.

/** 주간 리포트를 받아 온 결과. 401 을 실패와 갈라야 해서 저장소를 쓰지 않는다 */
internal sealed interface WeeklyFetch {
    data class Ok(val view: WeeklyReportView) : WeeklyFetch

    /** 로그인이 풀렸다. 알림을 보내지 않는다 */
    data object Unauthorized : WeeklyFetch
    data object Failed : WeeklyFetch
}

/** [today] 가 든 주의 리포트. 준비 전이거나 네트워크가 없으면 실패다 — 실패해도 "이번 주 결과를 확인해 보세요" 는 보낸다 */
internal suspend fun fetchWeeklyReport(
    today: LocalDate,
    isReady: Boolean,
    api: () -> ReportsApi,
): WeeklyFetch {
    if (!isReady) return WeeklyFetch.Failed
    return try {
        val response = api().weekly(today.toString())
        val body = response.body()
        when {
            response.code() == 401 -> WeeklyFetch.Unauthorized
            response.isSuccessful && body != null -> WeeklyFetch.Ok(body.toView())
            else -> WeeklyFetch.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(WEEKLY_FETCH_TAG, "주간 리포트를 받지 못했다", e)
        WeeklyFetch.Failed
    } catch (e: Exception) {
        Log.e(WEEKLY_FETCH_TAG, "주간 리포트 처리 실패", e)
        WeeklyFetch.Failed
    }
}

/** 받아 온 결과 → 알림 문구. 로그인이 풀렸으면 null(보내지 않는다) */
internal fun reminderMessageFor(fetched: WeeklyFetch): WeeklyReportReminderLogic.Message? = when (fetched) {
    WeeklyFetch.Unauthorized -> null
    WeeklyFetch.Failed -> WeeklyReportReminderLogic.message(null)
    is WeeklyFetch.Ok -> WeeklyReportReminderLogic.message(fetched.view)
}

private const val WEEKLY_FETCH_TAG = "WeeklyReportReminder"
