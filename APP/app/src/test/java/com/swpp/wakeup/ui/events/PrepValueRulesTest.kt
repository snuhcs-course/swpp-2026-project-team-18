// AI-generated (Claude)
package com.swpp.wakeup.ui.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⑮ 준비 시간 값 카드(iter4 H2-5): −/+ 5분, 범위 끝에서 꺼짐, 값이 없으면 둘 다 꺼짐. */
class PrepValueRulesTest {

    private val range = 5..240

    @Test
    fun `값이 없으면 −와 +가 둘 다 꺼진다`() {
        assertFalse(PrepValueRules.canMinus(null, range))
        assertFalse(PrepValueRules.canPlus(null, range))
        assertEquals("아직 정하지 않았어요", PrepValueRules.caption(null))
    }

    @Test
    fun `범위 끝에서는 그쪽만 꺼진다`() {
        assertFalse(PrepValueRules.canMinus(5, range))
        assertTrue(PrepValueRules.canPlus(5, range))
        assertTrue(PrepValueRules.canMinus(240, range))
        assertFalse(PrepValueRules.canPlus(240, range))
        assertTrue(PrepValueRules.canMinus(35, range) && PrepValueRules.canPlus(35, range))
    }

    @Test
    fun `5분씩 바꾸되 범위를 넘지 않는다`() {
        assertEquals(30, PrepValueRules.minus(35, range))
        assertEquals(40, PrepValueRules.plus(35, range))
        assertEquals(5, PrepValueRules.minus(7, range))
        assertEquals(240, PrepValueRules.plus(238, range))
    }

    @Test
    fun `빠른 칩은 20분 30분 45분 1시간이다`() {
        assertEquals(listOf(20, 30, 45, 60), PrepValueRules.CHIPS)
        assertEquals("5분씩", PrepValueRules.caption(35))
    }
}
