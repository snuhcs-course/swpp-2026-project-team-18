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
