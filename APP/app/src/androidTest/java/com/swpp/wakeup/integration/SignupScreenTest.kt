package com.swpp.wakeup.integration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.ui.auth.AuthViewModel.Screen
import com.swpp.wakeup.ui.auth.AuthViewModel.UiState
import com.swpp.wakeup.ui.auth.SignupScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 회원가입 화면 + 가입 상태 규칙(`AuthViewModel.UiState`).
 *
 * 비밀번호 검사 결과는 서버가 주므로 기기 테스트에서 실제 `AuthViewModel` 로는
 * 만들 수 없다(서버를 불러야 한다). 대신 뷰 모델과 **같은 상태 클래스**를 들고
 * 화면을 그린다. 버튼을 켤지 정하는 규칙(`canSubmitSignup`)·확인 일치
 * (`passwordsMatch`)는 실제 코드를 그대로 탄다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 가입 버튼은 서버 비밀번호 검사 통과·확인 일치·약관 동의가 **모두** 있어야 켜진다.
 *   약관을 건너뛰고 가입되면 동의 기록이 없는 계정이 생긴다.
 * - 확인 칸의 ✓ 는 두 칸이 같을 때만 보인다.
 * - 비밀번호 검사 자체가 실패하면(네트워크) 그 이유를 보여준다. 조용히 회색으로
 *   두면 사용자는 왜 버튼이 안 켜지는지 모른다.
 * - 처리 중에는 입력과 버튼이 잠긴다(두 번 가입 요청 방지).
 */
@RunWith(AndroidJUnit4::class)
class SignupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val passed = fromJson<PasswordCheckResponse>(
        """{"min_length":true,"letters_and_digits":true,"messages":[]}"""
    )

    /** 약관 동의만 빠진, 나머지는 다 갖춘 상태. */
    private val almostReady = UiState(
        screen = Screen.SIGNUP,
        email = "me@snu.ac.kr",
        nickname = "수연",
        password = "pw12345678",
        passwordConfirm = "pw12345678",
        passwordCheck = passed,
        termsAgreed = false,
    )

    private val state = mutableStateOf(almostReady)
    private var submits = 0
    private var backs = 0

    /** 뷰 모델의 입력 처리처럼 상태만 바꾼다. 서버는 부르지 않는다. */
    private fun show(initial: UiState) {
        state.value = initial
        compose.setContent {
            JitTheme {
                val s = state.value
                SignupScreen(
                    email = s.email,
                    nickname = s.nickname,
                    password = s.password,
                    passwordConfirm = s.passwordConfirm,
                    passwordsMatch = s.passwordsMatch,
                    termsAgreed = s.termsAgreed,
                    loading = s.loading,
                    error = s.error,
                    canSubmit = s.canSubmitSignup,
                    onEmailChange = { v -> state.value = state.value.copy(email = v) },
                    onNicknameChange = { v -> state.value = state.value.copy(nickname = v) },
                    onPasswordChange = { v -> state.value = state.value.copy(password = v) },
                    onPasswordConfirmChange = { v -> state.value = state.value.copy(passwordConfirm = v) },
                    onTermsAgreedChange = { v -> state.value = state.value.copy(termsAgreed = v) },
                    onSubmitClick = { submits++ },
                    onBackToLoginClick = { backs++ },
                    passwordCheck = s.passwordCheck,
                    passwordCheckError = s.passwordCheckError,
                )
            }
        }
    }

    private fun submit() = compose.onNodeWithTag("signup_submit").performScrollTo()

    @Test
    fun `약관에 동의해야 가입 버튼이 켜지고 누르면 한 번 제출된다`() {
        show(almostReady)
        submit().assertIsNotEnabled()

        compose.onNodeWithTag("signup_terms").performScrollTo().performClick()

        submit().assertIsEnabled().performClick()
        assertEquals(1, submits)
    }

    @Test
    fun `약관을 다시 끄면 가입 버튼도 다시 꺼진다`() {
        show(almostReady.copy(termsAgreed = true))
        submit().assertIsEnabled()

        compose.onNodeWithTag("signup_terms").performScrollTo().performClick()

        submit().assertIsNotEnabled()
    }

    @Test
    fun `비밀번호 확인이 다르면 체크 표시가 없고 같게 고치면 생긴다`() {
        show(almostReady.copy(termsAgreed = true, passwordConfirm = "pw1234567"))
        compose.onNodeWithText("✓").assertDoesNotExist()
        submit().assertIsNotEnabled()

        compose.onNodeWithTag("signup_password_confirm").performTextReplacement("pw12345678")

        compose.onNodeWithText("✓").assertExists()
        submit().assertIsEnabled()
    }

    @Test
    fun `서버 비밀번호 검사를 통과하지 못하면 다른 조건이 다 있어도 꺼져 있다`() {
        val failed = fromJson<PasswordCheckResponse>(
            """{"min_length":true,"letters_and_digits":false,"messages":["영문과 숫자를 함께 쓴다."]}"""
        )

        show(almostReady.copy(termsAgreed = true, passwordCheck = failed))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `비밀번호 검사 자체가 실패하면 이유를 보여준다`() {
        show(
            almostReady.copy(
                termsAgreed = true,
                passwordCheck = null,
                passwordCheckError = "서버에 연결할 수 없다.",
            )
        )

        compose.onNodeWithText("서버에 연결할 수 없다.").assertExists()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `가입이 거절되면 서버 문구를 보여주고 다시 누를 수 있다`() {
        show(almostReady.copy(termsAgreed = true, error = "이미 가입된 이메일이다."))

        compose.onNodeWithText("이미 가입된 이메일이다.").performScrollTo()
        submit().assertIsEnabled()
    }

    @Test
    fun `처리 중에는 입력과 버튼이 잠긴다`() {
        show(almostReady.copy(termsAgreed = true, loading = true))

        submit().assertIsNotEnabled()
        compose.onNodeWithTag("signup_email").assertIsNotEnabled()
        compose.onNodeWithTag("signup_terms").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `로그인으로 돌아가기를 누르면 돌아간다`() {
        show(almostReady)

        compose.onNodeWithText("로그인").performScrollTo().performClick()

        assertEquals(1, backs)
    }
}
