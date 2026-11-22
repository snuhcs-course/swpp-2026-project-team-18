// AI-generated (Claude) — A-6 크래시 보고에 개인정보가 나가지 않는지(11-20)
package com.swpp.wakeup.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashReportTest {

    private class RecordingSink : CrashReport.Sink {
        val userIds = mutableListOf<String>()
        val keys = mutableListOf<Pair<String, String>>()
        override fun setUserId(id: String) { userIds += id }
        override fun setKey(key: String, value: String) { keys += key to value }
        override fun setCollectionEnabled(enabled: Boolean) = Unit
    }

    private lateinit var sink: RecordingSink
    private lateinit var original: CrashReport.Sink

    @Before
    fun setUp() {
        original = CrashReport.sink
        sink = RecordingSink()
        CrashReport.sink = sink
    }

    @After
    fun tearDown() {
        CrashReport.sink = original
    }

    @Test
    fun `사용자 식별값은 서버 숫자 id 만이고 로그아웃하면 지운다`() {
        CrashReport.setUser(42)
        CrashReport.setUser(0)
        CrashReport.setUser(-3)
        CrashReport.clearUser()

        assertEquals("0 · 음수는 보내지 않고, 로그아웃은 빈 값", listOf("42", ""), sink.userIds)
    }

    @Test
    fun `허용한 키만 보내고 개인정보 모양의 값은 막는다`() {
        CrashReport.setKey(CrashReport.KEY_APP_VERSION, "0.16.0")
        CrashReport.setKey(CrashReport.KEY_SERVER_VERSION, "0.21.0")
        CrashReport.setKey(CrashReport.KEY_SCREEN, "AlarmDecision")

        // 허용하지 않은 키는 값과 상관없이 보내지 않는다
        CrashReport.setKey("email", "a@b.com")
        CrashReport.setKey("lat", "37.4812")
        CrashReport.setKey("event_title", "컴융응")
        CrashReport.setKey("token", "abc")

        // 허용한 키라도 값이 개인정보 모양이면 막는다
        CrashReport.setKey(CrashReport.KEY_SCREEN, "user@example.com")
        CrashReport.setKey(CrashReport.KEY_SCREEN, "서울대학교 제1공학관")
        CrashReport.setKey(CrashReport.KEY_SCREEN, "37.4812")
        CrashReport.setKey(CrashReport.KEY_SCREEN, "37.48,126.95")
        CrashReport.setKey(CrashReport.KEY_SCREEN, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnopqrstuvwxyz0123456789")
        CrashReport.setKey(CrashReport.KEY_SCREEN, null)

        assertEquals(
            listOf(
                CrashReport.KEY_APP_VERSION to "0.16.0",
                CrashReport.KEY_SERVER_VERSION to "0.21.0",
                CrashReport.KEY_SCREEN to "AlarmDecision",
            ),
            sink.keys,
        )
    }

    @Test
    fun `값 거르기 규칙`() {
        assertEquals("0.16.0", CrashReport.safeValue(" 0.16.0 "))
        assertNull(CrashReport.safeValue(""))
        assertNull("공백 섞인 문장", CrashReport.safeValue("일정 제목"))
        assertNull("좌표 한 축", CrashReport.safeValue("-126.9520"))
        assertNull("너무 긴 값(토큰)", CrashReport.safeValue("a".repeat(65)))
        assertTrue(CrashReport.ALLOWED_KEYS.none { it.contains("email") || it.contains("lat") || it.contains("token") })
    }
}
