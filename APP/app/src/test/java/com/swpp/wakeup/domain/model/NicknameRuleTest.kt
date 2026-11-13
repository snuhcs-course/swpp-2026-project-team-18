// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가입 닉네임 칸(iter4 H2-11, Figma `549:2029`): 서버와 같은 1~20자, 칸 아래 줄의 문구. */
class NicknameRuleTest {

    @Test
    fun `서버처럼 앞뒤 공백을 빼고 코드 포인트로 센다`() {
        assertEquals(2, NicknameRule.length("  수연 "))
        assertEquals(1, NicknameRule.length("😀")) // Kotlin length 는 2
        assertEquals("7/20", NicknameRule.counter("자료구조 수연"))
        assertEquals("0/20", NicknameRule.counter(""))
    }

    @Test
    fun `1~20자만 받는다`() {
        assertTrue(NicknameRule.isValid("가".repeat(20)))
        assertFalse(NicknameRule.isValid("가".repeat(21)))
        assertFalse(NicknameRule.isValid(" "))
        assertTrue(NicknameRule.isValid("😀".repeat(20)))
    }

    @Test
    fun `20자를 넘으면 치는 동안 바로 이유를 보인다`() {
        val long = "자료구조 듣는 서울대 20학번 김진호입니다"
        assertEquals("닉네임은 20자까지 쓸 수 있어요", NicknameRule.error(long, blurred = false, serverError = null))
        assertEquals("23/20", NicknameRule.counter(long))
    }

    @Test
    fun `비었음은 칸을 벗어난 뒤에만 보인다`() {
        assertNull(NicknameRule.error("", blurred = false, serverError = null))
        assertEquals("닉네임을 입력해 주세요", NicknameRule.error("", blurred = true, serverError = null))
        assertEquals("닉네임을 입력해 주세요", NicknameRule.error("  ", blurred = true, serverError = null))
    }

    @Test
    fun `서버 이유는 그대로 보이고 평소에는 오류가 없다`() {
        assertEquals("쓸 수 없는 닉네임", NicknameRule.error("수연", blurred = true, serverError = "쓸 수 없는 닉네임"))
        assertNull(NicknameRule.error("수연", blurred = true, serverError = null))
        assertEquals("다른 사람에게 보이는 이름이에요 · 20자까지", NicknameRule.HINT)
    }
}
