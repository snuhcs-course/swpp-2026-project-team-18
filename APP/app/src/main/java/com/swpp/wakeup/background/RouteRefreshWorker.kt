package com.swpp.wakeup.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.sensing.TripLiveState

/**
 * 임박한 일정의 경로를 다시 계산한다.
 *
 * ## 왜 필요한가
 *
 * 배차와 교통 상황이 바뀌면 가장 빠른 경로도 바뀐다. 알람 시각과 경로선이 그것을
 * 따라가야 하는데, 재계산은 **사용자가 "다시 계산" 을 누를 때만** 일어났다.
 * [PlanSyncWorker] 는 6시간마다 돌지만 조회와 알람 등록만 한다.
 *
 * 그래서 아침에 배차가 바뀌면 알람은 어제 계산한 시각으로 울린다.
 *
 * ## 15분 주기가 비싸지 않은 이유
 *
 * 임박한 일정이 있는지를 **네트워크 없이** 판단한다. 기기에 등록해 둔 알람 목록만
 * 보고([AlarmScheduler.registered]), 대상이 없으면 그대로 끝난다. 아침 3시간을
 * 빼면 하루 대부분은 아무 일도 하지 않는다.
 *
 * ## 쿼터
 *
 * 재계산 한 번이 카카오 경로 API 를 1~2회 부른다. 일정 1건이 3시간 동안 15분마다
 * 갱신되면 12회, 최대 24회다. 한 번에 [RouteRefreshDecision.REFRESH_MAX_EVENTS]
 * 건으로 묶어 두므로 사용자 한 명이 하루에 태우는 양이 50회를 넘지 않는다.
 *
 * 서버 쪽에도 `route` scope throttle 을 걸어 두었다 — 앱 버그가 쿼터를 태우는
 * 구멍을 앱 코드만으로 막지 않는다.
 *
 * ## 이동 중에는 갱신하지 않는다
 *
 * 경로가 바뀌면 진행률의 분모가 바뀌어 바가 뒤로 물러난다. 이미 타고 있는
 * 사람에게 다른 경로를 제안하는 것은 의미도 없다. 판단은
 * [RouteRefreshDecision.dueEventIds] 에 있다.
 */
class RouteRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tokenStore = TokenStore(applicationContext)
        if (!tokenStore.isLoggedIn) {
            // 정기 작업은 살려 둔다. 다시 로그인하면 바로 이어진다.
            return Result.success()
        }

        // 1) 네트워크를 쓰기 전에 대상이 있는지 본다. 없으면 여기서 끝난다.
        val registered = runCatching { AlarmScheduler(applicationContext).registered() }
            .getOrElse {
                Log.w(TAG, "등록된 알람을 읽지 못했다", it)
                return Result.success()
            }
        val due = RouteRefreshDecision.dueEventIds(
            alarms = registered.map { it.eventId to it.alarmAtMillis },
            nowMillis = System.currentTimeMillis(),
            trackingEventId = TripLiveState.snapshot.value?.eventId,
        )
        if (due.isEmpty()) return Result.success()

        if (!ApiClient.isReady) {
            Log.w(TAG, "ApiClient 초기화 전이다. 다시 시도한다")
            return Result.retry()
        }

        // 2) 재계산. 한 건이 실패해도 나머지를 시도한다 — 한 일정의 장소가
        //    지워졌다고 다른 일정의 알람이 낡은 채로 남을 이유가 없다.
        val cache = OfflineCache(applicationContext, tokenStore.email)
        val repository = EventRepository(cache = cache)
        var refreshed = 0
        for (eventId in due) {
            when (val result = repository.recomputePlan(eventId)) {
                is EventRepository.Result.Success -> refreshed++
                is EventRepository.Result.Failure ->
                    Log.w(TAG, "일정 $eventId 재계산 실패: ${result.message}")
            }
        }
        if (refreshed == 0) {
            Log.w(TAG, "대상 ${due.size}건이 모두 실패했다. 다시 시도한다")
            return Result.retry()
        }

        // 3) 알람 시각이 바뀌었을 수 있다. 등록을 다시 맞춘다.
        //
        // 재계산 응답만으로 알람을 고칠 수 없다 — `AlarmScheduler.sync` 는 목록
        // 전체로 등록 상태를 갈아 끼우므로 한 건만 주면 나머지가 취소된다.
        return when (val result = repository.loadHome()) {
            is EventRepository.Result.Success -> {
                val data = result.data
                if (SyncDecision.shouldResyncAlarms(true, fromCache = data.fromCache)) {
                    runCatching { AlarmScheduler(applicationContext).sync(data.schedules) }
                        .onFailure { Log.e(TAG, "알람 등록 실패", it) }
                    Log.i(TAG, "경로 갱신 ${refreshed}건, 알람 재등록 완료")
                    Result.success()
                } else {
                    // 캐시로 채워진 성공이다. 재계산은 이미 서버에 반영됐으므로
                    // 다음 실행이나 홈 화면 진입에서 알람이 맞춰진다.
                    Log.i(TAG, "경로 갱신 ${refreshed}건. 알람은 캐시라 건드리지 않는다")
                    Result.success()
                }
            }

            is EventRepository.Result.Failure -> {
                Log.w(TAG, "갱신 후 조회 실패: ${result.message}. 알람은 그대로 둔다")
                Result.success()
            }
        }
    }

    internal companion object {
        private const val TAG = "RouteRefreshWorker"
        const val UNIQUE_NAME = "jit_route_refresh"
    }
}
