package com.swpp.wakeup.ui.auth

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.RegisterRequest
import org.junit.Assert.*
import org.junit.Test

class SignupContractTest {
    private val gson = Gson()
    private val valid = PasswordCheckResponse(true, true, emptyList())
    private fun state(check: PasswordCheckResponse? = valid, terms: Boolean = true) =
        AuthViewModel.UiState(email = "qa@example.com", nickname = "QA", password = "Example123!",
            passwordConfirm = "Example123!", termsAgreed = terms, passwordCheck = check)

    @Test
    fun `검사 대기와 약관 미동의는 제출을 막는다`() {
        assertFalse(state(check = null).canSubmitSignup)
        assertFalse(state(terms = false).canSubmitSignup)
        assertTrue(state().canSubmitSignup)
    }

    // AI-generated (Claude)
    @Test
    fun `닉네임은 앞뒤 공백을 빼고 1~20자여야 가입할 수 있다`() {
        assertTrue(state().copy(nickname = "가".repeat(20)).canSubmitSignup)
        assertTrue(state().copy(nickname = "  ${"가".repeat(20)}  ").canSubmitSignup)
        assertFalse(state().copy(nickname = "가".repeat(21)).canSubmitSignup)
        assertFalse(state().copy(nickname = "   ").canSubmitSignup)
    }

    @Test
    fun `이메일 형식이 틀리면 가입을 막는다`() {
        assertFalse(state().copy(email = "jinho@snu").canSubmitSignup)
        assertFalse(state().copy(email = "").canSubmitSignup)
        assertTrue(state().copy(email = " jinho@snu.ac.kr ").canSubmitSignup)
    }

    @Test
    fun `로그인은 이메일 형식이 틀려도 막지 않는다`() {
        val login = AuthViewModel.UiState(email = "admin", password = "pw")
        assertFalse(login.emailFormatOk)
        assertTrue(login.canSubmitLogin)
    }

    @Test
    fun `재설정 코드 받기는 이메일 형식이 맞아야 켜진다`() {
        assertFalse(AuthViewModel.UiState(email = "a@b").canRequestReset)
        assertTrue(AuthViewModel.UiState(email = "a@snu.ac.kr").canRequestReset)
        assertFalse(AuthViewModel.UiState(email = "a@snu.ac.kr", loading = true).canRequestReset)
    }

    @Test
    fun `재설정 확인은 6자리 코드와 일치하는 새 비밀번호와 검사 통과가 필요하다`() {
        val ready = AuthViewModel.UiState(
            resetCode = "123456", password = "Newpass123", passwordConfirm = "Newpass123", passwordCheck = valid,
        )
        assertTrue(ready.canSubmitResetConfirm)
        assertFalse(ready.copy(resetCode = "12345").canSubmitResetConfirm)
        assertFalse(ready.copy(passwordConfirm = "Newpass124").canSubmitResetConfirm)
        assertFalse(ready.copy(passwordCheck = null).canSubmitResetConfirm)
        assertFalse(ready.copy(passwordCheck = PasswordCheckResponse(true, true, listOf("흔한"))).canSubmitResetConfirm)
        assertFalse(ready.copy(loading = true).canSubmitResetConfirm)
    }

    @Test
    fun `새 비밀번호가 지금 것과 같으면 변경을 막는다`() {
        val form = com.swpp.wakeup.ui.settings.PasswordChangeState(
            current = "Oldpass123", newPassword = "Newpass123", confirm = "Newpass123", passwordCheck = valid,
        )
        assertTrue(form.canSubmit)
        assertFalse(form.copy(newPassword = "Oldpass123", confirm = "Oldpass123").canSubmit)
        assertFalse(form.copy(current = "").canSubmit)
        assertFalse(form.copy(confirm = "Other12345").canSubmit)
        assertFalse(form.copy(passwordCheck = null).canSubmit)
        assertFalse(form.copy(busy = true).canSubmit)
    }

    @Test
    fun `흔한 비밀번호 오류도 제출을 막는다`() {
        assertFalse(state(check = PasswordCheckResponse(true, true, listOf("흔한 비밀번호"))).canSubmitSignup)
        assertFalse(state(check = PasswordCheckResponse(false, false, emptyList())).canSubmitSignup)
    }

    @Test
    fun `검사 응답 필드 누락은 통과로 해석하지 않는다`() {
        for (json in listOf("{}", """{"min_length":true,"letters_and_digits":true}""")) {
            assertFalse(gson.fromJson(json, PasswordCheckResponse::class.java).valid)
        }
    }

    @Test
    fun `가입 요청은 실제 동의 값을 JSON boolean으로 전송한다`() {
        for (terms in listOf(false, true)) {
            val request = RegisterRequest("qa@example.com", "QA", "Example123!", "Example123!", terms)
            val json = gson.toJsonTree(request).asJsonObject
            assertTrue(json["terms_agreed"].asJsonPrimitive.isBoolean)
            assertEquals(terms, json["terms_agreed"].asBoolean)
        }
    }
}
