// AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.ui.auth.AuthViewModel.Screen
import com.swpp.wakeup.ui.auth.AuthViewModel.UiState
import com.swpp.wakeup.ui.auth.PasswordResetConfirmScreen
import com.swpp.wakeup.ui.auth.PasswordResetRequestScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 비밀번호 재설정 두 화면(코드 요청 · 코드 확인).
 *
 * `SignupScreenTest` 처럼 뷰 모델과 **같은 상태 클래스**(`AuthViewModel.UiState`)를 들고 화면을 그린다. 버튼을 켤지
 * 정하는 규칙(`canRequestReset` · `canSubmitResetConfirm`)은 실제 코드를 탄다. 서버는 부르지 않는다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 코드 요청 화면은 칸이 하나뿐이라 칸을 벗어날 일이 없다. 형식이 틀린 이메일을 넣고 키보드의 완료 키를 누르면
 *   "확인 코드 받기" 가 꺼진 이유(형식 오류)가 보인다. 예전에는 칸을 벗어나야만 보여서 이유가 끝내 나오지 않았다.
 *   처음 입력하는 동안에는 조용하고, 맞는 이메일이면 완료 키가 그대로 코드를 요청한다.
 * - 코드 확인 화면에서 두 규칙 점이 다 초록인데 서버가 거절한 이유(흔한 비밀번호)가 규칙 아래에 보인다.
 */
@RunWith(AndroidJUnit4::class)
class PasswordResetScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val passed = fromJson<PasswordCheckResponse>(
        """{"min_length":true,"letters_and_digits":true,"messages":[]}"""
    )

    /** "password1": 두 점은 초록이지만 흔한 비밀번호라 서버가 통과시키지 않는다 */
    private val common = fromJson<PasswordCheckResponse>(
        """{"min_length":true,"letters_and_digits":true,"messages":["너무 흔히 사용되는 비밀번호입니다."]}"""
    )

    /** 코드를 받았고 새 비밀번호 두 칸이 같다. 서버 검사만 남은 코드 확인 상태 */
    private val confirmReady = UiState(
        screen = Screen.RESET_CONFIRM,
        email = "jinho@snu.ac.kr",
        resetSentTo = "jinho@snu.ac.kr",
        resetCode = "123456",
        password = "password1",
        passwordConfirm = "password1",
    )

    private val state = mutableStateOf(UiState(screen = Screen.RESET_REQUEST))
    private var submits = 0

    /** 코드 요청 화면. 뷰 모델의 입력 처리처럼 상태만 바꾼다 */
    private fun showRequest(initial: UiState) {
        state.value = initial
        compose.setContent {
            JitTheme {
                val s = state.value
                PasswordResetRequestScreen(
                    email = s.email,
                    loading = s.loading,
                    error = s.error,
                    canSubmit = s.canRequestReset,
                    onEmailChange = { v -> state.value = state.value.copy(email = v) },
                    onSubmitClick = { submits++ },
                    onBackClick = {},
                )
            }
        }
    }

    /** 코드 확인 화면. 입력은 이 테스트에서 바꾸지 않는다 */
    private fun showConfirm(initial: UiState) {
        state.value = initial
        compose.setContent {
            JitTheme {
                val s = state.value
                PasswordResetConfirmScreen(
                    sentTo = s.resetSentTo ?: s.email,
                    code = s.resetCode,
                    password = s.password,
                    passwordConfirm = s.passwordConfirm,
                    passwordsMatch = s.passwordsMatch,
                    passwordCheck = s.passwordCheck,
                    passwordCheckError = s.passwordCheckError,
                    loading = s.loading,
                    error = s.error,
                    info = s.resetInfo,
                    canSubmit = s.canSubmitResetConfirm,
                    onCodeChange = {},
                    onPasswordChange = {},
                    onPasswordConfirmChange = {},
                    onSubmitClick = { submits++ },
                    onResendClick = {},
                    onBackClick = {},
                )
            }
        }
    }

    // --- 코드 요청: 완료 키 (HE 2-11) --------------------------------------------

    @Test
    fun `형식이 틀린 이메일은 입력하는 동안 조용하고 완료 키를 누르면 버튼이 꺼진 이유를 보인다`() {
        // Given 빈 칸으로 연 코드 요청 화면
        showRequest(UiState(screen = Screen.RESET_REQUEST))

        // When 하나뿐인 칸에 "jinho@naver" 를 입력한다
        compose.onNodeWithTag("reset_email").performTextInput("jinho@naver")

        // Then 입력하는 동안은 조용하고 버튼은 꺼져 있다
        compose.onNodeWithTag("reset_email_error").assertDoesNotExist()
        compose.onNodeWithTag("reset_request_submit").assertIsNotEnabled()

        // When 키보드의 완료 키를 누른다(칸은 그대로다)
        compose.onNodeWithTag("reset_email").performImeAction()

        // Then 버튼이 꺼진 이유가 보이고 요청은 나가지 않는다
        compose.onNodeWithTag("reset_email_error").assertTextEquals("이메일 형식이 아님")
        compose.onNodeWithTag("reset_request_submit").assertIsNotEnabled()
        assertEquals(0, submits)
    }

    @Test
    fun `맞는 이메일에서 완료 키를 누르면 오류 없이 코드를 요청한다`() {
        // Given 빈 칸으로 연 코드 요청 화면에 맞는 이메일을 넣었다
        showRequest(UiState(screen = Screen.RESET_REQUEST))
        compose.onNodeWithTag("reset_email").performTextInput("jinho@snu.ac.kr")
        compose.onNodeWithTag("reset_request_submit").assertIsEnabled()

        // When 키보드의 완료 키를 누른다
        compose.onNodeWithTag("reset_email").performImeAction()

        // Then 오류 줄 없이 한 번 요청한다
        compose.onNodeWithTag("reset_email_error").assertDoesNotExist()
        assertEquals(1, submits)
    }

    // --- 코드 확인: 점으로 말할 수 없는 거절 이유 (HE 2-11) ----------------------

    @Test
    fun `코드 확인 화면에서 두 점을 다 통과했는데 서버가 거절하면 그 이유를 보인다`() {
        // Given 코드와 같은 새 비밀번호 두 칸을 넣었는데 흔한 비밀번호다
        showConfirm(confirmReady.copy(passwordCheck = common))

        // Then 버튼이 꺼진 이유가 규칙 아래에 있고 버튼은 꺼져 있다
        compose.onNodeWithTag("reset_password_reason").performScrollTo()
            .assertTextEquals("너무 흔히 사용되는 비밀번호입니다.")
        compose.onNodeWithTag("reset_confirm_submit").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `코드 확인 화면은 서버 검사를 통과하면 이유 없이 버튼이 켜진다`() {
        // Given 같은 상태에서 서버 검사만 통과했다
        showConfirm(confirmReady.copy(password = "Newpass456", passwordConfirm = "Newpass456", passwordCheck = passed))

        // Then 이유 줄이 없고 버튼이 켜진다(위 테스트의 꺼짐이 이유 때문이라는 것을 보인다)
        compose.onNodeWithTag("reset_password_reason").assertDoesNotExist()
        compose.onNodeWithTag("reset_confirm_submit").performScrollTo().assertIsEnabled()
    }
}
