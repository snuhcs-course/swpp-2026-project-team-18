package com.swpp.wakeup.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 배경 작업 등록.
 *
 * 워커를 넣을 위치와 정책을 한곳에 모은다. 흩어 두면 같은 작업이 서로 다른
 * 정책으로 두 번 등록되고, 그 증상은 "가끔 두 번 올라간다" 처럼 보여 원인을
 * 찾기 어렵다.
 *
 * WorkManager 는 기본 초기화(`androidx.startup`)를 쓴다. 커스텀 `Configuration`
 * 이 필요 없으므로 매니페스트에 넣을 것이 없다.
 */
object JitWork {

    /**
     * 네트워크가 있어야 하는 작업의 공통 제약.
     *
     * `CONNECTED` 면 충분하다. `UNMETERED`(와이파이)를 요구하면 데이터만 쓰는
     * 사용자의 관측이 영영 올라가지 않는다. 보내는 양은 한 번에 수 KB 다.
     */
    private val networkRequired = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 관측 업로드를 예약한다. **판정 직후에 부른다.**
     *
     * 지금 네트워크가 없어도 된다 — 제약이 붙어 있어 연결되는 순간 실행된다.
     * 이것이 "앱을 열어야 큐가 비는" 문제의 해법이다.
     *
     * [ExistingWorkPolicy.KEEP] 인 이유: 워커가 큐를 **통째로** 비우므로 관측
     * 하나마다 작업을 쌓을 필요가 없다. 이미 대기 중인 작업이 둘 다 처리한다.
     */
    fun requestObservationUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<ObservationUploadWorker>()
            .setConstraints(networkRequired)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ObservationUploadWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure {
            // 워커 등록 실패로 판정 자체를 실패시키지 않는다. 큐에는 이미
            // 적혀 있으므로 앱을 열면 올라간다.
            Log.w(TAG, "관측 업로드 작업 등록 실패", it)
        }
    }

    /**
     * 정기 동기화를 예약한다. **앱이 뜰 때마다 불러도 안전하다.**
     *
     * [ExistingPeriodicWorkPolicy.KEEP] 라서 이미 예약돼 있으면 주기가
     * 초기화되지 않는다. `UPDATE` 를 쓰면 앱을 열 때마다 다음 실행이 뒤로
     * 밀려서, 자주 여는 사용자는 정기 동기화가 영영 돌지 않는다.
     */
    fun ensurePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<PlanSyncWorker>(
            SyncDecision.PERIODIC_SYNC_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(networkRequired)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PlanSyncWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { Log.w(TAG, "정기 동기화 작업 등록 실패", it) }

        ensureRouteRefresh(context)
    }

    /**
     * 임박한 일정의 경로 갱신을 예약한다. 15분 주기다.
     *
     * [ensurePeriodicSync] 와 따로 두는 이유는 주기가 다르기 때문이다. 알람 등록
     * 동기화는 6시간이면 충분하지만, 배차가 바뀌는 것을 따라가려면 아침에는 더
     * 자주 봐야 한다.
     *
     * **15분마다 네트워크를 깨우지 않는다.** [RouteRefreshWorker] 가 먼저 기기에
     * 등록된 알람 목록만 보고 임박한 일정이 없으면 그대로 끝낸다.
     */
    fun ensureRouteRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<RouteRefreshWorker>(
            RouteRefreshDecision.REFRESH_PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(networkRequired)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                RouteRefreshWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { Log.w(TAG, "경로 갱신 작업 등록 실패", it) }
    }

    /**
     * 예약된 작업을 모두 취소한다. **로그아웃에서 부른다.**
     *
     * 남겨 두면 로그아웃 뒤에도 워커가 깨어난다. 워커가 로그인 상태를 확인해
     * 아무 일도 하지 않지만, 그렇다고 6시간마다 프로세스를 깨울 이유는 없다.
     */
    fun cancelAll(context: Context) {
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(ObservationUploadWorker.UNIQUE_NAME)
                cancelUniqueWork(PlanSyncWorker.UNIQUE_NAME)
                cancelUniqueWork(RouteRefreshWorker.UNIQUE_NAME)
            }
        }.onFailure { Log.w(TAG, "작업 취소 실패", it) }
    }

    private const val TAG = "JitWork"

    /** 지수 백오프 시작값. WorkManager 최소값이 10초다. */
    private const val BACKOFF_SECONDS = 30L
}
