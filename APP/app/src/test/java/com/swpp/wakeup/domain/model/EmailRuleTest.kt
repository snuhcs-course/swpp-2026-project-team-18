package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

// Partly AI-generated (Claude)
/** 서버(Django 5.2 `EmailValidator`)가 같은 값에 내린 판정과 맞춘 표다 */
class EmailRuleTest {

    @Test
    fun `서버가 받는 주소는 통과한다`() {
        listOf(
            "a@b.co",
            "  a@b.co  ",
            "user.name+tag@snu.ac.kr",
            "\"quoted\"@x.com",
            "a@localhost",
            "a@[127.0.0.1]",
            "a@[::1]",
            "a@예시.한국",
            "a@xn--9n2bp8q.xn--3e0b707e",
            "a@b.technology",
            "x".repeat(60) + "@" + "d".repeat(60) + "." + "d".repeat(60) + "." + "d".repeat(60) + ".com",
        ).forEach { assertEquals("valid: $it", true, EmailRule.isValid(it)) }
    }

    @Test
    fun `서버가 거절하는 주소는 막는다`() {
        listOf(
            "",
            "   ",
            "ab",
            "a@b",
            "a@b.c",
            "a@-b.com",
            "a@b-.com",
            "a@b..com",
            ".a@b.com",
            "a.@b.com",
            "a b@c.com",
            "a@b.c0m1",
            "a@b.co m",
            "@b.com",
            "a@",
            "a@[999.1.1.1]",
            "a@[01.1.1.1]",
            "a\n@b.com",
            "x".repeat(321),
            "x".repeat(317) + "@b.com",
        ).forEach { assertEquals("invalid: $it", false, EmailRule.isValid(it)) }
    }

    @Test
    fun `마지막 골뱅이로 나눈다`() {
        assertEquals(true, EmailRule.isValid("\"a@b\"@x.com"))
        assertEquals(false, EmailRule.isValid("a@b@x"))
    }
}
