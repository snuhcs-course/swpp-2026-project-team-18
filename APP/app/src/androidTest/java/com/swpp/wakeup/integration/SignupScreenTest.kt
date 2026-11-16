package com.swpp.wakeup.integration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
 * - 버튼이 꺼진 이유가 화면에 있다: 닉네임 한도·빈 닉네임·서버의 닉네임 이유, 점으로 말할 수 없는 비밀번호
 *   거절 이유(흔한 비밀번호), 앞 화면에서 가져온 이메일의 형식 오류.
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

    // Partly AI-generated (Claude) — 서버의 닉네임 이유(nicknameServerError)를 그리고, 닉네임을 고치면 지운다.
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
                    // 뷰 모델처럼 닉네임을 고치면 서버의 닉네임 이유를 지운다.
                    onNicknameChange = { v -> state.value = state.value.copy(nickname = v, nicknameServerError = null) },
                    onPasswordChange = { v -> state.value = state.value.copy(password = v) },
                    onPasswordConfirmChange = { v -> state.value = state.value.copy(passwordConfirm = v) },
                    onTermsAgreedChange = { v -> state.value = state.value.copy(termsAgreed = v) },
                    onSubmitClick = { submits++ },
                    onBackToLoginClick = { backs++ },
                    passwordCheck = s.passwordCheck,
                    passwordCheckError = s.passwordCheckError,
                    nicknameServerError = s.nicknameServerError,
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

    // --- 닉네임 오류 줄 (HE 2-11) — AI-generated (Claude) ---------------------------

    @Test
    fun `닉네임이 20자를 넘으면 칸 아래에 한도와 글자 수를 보이고 가입 버튼이 꺼진다`() {
        // Given 다른 조건은 다 갖췄고 닉네임만 23자
        show(almostReady.copy(termsAgreed = true, nickname = "가".repeat(23)))

        // Then 칸을 벗어나지 않아도 바로 보인다
        compose.onNodeWithTag("signup_nickname_error").assertTextEquals("닉네임은 20자까지 쓸 수 있어요")
        compose.onNodeWithTag("signup_nickname_counter").assertTextEquals("23/20")
        submit().assertIsNotEnabled()

        // When 한도 안으로 고친다
        compose.onNodeWithTag("signup_nickname").performTextReplacement("수연")

        // Then 줄과 글자 수가 사라지고 버튼이 켜진다
        compose.onNodeWithTag("signup_nickname_error").assertDoesNotExist()
        compose.onNodeWithTag("signup_nickname_counter").assertDoesNotExist()
        submit().assertIsEnabled()
    }

    @Test
    fun `닉네임을 비운 채 칸을 벗어나면 입력하라고 한다`() {
        // Given 화면을 열었을 때는 조용하다
        show(almostReady.copy(termsAgreed = true))
        compose.onNodeWithTag("signup_nickname_error").assertDoesNotExist()

        // When 닉네임 칸에 들어가 비운다
        compose.onNodeWithTag("signup_nickname").performClick()
        compose.onNodeWithTag("signup_nickname").performTextReplacement("")

        // Then 입력하는 동안은 아직 조용하다
        compose.onNodeWithTag("signup_nickname_error").assertDoesNotExist()

        // When 다른 칸으로 옮긴다
        compose.onNodeWithTag("signup_email").performClick()

        // Then 이유가 보이고 버튼은 꺼져 있다
        compose.onNodeWithTag("signup_nickname_error").assertTextEquals("닉네임을 입력해 주세요")
        submit().assertIsNotEnabled()
    }

    @Test
    fun `서버가 닉네임을 거절하면 그 이유를 닉네임 칸 아래에 보이고 고치면 지운다`() {
        // Given 서버가 닉네임 칸에 이유를 붙여 가입을 거절했다
        val reason = "이 필드의 글자 수가 20 이하인지 확인하십시오."
        show(almostReady.copy(termsAgreed = true, nicknameServerError = reason))

        // Then 칸 아래에 그 문구가 있고, 한도 안이라 글자 수는 없다
        compose.onNodeWithTag("signup_nickname_error").assertTextEquals(reason)
        compose.onNodeWithTag("signup_nickname_counter").assertDoesNotExist()

        // When 닉네임을 고친다
        compose.onNodeWithTag("signup_nickname").performTextReplacement("수연2")

        // Then 지난 이유는 사라진다
        compose.onNodeWithTag("signup_nickname_error").assertDoesNotExist()
    }

    // --- 버튼이 꺼진 이유 (HE 2-11) — AI-generated (Claude) -------------------------

    @Test
    fun `두 조건을 다 통과했는데 서버가 거절하면 그 이유를 보인다`() {
        // Given "password1": 두 점은 초록이지만 흔한 비밀번호라 서버가 통과시키지 않는다
        val common = fromJson<PasswordCheckResponse>(
            """{"min_length":true,"letters_and_digits":true,"messages":["너무 흔히 사용되는 비밀번호입니다."]}"""
        )

        show(almostReady.copy(termsAgreed = true, passwordCheck = common))

        // Then 버튼이 꺼진 이유가 화면에 있다
        compose.onNodeWithTag("signup_password_reason").performScrollTo()
            .assertTextEquals("너무 흔히 사용되는 비밀번호입니다.")
        submit().assertIsNotEnabled()
    }

    @Test
    fun `가져온 이메일이 형식에 맞지 않으면 칸을 건드리지 않아도 이유를 보인다`() {
        // Given 로그인 화면에서 "jinho@snu" 를 넣고 바로 회원가입으로 왔다
        show(almostReady.copy(termsAgreed = true, email = "jinho@snu"))

        // Then 칸을 벗어날 일이 없어도 형식 오류가 보이고 버튼은 꺼져 있다
        compose.onNodeWithTag("signup_email_error").assertExists()
        submit().assertIsNotEnabled()
    }
}
