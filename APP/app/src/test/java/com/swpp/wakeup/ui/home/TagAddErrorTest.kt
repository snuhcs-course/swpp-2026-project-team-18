// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.failureFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/** 종류 "+ 추가" 대화상자의 실패 문구(B1). 본문은 서버 오류 모양 그대로 [failureFrom] 에 넣는다. */
class TagAddErrorTest {
    private fun error(code: Int, body: String? = null) = tagAddErrorOf(failureFrom(code, body))

    @Test
    fun `400 은 label 문구를 먼저 쓰고 없으면 서버 문구를 쓴다`() {
        assertEquals(
            "이름을 입력해야 한다.",
            error(400, """{"error":{"code":"invalid","message":"틀림","details":{"label":["이름을 입력해야 한다."]}}}"""),
        )
        assertEquals(
            "틀림",
            error(400, """{"error":{"code":"invalid","message":"틀림","details":{}}}"""),
        )
    }

    @Test
    fun `409 tag_limit 은 20개 안내다`() {
        assertEquals(
            "종류는 20개까지 만들 수 있어요",
            error(409, """{"error":{"code":"tag_limit","message":"x","details":{}}}"""),
        )
    }

    @Test
    fun `404 와 405 는 서버가 아직 지원하지 않는다는 안내다`() {
        assertEquals("아직 서버에서 지원하지 않아요", error(404))
        assertEquals("아직 서버에서 지원하지 않아요", error(405))
    }

    @Test
    fun `통신 실패와 429 와 HTML 500 은 다시 시도 안내다`() {
        val net = EventRepository.Result.Failure(message = "offline", network = true)
        assertEquals("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요", tagAddErrorOf(net))
        assertEquals("요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요", error(429))
        assertEquals("서버에 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요", error(500, "<html>oops</html>"))
    }
}