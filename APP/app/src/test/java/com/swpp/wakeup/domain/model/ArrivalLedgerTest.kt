// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이 기기가 아는 도착(todo §2 "앱 재시작 후 도착 판정 복원"). */
class ArrivalLedgerTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    // --- 디스크 형식 ---------------------------------------------------------

    @Test
    fun `적은 그대로 읽는다`() {
        val arrivals = mapOf(12L to now - hour, 13L to now)
        assertEquals(arrivals, ArrivalLedger.decode(ArrivalLedger.encode(arrivals)))
        assertEquals(emptyMap<Long, Long>(), ArrivalLedger.decode(ArrivalLedger.encode(emptyMap())))
    }

    @Test
    fun `깨진 항목은 그 항목만 버린다`() {
        assertEquals(
            mapOf(12L to 1_000L),
            ArrivalLedger.decode("12:1000;x:5;13;14:-1;0:2000;15:abc;:"),
        )
        assertEquals(emptyMap<Long, Long>(), ArrivalLedger.decode(null))
        assertEquals(emptyMap<Long, Long>(), ArrivalLedger.decode("  "))
    }

    // --- 보관 ----------------------------------------------------------------

    @Test
    fun `사흘이 지났거나 먼 미래인 도착은 버린다`() {
        val kept = ArrivalLedger.prune(
            mapOf(
                1L to now - ArrivalLedger.KEEP_MILLIS,
                2L to now - ArrivalLedger.KEEP_MILLIS - 1,
                3L to now + ArrivalLedger.FUTURE_SLACK_MILLIS,
                4L to now + ArrivalLedger.FUTURE_SLACK_MILLIS + 1,
            ),
            now,
        )
        assertEquals(setOf(1L, 3L), kept.keys)
    }

    @Test
    fun `최근 것만 정해진 수만큼 남긴다`() {
        val many = (1L..40L).associateWith { now - it * minute }
        val kept = ArrivalLedger.prune(many, now)
        assertEquals(ArrivalLedger.MAX_ENTRIES, kept.size)
        assertEquals("가장 최근 30건", (1L..30L).toSet(), kept.keys)
    }

    @Test
    fun `같은 일정의 새 판정이 앞 것을 덮는다`() {
        val first = ArrivalLedger.add(emptyMap(), 7L, now - 3 * minute, now)
        assertEquals(now - minute, ArrivalLedger.add(first, 7L, now - minute, now)[7L])
        assertEquals(2, ArrivalLedger.add(first, 8L, now, now).size)
    }

    // --- 서버 확인 -----------------------------------------------------------

    private fun ask(
        start: Long = now + 30 * minute,
        alarm: Long? = now - 10 * minute,
        skipped: Boolean = false,
        known: Boolean = false,
        lastChecked: Long? = null,
    ) = ArrivalLedger.shouldAskServer(start, alarm, skipped, known, now, lastChecked)

    @Test
    fun `알람 뒤부터 시작 5시간 뒤까지만 묻는다`() {
        assertTrue(ask())
        assertFalse("알람 전", ask(start = now + 3 * hour, alarm = now + minute))
        assertTrue("알람이 없으면 시작 1시간 전부터", ask(start = now + 59 * minute, alarm = null))
        assertFalse(ask(start = now + 61 * minute, alarm = null))
        assertTrue(ask(start = now - 5 * hour, alarm = null))
        assertFalse("시작 5시간 뒤", ask(start = now - 5 * hour - 1, alarm = null))
    }

    @Test
    fun `알거나 안 간 일정은 묻지 않고 같은 일정은 5분에 한 번만 묻는다`() {
        assertFalse(ask(known = true))
        assertFalse(ask(skipped = true))
        assertFalse(ask(lastChecked = now - 4 * minute))
        assertTrue(ask(lastChecked = now - ArrivalLedger.SERVER_CHECK_INTERVAL_MILLIS))
        assertTrue("시계가 거꾸로 갔으면 다시 묻는다", ask(lastChecked = now + minute))
    }

    @Test
    fun `GPS 도착을 먼저 쓰고 없으면 가장 이른 도착했어요를 쓴다`() {
        assertEquals(
            now - 5 * minute,
            ArrivalLedger.representative(
                listOf(
                    ArrivalLedger.Observed(now - 9 * minute, manual = true),
                    ArrivalLedger.Observed(now - 2 * minute, manual = false),
                    ArrivalLedger.Observed(now - 5 * minute, manual = false),
                )
            ),
        )
        assertEquals(
            now - 9 * minute,
            ArrivalLedger.representative(
                listOf(
                    ArrivalLedger.Observed(now - 4 * minute, manual = true),
                    ArrivalLedger.Observed(now - 9 * minute, manual = true),
                )
            ),
        )
        assertNull(ArrivalLedger.representative(emptyList()))
    }
}
