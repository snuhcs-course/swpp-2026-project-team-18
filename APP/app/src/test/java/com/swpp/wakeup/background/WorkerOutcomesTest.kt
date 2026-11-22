package com.swpp.wakeup.background

// Partly AI-generated (Claude)

import androidx.work.ListenableWorker
import com.swpp.wakeup.testing.prefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 배경 워커의 끝맺음. 재시도는 백오프로 다시 깨우고 성공은 다음 주기까지 조용하다 — 잘못 고르면
 * 로그아웃한 사용자의 기기를 계속 깨우거나, 캐시로 알람을 덮어 지운 일정이 다시 울린다.
 */
class WorkerOutcomesTest {

    @Test
    fun `로그아웃이면 성공으로 끝낸다`() {
        assertEquals(WorkOutcome.SUCCESS, WorkerOutcomes.precheck(loggedIn = false, apiReady = false))
        assertEquals(WorkOutcome.SUCCESS, WorkerOutcomes.precheck(loggedIn = false, apiReady = true))
    }

    @Test
    fun `로그인했지만 ApiClient 가 아직이면 재시도한다`() {
        assertEquals(WorkOutcome.RETRY, WorkerOutcomes.precheck(loggedIn = true, apiReady = false))
    }

    @Test
    fun `둘 다 준비됐으면 계속한다`() {
        assertNull(WorkerOutcomes.precheck(loggedIn = true, apiReady = true))
    }

    @Test
    fun `업로드가 다 끝나면 성공이다`() {
        assertEquals(WorkOutcome.SUCCESS, WorkerOutcomes.afterUpload(pendingAfter = 0, attempt = 1))
    }

    @Test
    fun `남은 관측이 있으면 상한 전까지 재시도한다`() {
        assertEquals(WorkOutcome.RETRY, WorkerOutcomes.afterUpload(pendingAfter = 3, attempt = 1))
        assertEquals(
            WorkOutcome.RETRY,
            WorkerOutcomes.afterUpload(pendingAfter = 3, attempt = SyncDecision.MAX_UPLOAD_ATTEMPTS - 1),
        )
    }

    @Test
    fun `상한에 닿으면 큐에 남긴 채 성공으로 끝낸다`() {
        assertEquals(
            WorkOutcome.SUCCESS,
            WorkerOutcomes.afterUpload(pendingAfter = 3, attempt = SyncDecision.MAX_UPLOAD_ATTEMPTS),
        )
    }

    @Test
    fun `정기 동기화 - 서버에서 받았을 때만 알람을 맞추고 성공한다`() {
        assertEquals(true to WorkOutcome.SUCCESS, WorkerOutcomes.afterPlanSync(succeeded = true, fromCache = false))
    }

    @Test
    fun `정기 동기화 - 캐시였거나 실패면 알람을 두고 재시도한다`() {
        assertEquals(false to WorkOutcome.RETRY, WorkerOutcomes.afterPlanSync(succeeded = true, fromCache = true))
        assertEquals(false to WorkOutcome.RETRY, WorkerOutcomes.afterPlanSync(succeeded = false, fromCache = false))
    }

    @Test
    fun `경로 갱신 - 하나도 다시 계산하지 못하면 재시도한다`() {
        assertEquals(WorkOutcome.RETRY, WorkerOutcomes.afterRecompute(0))
        assertNull(WorkerOutcomes.afterRecompute(1))
    }

    @Test
    fun `경로 갱신 - 서버 응답일 때만 알람을 다시 맞춘다`() {
        assertTrue(WorkerOutcomes.afterRouteRefreshLoad(succeeded = true, fromCache = false))
        assertFalse(WorkerOutcomes.afterRouteRefreshLoad(succeeded = true, fromCache = true))
        assertFalse(WorkerOutcomes.afterRouteRefreshLoad(succeeded = false, fromCache = false))
    }

    @Test
    fun `끝맺음은 WorkManager 결과로 그대로 바뀐다`() {
        assertEquals(ListenableWorker.Result.success(), WorkOutcome.SUCCESS.toResult())
        assertEquals(ListenableWorker.Result.retry(), WorkOutcome.RETRY.toResult())
    }

    @Test
    fun `작업 등록은 WorkManager 가 초기화되지 않아도 죽지 않는다`() {
        // 앱 밖(JVM)에서는 WorkManager 를 얻을 수 없다. 등록 실패가 판정 · 로그아웃을 막지 않아야 한다.
        val context = prefsContext()
        JitWork.requestObservationUpload(context)
        JitWork.requestPlanSync(context)
        JitWork.ensurePeriodicSync(context)
        JitWork.ensureRouteRefresh(context)
        JitWork.cancelAll(context)
    }
}
