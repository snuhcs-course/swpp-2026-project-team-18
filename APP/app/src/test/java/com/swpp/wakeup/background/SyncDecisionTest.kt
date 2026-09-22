package com.swpp.wakeup.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 배경 동기화 규칙.
 *
 * 여기 담긴 판단이 틀리면 **알람이 사라지거나** 학습 자료가 유실된다. 워커
 * 자체는 기기 없이 돌릴 수 없으므로 규칙만 떼어 내 테스트한다.
 */
class SyncDecisionTest {

    // --- 알람 재등록 ------------------------------------------------------

    @Test
    fun `서버에서 새로 받았을 때만 알람을 다시 등록한다`() {
        assertTrue(
            SyncDecision.shouldResyncAlarms(networkSucceeded = true, fromCache = false)
        )
    }

    @Test
    fun `네트워크 실패면 알람을 건드리지 않는다`() {
        // 이것이 가장 중요한 규칙이다. AlarmScheduler.sync 는 받은 목록으로
        // 등록 상태를 갈아 끼우므로, 빈 목록으로 부르면 걸려 있던 알람이
        // **전부 취소된다.** 알람이 한 번 안 울리면 이 앱은 존재 이유가 없다.
        assertFalse(
            SyncDecision.shouldResyncAlarms(networkSucceeded = false, fromCache = false)
        )
        assertFalse(
            SyncDecision.shouldResyncAlarms(networkSucceeded = false, fromCache = true)
        )
    }

    @Test
    fun `캐시로 채운 성공은 재등록 근거가 아니다`() {
        // 저장소는 오프라인일 때도 Success 를 준다(사본으로 화면을 채우려고).
        // 그 성공을 재등록 신호로 읽으면 오래된 사본이 지금 걸린 알람을 덮어쓰고,
        // 사용자가 미뤄 둔 알람도 되돌린다.
        assertFalse(
            SyncDecision.shouldResyncAlarms(networkSucceeded = true, fromCache = true)
        )
    }

    // --- 관측 업로드 재시도 -----------------------------------------------

    @Test
    fun `큐가 비었으면 재시도하지 않는다`() {
        assertFalse(SyncDecision.shouldRetryUpload(pendingAfterFlush = 0, attemptCount = 1))
    }

    @Test
    fun `큐가 남았으면 재시도한다`() {
        // 큐는 영구 실패 항목을 버리고 비운다. 남아 있다는 것은 일시적 실패다.
        assertTrue(SyncDecision.shouldRetryUpload(pendingAfterFlush = 3, attemptCount = 1))
    }

    @Test
    fun `상한에 닿으면 포기한다`() {
        val limit = SyncDecision.MAX_UPLOAD_ATTEMPTS
        assertTrue(SyncDecision.shouldRetryUpload(pendingAfterFlush = 1, attemptCount = limit - 1))
        assertFalse(SyncDecision.shouldRetryUpload(pendingAfterFlush = 1, attemptCount = limit))
        assertFalse(
            SyncDecision.shouldRetryUpload(pendingAfterFlush = 1, attemptCount = limit + 10)
        )
    }

    @Test
    fun `상한이 있어야 무한 재시도가 되지 않는다`() {
        // 서버가 계속 5xx 를 내는 동안 무한히 깨어나면 배터리를 먹는다.
        assertTrue("상한이 1 이상이어야 한다", SyncDecision.MAX_UPLOAD_ATTEMPTS >= 1)
        assertTrue("상한이 과하다", SyncDecision.MAX_UPLOAD_ATTEMPTS <= 10)
    }

    // --- 주기 -------------------------------------------------------------

    @Test
    fun `정기 주기가 알람 지평보다 훨씬 짧다`() {
        // 등록 지평은 7일이다. 주기가 그보다 길면 8일 뒤 일정의 알람이 걸리는
        // 시점을 놓친다. 넉넉한 여유를 두고 하루 이내여야 한다.
        assertTrue(
            "주기가 너무 길다: ${SyncDecision.PERIODIC_SYNC_HOURS}시간",
            SyncDecision.PERIODIC_SYNC_HOURS <= 24,
        )
        // WorkManager 최소 주기는 15분이다. 그렇다고 그 빈도로 라디오를 켜면
        // 배터리만 쓴다 — 하는 일이 하루 한 번 갱신되는 모델을 받아 오는 것이다.
        assertTrue(
            "주기가 너무 짧다: ${SyncDecision.PERIODIC_SYNC_HOURS}시간",
            SyncDecision.PERIODIC_SYNC_HOURS >= 1,
        )
        assertEquals(6L, SyncDecision.PERIODIC_SYNC_HOURS)
    }
}
