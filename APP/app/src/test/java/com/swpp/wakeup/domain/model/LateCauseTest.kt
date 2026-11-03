// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 이동 중 늦는 이유 한 줄(todo "이동 중 재계획 ⑦"). */
class LateCauseTest {
    private val min = 60_000L
    private val departBy = 1_800_000_000_000L
    private val travel = 30

    /** departDelay 분 늦게 출발하고, 도착이 계획 이동보다 overrun 분 길게 잡힌 경우 */
    private fun line(
        late: Int? = 8,
        delay: Int? = 5,
        overrun: Int? = 1,
        departBy: Long? = this.departBy,
        travel: Int? = this.travel,
    ): String? {
        val departed = delay?.let { departBy?.plus(it * min) }
        val live = if (overrun != null && departed != null) departed + (this.travel + overrun) * min else null
        return LateCause.line(late, departBy, departed, travel, live)
    }

    @Test
    fun `안 늦으면 한 줄도 없다`() {
        assertNull(line(late = 0))
        assertNull(line(late = null))
    }

    @Test
    fun `출발이 더 늦으면 출발 이유`() {
        assertEquals("출발이 5분 늦었어요", line(delay = 5, overrun = 1))
    }

    @Test
    fun `이동이 더 길면 이동 이유`() {
        assertEquals("이동이 예상보다 7분 길어요", line(delay = 3, overrun = 7))
    }

    @Test
    fun `같으면 출발`() {
        assertEquals("출발이 4분 늦었어요", line(delay = 4, overrun = 4))
    }

    @Test
    fun `실제 출발 시각을 모르면 말하지 않는다`() {
        assertNull(line(delay = null))
        assertNull(LateCause.line(8, departBy, null, travel, departBy + 40 * min))
    }

    @Test
    fun `둘 다 2분 미만이면 말하지 않는다`() {
        assertNull(line(delay = 1, overrun = 1))
    }

    @Test
    fun `2분은 원인으로 센다`() {
        assertEquals("출발이 2분 늦었어요", line(delay = 2, overrun = 0))
    }

    @Test
    fun `계획 출발을 모르면 이동만 본다`() {
        val departed = departBy + 5 * min
        assertEquals(
            "이동이 예상보다 6분 길어요",
            LateCause.line(8, null, departed, travel, departed + (travel + 6) * min),
        )
    }

    @Test
    fun `도착 시각이나 이동 분을 모르면 출발만 본다`() {
        assertEquals("출발이 5분 늦었어요", LateCause.line(8, departBy, departBy + 5 * min, null, null))
    }

    @Test
    fun `일찍 나왔으면 출발 이유가 아니다`() {
        assertNull(line(delay = -3, overrun = 0))
    }
}
