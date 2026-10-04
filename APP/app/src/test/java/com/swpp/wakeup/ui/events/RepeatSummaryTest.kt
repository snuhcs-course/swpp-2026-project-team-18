package com.swpp.wakeup.ui.events

import org.junit.Assert.assertEquals
import org.junit.Test

/** 일정 추가 반복 카드 오른쪽 위의 요약("월 · 수"). */
class RepeatSummaryTest {

    @Test
    fun `아무것도 고르지 않으면 반복 안 함`() {
        assertEquals("반복 안 함", repeatSummary(emptySet()))
    }

    @Test
    fun `고른 순서와 상관없이 월요일부터`() {
        assertEquals("월 · 수", repeatSummary(setOf(2, 0)))
        assertEquals("월 · 수 · 일", repeatSummary(setOf(6, 0, 2)))
    }
}
