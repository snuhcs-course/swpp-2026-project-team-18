// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.failureFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/** 내 종류 이름 바꾸기·지우기 대화상자의 실패 문구. 본문은 서버 오류 모양 그대로 [failureFrom] 에 넣는다. */
class TagEditErrorTest {
    private fun error(code: Int, body: String? = null) = tagEditErrorOf(failureFrom(code, body))

    @Test
    fun `400 은 label 문구를 먼저 쓴다`() {
        assertEquals(
            "이름을 입력해야 한다.",
            error(400, """{"error":{"code":"invalid","message":"틀림","details":{"label":["이름을 입력해야 한다."]}}}"""),
        )
    }

    @Test
    fun `409 tag_duplicate 는 같은 이름 안내다`() {
        assertEquals(
            "같은 이름의 종류가 이미 있어요",
            error(409, """{"error":{"code":"tag_duplicate","message":"x","details":{}}}"""),
        )
    }

    @Test
    fun `404 는 지워진 종류 안내이고 405 는 구버전 서버 안내다`() {
        assertEquals("이 종류를 찾지 못했어요. 다른 기기에서 지웠을 수 있어요", error(404))
        assertEquals("아직 서버에서 지원하지 않아요", error(405))
    }

    @Test
    fun `통신 실패와 429 와 HTML 500 은 다시 시도 안내다`() {
        val net = EventRepository.Result.Failure(message = "offline", network = true)
        assertEquals("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요", tagEditErrorOf(net))
        assertEquals("요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요", error(429))
        assertEquals("서버에 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요", error(500, "<html>oops</html>"))
    }
}
