package com.swpp.wakeup.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.sensing.TripObservationQueue

/**
 * 밀린 관측을 서버에 올린다. **이동 관측과 아침 블록 기록 둘 다.**
 *
 * ## 왜 워커가 필요한가
 *
 * 관측은 이동 중에 판정된다. 지하철이면 네트워크가 없다. 그래서
 * [TripObservationQueue] 가 디스크에 적어 두는데, **비우는 계기가 앱을 여는
 * 것뿐이었다.** 사용자가 며칠 앱을 열지 않으면 그동안의 아침이 학습에 들어가지
 * 않는다. 관측이 없으면 확률이 만들어지지 않으므로 이 앱의 핵심 기능이 멈춘다.
 *
 * 이 워커는 **네트워크가 돌아오는 순간** 실행된다. 앱이 닫혀 있어도 된다.
 *
 * ## 실패 처리
 *
 * 큐가 영구 실패(형식 오류·일정 삭제)를 만나면 그 항목을 **버린다**. 안 버리면
 * 큐가 영구히 막혀 뒤의 정상 관측도 못 올린다. 그래서 큐가 남아 있다는 것은
 * 일시적 실패를 뜻하고, 그때만 재시도한다([SyncDecision.shouldRetryUpload]).
 */
class ObservationUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 로그아웃 상태면 올릴 곳이 없다. **실패가 아니라 성공으로 끝낸다** —
        // 실패로 두면 WorkManager 가 백오프를 걸고 재시도하며 배터리를 쓴다.
        if (!TokenStore(applicationContext).isLoggedIn) {
            Log.i(TAG, "로그아웃 상태. 업로드를 건너뛴다")
            return Result.success()
        }

        // 프로세스가 워커 때문에 처음 깨어났으면 ApiClient 가 아직 비어 있다.
        // Application.onCreate 가 먼저 돌지만, 순서를 가정하지 않고 확인한다.
        if (!ApiClient.isReady) {
            Log.w(TAG, "ApiClient 초기화 전이다. 다시 시도한다")
            return Result.retry()
        }

        // 두 큐를 모두 비운다. 같은 네트워크 창에서 처리해야 라디오를 한 번만
        // 켠다. 이동 관측(GPS 판정)과 블록 관측(아침 기록)은 출처가 다르지만
        // 올려야 하는 시점은 같다.
        val trips = TripObservationQueue(applicationContext)
        val blocks = BlockObservationQueue(applicationContext)

        val before = trips.pendingCount + blocks.pendingCount
        if (before == 0) return Result.success()

        // 한쪽이 실패해도 다른 쪽은 올린다. 예외로 묶으면 이동 관측의 형식
        // 오류가 아침 기록까지 막는다.
        val sent = runCatching { trips.flush() }.getOrDefault(0) +
            runCatching { blocks.flush() }.getOrDefault(0)
        val after = trips.pendingCount + blocks.pendingCount

        Log.i(TAG, "관측 업로드: 대기 ${before}건 → 처리 ${sent}건, 남음 ${after}건")

        return when {
            after == 0 -> Result.success()

            SyncDecision.shouldRetryUpload(after, runAttemptCount + 1) -> Result.retry()

            else -> {
                // 포기한다. 큐는 그대로 남아 다음 정기 실행이나 앱을 열 때
                // 다시 시도된다. 관측은 실시간이 아니라 늦어도 된다.
                Log.w(TAG, "재시도 상한에 닿았다. 관측 ${after}건을 큐에 남긴다")
                Result.success()
            }
        }
    }

    internal companion object {
        private const val TAG = "ObservationUploadWorker"

        /**
         * 고유 작업 이름.
         *
         * 같은 이름으로 KEEP 정책을 쓰면 이미 대기 중인 작업이 있을 때 새
         * 요청이 버려진다. 워커가 큐를 **통째로** 비우므로 그게 맞다 — 관측
         * 하나마다 작업을 쌓으면 같은 일을 여러 번 한다.
         */
        const val UNIQUE_NAME = "jit_observation_upload"
    }
}
