// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 정보 › "문제 신고"(PM A-7): 같은 문의 시트, 보내는 글 첫 줄에만 "[문제 신고]" */
class ProblemReportTest {

    private val text = "알람이 울렸는데 화면이 안 떴어요\n오전 7시쯤이었어요"

    @Test
    fun `신고면 요청 본문 첫 줄 앞에 표시가 붙는다`() {
        val req = Inquiry.requestOf(text, " me@snu.ac.kr ", Inquiry.Source.PROBLEM_REPORT, "0.15.1", "Pixel 8 · Android 15")
        assertEquals("[문제 신고] 알람이 울렸는데 화면이 안 떴어요\n오전 7시쯤이었어요", req.message)
        assertEquals("me@snu.ac.kr", req.replyEmail)
        assertEquals("0.15.1", req.appVersion)
        assertEquals("Pixel 8 · Android 15", req.device)
    }

    @Test
    fun `문의는 지금처럼 글 그대로`() {
        val req = Inquiry.requestOf("  $text  ", "me@snu.ac.kr", Inquiry.Source.CONTACT, "0.15.1", "Pixel 8")
        assertEquals(text, req.message)
    }

    @Test
    fun `신고는 표시까지 1000자 안에 들어가게 덜 쓸 수 있다`() {
        val max = Inquiry.maxFor(Inquiry.Source.PROBLEM_REPORT)
        assertEquals(992, max)
        val full = "가".repeat(max)
        assertEquals(1000, Inquiry.length(Inquiry.bodyFor(full, Inquiry.Source.PROBLEM_REPORT)))
        assertTrue(Inquiry.canSend(full, "me@snu.ac.kr", Inquiry.Source.PROBLEM_REPORT))
        assertFalse(Inquiry.canSend(full + "가", "me@snu.ac.kr", Inquiry.Source.PROBLEM_REPORT))
        assertEquals("992자까지 쓸 수 있어요", Inquiry.messageError(full + "가", tried = false, source = Inquiry.Source.PROBLEM_REPORT))
        assertNull(Inquiry.messageError(full, tried = true, source = Inquiry.Source.PROBLEM_REPORT))
        assertEquals("0/992", Inquiry.counter("", Inquiry.Source.PROBLEM_REPORT))
        assertEquals("0/1000", Inquiry.counter(""))
    }

    @Test
    fun `시트 제목 · 안내는 출처에 따라`() {
        assertEquals("문제 신고", Inquiry.title(Inquiry.Source.PROBLEM_REPORT))
        assertEquals(Inquiry.TITLE, Inquiry.title(Inquiry.Source.CONTACT))
        assertEquals(Inquiry.SUBTITLE, Inquiry.subtitle(Inquiry.Source.CONTACT))
    }
}
