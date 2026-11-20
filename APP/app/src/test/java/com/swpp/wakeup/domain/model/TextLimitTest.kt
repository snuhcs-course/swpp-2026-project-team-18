package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

// AI-generated (Claude)
/** 글자 수는 서버(Python `len`)처럼 코드 포인트로 센다 */
class TextLimitTest {

    @Test
    fun `한글과 영문은 한 글자를 하나로 센다`() {
        assertEquals(5, "가나다ab".codePointLength())
    }

    @Test
    fun `이모지는 UTF-16 두 단위지만 한 글자로 센다`() {
        val text = "집🏠" // 집🏠
        assertEquals(3, text.length)
        assertEquals(2, text.codePointLength())
    }

    @Test
    fun `한도 안이면 그대로 둔다`() {
        assertEquals("가나다", "가나다".takeCodePoints(3))
        assertEquals("", "".takeCodePoints(20))
    }

    @Test
    fun `한도를 넘으면 앞에서 그 글자 수만 남기고 이모지를 반으로 자르지 않는다`() {
        val text = "가🏠나" // 가🏠나
        assertEquals("가🏠", text.takeCodePoints(2))
        assertEquals("가", text.takeCodePoints(1))
    }
}
