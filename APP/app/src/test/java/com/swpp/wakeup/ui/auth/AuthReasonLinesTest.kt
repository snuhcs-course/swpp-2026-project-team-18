// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.ui.settings.PasswordChangeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 버튼이 꺼진 이유를 칸 아래에 말한다(fix/iter4-he-bugs 이식, 11-19) */
class AuthReasonLinesTest {

    @Test
    fun `가져온 이메일은 칸 밖이면 형식 오류를 보이고 입력 중에는 조용하다`() {
        assertTrue("로그인에서 가져와 손대지 않은 값", emailFormatErrorVisible("me@", focused = false, leftOnce = false))
        assertFalse("처음 들어와 입력하는 중", emailFormatErrorVisible("me@", focused = true, leftOnce = false))
        assertTrue(emailFormatErrorVisible("me@", focused = true, leftOnce = true))
        assertFalse(emailFormatErrorVisible("", focused = false, leftOnce = true))
        assertFalse(emailFormatErrorVisible("me@snu.ac.kr", focused = false, leftOnce = true))
    }

    @Test
    fun `두 점이 다 초록인데 남은 서버 이유는 그대로 · 점이 빨가면 글로 되풀이하지 않는다`() {
        assertEquals("너무 흔한 비밀번호예요", PasswordCheckResponse(true, true, listOf("너무 흔한 비밀번호예요")).extraReason)
        assertNull(PasswordCheckResponse(false, true, listOf("너무 짧아요")).extraReason)
        assertNull(PasswordCheckResponse(true, true, emptyList()).extraReason)
    }

    @Test
    fun `새 비밀번호가 지금 것과 같으면 이유가 있다`() {
        val state = PasswordChangeState(current = "abc12345", newPassword = "abc12345", confirm = "abc12345")
        assertTrue(state.sameAsCurrent)
        assertFalse(state.canSubmit)
        assertEquals("지금 비밀번호와 달라야 해요", PasswordChangeState.SAME_AS_CURRENT)
    }
}
