// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 소셜 로그인 화면 문구와 작은 규칙(docs/iter3/social-login-api.md). */
class SocialLoginTest {

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        return "$header.${encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))}.signature"
    }

    @Test
    fun `자리 표시 이메일은 보이지 않는다`() {
        assertEquals("me@kakao.com", SocialLogin.visibleEmail(" me@kakao.com "))
        assertNull(SocialLogin.visibleEmail("kakao-1234567890@social.invalid"))
        assertNull(SocialLogin.visibleEmail("google-1@SOCIAL.INVALID"))
        assertNull(SocialLogin.visibleEmail(""))
        assertNull(SocialLogin.visibleEmail(null))
    }

    @Test
    fun `로그인 방법 이름`() {
        assertEquals("이메일", SocialLogin.methodLabel("password"))
        assertEquals("카카오", SocialLogin.methodLabel("kakao"))
        assertEquals("Google", SocialLogin.methodLabel("google"))
        assertEquals("모르는 방법은 그대로", "apple", SocialLogin.methodLabel("apple"))
    }

    @Test
    fun `이미 가입한 이메일이면 원래 방법으로 로그인한 뒤 연결하라고 안내한다`() {
        val notice = SocialLogin.emailInUseNotice(SocialProvider.KAKAO, listOf("password"))
        assertEquals("이 이메일은 이미 가입돼 있어요", notice.title)
        assertEquals("이메일·비밀번호로 로그인한 뒤 설정 › 로그인 연결에서 카카오를 이어 주세요", notice.body)
        assertEquals("자동으로 합치지 않음. 원래 로그인 방법: 이메일", notice.footnote)
        assertTrue(notice.amber)

        val google = SocialLogin.emailInUseNotice(SocialProvider.GOOGLE, listOf("kakao", "password", "kakao"))
        assertEquals("카카오·이메일로 로그인한 뒤 설정 › 로그인 연결에서 Google을 이어 주세요", google.body)
        assertEquals("자동으로 합치지 않음. 원래 로그인 방법: 카카오·이메일", google.footnote)

        val unknown = SocialLogin.emailInUseNotice(SocialProvider.KAKAO, listOf(" ", ""))
        assertEquals("원래 로그인 방법으로 로그인한 뒤 설정 › 로그인 연결에서 카카오를 이어 주세요", unknown.body)
        assertEquals("자동으로 합치지 않음", unknown.footnote)
    }

    @Test
    fun `쓸 수 없으면 이메일로 로그인하라고 한다`() {
        val notice = SocialLogin.unavailableNotice(SocialProvider.GOOGLE)
        assertEquals("지금은 Google 로그인을 쓸 수 없어요", notice.title)
        assertEquals("이메일로 로그인해 주세요", notice.body)
        assertFalse(notice.amber)
    }

    @Test
    fun `목적격 조사는 소리에 맞춘다`() {
        assertEquals("카카오를", SocialProvider.KAKAO.asObject)
        assertEquals("Google을", SocialProvider.GOOGLE.asObject)
        assertEquals(SocialProvider.KAKAO, SocialProvider.of("kakao"))
        assertNull(SocialProvider.of("apple"))
    }

    @Test
    fun `ID 토큰의 이름으로 닉네임을 미리 채운다`() {
        assertEquals("김진호", SocialLogin.nicknameHint(jwt("""{"sub":"1","nickname":"김진호"}""")))
        assertEquals("구글은 name", "Jinho Kim", SocialLogin.nicknameHint(jwt("""{"name":"Jinho Kim"}""")))
        assertEquals(
            "카카오 nickname 이 먼저",
            "진호",
            SocialLogin.nicknameHint(jwt("""{"name":"Jinho","nickname":"진호"}""")),
        )
        assertEquals(
            "20자까지",
            "가".repeat(SocialLogin.NICKNAME_MAX),
            SocialLogin.nicknameHint(jwt("""{"nickname":"${"가".repeat(30)}"}""")),
        )
    }

    @Test
    fun `읽지 못하는 토큰이면 닉네임을 비워 둔다`() {
        assertNull(SocialLogin.nicknameHint("not-a-jwt"))
        assertNull(SocialLogin.nicknameHint("a.@@@.c"))
        assertNull(SocialLogin.nicknameHint(jwt("""{"nickname":"  "}""")))
        assertNull(SocialLogin.nicknameHint(jwt("""{"nickname":{"x":1}}""")))
        assertNull(SocialLogin.nicknameHint(jwt("""[1,2]""")))
    }
}
