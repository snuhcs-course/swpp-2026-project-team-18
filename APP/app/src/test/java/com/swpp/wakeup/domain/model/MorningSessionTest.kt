package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 아침 기록 계산 규칙.
 *
 * 이 값들이 서버로 올라가 **준비 시간 분포를 직접 움직인다.** 틀리면 알람 시각이
 * 조용히 어긋나고, 그 원인을 화면에서 볼 방법이 없다.
 *
 * 특히 `slackAtStartOf` 는 측정 시점을 잘못 잡으면 `slack_coef` 학습의 상관이
 * **뒤집힌다** — 오래 걸린 블록일수록 여유가 적게 나와 "여유가 적으면 느리다"
 * 로 학습된다.
 */
class MorningSessionTest {

    private val minute = 60_000L
    private val start = 1_000_000_000L

    private fun session(
        departByOffsetMinutes: Long? = 60,
        blocks: List<MorningBlock> = listOf(
            MorningBlock(1, "샤워", 15.0),
            MorningBlock(2, "아침 식사", 10.0),
            MorningBlock(3, "옷", 5.0),
        ),
    ) = MorningSession(
        eventId = 7,
        startedAtMillis = start,
        departByMillis = departByOffsetMinutes?.let { start + it * minute },
        plannedPrepMinutes = 30,
        blocks = blocks,
    )

    // --- 진행 상태 --------------------------------------------------------

    @Test
    fun `아무것도 안 했으면 첫 블록이 현재다`() {
        assertEquals(1L, session().current?.blockId)
        assertEquals(3, session().remaining.size)
        assertTrue(session().done.isEmpty())
        assertFalse(session().isComplete)
    }

    @Test
    fun `마치면 다음 블록으로 넘어간다`() {
        val next = session().mark(1, start + 12 * minute)
        assertEquals(2L, next.current?.blockId)
        assertEquals(1, next.done.size)
    }

    @Test
    fun `전부 마치면 완료다`() {
        val done = session()
            .mark(1, start + 12 * minute)
            .mark(2, start + 22 * minute)
            .mark(3, start + 28 * minute)
        assertTrue(done.isComplete)
        assertNull(done.current)
    }

    @Test
    fun `블록이 없으면 완료가 아니다`() {
        // 빈 세션을 완료로 보면 기록할 것이 없는데 "끝내기" 만 뜬다.
        assertFalse(session(blocks = emptyList()).isComplete)
    }

    // --- 소요 시간 --------------------------------------------------------

    @Test
    fun `첫 블록의 소요는 알람 해제부터다`() {
        assertEquals(12.0, session().durationOf(1, start + 12 * minute), 0.01)
    }

    @Test
    fun `두 번째 블록의 소요는 앞 블록이 끝난 뒤부터다`() {
        val after = session().mark(1, start + 12 * minute)
        // 12분에 샤워 끝, 25분에 식사 끝 → 식사는 13분
        assertEquals(13.0, after.durationOf(2, start + 25 * minute), 0.01)
    }

    @Test
    fun `음수 소요는 0으로 막는다`() {
        // 기기 시계가 뒤로 조정되면 음수가 나올 수 있다. 음수 소요를 보내면
        // 서버가 400 으로 거부해 그 아침의 기록이 전부 버려진다.
        assertEquals(0.0, session().durationOf(1, start - 5 * minute), 0.01)
    }

    @Test
    fun `소요를 소수 첫째 자리로 맞춘다`() {
        assertEquals(1.5, session().durationOf(1, start + 90_000L), 0.001)
    }

    // --- 남은 계획 --------------------------------------------------------

    @Test
    fun `남은 계획은 안 마친 것만 더한다`() {
        assertEquals(30.0, session().remainingPlannedMinutes(), 0.01)
        val after = session().mark(1, start + 12 * minute)
        assertEquals(15.0, after.remainingPlannedMinutes(), 0.01)
    }

    @Test
    fun `병렬 블록은 남은 계획에서 뺀다`() {
        // 계획이 병렬을 max 로 넣으므로 남은 시간에서도 빼야 같은 기준이다.
        // 넣으면 실제보다 빡빡하게 보여 사용자를 불필요하게 재촉한다.
        val withParallel = session(
            blocks = listOf(
                MorningBlock(1, "샤워", 15.0),
                MorningBlock(2, "세탁기", 40.0, parallelizable = true),
            )
        )
        assertEquals(15.0, withParallel.remainingPlannedMinutes(), 0.01)
    }

    // --- 여유 (학습의 핵심 입력) ------------------------------------------

    @Test
    fun `첫 블록의 여유는 전체 계획을 뺀 값이다`() {
        // 출발까지 60분, 계획 30분 → 여유 30분
        assertEquals(30.0, session().slackAtStartOf(1)!!, 0.01)
    }

    @Test
    fun `여유는 블록 시작 시점 기준이다`() {
        // 샤워가 12분 걸렸다. 식사를 시작할 때는 출발까지 48분이 남았고
        // 남은 계획은 15분이므로 여유는 33분이다.
        val after = session().mark(1, start + 12 * minute)
        assertEquals(33.0, after.slackAtStartOf(2)!!, 0.01)
    }

    @Test
    fun `오래 걸린 블록이 여유를 깎지 않는다`() {
        // **이것이 이 테스트 파일의 핵심이다.**
        //
        // 끝난 뒤 기준으로 재면 샤워가 오래 걸릴수록 샤워의 여유가 작게 나와
        // "여유가 적으면 느리다" 로 학습된다. 인과가 거꾸로다. 시작 시점 기준이면
        // 샤워가 몇 분 걸렸든 샤워의 여유는 같다.
        val quick = session().mark(1, start + 5 * minute)
        val slow = session().mark(1, start + 25 * minute)
        assertEquals(quick.slackAtStartOf(1)!!, slow.slackAtStartOf(1)!!, 0.01)
    }

    @Test
    fun `이미 늦었으면 여유가 음수다`() {
        // 출발까지 20분인데 계획이 30분이다.
        val tight = session(departByOffsetMinutes = 20)
        assertEquals(-10.0, tight.slackAtStartOf(1)!!, 0.01)
    }

    @Test
    fun `출발마감을 모르면 여유는 null 이다`() {
        // **0 으로 채우지 않는다.** "여유가 없었다" 와 "여유를 알 수 없다" 는
        // 다르고, 0 을 넣으면 학습이 없는 상관을 만든다.
        assertNull(session(departByOffsetMinutes = null).slackAtStartOf(1))
    }

    // --- 되돌리기 ---------------------------------------------------------

    @Test
    fun `되돌리면 마지막 기록이 풀린다`() {
        val after = session()
            .mark(1, start + 12 * minute)
            .mark(2, start + 25 * minute)
            .undoLast()
        assertEquals(1, after.done.size)
        assertEquals(2L, after.current?.blockId)
    }

    @Test
    fun `기록이 없으면 되돌려도 그대로다`() {
        val same = session().undoLast()
        assertTrue(same.done.isEmpty())
        assertEquals(3, same.blocks.size)
    }

    // --- 만료 -------------------------------------------------------------

    @Test
    fun `오래된 세션은 만료다`() {
        // 기록만 시작하고 닫아 두면 다음 아침까지 남는다. 그 상태에서 탭하면
        // 소요가 18시간으로 올라가 학습을 망친다.
        val old = session()
        assertFalse(old.isStale(start + 60 * minute))
        assertTrue(old.isStale(start + MorningSession.STALE_AFTER_MILLIS + 1))
    }

    // --- 시작 시각 --------------------------------------------------------

    @Test
    fun `첫 블록의 시작은 알람 해제 시각이다`() {
        assertEquals(start, session().startOf(1))
    }

    @Test
    fun `모르는 블록은 알람 해제 시각으로 떨어진다`() {
        // 블록이 삭제된 뒤 큐가 남아 있는 경우를 방어한다. 여기서 예외가 나면
        // 아침 기록 화면 전체가 죽는다.
        assertEquals(start, session().startOf(999))
    }
}
