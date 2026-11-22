package com.swpp.wakeup.background

// Partly AI-generated (Claude)

import androidx.work.ListenableWorker

/**
 * 배경 워커가 "이번 실행을 어떻게 끝낼지" 정하는 규칙.
 *
 * 워커는 `WorkerParameters` 가 있어야 만들 수 있어 JVM 테스트로 띄울 수 없다. 그래서 끝맺음 판단만
 * 값으로 떼어 `WorkerOutcomesTest` 로 고정한다. 워커는 [toResult] 로 바꿔 돌려줄 뿐이다. 동작은 그대로다.
 *
 * 성공으로 끝내는 것과 재시도는 무게가 다르다. 재시도는 백오프로 다시 깨어나고, 성공은 다음 주기까지
 * 조용하다. 로그아웃 상태에서 재시도하면 할 일 없는 워커가 계속 깨어난다.
 */
internal enum class WorkOutcome {
    SUCCESS,
    RETRY,
    ;

    fun toResult(): ListenableWorker.Result = when (this) {
        SUCCESS -> ListenableWorker.Result.success()
        RETRY -> ListenableWorker.Result.retry()
    }
}

internal object WorkerOutcomes {

    /**
     * 네트워크를 쓰기 전의 공통 점검. 로그아웃이면 할 일이 없으니 성공으로 끝낸다(재시도하면 백오프로
     * 계속 깨어난다). 로그인했지만 ApiClient 가 아직이면 프로세스가 막 뜬 것이라 재시도한다.
     *
     * @return 여기서 끝내야 하면 그 결과, 계속 진행하면 null
     */
    fun precheck(loggedIn: Boolean, apiReady: Boolean): WorkOutcome? = when {
        !loggedIn -> WorkOutcome.SUCCESS
        !apiReady -> WorkOutcome.RETRY
        else -> null
    }

    /**
     * 관측 업로드 뒤. 다 올렸으면 성공, 남았으면 상한까지 재시도, 상한에 닿으면 큐에 남긴 채 성공으로
     * 끝낸다 — 다음 판정이 다시 예약한다.
     *
     * @param attempt 이번이 몇 번째 실행인가(1부터). `runAttemptCount + 1`
     */
    fun afterUpload(pendingAfter: Int, attempt: Int): WorkOutcome = when {
        pendingAfter == 0 -> WorkOutcome.SUCCESS
        SyncDecision.shouldRetryUpload(pendingAfter, attempt) -> WorkOutcome.RETRY
        else -> WorkOutcome.SUCCESS
    }

    /**
     * 정기 동기화가 계획을 받은 뒤. 서버에서 새로 받았을 때만 알람을 다시 맞춘다 — 캐시로 맞추면
     * 서버에서 지운 일정의 알람이 되살아난다. 캐시였거나 실패했으면 알람은 그대로 두고 재시도한다.
     */
    fun afterPlanSync(succeeded: Boolean, fromCache: Boolean): Pair<Boolean, WorkOutcome> {
        val resync = succeeded && SyncDecision.shouldResyncAlarms(networkSucceeded = true, fromCache = fromCache)
        return resync to (if (resync) WorkOutcome.SUCCESS else WorkOutcome.RETRY)
    }

    /**
     * 경로 갱신이 대상 일정을 다시 계산한 뒤. 하나도 못 했으면 재시도하고, 하나라도 했으면 목록을
     * 다시 받으러 간다(null).
     */
    fun afterRecompute(refreshed: Int): WorkOutcome? = if (refreshed == 0) WorkOutcome.RETRY else null

    /**
     * 경로 갱신 뒤 목록을 다시 받은 결과. 서버 응답이면 알람을 다시 맞춘다. 캐시였거나 실패해도 성공으로
     * 끝낸다 — 15분 뒤 다시 돈다.
     */
    fun afterRouteRefreshLoad(succeeded: Boolean, fromCache: Boolean): Boolean =
        succeeded && SyncDecision.shouldResyncAlarms(networkSucceeded = true, fromCache = fromCache)
}
