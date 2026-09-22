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
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.sensing.TripObservationQueue

/**
 * 알람 등록을 서버 계획과 맞춘다.
 *
 * ## 왜 필요한가
 *
 * 알람 등록을 갱신하는 유일한 경로가 **홈 화면을 여는 것**이었다. 그래서 두
 * 가지가 깨진다.
 *
 * 1. **등록 지평이 7일이다.** 앱을 열지 않으면 8일 뒤 일정의 알람이 영영
 *    걸리지 않는다.
 * 2. **학습이 알람 시각을 바꾼다.** 하루 한 번 서버가 모델을 갱신하면 알람이
 *    앞뒤로 움직이는데, 앱을 열지 않으면 기기는 옛 시각으로 울린다. 서버만 아는
 *    알람은 울리지 않는다.
 *
 * ## 무엇을 하지 않는가
 *
 * **네트워크가 실패했으면 알람을 건드리지 않는다.** `AlarmScheduler.sync` 는
 * 받은 목록으로 등록 상태를 갈아 끼우므로, 빈 목록이나 오래된 사본으로 부르면
 * 걸려 있던 알람이 취소된다. 알람이 한 번 안 울리면 이 앱은 존재 이유가 없다.
 * 판단은 [SyncDecision.shouldResyncAlarms] 에 있고 단위 테스트가 지킨다.
 */
class PlanSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tokenStore = TokenStore(applicationContext)
        if (!tokenStore.isLoggedIn) {
            // 정기 작업은 살려 둔다. 다시 로그인하면 바로 동기화가 이어진다.
            Log.i(TAG, "로그아웃 상태. 동기화를 건너뛴다")
            return Result.success()
        }
        if (!ApiClient.isReady) {
            Log.w(TAG, "ApiClient 초기화 전이다. 다시 시도한다")
            return Result.retry()
        }

        // 같은 네트워크 창에서 관측도 올린다. 따로 깨우면 라디오를 두 번 켠다.
        flushObservations()

        val cache = OfflineCache(applicationContext, tokenStore.email)
        val repository = EventRepository(cache = cache)

        return when (val result = repository.loadHome()) {
            is EventRepository.Result.Success -> {
                val data = result.data
                if (SyncDecision.shouldResyncAlarms(
                        networkSucceeded = true,
                        fromCache = data.fromCache,
                    )
                ) {
                    runCatching { AlarmScheduler(applicationContext).sync(data.schedules) }
                        .onSuccess {
                            Log.i(TAG, "알람 동기화 완료: 후보 ${data.schedules.size}건")
                        }
                        .onFailure { Log.e(TAG, "알람 등록 실패", it) }
                    Result.success()
                } else {
                    // 캐시로 채워진 성공이다. 서버에 닿지 못했다는 뜻이므로
                    // 알람은 그대로 두고 다음 기회를 기다린다.
                    Log.i(TAG, "캐시로 응답했다. 알람은 건드리지 않고 재시도한다")
                    Result.retry()
                }
            }

            is EventRepository.Result.Failure -> {
                Log.w(TAG, "계획 조회 실패: ${result.message}. 알람은 그대로 둔다")
                Result.retry()
            }
        }
    }

    private suspend fun flushObservations() {
        runCatching { TripObservationQueue(applicationContext).flush() }
            .onFailure { Log.w(TAG, "이동 관측 업로드 실패. 큐에 남는다", it) }
        runCatching { BlockObservationQueue(applicationContext).flush() }
            .onFailure { Log.w(TAG, "블록 관측 업로드 실패. 큐에 남는다", it) }
    }

    internal companion object {
        private const val TAG = "PlanSyncWorker"
        const val UNIQUE_NAME = "jit_plan_sync"
    }
}
