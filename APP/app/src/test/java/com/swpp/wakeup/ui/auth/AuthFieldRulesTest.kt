// AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가입·로그인 입력칸 오류 줄의 판정(AuthFieldRules.kt).
 *
 * ## 이 테스트가 지키는 것
 *
 * - 형식 때문에 버튼이 꺼져 있으면 그 이유가 보인다. 앞 화면에서 가져와 손대지 않은 이메일은 칸을 벗어날 일이
 *   없어서, 벗어난 뒤에만 보이던 오류 줄이 끝내 나오지 않았다.
 * - 처음 입력하는 동안에는 조용하다(입력 도중 빨간 줄이 깜빡이지 않게).
 * - 닉네임은 서버처럼 앞뒤 공백을 빼고 코드 포인트로 20자까지다. 넘으면 바로 이유와 글자 수를 보인다.
 */
class AuthFieldRulesTest {

    /** 😀 — UTF-16 으로는 두 단위, 서버(Python len)로는 한 글자 */
    private val smile = "😀"

    // --- 이메일 -------------------------------------------------------------

    @Test
    fun `앞 화면에서 가져와 손대지 않은 이메일도 형식이 틀리면 바로 보인다`() {
        // Given 로그인 화면에서 가져온 "jinho@snu", 칸에 들어간 적 없음
        // When
        val visible = emailFormatErrorVisible("jinho@snu", focused = false, leftOnce = false)

        // Then 가입 버튼이 형식 때문에 꺼져 있으니 이유가 보여야 한다
        assertTrue(visible)
    }

    @Test
    fun `처음 입력하는 동안은 조용하고 칸을 벗어나거나 완료 키를 누르면 보인다`() {
        // Given 칸에 들어와 "jinho@naver" 를 입력하는 중
        assertFalse(emailFormatErrorVisible("jinho@naver", focused = true, leftOnce = false))

        // When 완료 키를 눌렀다(포커스는 그대로)
        // Then 보인다
        assertTrue(emailFormatErrorVisible("jinho@naver", focused = true, leftOnce = true))

        // When 칸을 벗어났다
        // Then 보인다
        assertTrue(emailFormatErrorVisible("jinho@naver", focused = false, leftOnce = true))
    }

    @Test
    fun `빈 칸과 맞는 주소에는 형식 오류가 없다`() {
        assertFalse(emailFormatErrorVisible("", focused = false, leftOnce = true))
        assertFalse(emailFormatErrorVisible("   ", focused = false, leftOnce = true))
        assertFalse(emailFormatErrorVisible("jinho@snu.ac.kr", focused = false, leftOnce = false))
    }

    // --- 닉네임 -------------------------------------------------------------

    @Test
    fun `닉네임이 20자를 넘으면 입력하는 도중에도 한도와 글자 수를 보인다`() {
        // Given 23자
        val long = "가".repeat(23)

        // When 아직 칸을 벗어나지 않았다
        val error = nicknameError(long, serverError = null, leftOnce = false)

        // Then
        assertEquals("닉네임은 20자까지 쓸 수 있어요", error)
        assertEquals("23/20", nicknameOverLimitCount(long))
    }

    @Test
    fun `닉네임 글자 수는 서버처럼 앞뒤 공백을 빼고 코드 포인트로 센다`() {
        // 앞뒤 공백은 서버가 자른다
        assertEquals(20, nicknameLength(" " + "가".repeat(20) + " "))
        assertNull(nicknameError(" " + "가".repeat(20) + " ", serverError = null, leftOnce = true))

        // 이모지 20개는 UTF-16 으로 40단위지만 20자다
        assertEquals(40, smile.repeat(20).length)
        assertFalse(isNicknameTooLong(smile.repeat(20)))
        assertNull(nicknameOverLimitCount(smile.repeat(20)))
        assertTrue(isNicknameTooLong(smile.repeat(21)))
        assertEquals("21/20", nicknameOverLimitCount(smile.repeat(21)))
    }

    @Test
    fun `서버가 닉네임을 거절하면 그 문구를 칸 아래에 쓴다`() {
        // Given 서버가 닉네임 칸에 붙인 이유
        val reason = "쓸 수 없는 닉네임이다."

        // When / Then 칸을 벗어나기 전이어도 보인다. 한도 안이라 글자 수는 없다
        assertEquals(reason, nicknameError("수연", serverError = reason, leftOnce = false))
        assertNull(nicknameOverLimitCount("수연"))
    }

    @Test
    fun `빈 닉네임은 칸을 벗어난 뒤에만 입력하라고 한다`() {
        // 가입 화면을 열자마자 빨간 줄을 띄우지 않는다
        assertNull(nicknameError("", serverError = null, leftOnce = false))
        // 비워 둔 채 칸을 벗어났다
        assertEquals("닉네임을 입력해 주세요", nicknameError("  ", serverError = null, leftOnce = true))
        // 채운 칸은 조용하다
        assertNull(nicknameError("수연", serverError = null, leftOnce = true))
    }
}
