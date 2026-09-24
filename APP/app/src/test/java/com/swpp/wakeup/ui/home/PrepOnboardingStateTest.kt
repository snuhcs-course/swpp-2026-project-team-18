package com.swpp.wakeup.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 준비 시간 온보딩의 입력 판정.
 *
 * 이 값은 알람 시각을 거꾸로 계산하는 **시작값**이다. 그래서 두 가지를 고정한다.
 *
 * 1. **비어 있으면 저장할 수 없다.** 기본값을 채워 두고 그대로 저장되면,
 *    지금까지처럼 전원이 같은 값을 쓰게 되어 묻는 의미가 사라진다.
 * 2. **범위를 벗어난 값은 없는 것으로 본다.** 3분·600분은 입력 실수이고,
 *    그대로 받으면 알람이 한참 어긋나는데 사용자는 원인을 모른다.
 */
class PrepOnboardingStateTest {

    private fun state(minutes: String) = HomeViewModel.PrepOnboardingState(minutes = minutes)

    @Test
    fun `기본값은 비어 있다`() {
        val s = HomeViewModel.PrepOnboardingState()

        assertEquals("", s.minutes)
        assertNull(s.parsed)
        assertFalse("빈 값으로 저장되면 묻는 의미가 없다", s.canSubmit)
    }

    @Test
    fun `정상 범위는 받아들인다`() {
        assertEquals(5, state("5").parsed)
        assertEquals(35, state("35").parsed)
        assertEquals(240, state("240").parsed)
        assertTrue(state("35").canSubmit)
    }

    @Test
    fun `범위를 벗어나면 없는 값으로 본다`() {
        assertNull("5분 미만은 입력 실수다", state("4").parsed)
        assertNull("0 은 준비 시간이 될 수 없다", state("0").parsed)
        assertNull("4시간 초과는 입력 실수다", state("241").parsed)
        assertFalse(state("999").canSubmit)
    }

    @Test
    fun `숫자가 아니면 없는 값으로 본다`() {
        assertNull(state("").parsed)
        assertNull(state("삼십").parsed)
    }

    @Test
    fun `저장 중에는 다시 제출할 수 없다`() {
        val s = HomeViewModel.PrepOnboardingState(minutes = "35", submitting = true)

        assertEquals(35, s.parsed)
        assertFalse("중복 제출이 되면 PATCH 가 두 번 나간다", s.canSubmit)
    }
}
