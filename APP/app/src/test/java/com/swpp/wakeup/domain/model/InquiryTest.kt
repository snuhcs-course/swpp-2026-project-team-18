// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.domain.model.Inquiry.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도움말 › 문의하기: 보낼 수 있는 조건, 칸 아래 오류, 서버 응답 → 안내 */
class InquiryTest {

    private val ok = "알람이 예상보다 30분 일찍 울렸어요"
    private val mail = "me@snu.ac.kr"

    @Test
    fun `글은 앞뒤 공백을 빼고 10자부터 1000자까지`() {
        assertFalse(Inquiry.canSend("   안돼요   ", mail))
        assertTrue(Inquiry.canSend("가".repeat(10), mail))
        assertTrue(Inquiry.canSend("가".repeat(1000), mail))
        assertFalse(Inquiry.canSend("가".repeat(1001), mail))
        assertEquals("3/1000", Inquiry.counter("  세 자  "))
    }

    @Test
    fun `이메일이 비었거나 틀리면 보내지 못한다`() {
        assertFalse(Inquiry.canSend(ok, ""))
        assertFalse(Inquiry.canSend(ok, "me@"))
        assertTrue(Inquiry.canSend(ok, "  $mail  "))
    }

    @Test
    fun `짧은 글과 이메일 오류는 보내려 한 뒤에만 말한다`() {
        assertNull(Inquiry.messageError("짧음", tried = false))
        assertEquals("10자 이상 적어 주세요", Inquiry.messageError("짧음", tried = true))
        assertNull(Inquiry.emailError("", tried = false))
        assertEquals("답 받을 이메일을 적어 주세요", Inquiry.emailError(" ", tried = true))
        assertEquals("이메일 형식을 확인해 주세요", Inquiry.emailError("me@", tried = true))
        assertNull(Inquiry.emailError(mail, tried = true))
        assertNull(Inquiry.messageError(ok, tried = true))
    }

    @Test
    fun `넘친 글은 바로 말한다`() {
        assertEquals("1000자까지 쓸 수 있어요", Inquiry.messageError("가".repeat(1001), tried = false))
    }

    @Test
    fun `응답 코드에 따른 결과`() {
        assertEquals(Outcome.DONE, Inquiry.outcome(201))
        assertEquals(Outcome.DONE, Inquiry.outcome(200))
        listOf(404, 405, 501).forEach { assertEquals(Outcome.UNSUPPORTED, Inquiry.outcome(it)) }
        assertEquals(Outcome.LIMITED, Inquiry.outcome(429))
        listOf(400, 401, 500).forEach { assertEquals(Outcome.FAILED, Inquiry.outcome(it)) }
        assertEquals(Outcome.OFFLINE, Inquiry.outcome(null))
    }

    @Test
    fun `보냈다는 말은 성공에서만 나온다`() {
        Outcome.entries.filter { it != Outcome.DONE }.forEach {
            assertFalse(it.name, Inquiry.message(it).contains("보냈어요. 답"))
        }
        assertEquals("문의를 보냈어요. 답은 이메일로 보내 드릴게요", Inquiry.message(Outcome.DONE))
        assertTrue(Inquiry.message(Outcome.UNSUPPORTED).contains("그대로"))
    }
}
