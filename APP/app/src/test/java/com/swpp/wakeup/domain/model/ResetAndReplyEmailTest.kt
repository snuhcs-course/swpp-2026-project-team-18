// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4 기록 초기화 뒤 대기열 거르기(`server-learning-reset.md` 7절)와
 * C5 문의 답 받을 이메일(`server-inquiry.md` 5절)
 */
class ResetAndReplyEmailTest {

    private val resetAt = "2026-11-15T21:38:41.408847+09:00"

    @Test
    fun `초기화 시각보다 이른 기록만 지운다 - 같은 시각은 남긴다`() {
        assertTrue(LearningReset.isBeforeReset("2026-11-15T21:38:41+09:00", resetAt))
        assertTrue("시간대가 달라도 같은 순간으로 비교한다", LearningReset.isBeforeReset("2026-11-15T12:38:00Z", resetAt))
        assertFalse(LearningReset.isBeforeReset("2026-11-15T21:38:41.408847+09:00", resetAt))
        assertFalse(LearningReset.isBeforeReset("2026-11-15T21:40:00+09:00", resetAt))
    }

    @Test
    fun `읽을 수 없으면 지우지 않는다 - 서버가 마지막으로 거른다`() {
        assertFalse(LearningReset.isBeforeReset("어제", resetAt))
        assertFalse(LearningReset.isBeforeReset(null, resetAt))
        assertFalse(LearningReset.isBeforeReset("2026-11-01T07:00:00+09:00", null))
        assertFalse(LearningReset.isBeforeReset("2026-11-01T07:00:00+09:00", "?"))
    }

    @Test
    fun `다시 맞추지 못한 일정이 있으면 한 줄을 덧붙인다`() {
        assertEquals(LearningReset.DONE, LearningReset.doneMessage(0))
        assertEquals(LearningReset.DONE, LearningReset.doneMessage(null))
        assertEquals(
            "기록을 모두 지웠어요. 알람은 처음 기준으로 다시 맞춰요. 곧 울릴 알람과 이미 시작한 일정은 그대로예요",
            LearningReset.doneMessage(1),
        )
    }

    @Test
    fun `답 받을 이메일은 254자까지 - 계정 이메일 규칙은 320자 그대로`() {
        val domain = "@example.com" // 12자
        val ok = "a".repeat(64) + "." + "b".repeat(254 - 12 - 65) + domain
        assertEquals(254, ok.length)
        val long = "c" + ok
        assertTrue(Inquiry.isReplyEmailValid(ok))
        assertFalse(Inquiry.isReplyEmailValid(long))
        assertTrue("로그인 · 가입 규칙은 바꾸지 않았다", EmailRule.isValid(long))
        assertEquals(Inquiry.EMAIL_INVALID, Inquiry.emailError(long, tried = true))
        assertFalse(Inquiry.canSend("알람이 너무 일찍 울렸어요", long))
        assertTrue(Inquiry.canSend("알람이 너무 일찍 울렸어요", " $ok "))
    }

    @Test
    fun `따옴표 로컬 부분 · 제어 문자 · 보이지 않는 문자는 막는다`() {
        assertTrue("계정 규칙은 따옴표를 받는다", EmailRule.isValid("\"me\"@snu.ac.kr"))
        assertFalse(Inquiry.isReplyEmailValid("\"me\"@snu.ac.kr"))
        assertFalse(Inquiry.isReplyEmailValid("me\u0001@snu.ac.kr"))
        assertFalse(Inquiry.isReplyEmailValid("me@snu.ac.kr\nBcc: x@y.com"))
        assertFalse(Inquiry.isReplyEmailValid("m\u200Be@snu.ac.kr"))
        assertFalse(Inquiry.isReplyEmailValid("m\u00A0e@snu.ac.kr"))
        assertTrue(Inquiry.isReplyEmailValid("me.you+jit@snu.ac.kr"))
        assertEquals(Inquiry.EMAIL_INVALID, Inquiry.emailError("\"me\"@snu.ac.kr", tried = true))
    }
}
