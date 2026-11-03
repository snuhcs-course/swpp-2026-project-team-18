// Partly AI-generated (Claude)
package com.swpp.wakeup.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** HTTP 로그에 비밀번호·토큰 원문이 남지 않는다(todo §2 토큰 암호화 저장, token-security-api.md). */
class ApiLogRedactionTest {

    @Test
    fun `로그인 응답의 access와 refresh를 가린다`() {
        val line = """{"access":"eyJhbGciOi.a.b","refresh":"eyJhbGciOi.c.d","user":{"email":"me@snu.ac.kr"}}"""

        val redacted = ApiClient.redactSecrets(line)

        assertEquals(
            """{"access":"***","refresh":"***","user":{"email":"me@snu.ac.kr"}}""",
            redacted,
        )
    }

    @Test
    fun `갱신·로그아웃 본문과 소셜 ID 토큰도 가린다`() {
        assertEquals("""{"refresh":"***"}""", ApiClient.redactSecrets("""{"refresh": "eyJ.x.y"}"""))
        val social = ApiClient.redactSecrets("""{"id_token":"eyJ.k.z","terms_agreed":true,"nickname":"진호"}""")
        assertFalse(social.contains("eyJ.k.z"))
        assertEquals("""{"id_token":"***","terms_agreed":true,"nickname":"진호"}""", social)
    }

    @Test
    fun `들여쓰기·줄바꿈·콜론 앞뒤 공백이 있는 본문도 가린다`() {
        val pretty = """
            {
              "access" : "eyJ.p.q",
              "refresh":
                "eyJ.r.s",
              "id_token" :"eyJ.t.u"
            }
        """.trimIndent()

        val redacted = ApiClient.redactSecrets(pretty)

        assertEquals(
            """
                {
                  "access":"***",
                  "refresh":"***",
                  "id_token":"***"
                }
            """.trimIndent(),
            redacted,
        )
    }

    @Test
    fun `값 안의 이스케이프한 따옴표에서 멈추지 않는다`() {
        val line = """{"password":"sw\"pp\\2026","email":"me@snu.ac.kr"}"""

        assertEquals("""{"password":"***","email":"me@snu.ac.kr"}""", ApiClient.redactSecrets(line))
    }

    @Test
    fun `재설정 코드와 현재·새 비밀번호 확인 칸도 가린다`() {
        val line = """{"email":"me@snu.ac.kr","reset_code":"123456","current_password":"old-pw-1","new_password":"n1","new_password_confirm":"n1"}"""

        assertEquals(
            """{"email":"me@snu.ac.kr","reset_code":"***","current_password":"***","new_password":"***","new_password_confirm":"***"}""",
            ApiClient.redactSecrets(line),
        )
    }

    @Test
    fun `비밀번호는 전처럼 가리고 다른 값은 그대로 둔다`() {
        val line = """{"email":"me@snu.ac.kr","password":"swpp2026!","password_confirm":"swpp2026!"}"""

        assertEquals(
            """{"email":"me@snu.ac.kr","password":"***","password_confirm":"***"}""",
            ApiClient.redactSecrets(line),
        )
    }

    @Test
    fun `토큰이 아닌 줄은 바꾸지 않는다`() {
        val line = """{"event_id":1,"lat":37.48,"lng":126.94,"refresh_at":"2026-11-03T00:00:00+09:00"}"""

        assertEquals(line, ApiClient.redactSecrets(line))
    }
}
