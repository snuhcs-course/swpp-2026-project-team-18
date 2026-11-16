package com.swpp.wakeup.ui.auth

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.RegisterRequest
import com.swpp.wakeup.data.repository.AuthRepository
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

    // --- 닉네임 한도와 서버의 칸별 이유 — AI-generated (Claude) ----------------------

    @Test
    fun `닉네임이 20자를 넘으면 가입을 막는다`() {
        // Given 다른 조건은 다 갖춘 상태
        // When 닉네임만 21자
        val tooLong = state().copy(nickname = "가".repeat(21))

        // Then 서버가 거절할 값이라 버튼을 끈다
        assertTrue(tooLong.nicknameTooLong)
        assertFalse(tooLong.canSubmitSignup)
        assertFalse(state().copy(nickname = "가".repeat(20)).nicknameTooLong)
    }

    @Test
    fun `닉네임 한도는 서버처럼 앞뒤 공백을 빼고 코드 포인트로 센다`() {
        // 앞뒤 공백은 서버가 자르므로 세지 않는다
        assertTrue(state().copy(nickname = " " + "가".repeat(20) + " ").canSubmitSignup)
        // 이모지는 UTF-16 두 단위지만 서버(Python len)는 한 글자로 센다
        assertTrue(state().copy(nickname = "😀".repeat(20)).canSubmitSignup)
        assertFalse(state().copy(nickname = "😀".repeat(21)).canSubmitSignup)
    }

    @Test
    fun `서버가 닉네임만 거절하면 칸 아래에만 쓰고 아래 줄에는 되풀이하지 않는다`() {
        // Given 가입 요청을 보내는 중
        val sending = state().copy(loading = true, waking = true)
        val reason = "이 필드의 글자 수가 20 이하인지 확인하십시오."

        // When 서버가 닉네임 칸에만 이유를 붙여 거절한다
        val failed = sending.failedWith(AuthRepository.AuthResult.Failure(reason, mapOf("nickname" to reason)))

        // Then
        assertEquals(reason, failed.nicknameServerError)
        assertNull("같은 문구를 두 번 그리지 않는다", failed.error)
        assertFalse(failed.loading)
        assertFalse(failed.waking)
    }

    @Test
    fun `다른 칸의 이유도 함께 오면 서버의 대표 문구는 아래 줄에 남긴다`() {
        // Given 이메일과 닉네임이 함께 거절됐다. 대표 문구는 첫 칸(이메일)의 것이다
        val failure = AuthRepository.AuthResult.Failure(
            message = "이미 가입된 이메일이다.",
            fields = mapOf("email" to "이미 가입된 이메일이다.", "nickname" to "쓸 수 없는 닉네임이다."),
        )

        // When
        val failed = state().copy(loading = true).failedWith(failure)

        // Then
        assertEquals("쓸 수 없는 닉네임이다.", failed.nicknameServerError)
        assertEquals("이미 가입된 이메일이다.", failed.error)
    }

    @Test
    fun `칸별 이유가 없는 실패는 아래 줄에만 쓰고 지난 닉네임 이유를 지운다`() {
        // Given 지난 시도에서 닉네임 이유가 남았다
        val before = state().copy(loading = true, nicknameServerError = "쓸 수 없는 닉네임이다.")

        // When 이번에는 연결이 끊겼다
        val failed = before.failedWith(AuthRepository.AuthResult.Failure("서버에 연결할 수 없다."))

        // Then
        assertNull(failed.nicknameServerError)
        assertEquals("서버에 연결할 수 없다.", failed.error)
    }

    // --- 점이 말하지 못하는 거절 이유(흔한 비밀번호) — AI-generated (Claude) -----------

    @Test
    fun `두 조건을 다 통과했는데 서버가 거절하면 그 이유를 따로 보인다`() {
        // Given 서버가 "password1" 에 준 응답(두 점은 초록, messages 는 남음)
        val common = PasswordCheckResponse(true, true, listOf("너무 흔히 사용되는 비밀번호입니다."))

        // Then 버튼이 꺼져 있으니 이유가 있어야 한다
        assertFalse(state(check = common).canSubmitSignup)
        assertEquals("너무 흔히 사용되는 비밀번호입니다.", common.extraReason)
        assertEquals("빈 줄은 건너뛴다", "흔한", PasswordCheckResponse(true, true, listOf(" ", "흔한")).extraReason)
    }

    @Test
    fun `점이 이미 말하는 이유와 통과한 비밀번호에는 따로 이유가 없다`() {
        assertNull(PasswordCheckResponse(false, true, listOf("짧음")).extraReason)
        assertNull(PasswordCheckResponse(true, false, listOf("영문과 숫자", "흔한")).extraReason)
        assertNull(PasswordCheckResponse(true, true, emptyList()).extraReason)
        assertNull(PasswordCheckResponse(true, true, null).extraReason)
    }

    // --- 비밀번호 변경: 지금 것과 같음 — AI-generated (Claude) ------------------------

    @Test
    fun `새 비밀번호가 지금 것과 같으면 버튼이 꺼진 이유를 표시한다`() {
        // Given 지금 비밀번호를 새 칸과 확인 칸에 다시 넣었다
        val same = com.swpp.wakeup.ui.settings.PasswordChangeState(
            current = "Oldpass123", newPassword = "Oldpass123", confirm = "Oldpass123", passwordCheck = valid,
        )

        // Then 버튼은 꺼지고, 그 이유를 화면이 적을 수 있다
        assertTrue(same.sameAsCurrent)
        assertFalse(same.canSubmit)

        // 비어 있거나 다르면 표시하지 않는다
        assertFalse(same.copy(newPassword = "").sameAsCurrent)
        assertFalse(same.copy(newPassword = "Newpass123").sameAsCurrent)
        assertFalse(com.swpp.wakeup.ui.settings.PasswordChangeState().sameAsCurrent)
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
